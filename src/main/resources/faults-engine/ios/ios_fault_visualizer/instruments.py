from __future__ import annotations

import os
import signal
import subprocess
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from .devices import Target
from .subprocesses import command_text, run

VIRTUAL_MEMORY_XPATH = (
    '/trace-toc/run[@number="1"]/data/table[@schema="virtual-memory"]'
)
NOTIFYUTIL_PATH = Path("/usr/bin/notifyutil")


def xctrace_command(*arguments: str) -> list[str]:
    arm64 = subprocess.run(
        ["/usr/sbin/sysctl", "-n", "hw.optional.arm64"],
        capture_output=True,
        text=True,
    )
    prefix = (
        ["/usr/bin/arch", "-arm64"]
        if arm64.returncode == 0 and arm64.stdout.strip() == "1"
        else []
    )
    return [*prefix, "xcrun", "xctrace", *arguments]


@dataclass
class Recording:
    process: subprocess.Popen[str]
    notification_listener: subprocess.Popen[str] | None
    command: list[str]
    stdout_path: Path
    stderr_path: Path


def require_virtual_memory_instrument() -> None:
    instruments = run(xctrace_command("list", "instruments")).stdout
    if "Virtual Memory Trace" not in instruments.splitlines():
        raise RuntimeError(
            "This Xcode installation does not expose the Virtual Memory Trace "
            "instrument required by ios-fault-visualizer"
        )


def start_recording(
    target: Target,
    trace_path: Path,
    time_limit_seconds: int,
    log_directory: Path,
) -> Recording:
    require_virtual_memory_instrument()
    if trace_path.exists():
        raise RuntimeError(f"xctrace output already exists: {trace_path}")
    if not NOTIFYUTIL_PATH.exists():
        raise RuntimeError(
            "xctrace readiness requires /usr/bin/notifyutil on the macOS host"
        )
    notification = f"com.bromano.ios-fault-visualizer.{uuid.uuid4()}"
    listener = subprocess.Popen(
        [str(NOTIFYUTIL_PATH), "-1", notification],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        text=True,
    )

    command = xctrace_command(
        "record",
        "--instrument",
        "Virtual Memory Trace",
        "--all-processes",
        "--time-limit",
        f"{time_limit_seconds}s",
        "--output",
        str(trace_path),
        f"--notify-tracing-started={notification}",
        "--no-prompt",
    )
    # Simulator apps are macOS host processes. Recording the Simulator device
    # would miss the host VM events that carry the fault address and stack.
    if not target.is_simulator:
        command.extend(["--device", target.identifier])

    stdout_path = log_directory / "xctrace.stdout.log"
    stderr_path = log_directory / "xctrace.stderr.log"
    stdout_file = stdout_path.open("w")
    stderr_file = stderr_path.open("w")
    try:
        process = subprocess.Popen(
            command,
            stdout=stdout_file,
            stderr=stderr_file,
            text=True,
            start_new_session=True,
        )
    except BaseException:
        listener.terminate()
        try:
            listener.wait(timeout=5)
        except subprocess.TimeoutExpired:
            listener.kill()
            listener.wait()
        stdout_file.close()
        stderr_file.close()
        raise
    process._ios_fault_stdout = stdout_file  # type: ignore[attr-defined]
    process._ios_fault_stderr = stderr_file  # type: ignore[attr-defined]
    return Recording(process, listener, command, stdout_path, stderr_path)


def wait_until_recording(recording: Recording, timeout_seconds: int = 20) -> None:
    # --notify-tracing-started is xctrace's explicit readiness contract. A log
    # line such as "Starting recording" can precede data collection and is not
    # sufficient to launch the target.
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        listener_status = (
            recording.notification_listener.poll()
            if recording.notification_listener is not None
            else None
        )
        if listener_status == 0 and recording.process.poll() is None:
            return
        if listener_status is not None and listener_status != 0:
            break
        if recording.process.poll() is not None:
            break
        time.sleep(0.1)
    detail = ""
    for path in (recording.stdout_path, recording.stderr_path):
        if path.exists():
            detail += path.read_text(errors="replace")
    raise RuntimeError(
        "xctrace exited or timed out before the target could be launched: "
        + detail.strip()
    )


def _close_recording_handles(recording: Recording) -> None:
    listener = recording.notification_listener
    if listener is not None and listener.poll() is None:
        listener.terminate()
        try:
            listener.wait(timeout=5)
        except subprocess.TimeoutExpired:
            listener.kill()
            listener.wait()
    stdout = getattr(recording.process, "_ios_fault_stdout", None)
    stderr = getattr(recording.process, "_ios_fault_stderr", None)
    if stdout is not None and not stdout.closed:
        stdout.close()
    if stderr is not None and not stderr.closed:
        stderr.close()


def abort_recording(recording: Recording, timeout_seconds: int = 15) -> None:
    """Stop and reap a recorder after readiness, cache, or launch failure."""
    try:
        if recording.process.poll() is None:
            try:
                os.killpg(recording.process.pid, signal.SIGINT)
            except (ProcessLookupError, PermissionError):
                recording.process.terminate()
            try:
                recording.process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                recording.process.terminate()
                try:
                    recording.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    recording.process.kill()
                    recording.process.wait()
    finally:
        _close_recording_handles(recording)


def finish_recording(recording: Recording, timeout_seconds: int) -> None:
    try:
        return_code = recording.process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        recording.process.terminate()
        try:
            return_code = recording.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            recording.process.kill()
            return_code = recording.process.wait()
    finally:
        _close_recording_handles(recording)
    if return_code != 0:
        detail = recording.stderr_path.read_text(errors="replace").strip()
        raise RuntimeError(
            f"xctrace failed ({return_code}): {command_text(recording.command)}"
            + (f"\n{detail}" if detail else "")
        )


def export_trace(trace_path: Path, output_directory: Path) -> tuple[Path, Path]:
    toc_path = output_directory / "trace-toc.xml"
    vm_path = output_directory / "virtual-memory.xml"
    run(
        xctrace_command(
            "export",
            "--input",
            str(trace_path),
            "--toc",
            "--output",
            str(toc_path),
        )
    )
    run(
        xctrace_command(
            "export",
            "--input",
            str(trace_path),
            "--xpath",
            VIRTUAL_MEMORY_XPATH,
            "--output",
            str(vm_path),
        )
    )
    if vm_path.stat().st_size < 100:
        raise RuntimeError(
            "The trace exported no Virtual Memory rows. Confirm the target and "
            "that Virtual Memory Trace is supported on this OS/device."
        )
    return toc_path, vm_path
