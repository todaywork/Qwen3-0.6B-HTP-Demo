"""Refresh channel runtimes from the approved compile outputs (Python 3)."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    68: Path('E:/QualComm/ai-hub-compiles/qwen3_0_6b-geniex_qairt-w4a16-qualcomm-sa8295p-ctx512-qnn234-local'),
    73: Path('E:/QualComm/ai-hub-compiles/qwen3_0_6b-geniex_qairt-w4a16-sa8775p-ctx512-qairt246-local'),
}


def refresh(channels):
    selected = {arch: SOURCES[arch] for arch in channels}
    # Validate both inputs before writing any files.
    for arch, source in selected.items():
        for relative in ('genie_cli/libs/libGenie.so', 'genie_cli/libs/libQnnHtp.so',
                         'genie_cli/libs/libQnnSystem.so',
                         'genie_cli/libs/libQnnHtpNetRunExtensions.so',
                         f'genie_cli/libs/libQnnHtpV{arch}Stub.so',
                         f'dsp/libQnnHtpV{arch}Skel.so'):
            if not (source / relative).is_file():
                raise FileNotFoundError(source / relative)
    for arch, source in selected.items():
        channel = ROOT / f'app/src/v{arch}'
        hashes = {}
        for origin, target in ((source / 'genie_cli/libs', channel / 'jniLibs/arm64-v8a'),
                               (source / 'dsp', channel / f'assets/qnn/v{arch}/dsp')):
            target.mkdir(parents=True, exist_ok=True)
            for item in origin.iterdir():
                if item.is_file():
                    destination = target / item.name
                    shutil.copy2(item, destination)
                    hashes[str(destination.relative_to(channel)).replace('\\', '/')] = hashlib.sha256(destination.read_bytes()).hexdigest()
            # Never silently retain an obsolete library from an earlier runtime.
            extras = [p.name for p in target.iterdir() if not (origin / p.name).is_file()]
            if extras:
                raise RuntimeError(f'Remove obsolete files from {target}: {extras}')
        (channel / 'runtime-manifest.json').write_text(json.dumps({
            'architecture': arch, 'source': str(source), 'sha256': hashes,
        }, indent=2) + '\n', encoding='utf-8')
        print(f'Refreshed v{arch}: {len(hashes)} files')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", type=int, choices=(68, 73), action="append")
    args = parser.parse_args()
    refresh(args.channel or (68, 73))
