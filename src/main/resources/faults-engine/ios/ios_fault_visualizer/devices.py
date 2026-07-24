from __future__ import annotations

import json
import os
import plistlib
import re
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

from .subprocesses import CommandError, run


@dataclass(frozen=True)
class Target:
    kind: str
    identifier: str
    name: str
    runtime: str = ""

    @property
    def is_simulator(self) -> bool:
        return self.kind == "simulator"


def _simulators() -> list[Target]:
    payload = json.loads(run(["xcrun", "simctl", "list", "devices", "--json"]).stdout)
    targets = []
    for runtime, devices in payload.get("devices", {}).items():
        for device in devices:
            if not device.get("isAvailable", True):
                continue
            targets.append(
                Target(
                    kind="simulator",
                    identifier=str(device["udid"]),
                    name=str(device["name"]),
                    runtime=runtime,
                )
            )
    return targets


def _connected_physical_devices() -> list[Target]:
    output = run(["xcrun", "xctrace", "list", "devices"]).stdout
    targets = []
    in_devices = False
    for line in output.splitlines():
        if line.strip() == "== Devices ==":
            in_devices = True
            continue
        if line.strip() == "== Simulators ==":
            break
        if not in_devices:
            continue
        match = re.match(r"^(.+?) \(([0-9A-Fa-f-]{20,})\)(?: \((.+)\))?$", line.strip())
        if match and "Mac" not in match.group(1):
            targets.append(
                Target(
                    kind="physical",
                    identifier=match.group(2),
                    name=match.group(1),
                    runtime=match.group(3) or "",
                )
            )
    return targets


def resolve_target(selector: str) -> Target:
    simulators = _simulators()
    if selector.lower() == "booted":
        payload = json.loads(
            run(["xcrun", "simctl", "list", "devices", "booted", "--json"]).stdout
        )
        booted_ids = {
            str(device["udid"])
            for devices in payload.get("devices", {}).values()
            for device in devices
            if device.get("state") == "Booted"
        }
        matches = [target for target in simulators if target.identifier in booted_ids]
        if len(matches) != 1:
            names = ", ".join(
                f"{target.name} ({target.identifier})" for target in matches
            )
            raise RuntimeError(
                "Expected exactly one booted Simulator; select one with --device. "
                f"Booted: {names or 'none'}"
            )
        return matches[0]

    normalized = selector.casefold()
    simulator_matches = [
        target
        for target in simulators
        if target.identifier.casefold() == normalized
        or target.name.casefold() == normalized
    ]
    if len(simulator_matches) == 1:
        return simulator_matches[0]
    if len(simulator_matches) > 1:
        raise RuntimeError(
            f"Multiple Simulators are named {selector!r}; select one by UDID"
        )

    physical = _connected_physical_devices()
    physical_matches = [
        target
        for target in physical
        if target.identifier.casefold() == normalized
        or target.name.casefold() == normalized
    ]
    if len(physical_matches) == 1:
        return physical_matches[0]
    if len(physical_matches) > 1:
        raise RuntimeError(
            f"Multiple physical devices are named {selector!r}; select one by UDID"
        )
    available = [f"{target.name} ({target.identifier})" for target in simulators]
    available.extend(f"{target.name} ({target.identifier})" for target in physical)
    raise RuntimeError(
        f"Device {selector!r} was not found. Available targets:\n"
        + "\n".join(f"  {entry}" for entry in available)
    )


def app_info(app_bundle: Path) -> dict[str, Any]:
    with (app_bundle / "Info.plist").open("rb") as file:
        return plistlib.load(file)


def install_app(target: Target, app_bundle: Path) -> None:
    if not app_bundle.is_dir() or app_bundle.suffix != ".app":
        raise RuntimeError(f"Not an app bundle: {app_bundle}")
    if target.is_simulator:
        run(["xcrun", "simctl", "install", target.identifier, str(app_bundle)])
    else:
        run(
            [
                "xcrun",
                "devicectl",
                "device",
                "install",
                "app",
                "--device",
                target.identifier,
                str(app_bundle),
            ]
        )


def simulator_app_container(target: Target, bundle_id: str) -> Path:
    if not target.is_simulator:
        raise RuntimeError("App containers are only host-readable for Simulators")
    output = run(
        [
            "xcrun",
            "simctl",
            "get_app_container",
            target.identifier,
            bundle_id,
            "app",
        ]
    ).stdout.strip()
    path = Path(output)
    if not path.is_dir():
        raise RuntimeError(f"Simulator app container does not exist: {path}")
    return path


def terminate_simulator_app(target: Target, bundle_id: str) -> None:
    result = run(
        ["xcrun", "simctl", "terminate", target.identifier, bundle_id],
        check=False,
    )
    if result.returncode not in (0, 3):
        detail = (result.stderr or result.stdout).strip()
        if "found nothing to terminate" not in detail.lower():
            raise CommandError(f"Unable to terminate {bundle_id}: {detail}")


def _nested_strings(payload: Any) -> list[str]:
    values: list[str] = []
    if isinstance(payload, dict):
        for value in payload.values():
            values.extend(_nested_strings(value))
    elif isinstance(payload, list):
        for value in payload:
            values.extend(_nested_strings(value))
    elif isinstance(payload, str):
        values.append(payload)
    return values


def _app_process_ids(payload: Any, bundle_id: str, app_binary_name: str) -> set[int]:
    identifiers: set[int] = set()
    if isinstance(payload, dict):
        direct_pid = next(
            (
                value
                for key in ("processIdentifier", "pid")
                if isinstance((value := payload.get(key)), int)
            ),
            None,
        )
        if direct_pid is not None:
            strings = _nested_strings(payload)
            binary_match = any(
                value == app_binary_name
                or Path(value).name == app_binary_name
                or value.endswith(f"/{app_binary_name}")
                for value in strings
                if app_binary_name
            )
            bundle_match = any(
                value == bundle_id or bundle_id in value for value in strings
            )
            if binary_match or bundle_match:
                identifiers.add(direct_pid)
        for value in payload.values():
            identifiers.update(_app_process_ids(value, bundle_id, app_binary_name))
    elif isinstance(payload, list):
        for value in payload:
            identifiers.update(_app_process_ids(value, bundle_id, app_binary_name))
    return identifiers


def _physical_app_process_ids(
    target: Target, bundle_id: str, app_binary_name: str
) -> set[int]:
    with tempfile.TemporaryDirectory() as directory:
        json_path = Path(directory) / "processes.json"
        run(
            [
                "xcrun",
                "devicectl",
                "device",
                "info",
                "processes",
                "--device",
                target.identifier,
                "--json-output",
                str(json_path),
            ]
        )
        return _app_process_ids(
            json.loads(json_path.read_text()), bundle_id, app_binary_name
        )


def terminate_app(
    target: Target,
    bundle_id: str,
    app_binary_name: str,
    timeout_seconds: int = 20,
) -> dict[str, object]:
    if target.is_simulator:
        terminate_simulator_app(target, bundle_id)
        return {
            "procedure": "simctl-terminate",
            "verified_absent": True,
            "terminated_pids": [],
        }

    terminated: list[int] = []
    for pid in sorted(_physical_app_process_ids(target, bundle_id, app_binary_name)):
        run(
            [
                "xcrun",
                "devicectl",
                "device",
                "process",
                "terminate",
                "--device",
                target.identifier,
                "--pid",
                str(pid),
            ]
        )
        terminated.append(pid)

    deadline = time.monotonic() + timeout_seconds
    remaining = _physical_app_process_ids(target, bundle_id, app_binary_name)
    while remaining and time.monotonic() < deadline:
        time.sleep(0.5)
        remaining = _physical_app_process_ids(target, bundle_id, app_binary_name)
    if remaining:
        for pid in sorted(remaining):
            run(
                [
                    "xcrun",
                    "devicectl",
                    "device",
                    "process",
                    "terminate",
                    "--device",
                    target.identifier,
                    "--pid",
                    str(pid),
                    "--kill",
                ]
            )
        time.sleep(0.5)
        remaining = _physical_app_process_ids(target, bundle_id, app_binary_name)
    if remaining:
        raise RuntimeError(
            f"Target app still has physical-device processes after termination: "
            f"{sorted(remaining)}"
        )
    return {
        "procedure": "devicectl-terminate",
        "verified_absent": True,
        "terminated_pids": terminated,
    }


def verify_app_absent(
    target: Target,
    bundle_id: str,
    app_binary_name: str,
) -> dict[str, object]:
    if target.is_simulator:
        return {
            "procedure": "not-required-for-simulator",
            "verified_absent": True,
        }
    remaining = _physical_app_process_ids(target, bundle_id, app_binary_name)
    if remaining:
        raise RuntimeError(
            "Target app relaunched during physical-device cache preparation; "
            f"aborting rather than warming the capture with PIDs {sorted(remaining)}"
        )
    return {
        "procedure": "devicectl-process-recheck",
        "verified_absent": True,
        "matching_pids": [],
    }


def _recursive_int(payload: Any, preferred_keys: tuple[str, ...]) -> Optional[int]:
    if isinstance(payload, dict):
        for key in preferred_keys:
            value = payload.get(key)
            if isinstance(value, int):
                return value
        for value in payload.values():
            found = _recursive_int(value, preferred_keys)
            if found is not None:
                return found
    elif isinstance(payload, list):
        for value in payload:
            found = _recursive_int(value, preferred_keys)
            if found is not None:
                return found
    return None


def launch_app(
    target: Target,
    bundle_id: str,
    arguments: list[str] | None = None,
    log_directory: Path | None = None,
) -> int:
    arguments = arguments or []
    if target.is_simulator:
        command = [
            "xcrun",
            "simctl",
            "launch",
            "--terminate-running-process",
            target.identifier,
            bundle_id,
            *arguments,
        ]
        output = run(command).stdout
        match = re.search(r":\s*(\d+)\s*$", output)
        if not match:
            raise RuntimeError(f"Could not parse Simulator launch PID from: {output}")
        return int(match.group(1))

    log_directory = log_directory or Path.cwd()
    log_directory.mkdir(parents=True, exist_ok=True)
    json_path = log_directory / "device-launch.json"
    log_path = log_directory / "device-launch.log"
    run(
        [
            "xcrun",
            "devicectl",
            "device",
            "process",
            "launch",
            "--device",
            target.identifier,
            "--terminate-existing",
            "--json-output",
            str(json_path),
            "--log-output",
            str(log_path),
            bundle_id,
            *arguments,
        ]
    )
    payload = json.loads(json_path.read_text())
    pid = _recursive_int(payload, ("processIdentifier", "pid"))
    if pid is None:
        raise RuntimeError(f"Could not find processIdentifier in {json_path}")
    return pid


def build_pressure_helper(
    repository_root: Path,
    target: Target,
    development_team: Optional[str],
) -> Path:
    project = repository_root / "cache-pressure" / "CachePressure.xcodeproj"
    derived_data = repository_root / "build" / "cache-pressure-derived-data"
    command = [
        "xcodebuild",
        "-project",
        str(project),
        "-scheme",
        "CachePressure",
        "-configuration",
        "Release",
        "-derivedDataPath",
        str(derived_data),
    ]
    if target.is_simulator:
        command.extend(
            ["-destination", f"platform=iOS Simulator,id={target.identifier}"]
        )
        product_directory = "Release-iphonesimulator"
    else:
        if not development_team:
            raise RuntimeError(
                "Physical-device cache pressure requires --development-team so "
                "Xcode can sign the bundled CachePressure helper"
            )
        command.extend(
            [
                "-destination",
                f"platform=iOS,id={target.identifier}",
                f"DEVELOPMENT_TEAM={development_team}",
                "-allowProvisioningUpdates",
            ]
        )
        product_directory = "Release-iphoneos"
    run(command)
    app = derived_data / "Build" / "Products" / product_directory / "CachePressure.app"
    if not app.is_dir():
        raise RuntimeError(f"CachePressure build did not produce {app}")
    return app


def run_pressure_helper(
    target: Target,
    *,
    hold_seconds: int,
    fraction: float,
    megabytes: Optional[int],
    log_directory: Path,
) -> dict[str, object]:
    bundle_id = "com.bromano.ios-fault-visualizer.CachePressure"
    arguments = [
        "--fraction",
        str(fraction),
        "--hold-seconds",
        str(hold_seconds),
    ]
    if megabytes is not None:
        arguments.extend(["--megabytes", str(megabytes)])
    log_directory.mkdir(parents=True, exist_ok=True)
    if target.is_simulator:
        stdout_path = log_directory / "cache-pressure.stdout.log"
        stderr_path = log_directory / "cache-pressure.stderr.log"
        command = [
            "xcrun",
            "simctl",
            "launch",
            "--terminate-running-process",
            f"--stdout={stdout_path}",
            f"--stderr={stderr_path}",
            target.identifier,
            bundle_id,
            *arguments,
        ]
        pid = int(re.search(r":\s*(\d+)\s*$", run(command).stdout).group(1))
        return {
            "procedure": "cache-pressure-helper",
            "helper_pid": pid,
            "hold_seconds": hold_seconds,
            "physical_memory_fraction": fraction,
            "requested_megabytes": megabytes,
            "stdout_log": str(stdout_path),
            "stderr_log": str(stderr_path),
            "confidence": "best-effort",
        }

    json_path = log_directory / "cache-pressure-launch.json"
    log_path = log_directory / "cache-pressure-launch.log"
    stdout_path = log_directory / "cache-pressure.stdout.log"
    stderr_path = log_directory / "cache-pressure.stderr.log"
    command = [
        "xcrun",
        "devicectl",
        "device",
        "process",
        "launch",
        "--device",
        target.identifier,
        "--terminate-existing",
        "--json-output",
        str(json_path),
        "--log-output",
        str(log_path),
        "--console",
        "--timeout",
        str(max(60, hold_seconds + 45)),
        bundle_id,
        *arguments,
    ]
    result = run(command, check=False)
    stdout_path.write_text(result.stdout or "")
    stderr_path.write_text(result.stderr or "")
    completion = _pressure_completion(result.stdout or "")
    if result.returncode != 0 or not completion["validated"]:
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(
            "Physical CachePressure did not complete its requested allocation "
            f"and hold (exit {result.returncode}). Logs: {stdout_path}, "
            f"{stderr_path}" + (f"\n{detail}" if detail else "")
        )
    payload = json.loads(json_path.read_text()) if json_path.exists() else {}
    return {
        "procedure": "cache-pressure-helper",
        "helper_pid": _recursive_int(payload, ("processIdentifier", "pid")),
        "hold_seconds": hold_seconds,
        "physical_memory_fraction": fraction,
        "requested_megabytes": megabytes,
        "confidence": "best-effort",
        "stdout_log": str(stdout_path),
        "stderr_log": str(stderr_path),
        "completion": completion,
    }


def _collect_process_ids(payload: Any) -> set[int]:
    identifiers: set[int] = set()
    if isinstance(payload, dict):
        for key, value in payload.items():
            if key in {"processIdentifier", "pid"} and isinstance(value, int):
                identifiers.add(value)
            else:
                identifiers.update(_collect_process_ids(value))
    elif isinstance(payload, list):
        for value in payload:
            identifiers.update(_collect_process_ids(value))
    return identifiers


def _process_is_running(target: Target, pid: int) -> bool:
    if target.is_simulator:
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
    with tempfile.TemporaryDirectory() as directory:
        json_path = Path(directory) / "processes.json"
        result = run(
            [
                "xcrun",
                "devicectl",
                "device",
                "info",
                "processes",
                "--device",
                target.identifier,
                "--json-output",
                str(json_path),
            ],
            check=False,
        )
        if result.returncode != 0 or not json_path.exists():
            return True
        return pid in _collect_process_ids(json.loads(json_path.read_text()))


def _pressure_completion(output: str) -> dict[str, object]:
    ready = re.search(
        r"CACHE_PRESSURE_READY allocated_bytes=(\d+) "
        r"target_bytes=(\d+) hold_seconds=(\d+)",
        output,
    )
    complete = re.search(r"CACHE_PRESSURE_COMPLETE allocated_bytes=(\d+)", output)
    aborted = "CACHE_PRESSURE_ABORTED" in output
    allocated = int(ready.group(1)) if ready else None
    target = int(ready.group(2)) if ready else None
    complete_allocated = int(complete.group(1)) if complete else None
    validated = (
        not aborted
        and allocated is not None
        and target is not None
        and allocated == target
        and complete_allocated == target
    )
    return {
        "validated": validated,
        "ready_marker": ready is not None,
        "complete_marker": complete is not None,
        "aborted_marker": aborted,
        "allocated_bytes": allocated,
        "target_bytes": target,
        "hold_seconds": int(ready.group(3)) if ready else None,
    }


def wait_for_pressure(
    target: Target,
    metadata: dict[str, object],
    timeout_seconds: int = 120,
) -> dict[str, object]:
    import time

    pid = metadata.get("helper_pid")
    if not isinstance(pid, int):
        raise RuntimeError("CachePressure launch did not return a helper PID")
    start = time.monotonic()
    deadline = start + timeout_seconds
    while time.monotonic() < deadline:
        if not _process_is_running(target, pid):
            elapsed = time.monotonic() - start
            stdout_log = metadata.get("stdout_log")
            completion: dict[str, object] = {"validated": False}
            if isinstance(stdout_log, str) and Path(stdout_log).exists():
                completion = _pressure_completion(Path(stdout_log).read_text())
            completion.update(
                {
                    "helper_exit_observed": True,
                    "elapsed_seconds": elapsed,
                }
            )
            if not completion["validated"]:
                raise RuntimeError(
                    "CachePressure exited without proving that it allocated and "
                    f"held its requested target. Log: {stdout_log or 'unavailable'}"
                )
            return completion
        time.sleep(0.5)

    if target.is_simulator:
        terminate_simulator_app(
            target, "com.bromano.ios-fault-visualizer.CachePressure"
        )
    else:
        run(
            [
                "xcrun",
                "devicectl",
                "device",
                "process",
                "terminate",
                "--device",
                target.identifier,
                "--pid",
                str(pid),
            ],
            check=False,
        )
    raise RuntimeError(
        f"CachePressure helper PID {pid} did not exit within {timeout_seconds} seconds"
    )


def reboot_physical_device(target: Target, timeout_seconds: int = 240) -> None:
    if target.is_simulator:
        raise RuntimeError(
            "Simulator reboot does not clear the macOS host page cache; use purge"
        )
    run(
        [
            "xcrun",
            "devicectl",
            "device",
            "reboot",
            "--device",
            target.identifier,
        ]
    )
    import time

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            if any(
                device.identifier == target.identifier
                for device in _connected_physical_devices()
            ):
                return
        except Exception:
            pass
        time.sleep(3)
    raise RuntimeError(
        f"Physical device {target.name} did not reconnect after reboot within "
        f"{timeout_seconds} seconds"
    )
