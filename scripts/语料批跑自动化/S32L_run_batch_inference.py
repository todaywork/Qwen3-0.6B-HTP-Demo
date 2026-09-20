"""S32L transport adapter; original batch workflow remains unchanged.

Uses vendor adb -host, then host adb_and to reach Android.
--serial selects the S32L host. Requires adb_and push and exec-out support.
"""
from __future__ import annotations

import shlex
import sys
import uuid
from pathlib import Path

import run_batch_inference as batch

VENDOR_ADB = r"E:\QualComm\shangqi_S32L\windows_adb_7.1.39_20230925\adb.exe"
BaseAdbClient = batch.AdbClient


class S32LAdbClient(BaseAdbClient):
    def __init__(self, executable=VENDOR_ADB, serial=None):
        if not Path(VENDOR_ADB).is_file():
            raise batch.BatchAutomationError(f"Required S32L vendor adb was not found: {VENDOR_ADB}")
        super().__init__(VENDOR_ADB, serial)

    def host(self, arguments, *, allow_failure=False, without_serial=False):
        command = ["-host"]
        if self.serial and not without_serial:
            command += ["-s", self.serial]
        command += arguments
        # Keep serial handling entirely in the host transport.
        return BaseAdbClient(self.executable).run(
            command, allow_failure=allow_failure
        )

    @staticmethod
    def android_command(arguments):
        if arguments[0] == "shell":
            inner = arguments[1:]
            if inner[:2] == ["am", "start"] and "--display" not in inner:
                inner = inner + ["--display", "0"]
            # Quote once for Android's shell, then again for the host shell.
            return shlex.join(["adb_and", "shell", shlex.join(inner)])
        if arguments[0] == "logcat":
            return shlex.join(["adb_and", "shell", shlex.join(arguments)])
        return shlex.join(["adb_and", *arguments])

    def run(self, arguments, *, allow_failure=False, without_serial=False):
        if arguments[0] in ("devices", "get-state"):
            output = self.host(arguments, allow_failure=allow_failure,
                               without_serial=without_serial)
            if arguments[0] == "get-state" and output.strip() == "device":
                self.host(["shell", "adb_and shell true"])
            return output
        if arguments[0] == "push":
            if len(arguments) != 3:
                raise batch.BatchAutomationError("S32L push expects one source and destination.")
            staged = f"/cache/s32l_batch_{uuid.uuid4().hex}.xlsx"
            try:
                self.host(["push", arguments[1], staged])
                return self.host(["shell", self.android_command(
                    ["push", staged, arguments[2]])])
            finally:
                self.host(["shell", shlex.join(["rm", "-f", "--", staged])],
                          allow_failure=True)
        return self.host(["shell", self.android_command(arguments)],
                         allow_failure=allow_failure)

    def run_to_file(self, arguments, destination):
        # Redirect binary exec-out on the host, then use adb pull for exact bytes.
        if not arguments or arguments[0] != "exec-out":
            raise batch.BatchAutomationError("S32L export requires exec-out.")
        staged = f"/cache/s32l_batch_{uuid.uuid4().hex}.xlsx"
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            self.host(["shell", self.android_command(arguments)
                       + " > " + shlex.quote(staged)])
            return self.host(["pull", staged, str(destination)])
        except Exception:
            destination.unlink(missing_ok=True)
            raise
        finally:
            self.host(["shell", shlex.join(["rm", "-f", "--", staged])],
                      allow_failure=True)


def main():
    # Reuse input selection, parameter validation, polling and result validation.
    # Assignment affects only this S32L process, never the original script file.
    batch.AdbClient = S32LAdbClient
    # The last argparse value wins; S32L always requires this vendor binary.
    sys.argv.extend(["--adb", VENDOR_ADB])
    return batch.main()


if __name__ == "__main__":
    raise SystemExit(main())
