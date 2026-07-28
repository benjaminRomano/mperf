#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
import os
import plistlib
import shutil
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from ios_fault_visualizer.cache import (
    Residency,
    app_executable,
    app_bundle_files,
    file_inventory,
    measure_residency,
    pressure_host_cache,
    purge_host_cache,
    summarize_residency,
    write_residency,
)
from ios_fault_visualizer.devices import (
    Target,
    app_info,
    build_pressure_helper,
    install_app,
    launch_app,
    reboot_physical_device,
    resolve_target,
    run_pressure_helper,
    simulator_app_container,
    terminate_app,
    verify_app_absent,
    wait_for_pressure,
)
from ios_fault_visualizer.instruments import (
    Recording,
    abort_recording,
    export_trace,
    finish_recording,
    start_recording,
    wait_until_recording,
    xctrace_command,
)
from ios_fault_visualizer.parser import process_virtual_memory_export
from ios_fault_visualizer.reporting import build_report
from ios_fault_visualizer.subprocesses import run

ROOT = Path(__file__).resolve().parent
CAPTURE_MARKER = ".ios-fault-visualizer-capture"
CAPTURE_MARKER_CONTENT = "ios-fault-visualizer capture v1\n"


def validate_recording_window(
    settle_seconds: float, time_limit_seconds: Optional[int]
) -> None:
    if settle_seconds <= 0:
        raise ValueError("--settle-seconds must be positive")
    if time_limit_seconds is not None:
        if time_limit_seconds <= 0:
            raise ValueError("--time-limit must be positive")
        minimum = math.ceil(settle_seconds)
        if time_limit_seconds < minimum:
            raise ValueError(
                "--time-limit must be at least ceil(--settle-seconds) "
                f"({minimum} seconds)"
            )


def wait_for_post_launch_window(recording: Recording, settle_seconds: float) -> None:
    """Require xctrace to remain alive for the complete post-launch window."""
    try:
        return_code = recording.process.wait(timeout=settle_seconds)
    except subprocess.TimeoutExpired:
        return
    detail = recording.stderr_path.read_text(errors="replace").strip()
    raise RuntimeError(
        "xctrace ended before the requested post-launch analysis window "
        f"completed (exit {return_code}, requested {settle_seconds}s)"
        + (f"\n{detail}" if detail else "")
    )


def validate_output_directory(path: Path, overwrite: bool) -> Path:
    if path.is_symlink():
        raise ValueError(f"Refusing a symlink output directory: {path}")
    resolved = path.resolve()
    protected = {Path("/").resolve(), ROOT.resolve(), ROOT.parent.resolve()}
    if resolved in protected or resolved == Path.home().resolve():
        raise ValueError(f"Refusing to replace protected directory: {resolved}")
    if resolved.exists() and not resolved.is_dir():
        raise ValueError(f"Output path is not a directory: {resolved}")
    if resolved.exists():
        if any(resolved.iterdir()) and not overwrite:
            raise RuntimeError(
                f"Output directory is not empty: {resolved}\n"
                "Pass --overwrite to replace this capture directory."
            )
        if any(resolved.iterdir()) and overwrite:
            marker = resolved / CAPTURE_MARKER
            if (
                not marker.is_file()
                or marker.read_text(errors="replace") != CAPTURE_MARKER_CONTENT
            ):
                raise ValueError(
                    "Refusing to overwrite a non-capture directory without the "
                    f"{CAPTURE_MARKER} ownership marker: {resolved}"
                )
    return resolved


def create_staging_directory(output: Path) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f".{output.name}.staging-", dir=output.parent)
    )
    (staging / CAPTURE_MARKER).write_text(CAPTURE_MARKER_CONTENT)
    return staging


def publish_staging_directory(staging: Path, output: Path) -> None:
    backup: Optional[Path] = None
    if output.exists():
        backup = output.with_name(f".{output.name}.replaced-{uuid.uuid4().hex}")
        os.replace(output, backup)
    try:
        os.replace(staging, output)
    except BaseException:
        if backup is not None and backup.exists() and not output.exists():
            os.replace(backup, output)
        raise
    if backup is not None:
        shutil.rmtree(backup)


def _app_identity(app_bundle: Path) -> tuple[str, str]:
    info = app_info(app_bundle)
    bundle_id = str(info.get("CFBundleIdentifier") or "")
    executable = str(info.get("CFBundleExecutable") or "")
    if not bundle_id or not executable:
        raise RuntimeError(
            f"App bundle is missing CFBundleIdentifier/CFBundleExecutable: {app_bundle}"
        )
    return bundle_id, executable


def _prepare_pressure_helper(
    target: Target,
    development_team: Optional[str],
) -> None:
    helper = build_pressure_helper(ROOT, target, development_team)
    install_app(target, helper)


def prepare_cache(
    *,
    target: Target,
    bundle_id: str,
    policy: str,
    output_directory: Path,
    development_team: Optional[str],
    pressure_fraction: float,
    pressure_hold_seconds: int,
    pressure_megabytes: Optional[int],
    host_free_percent: int,
    maximum_host_pressure_delta: int,
    allow_host_pressure: bool,
    residency_threshold: float,
    allow_unconfirmed_cache: bool,
    require_cold_cache: bool,
) -> tuple[dict[str, Any], list[Residency], list[Path]]:
    residency_rows: list[Residency] = []
    measured_paths: list[Path] = []

    if target.is_simulator:
        app_bundle = simulator_app_container(target, bundle_id)
        executable = app_executable(app_bundle)
        measured_paths = app_bundle_files(app_bundle, executable)
        measurement_errors: list[dict[str, str]] = []
        inventory_before = file_inventory(app_bundle, measured_paths)
        residency_rows.extend(
            measure_residency(
                measured_paths,
                "before_cache_action",
                measurement_errors,
            )
        )
        if policy in {"auto", "purge", "pressure"}:
            cache_actions: list[dict[str, Any]] = []
            purge_fallback_reason = None
            if policy == "pressure":
                cache_actions.append(
                    pressure_host_cache(
                        host_free_percent,
                        maximum_host_pressure_delta,
                    )
                )
            else:
                try:
                    cache_actions.append(purge_host_cache())
                except RuntimeError as error:
                    if policy == "purge":
                        raise RuntimeError(
                            f"{error}. Use --cache-policy auto or pressure to "
                            "fall back to unprivileged host memory pressure."
                        ) from error
                    if not allow_host_pressure:
                        raise RuntimeError(
                            f"{error}. The auto policy will not create host memory "
                            "pressure without explicit consent. Pass "
                            "--allow-host-pressure, or select "
                            "--cache-policy pressure."
                        ) from error
                    purge_fallback_reason = str(error)
                    cache_actions.append(
                        pressure_host_cache(
                            host_free_percent,
                            maximum_host_pressure_delta,
                        )
                    )

            measured_paths = app_bundle_files(app_bundle, executable)
            inventory_after = file_inventory(app_bundle, measured_paths)
            after_rows = measure_residency(
                measured_paths,
                "after_cache_action",
                measurement_errors,
            )
            after = summarize_residency(after_rows, "after_cache_action")
            fraction = after["resident_fraction"]
            complete_coverage = (
                not measurement_errors
                and inventory_before["manifest_sha256"]
                == inventory_after["manifest_sha256"]
                and after["files"] == inventory_after["regular_file_count"]
            )
            allowed_fraction = 0.0 if require_cold_cache else residency_threshold
            accepted = (
                complete_coverage
                and fraction is not None
                and float(fraction) <= allowed_fraction
            )
            zero_resident = accepted and after["resident_pages"] == 0
            residency_rows.extend(after_rows)
            metadata = {
                "procedure": (
                    "+".join(str(action["procedure"]) for action in cache_actions)
                    + "+mincore"
                ),
                "actions": cache_actions,
                "confidence": (
                    "confirmed-evicted"
                    if zero_resident
                    else (
                        "threshold-met-partially-resident"
                        if accepted
                        else "unconfirmed"
                    )
                ),
                "inventory_before": inventory_before,
                "inventory_after": inventory_after,
                "app_executable_relative_path": str(executable.relative_to(app_bundle)),
                "complete_measurement_coverage": complete_coverage,
                "measurement_errors": measurement_errors,
                "residency_before": summarize_residency(
                    residency_rows, "before_cache_action"
                ),
                "residency_after": after,
                "confirmation_threshold": allowed_fraction,
                "note": (
                    "Simulator app files live on the macOS host. The host cache "
                    "action is followed by mincore residency checks over the "
                    "complete regular-file inventory in the app bundle."
                ),
            }
            if purge_fallback_reason:
                metadata["purge_fallback_reason"] = purge_fallback_reason
            if not accepted and not allow_unconfirmed_cache:
                fraction_text = (
                    f"{float(fraction):.1%}" if fraction is not None else "unknown"
                )
                raise RuntimeError(
                    "The host cache action did not meet the app-bundle residency "
                    f"gate ({fraction_text} resident, complete coverage: "
                    f"{complete_coverage}); refusing the capture."
                )
            return metadata, residency_rows, measured_paths
        if policy == "reboot":
            raise RuntimeError(
                "Simulator reboot does not clear host cache; use --cache-policy purge"
            )
        if require_cold_cache:
            raise RuntimeError(
                "--require-cold-cache cannot be used with --cache-policy none"
            )
        return (
            {
                "procedure": "none",
                "confidence": "none",
                "note": "No page-cache preparation was requested.",
            },
            residency_rows,
            measured_paths,
        )

    if policy in {"auto", "pressure"}:
        if require_cold_cache:
            raise RuntimeError(
                "A stock physical device cannot satisfy --require-cold-cache. "
                "Use --cache-policy reboot without the strict flag, or a supported "
                "internal/rooted cache-control environment."
            )
        _prepare_pressure_helper(target, development_team)
        pressure = run_pressure_helper(
            target,
            hold_seconds=pressure_hold_seconds,
            fraction=pressure_fraction,
            megabytes=pressure_megabytes,
            log_directory=output_directory,
        )
        if "completion" not in pressure:
            pressure["completion"] = wait_for_pressure(target, pressure)
        pressure["note"] = (
            "Stock iOS exposes no supported global page-cache flush or per-app "
            "residency query. Allocating and touching anonymous pages is a "
            "best-effort eviction heuristic, not proof of a cold cache."
        )
        return pressure, residency_rows, measured_paths
    if policy == "reboot":
        if require_cold_cache:
            raise RuntimeError(
                "A reboot is the strongest stock-device procedure, but the host "
                "cannot verify app-file residency; strict confirmation is unavailable."
            )
        reboot_physical_device(target)
        return (
            {
                "procedure": "physical-device-reboot",
                "confidence": "best-effort-reboot",
                "note": (
                    "A reboot clears prior VM state, but boot activity may repopulate "
                    "shared caches and app-file residency is not host-queryable."
                ),
            },
            residency_rows,
            measured_paths,
        )
    if policy == "purge":
        raise RuntimeError(
            "macOS purge cannot flush a physical iOS device. Use pressure or reboot."
        )
    if require_cold_cache:
        raise RuntimeError(
            "--require-cold-cache cannot be used with --cache-policy none"
        )
    return (
        {
            "procedure": "none",
            "confidence": "none",
            "note": "No page-cache preparation was requested.",
        },
        residency_rows,
        measured_paths,
    )


def should_validate_prelaunch_cache(
    target: Target,
    installed_bundle: Optional[Path],
    cache_metadata: dict[str, Any],
) -> bool:
    return (
        target.is_simulator
        and installed_bundle is not None
        and isinstance(cache_metadata.get("inventory_after"), dict)
    )


def validate_prelaunch_cache(
    *,
    installed_bundle: Path,
    cache_metadata: dict[str, Any],
    residency_rows: list[Residency],
    require_cold_cache: bool,
    residency_threshold: float,
    allow_unconfirmed_cache: bool,
) -> list[Path]:
    executable_relative = cache_metadata.get("app_executable_relative_path")
    installed_executable = (
        installed_bundle / str(executable_relative)
        if executable_relative
        else app_executable(installed_bundle)
    )
    prelaunch_paths = app_bundle_files(
        installed_bundle,
        installed_executable,
    )
    prelaunch_errors: list[dict[str, str]] = []
    prelaunch_inventory = file_inventory(installed_bundle, prelaunch_paths)
    prelaunch_rows = measure_residency(
        prelaunch_paths,
        "immediately_before_target_launch",
        prelaunch_errors,
    )
    residency_rows.extend(prelaunch_rows)
    prelaunch = summarize_residency(prelaunch_rows, "immediately_before_target_launch")
    expected_fingerprint = (
        cache_metadata.get("inventory_after", {})
        if isinstance(cache_metadata.get("inventory_after"), dict)
        else {}
    )
    complete_coverage = (
        not prelaunch_errors
        and expected_fingerprint.get("manifest_sha256")
        == prelaunch_inventory["manifest_sha256"]
        and prelaunch["files"] == prelaunch_inventory["regular_file_count"]
    )
    fraction = prelaunch["resident_fraction"]
    allowed_fraction = 0.0 if require_cold_cache else residency_threshold
    accepted = (
        complete_coverage
        and fraction is not None
        and float(fraction) <= allowed_fraction
    )
    cache_metadata["inventory_immediately_before_launch"] = prelaunch_inventory
    cache_metadata["residency_immediately_before_launch"] = prelaunch
    cache_metadata["prelaunch_measurement_errors"] = prelaunch_errors
    if accepted:
        cache_metadata["confidence"] = (
            "confirmed-evicted"
            if prelaunch["resident_pages"] == 0
            else "threshold-met-partially-resident"
        )
    else:
        cache_metadata["confidence"] = "unconfirmed-prelaunch"
        if not allow_unconfirmed_cache:
            fraction_text = (
                f"{float(fraction):.1%}" if fraction is not None else "unknown"
            )
            raise RuntimeError(
                "App-bundle cache residency changed before launch "
                f"({fraction_text} resident, complete coverage: "
                f"{complete_coverage}); refusing the capture."
            )
    return prelaunch_paths


def capture(args: argparse.Namespace) -> Path:
    validate_recording_window(args.settle_seconds, args.time_limit)
    requested_output = args.output.expanduser()
    if not requested_output.is_absolute():
        requested_output = Path.cwd() / requested_output
    output = validate_output_directory(requested_output, args.overwrite)
    target = resolve_target(args.device)

    app_binary_name = args.app_binary_name or ""
    bundle_id = args.bundle_id
    app_bundle: Optional[Path] = None
    if args.app:
        app_bundle = args.app.resolve()
        discovered_bundle, discovered_binary = _app_identity(app_bundle)
        if bundle_id and bundle_id != discovered_bundle:
            raise RuntimeError(
                f"--bundle-id {bundle_id!r} does not match {discovered_bundle!r} "
                f"in {app_bundle}"
            )
        bundle_id = discovered_bundle
        app_binary_name = app_binary_name or discovered_binary
    if not bundle_id:
        raise RuntimeError("--bundle-id is required when --app is not provided")

    working = create_staging_directory(output)
    try:
        if app_bundle is not None:
            install_app(target, app_bundle)

        installed_bundle: Optional[Path] = None
        if target.is_simulator:
            installed_bundle = simulator_app_container(target, bundle_id)
            _, installed_binary = _app_identity(installed_bundle)
            app_binary_name = app_binary_name or installed_binary
        app_bundle_root = (
            str(installed_bundle.resolve()) if installed_bundle is not None else ""
        )
        if not app_binary_name:
            raise RuntimeError(
                "--app-binary-name is required for an already-installed "
                "physical-device app"
            )
        target_preparation = {
            "initial_termination": terminate_app(
                target,
                bundle_id,
                app_binary_name,
            )
        }

        cache_metadata, residency_rows, measured_paths = prepare_cache(
            target=target,
            bundle_id=bundle_id,
            policy=args.cache_policy,
            output_directory=working,
            development_team=args.development_team,
            pressure_fraction=args.pressure_fraction,
            pressure_hold_seconds=args.pressure_hold_seconds,
            pressure_megabytes=args.pressure_megabytes,
            host_free_percent=args.host_free_percent,
            maximum_host_pressure_delta=args.max_host_pressure_delta,
            allow_host_pressure=args.allow_host_pressure,
            residency_threshold=args.residency_threshold,
            allow_unconfirmed_cache=args.allow_unconfirmed_cache,
            require_cold_cache=args.require_cold_cache,
        )
        if not target.is_simulator:
            target_preparation["absence_after_cache_preparation"] = verify_app_absent(
                target,
                bundle_id,
                app_binary_name,
            )

        time_limit = args.time_limit or math.ceil(args.settle_seconds + 5)
        trace_path = working / "faults.trace"
        recording = start_recording(target, trace_path, time_limit, working)
        target_pid: Optional[int] = None
        try:
            wait_until_recording(recording)
            if should_validate_prelaunch_cache(
                target,
                installed_bundle,
                cache_metadata,
            ):
                assert installed_bundle is not None
                measured_paths = validate_prelaunch_cache(
                    installed_bundle=installed_bundle,
                    cache_metadata=cache_metadata,
                    residency_rows=residency_rows,
                    require_cold_cache=args.require_cold_cache,
                    residency_threshold=args.residency_threshold,
                    allow_unconfirmed_cache=args.allow_unconfirmed_cache,
                )
            target_pid = launch_app(
                target,
                bundle_id,
                args.app_argument,
                log_directory=working,
            )
            wait_for_post_launch_window(recording, args.settle_seconds)
            if target.is_simulator and measured_paths:
                residency_rows.extend(
                    measure_residency(
                        measured_paths,
                        "after_target_launch",
                    )
                )
        except BaseException:
            abort_recording(recording)
            raise
        else:
            finish_recording(recording, time_limit + 45)
        if target_pid is None:
            raise RuntimeError("Target launch did not return a PID")

        write_residency(working / "cache_residency.csv", residency_rows)
        toc_path, vm_path = export_trace(trace_path, working)
        stats = process_virtual_memory_export(
            vm_path,
            working,
            target_pid,
            app_binary_name,
            args.settle_seconds * 1_000,
            app_bundle_root,
        )

        xctrace_warnings = [
            line
            for line in recording.stderr_path.read_text(errors="replace").splitlines()
            if line.strip()
        ]
        metadata: dict[str, Any] = {
            "schema_version": 1,
            "bundle_id": bundle_id,
            "app_binary_name": app_binary_name,
            "app_bundle_root": app_bundle_root,
            "target_pid": target_pid,
            "target_kind": target.kind,
            "target_identifier": target.identifier,
            "target_name": target.name,
            "target_runtime": target.runtime,
            "target_preparation": target_preparation,
            "trace_scope": (
                "macOS host all-processes"
                if target.is_simulator
                else "physical device all-processes"
            ),
            "instrument": "Virtual Memory Trace",
            "recording_time_limit_seconds": time_limit,
            "settle_seconds": args.settle_seconds,
            "cache": cache_metadata,
            "capture_quality_warnings": xctrace_warnings[:50],
            "artifacts": {
                "trace": trace_path.name,
                "trace_toc": toc_path.name,
                "virtual_memory_export": vm_path.name,
                "events": "page_fault_events.csv",
                "major_events": "major_page_fault_events.csv",
                "major_summary": "major_page_fault_code_summary.csv",
                "database": "page_faults.sqlite",
                "report": "report.html",
            },
            "tool_versions": {
                "xctrace": run(xctrace_command("version")).stdout.strip(),
                "xcode": run(["xcodebuild", "-version"]).stdout.strip(),
                "ios_fault_visualizer": "0.1.0",
            },
            "capture_time": datetime.now().astimezone().isoformat(),
            "stats": stats,
        }
        if target.is_simulator and residency_rows:
            metadata["cache"]["residency_after_target_launch"] = summarize_residency(
                residency_rows, "after_target_launch"
            )
        (working / "capture_metadata.json").write_text(
            json.dumps(metadata, indent=2, sort_keys=True) + "\n"
        )
        build_report(
            working,
            working / "report.html",
            display_capture=output,
        )
        publish_staging_directory(working, output)
        return output
    finally:
        if working.exists():
            shutil.rmtree(working)


def reprocess(args: argparse.Namespace) -> Path:
    output = args.output.resolve()
    metadata_path = output / "capture_metadata.json"
    if not metadata_path.exists():
        raise RuntimeError(f"Capture metadata is missing: {metadata_path}")
    marker = output / CAPTURE_MARKER
    if (
        not marker.is_file()
        or marker.read_text(errors="replace") != CAPTURE_MARKER_CONTENT
    ):
        raise RuntimeError(f"Capture ownership marker is missing or invalid: {marker}")
    metadata = json.loads(metadata_path.read_text())
    if metadata.get("schema_version") != 1:
        raise RuntimeError(
            f"Unsupported capture schema: {metadata.get('schema_version')!r}"
        )
    trace_name = metadata.get("artifacts", {}).get("trace", "faults.trace")
    trace_path = (output / trace_name).resolve()
    if not trace_path.is_relative_to(output) or not trace_path.exists():
        raise RuntimeError(
            f"Capture trace is missing or escapes its directory: {trace_path}"
        )

    working = create_staging_directory(output)
    try:
        shutil.copytree(output, working, dirs_exist_ok=True)
        working_metadata_path = working / "capture_metadata.json"
        working_metadata = json.loads(working_metadata_path.read_text())
        working_trace = working / trace_name
        _, vm_path = export_trace(working_trace, working)
        stats = process_virtual_memory_export(
            vm_path,
            working,
            int(working_metadata["target_pid"]),
            str(working_metadata.get("app_binary_name") or ""),
            float(working_metadata.get("settle_seconds") or 3.0) * 1_000,
            str(working_metadata.get("app_bundle_root") or ""),
        )
        working_metadata["stats"] = stats
        working_metadata_path.write_text(
            json.dumps(working_metadata, indent=2, sort_keys=True) + "\n"
        )
        build_report(
            working,
            working / "report.html",
            display_capture=output,
        )
        publish_staging_directory(working, output)
        return output
    finally:
        if working.exists():
            shutil.rmtree(working)


def parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Collect exact Instruments Virtual Memory events for one iOS app "
            "startup and generate an interactive stack report"
        )
    )
    parser.add_argument("--bundle-id")
    parser.add_argument(
        "--app",
        type=Path,
        help="Optional built .app to install before capture",
    )
    parser.add_argument(
        "--app-binary-name",
        help="Executable name used to identify the first app frame",
    )
    parser.add_argument(
        "--device",
        default="booted",
        help="Simulator/physical device name or UDID; 'booted' requires one booted Simulator",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("output") / datetime.now().strftime("%Y%m%d-%H%M%S"),
    )
    parser.add_argument(
        "--cache-policy",
        choices=("auto", "purge", "pressure", "reboot", "none"),
        default="auto",
        help="auto uses host purge for Simulator and pressure for physical device",
    )
    parser.add_argument(
        "--require-cold-cache",
        action="store_true",
        help="Fail unless Simulator app-bundle residency confirms purge effectiveness",
    )
    parser.add_argument("--development-team")
    parser.add_argument("--pressure-fraction", type=float, default=0.15)
    parser.add_argument(
        "--pressure-megabytes",
        type=int,
        help="Optional fixed helper allocation, useful for controlled testing",
    )
    parser.add_argument("--pressure-hold-seconds", type=int, default=6)
    parser.add_argument(
        "--host-free-percent",
        type=int,
        default=50,
        help="Simulator memory_pressure target when purge is unavailable",
    )
    parser.add_argument(
        "--max-host-pressure-delta",
        type=int,
        default=20,
        help="Maximum percentage-point drop requested from host free memory",
    )
    parser.add_argument(
        "--allow-host-pressure",
        action="store_true",
        help="Allow auto policy to fall back from purge to memory_pressure",
    )
    parser.add_argument(
        "--residency-threshold",
        type=float,
        default=0.05,
        help="Maximum post-action Simulator app-file residency fraction",
    )
    parser.add_argument(
        "--allow-unconfirmed-cache",
        action="store_true",
        help="Continue a Simulator capture when mincore does not confirm eviction",
    )
    parser.add_argument("--settle-seconds", type=float, default=3.0)
    parser.add_argument("--time-limit", type=int)
    parser.add_argument(
        "--app-argument",
        action="append",
        default=[],
        help="Argument passed to the target app; repeat as needed",
    )
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument(
        "--skip-collect",
        action="store_true",
        help="Re-export, reprocess, and regenerate an existing capture",
    )
    return parser


def main() -> None:
    args = parser().parse_args()
    try:
        validate_recording_window(args.settle_seconds, args.time_limit)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if not 0.1 <= args.pressure_fraction <= 0.9:
        raise SystemExit("--pressure-fraction must be between 0.1 and 0.9")
    if args.pressure_hold_seconds < 1:
        raise SystemExit("--pressure-hold-seconds must be positive")
    if args.pressure_megabytes is not None and args.pressure_megabytes < 16:
        raise SystemExit("--pressure-megabytes must be at least 16")
    if not 10 <= args.host_free_percent <= 80:
        raise SystemExit("--host-free-percent must be between 10 and 80")
    if not 5 <= args.max_host_pressure_delta <= 50:
        raise SystemExit("--max-host-pressure-delta must be between 5 and 50")
    if not 0 <= args.residency_threshold <= 1:
        raise SystemExit("--residency-threshold must be between 0 and 1")
    if args.require_cold_cache and args.allow_unconfirmed_cache:
        raise SystemExit(
            "--require-cold-cache cannot be combined with --allow-unconfirmed-cache"
        )
    try:
        output = reprocess(args) if args.skip_collect else capture(args)
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    stats = json.loads((output / "page_fault_stats.json").read_text())
    counts = stats["class_counts"]
    print(
        f"Capture complete: {output}\n"
        f"  faults: {stats['event_count']:,} "
        f"({counts.get('Minor', 0):,} minor, {counts.get('Major', 0):,} major)\n"
        f"  report: {output / 'report.html'}"
    )


if __name__ == "__main__":
    main()
