from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path


PACKAGE_NAME = "com.qairt.qwen3htp"
ACTIVITY_NAME = f"{PACKAGE_NAME}/.MainActivity"
REMOTE_INPUT_DIRECTORY = "/data/local/tmp/nlutest"
REMOTE_INPUT_FILE = f"{REMOTE_INPUT_DIRECTORY}/nlutest.xlsx"
PRIVATE_RESULT_DIRECTORY = "files/batch_results"
REMOTE_RESULT_DIRECTORY = f"/data/user/0/{PACKAGE_NAME}/{PRIVATE_RESULT_DIRECTORY}"
AUTOMATION_PREFIX = "BATCH_AUTOMATION EVENT="
LOGCAT_TAG = "Qwen3HtpDemo:I"


class BatchAutomationError(RuntimeError):
    pass


def configure_console() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace")


def stage(message: str) -> None:
    print(f"[batch] {message}", flush=True)


class AdbClient:
    def __init__(self, executable: str, serial: str | None = None) -> None:
        self.executable = executable
        self.serial = serial

    def run(
        self,
        arguments: list[str],
        *,
        allow_failure: bool = False,
        without_serial: bool = False,
    ) -> str:
        command = [self.executable]
        if self.serial and not without_serial:
            command.extend(["-s", self.serial])
        command.extend(arguments)
        try:
            completed = subprocess.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except FileNotFoundError as error:
            raise BatchAutomationError(
                f"adb was not found: {self.executable}. Install Android platform-tools "
                "or pass --adb with its path."
            ) from error
        output = completed.stdout.strip()
        if completed.returncode != 0 and not allow_failure:
            rendered = subprocess.list2cmdline(command)
            raise BatchAutomationError(
                f"adb command failed (exit {completed.returncode}): {rendered}\n{output}"
            )
        return output

    def run_to_file(self, arguments: list[str], destination: Path) -> str:
        command = [self.executable]
        if self.serial:
            command.extend(["-s", self.serial])
        command.extend(arguments)
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            with destination.open("wb") as output_stream:
                completed = subprocess.run(
                    command,
                    stdout=output_stream,
                    stderr=subprocess.PIPE,
                    check=False,
                )
        except FileNotFoundError as error:
            raise BatchAutomationError(
                f"adb was not found: {self.executable}. Install Android platform-tools "
                "or pass --adb with its path."
            ) from error
        if completed.returncode != 0:
            destination.unlink(missing_ok=True)
            error_output = completed.stderr.decode("utf-8", errors="replace").strip()
            rendered = subprocess.list2cmdline(command)
            raise BatchAutomationError(
                f"adb command failed (exit {completed.returncode}): {rendered}\n{error_output}"
            )
        return completed.stderr.decode("utf-8", errors="replace").strip()


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Push an Excel file, monitor Android batch inference, and export the "
            "private package result."
        )
    )
    parser.add_argument("input_excel", nargs="?", help="Input .xlsx file")
    parser.add_argument("--input-excel", dest="input_excel_option")
    parser.add_argument("--output-directory")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--max-all-token", type=int, default=256)
    parser.add_argument("--context-size", type=int, default=512)
    parser.add_argument("--click-timeout-seconds", type=int, default=600)
    parser.add_argument("--inference-timeout-seconds", type=int, default=0)
    parser.add_argument("--poll-interval-seconds", type=int, default=2)
    parser.add_argument("--clean-device-history", action="store_true")
    parser.add_argument("--adb", default="adb")
    return parser.parse_args()


def validate_integer(name: str, value: int, minimum: int, maximum: int) -> None:
    if value < minimum or value > maximum:
        raise BatchAutomationError(f"{name} must be between {minimum} and {maximum}; got {value}.")


def resolve_input_excel(args: argparse.Namespace, script_directory: Path) -> Path:
    selected = args.input_excel_option or args.input_excel
    if args.input_excel_option and args.input_excel:
        raise BatchAutomationError(
            "Specify the input Excel either as a positional path or with --input-excel, not both."
        )
    if selected:
        input_path = Path(selected).expanduser().resolve(strict=True)
    else:
        candidates = sorted(
            path
            for path in script_directory.glob("*.xlsx")
            if path.is_file() and not path.name.startswith("~$")
        )
        if not candidates:
            raise BatchAutomationError(
                f"No .xlsx file was found beside the script: '{script_directory}'."
            )
        if len(candidates) > 1:
            names = ", ".join(path.name for path in candidates)
            raise BatchAutomationError(
                "Multiple .xlsx files were found beside the script. Keep only one, "
                f"or pass --input-excel. Files: {names}"
            )
        input_path = candidates[0].resolve()
        stage(f"Auto-selected Excel beside the script: {input_path}")

    if input_path.suffix.lower() != ".xlsx":
        raise BatchAutomationError("The input file must use the .xlsx extension.")
    if not input_path.is_file() or input_path.stat().st_size == 0:
        raise BatchAutomationError("The input Excel must be a non-empty file.")
    return input_path


def select_device(adb: AdbClient, requested_serial: str | None) -> str:
    if not shutil.which(adb.executable) and not Path(adb.executable).is_file():
        raise BatchAutomationError(
            f"adb was not found: {adb.executable}. Install Android platform-tools "
            "or pass --adb with its path."
        )
    if requested_serial:
        adb.serial = requested_serial
        state = adb.run(["get-state"], allow_failure=True).strip()
        if state != "device":
            raise BatchAutomationError(
                f"Device '{requested_serial}' is not ready (state: '{state}')."
            )
        return requested_serial

    device_output = adb.run(["devices"], without_serial=True)
    devices = [
        match.group(1)
        for line in device_output.splitlines()
        if (match := re.match(r"^(\S+)\s+device$", line.strip()))
    ]
    if not devices:
        raise BatchAutomationError("No authorized adb device is connected.")
    if len(devices) > 1:
        raise BatchAutomationError(
            "Multiple adb devices are connected. Pass --serial. Devices: " + ", ".join(devices)
        )
    adb.serial = devices[0]
    return devices[0]


def get_device_result_files(adb: AdbClient) -> list[str]:
    output = adb.run(
        [
            "shell",
            "run-as",
            PACKAGE_NAME,
            "find",
            PRIVATE_RESULT_DIRECTORY,
            "-maxdepth",
            "1",
            "-type",
            "f",
            "-name",
            "*.xlsx",
            "-print",
        ],
        allow_failure=True,
    )
    return [
        line.strip()
        for line in output.splitlines()
        if line.strip().startswith(PRIVATE_RESULT_DIRECTORY + "/")
        and line.strip().endswith(".xlsx")
    ]


def verify_private_result_access(adb: AdbClient) -> None:
    adb.run(
        [
            "shell",
            "run-as",
            PACKAGE_NAME,
            "mkdir",
            "-p",
            PRIVATE_RESULT_DIRECTORY,
        ]
    )
    stage(
        "App-private result access verified with run-as: "
        f"'{REMOTE_RESULT_DIRECTORY}'."
    )


def clear_device_result_history(adb: AdbClient) -> None:
    existing_files = get_device_result_files(adb)
    if not existing_files:
        stage("No historical result Excel files were found on the device.")
        return
    stage(
        f"Deleting {len(existing_files)} historical result Excel file(s) "
        f"from '{REMOTE_RESULT_DIRECTORY}'..."
    )
    adb.run(
        [
            "shell",
            "run-as",
            PACKAGE_NAME,
            "find",
            PRIVATE_RESULT_DIRECTORY,
            "-maxdepth",
            "1",
            "-type",
            "f",
            "-name",
            "*.xlsx",
            "-delete",
        ]
    )
    remaining_files = get_device_result_files(adb)
    if remaining_files:
        raise BatchAutomationError(
            f"Device result cleanup failed; {len(remaining_files)} historical .xlsx file(s) remain."
        )
    stage("Device result history cleanup verified.")


def get_app_pid(adb: AdbClient) -> str:
    return adb.run(["shell", "pidof", PACKAGE_NAME], allow_failure=True).strip()


def wait_for_app_pid(adb: AdbClient) -> str:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        process_id = get_app_pid(adb)
        if process_id.isdigit():
            return process_id
        time.sleep(0.5)
    raise BatchAutomationError("The app did not start within 30 seconds.")


def get_automation_events(adb: AdbClient, process_id: str) -> list[str]:
    output = adb.run(
        ["logcat", "-d", f"--pid={process_id}", "-v", "raw", LOGCAT_TAG, "*:S"]
    )
    return [line for line in output.splitlines() if AUTOMATION_PREFIX in line]


def deadline_reached(started_at: float, timeout_seconds: int) -> bool:
    return timeout_seconds > 0 and time.monotonic() - started_at >= timeout_seconds


def wait_for_started_event(
    adb: AdbClient,
    process_id: str,
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> str:
    started_at = time.monotonic()
    while True:
        events = get_automation_events(adb, process_id)
        failures = [event for event in events if re.search(r"EVENT=(REJECTED|FAILED)", event)]
        if failures:
            raise BatchAutomationError(f"The app rejected or failed the batch: {failures[-1]}")
        started = [event for event in events if re.search(r"EVENT=STARTED\b", event)]
        if started:
            return started[-1]
        if get_app_pid(adb) != process_id:
            raise BatchAutomationError("The app process stopped while waiting for the user click.")
        if deadline_reached(started_at, timeout_seconds):
            raise BatchAutomationError(
                f"Timed out after {timeout_seconds} seconds waiting for the Start button."
            )
        time.sleep(poll_interval_seconds)


def wait_for_completed_event(
    adb: AdbClient,
    process_id: str,
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> str:
    started_at = time.monotonic()
    while True:
        events = get_automation_events(adb, process_id)
        failures = [event for event in events if re.search(r"EVENT=(REJECTED|FAILED)", event)]
        if failures:
            raise BatchAutomationError(f"Batch inference failed: {failures[-1]}")
        completed = [event for event in events if re.search(r"EVENT=COMPLETED\b", event)]
        if completed:
            return completed[-1]
        if get_app_pid(adb) != process_id:
            raise BatchAutomationError(
                "The app process stopped before the completion keyword was emitted."
            )
        if deadline_reached(started_at, timeout_seconds):
            raise BatchAutomationError(
                f"Timed out after {timeout_seconds} seconds waiting for batch completion."
            )
        time.sleep(poll_interval_seconds)


def make_local_output_path(output_directory: Path, remote_output: str) -> Path:
    output_directory.mkdir(parents=True, exist_ok=True)
    output_path = output_directory / Path(remote_output).name
    if not output_path.exists():
        return output_path
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    return output_directory / f"{output_path.stem}_pulled_{timestamp}{output_path.suffix}"


def run_automation(args: argparse.Namespace) -> None:
    script_directory = Path(__file__).resolve().parent
    input_excel = resolve_input_excel(args, script_directory)
    validate_integer("max-all-token", args.max_all_token, 1, 4096)
    validate_integer("context-size", args.context_size, 1, 4096)
    validate_integer("click-timeout-seconds", args.click_timeout_seconds, 1, 86400)
    validate_integer(
        "inference-timeout-seconds", args.inference_timeout_seconds, 0, 604800
    )
    validate_integer("poll-interval-seconds", args.poll_interval_seconds, 1, 30)
    if args.max_all_token > args.context_size:
        raise BatchAutomationError(
            f"max-all-token ({args.max_all_token}) cannot exceed "
            f"context-size ({args.context_size})."
        )

    output_directory = (
        Path(args.output_directory).expanduser().resolve()
        if args.output_directory
        else script_directory / "batch_results"
    )
    adb = AdbClient(args.adb)
    serial = select_device(adb, args.serial)
    stage(f"Using adb device: {serial}")
    verify_private_result_access(adb)

    if args.clean_device_history:
        clear_device_result_history(adb)

    local_size = input_excel.stat().st_size
    stage(f"Pushing '{input_excel}' to '{REMOTE_INPUT_FILE}'...")
    adb.run(["shell", "mkdir", "-p", REMOTE_INPUT_DIRECTORY])
    push_output = adb.run(["push", str(input_excel), REMOTE_INPUT_FILE])
    if push_output:
        print(push_output, flush=True)
    remote_size_text = adb.run(["shell", "stat", "-c", "%s", REMOTE_INPUT_FILE]).strip()
    if not remote_size_text.isdigit() or int(remote_size_text) != local_size:
        raise BatchAutomationError(
            f"Remote input size verification failed (local={local_size}, "
            f"remote='{remote_size_text}')."
        )
    stage(f"Excel upload verified ({remote_size_text} bytes).")

    stage("Restarting the app in manual-start mode...")
    adb.run(["shell", "am", "force-stop", PACKAGE_NAME])
    start_output = adb.run(
        [
            "shell",
            "am",
            "start",
            "-n",
            ACTIVITY_NAME,
            "--ei",
            "max_all_token",
            str(args.max_all_token),
            "--ei",
            "context_size",
            str(args.context_size),
        ]
    )
    if start_output:
        print(start_output, flush=True)
    process_id = wait_for_app_pid(adb)

    print()
    print("=" * 60)
    print("On the Android device, keep 'Pushed directory Excel' selected and click start.")
    print(f"The script is waiting for the app's STARTED event (PID {process_id}).")
    print("=" * 60, flush=True)

    started_event = wait_for_started_event(
        adb,
        process_id,
        args.click_timeout_seconds,
        args.poll_interval_seconds,
    )
    stage(f"Detected: {started_event}")
    stage("Batch inference is running; monitoring the completion keyword...")

    completed_event = wait_for_completed_event(
        adb,
        process_id,
        args.inference_timeout_seconds,
        args.poll_interval_seconds,
    )
    stage(f"Detected: {completed_event}")
    event_match = re.search(
        r"EVENT=COMPLETED\s+status=(\S+)\s+processed=(\d+)\s+"
        r"failed=(\d+)\s+total=(\d+)\s+output=(\S+)",
        completed_event,
    )
    if not event_match:
        raise BatchAutomationError(f"Completion event format is invalid: {completed_event}")

    status, processed_text, failed_text, total_text, remote_output = event_match.groups()
    if remote_output == "-":
        raise BatchAutomationError(
            f"Batch completed with status '{status}', but the app produced no Excel output."
        )
    expected_result_prefix = REMOTE_RESULT_DIRECTORY + "/"
    if not remote_output.startswith(expected_result_prefix):
        raise BatchAutomationError(
            "The app reported a result outside its private batch directory: "
            f"'{remote_output}' (expected prefix '{expected_result_prefix}')."
        )
    local_output = make_local_output_path(output_directory, remote_output)
    stage(f"Exporting private result Excel from '{remote_output}' with run-as...")
    export_output = adb.run_to_file(
        ["exec-out", "run-as", PACKAGE_NAME, "cat", remote_output], local_output
    )
    if export_output:
        print(export_output, flush=True)
    if not local_output.is_file():
        raise BatchAutomationError(
            f"Private result export returned without creating '{local_output}'."
        )
    result_size = local_output.stat().st_size
    if result_size == 0:
        raise BatchAutomationError(f"The exported result Excel is empty: '{local_output}'.")
    if not zipfile.is_zipfile(local_output):
        raise BatchAutomationError(
            f"The exported result is not a valid .xlsx archive: '{local_output}'."
        )

    print()
    stage(
        f"Done. status={status}, processed={processed_text}/{total_text}, failed={failed_text}"
    )
    stage(f"Local result: {local_output} ({result_size} bytes)")
    if status != "success":
        print(
            f"WARNING: The batch ended with status '{status}'. "
            "Review the exported rows for details.",
            file=sys.stderr,
        )


def main() -> int:
    configure_console()
    try:
        run_automation(parse_arguments())
        return 0
    except KeyboardInterrupt:
        print("\n[batch] Interrupted by user. Device inference was not stopped.", file=sys.stderr)
        return 130
    except (BatchAutomationError, OSError, ValueError) as error:
        print(f"[batch] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
