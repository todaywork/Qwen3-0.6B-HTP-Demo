#!/usr/bin/env python3
"""Read QNN build IDs through ADB without modifying the device (Python 3.8+)."""
import argparse
import datetime
import json
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

VERSION = re.compile(rb"v?\d+\.\d+\.\d+\.[0-9]+(?:[_-][A-Za-z0-9]+)*(?:\.[a-f0-9]{7,40})?")
LIBRARY = re.compile(r"^libQnn[^/\s]*\.so$")
DIRECTORIES = [
    '/usr/lib64', '/usr/lib', '/vendor/lib64/qnn', '/vendor/lib64',
    '/vendor/lib/rfsa/adsp', '/vendor/dsp', '/odm/lib64', '/system/lib64',
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb') or 'adb', help='ADB executable')
    parser.add_argument('--host', action='store_true', help='Use vendor S32L adb -host channel')
    parser.add_argument('-s', '--serial', help='Device serial; required when several devices are connected')
    parser.add_argument('--pid', type=int, help='Also inspect libraries mapped by this process (if permitted)')
    parser.add_argument('--library', action='append', default=[], help='Additional absolute device library path; repeatable')
    parser.add_argument('--json', type=pathlib.Path, help='Write a local JSON report')
    args = parser.parse_args()
    base = [args.adb] + (['-host'] if args.host else [])

    def command(argv, timeout=30):
        return subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)

    listing = command(base + ['devices', '-l'])
    if listing.returncode:
        raise RuntimeError(listing.stderr.decode('utf-8', 'replace'))
    devices = re.findall(r'^(\S+)\s+(device|offline|unauthorized)\b(.*)$', listing.stdout.decode('utf-8', 'replace'), re.M)
    online = [serial for serial, state, _ in devices if state == 'device']
    if args.serial:
        if args.serial not in online:
            raise RuntimeError('Requested device is not online. Devices: '+repr(devices))
        serial = args.serial
    elif len(online) == 1:
        serial = online[0]
    else:
        raise RuntimeError('Specify --serial for one online device. Devices: '+repr(devices))

    # Every subsequent command is pinned to the selected serial, including vendor ADB.
    target = base + ['-s', serial]

    def shell(text):
        p = command(target + ['shell', text])
        return (p.stdout + p.stderr).decode('utf-8', 'replace').replace('\r', '')

    info = shell("uname -a; if command -v getprop >/dev/null 2>&1; then "
                 "for p in ro.product.manufacturer ro.product.model ro.soc.model ro.board.platform ro.build.version.release; "
                 "do echo PROP:$p=$(getprop $p); done; fi")
    report = {'time': datetime.datetime.now().isoformat(timespec='seconds'),
              'adb': args.adb, 'host_channel': args.host, 'serial': serial,
              'device_info': info.strip(), 'libraries': [], 'discovery_errors': [],
              'scope': 'Library file build IDs; presence alone does not prove a running app uses them.'}
    print('Device:', serial, '| channel:', 'vendor -host' if args.host else 'standard ADB')
    print(info.strip())
    paths = set()
    for directory in DIRECTORIES:
        out = shell('ls -1 '+shlex.quote(directory))
        for line in out.splitlines():
            if LIBRARY.fullmatch(line.strip()): paths.add(directory+'/'+line.strip())
        if 'Permission denied' in out: report['discovery_errors'].append(out.strip())
    for path in args.library:
        if not path.startswith('/') or '\n' in path: raise ValueError('--library must be an absolute device path')
        paths.add(path)

    mapped = set()
    if args.pid is not None:
        if args.pid <= 0: raise ValueError('--pid must be positive')
        maps = shell('cat /proc/'+str(args.pid)+'/maps')
        report['pid'] = args.pid
        report['maps_readable'] = bool(re.search(r'^[0-9a-f]+-[0-9a-f]+\s', maps, re.M))
        if not report['maps_readable']: report['maps_error'] = maps.strip()
        for line in maps.splitlines():
            match = re.search(r'(/\S*/libQnn[^/\s]*\.so)(?: \(deleted\))?$', line)
            if match:
                mapped.add(match[1]); paths.add(match[1])

    # Copy only readable libraries into a temporary PC directory and scan bytes locally.
    # This avoids relying on strings/grep availability or decoding ELF as text on the device.
    with tempfile.TemporaryDirectory(prefix='qnn_version_') as temporary:
        for i, path in enumerate(sorted(paths)):
            readable = shell('if [ -r '+shlex.quote(path)+' ]; then echo __QNN_READABLE__; else echo __QNN_UNREADABLE__; fi')
            row = {'path': path, 'mapped_in_requested_pid': path in mapped, 'build_ids': []}
            if '__QNN_READABLE__' not in readable:
                row['status'] = 'unreadable_or_missing'
            else:
                dest = pathlib.Path(temporary)/str(i)
                p = command(target + ['pull', path, str(dest)], timeout=120)
                if p.returncode or not dest.is_file() or dest.stat().st_size == 0:
                    row['status'] = 'pull_failed'
                    row['error'] = (p.stdout+p.stderr).decode('utf-8', 'replace')
                else:
                    data = dest.read_bytes()
                    # QNN build IDs use a date-length fourth component. Ignore short
                    # semantic versions from unrelated dependencies embedded in the ELF.
                    ids = sorted({m.group().decode('ascii') for m in VERSION.finditer(data)
                                  if re.match(rb'v?\d+\.\d+\.\d+\.[0-9]{6,}', m.group())})
                    row.update(status='build_id_found' if ids else 'no_build_id_string', build_ids=ids)
            report['libraries'].append(row)
            print('\n'+path+(' [mapped in PID]' if path in mapped else ''))
            print('  '+(', '.join(row['build_ids']) if row['build_ids'] else row['status']))

    confirmed = any(x['build_ids'] for x in report['libraries'])
    report['any_build_id_found'] = confirmed
    print('\n'+('Build IDs above identify individual library files; versions may differ.' if confirmed else
                   'QNN version could not be confirmed. Permission denied or no readable build ID is NOT proof QNN is absent.'))
    print('V68/V73 in filenames identifies HTP architecture, not QNN SDK version.')
    if args.pid is None: print('Use --pid PID to inspect the QNN paths actually mapped by an app (permissions permitting).')
    elif not report.get('maps_readable'): print('Process maps unavailable; actual app library usage could not be confirmed.')
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Report:', args.json.resolve())
    return 0 if confirmed else 2


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
