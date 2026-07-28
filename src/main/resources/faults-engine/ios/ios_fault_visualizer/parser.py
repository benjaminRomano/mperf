from __future__ import annotations

import csv
import json
import pickle
import sqlite3
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable

MINOR_OPERATIONS = {
    "Page Cache Hit",
    "Zero Fill",
    "Copy On Write",
    "Decompress Memory",
}
MAJOR_OPERATIONS = {"File Backed Page In"}

EVENT_FIELDS = [
    "event_index",
    "trace_time_seconds",
    "time_since_first_fault_ms",
    "fault_class",
    "operation",
    "process_name",
    "address",
    "address_hex",
    "size_bytes",
    "duration_ns",
    "thread",
    "tid",
    "faulting_frame",
    "faulting_instruction",
    "faulting_binary",
    "faulting_binary_path",
    "faulting_binary_is_bundle_owned",
    "faulting_source_path",
    "faulting_source_line",
    "first_symbolicated_frame",
    "first_symbolicated_binary",
    "first_app_frame",
    "first_app_binary",
    "first_app_source_path",
    "first_app_source_line",
    "stack_depth",
    "stack",
]

MAJOR_SUMMARY_FIELDS = [
    "major_fault_count",
    "unique_addresses",
    "first_fault_ms",
    "last_fault_ms",
    "total_fault_duration_ms",
    "faulting_frame",
    "faulting_instruction",
    "faulting_binary",
    "faulting_binary_path",
    "faulting_source_path",
    "faulting_source_line",
    "first_symbolicated_frame",
    "first_symbolicated_binary",
    "first_app_frame",
    "first_app_binary",
    "first_app_source_path",
    "first_app_source_line",
    "example_address_hex",
    "example_stack",
]

GENERIC_APP_ENTRY_POINTS = {
    "_start",
    "start",
    "main",
    "NSExtensionMain",
    "UIApplicationMain",
}
GENERIC_APP_FRAME_PREFIXES = (
    "__llvm_profile_",
    "<deduplicated",
    "<redacted",
    "<unknown",
)


class ValueRegistry:
    def __init__(
        self,
        *,
        disk_backed: bool,
        maximum_cached_values: int = 20_000,
    ):
        self._memory: dict[tuple[str, str], Any] | None = None if disk_backed else {}
        self._cache: OrderedDict[tuple[str, str], Any] = OrderedDict()
        self._maximum_cached_values = maximum_cached_values
        self._temporary_directory: tempfile.TemporaryDirectory[str] | None = None
        self._connection: sqlite3.Connection | None = None
        if disk_backed:
            self._temporary_directory = tempfile.TemporaryDirectory(
                prefix="ios-fault-registry-"
            )
            path = Path(self._temporary_directory.name) / "registry.sqlite"
            self._connection = sqlite3.connect(path)
            self._connection.execute(
                "CREATE TABLE value_registry "
                "(tag TEXT, identifier TEXT, payload BLOB, "
                "PRIMARY KEY(tag, identifier)) WITHOUT ROWID"
            )

    def _remember(self, key: tuple[str, str], value: Any) -> None:
        self._cache[key] = value
        self._cache.move_to_end(key)
        if len(self._cache) > self._maximum_cached_values:
            self._cache.popitem(last=False)

    def get(self, key: tuple[str, str]) -> Any:
        if self._memory is not None:
            return self._memory.get(key)
        cached = self._cache.get(key)
        if cached is not None:
            self._cache.move_to_end(key)
            return cached
        assert self._connection is not None
        row = self._connection.execute(
            "SELECT payload FROM value_registry " "WHERE tag = ? AND identifier = ?",
            key,
        ).fetchone()
        if row is None:
            return None
        value = pickle.loads(row[0])
        self._remember(key, value)
        return value

    def __setitem__(self, key: tuple[str, str], value: Any) -> None:
        if self._memory is not None:
            self._memory[key] = value
            return
        assert self._connection is not None
        self._connection.execute(
            "INSERT OR REPLACE INTO value_registry VALUES (?, ?, ?)",
            (*key, pickle.dumps(value, protocol=pickle.HIGHEST_PROTOCOL)),
        )
        self._remember(key, value)

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
            self._connection = None
        if self._temporary_directory is not None:
            self._temporary_directory.cleanup()
            self._temporary_directory = None
        self._cache.clear()


def _child_value(registry: ValueRegistry, element: ET.Element, tag: str):
    child = element.find(tag)
    return _element_value(registry, child) if child is not None else None


def _element_value(registry: ValueRegistry, element: ET.Element) -> Any:
    tag = element.tag
    reference = element.get("ref")
    if reference is not None:
        return registry.get((tag, reference))
    if tag in {"start-time", "duration", "address", "size-in-bytes", "tid", "pid"}:
        return int(element.text or "0")
    if tag in {"vm-op", "path"}:
        return element.get("fmt") or element.text or ""
    if tag == "binary":
        return {
            "name": element.get("name", ""),
            "path": element.get("path", ""),
            "uuid": element.get("UUID", ""),
            "arch": element.get("arch", ""),
        }
    if tag == "source":
        return {
            "path": _child_value(registry, element, "path") or "",
            "line": int(element.get("line", "0") or "0"),
        }
    if tag == "frame":
        return {
            "name": element.get("name", ""),
            "address": element.get("addr", ""),
            "binary": _child_value(registry, element, "binary") or {},
            "source": _child_value(registry, element, "source") or {},
        }
    if tag == "backtrace":
        return [
            frame
            for child in element.findall("frame")
            if (frame := _element_value(registry, child))
        ]
    if tag == "tagged-backtrace":
        return {
            "label": element.get("fmt", ""),
            "frames": _child_value(registry, element, "backtrace") or [],
        }
    if tag == "process":
        return {
            "name": element.get("fmt", ""),
            "pid": _child_value(registry, element, "pid"),
        }
    if tag == "thread":
        return {
            "name": element.get("fmt", ""),
            "tid": _child_value(registry, element, "tid"),
            "process": _child_value(registry, element, "process") or {},
        }
    return element.text


def _register(registry: ValueRegistry, element: ET.Element) -> None:
    identifier = element.get("id")
    if identifier is None:
        return
    value = _element_value(registry, element)
    if value is not None:
        registry[(element.tag, identifier)] = value


def _frame_label(frame: dict[str, Any]) -> str:
    name = frame.get("name") or frame.get("address") or ""
    binary = frame.get("binary") or {}
    source = frame.get("source") or {}
    source_path = source.get("path", "")
    line = source.get("line", 0)
    location = (
        f"{source_path}:{line}" if source_path and line else source_path
    ) or binary.get("name", "")
    return f"{name} [{location}]" if location else name


def _first_frame(
    frames: Iterable[dict[str, Any]],
    predicate: Callable[[dict[str, Any]], bool],
) -> dict[str, Any]:
    return next((frame for frame in frames if predicate(frame)), {})


def _path_is_within_bundle(binary_path: str, app_bundle_root: str) -> bool:
    if not binary_path or not app_bundle_root:
        return False
    try:
        PurePosixPath(binary_path).relative_to(PurePosixPath(app_bundle_root))
        return True
    except ValueError:
        return False


def _infer_bundle_root(
    frames: Iterable[dict[str, Any]],
) -> str:
    for frame in frames:
        binary = frame.get("binary") or {}
        path = PurePosixPath(str(binary.get("path") or ""))
        for index, part in enumerate(path.parts):
            if part.endswith(".app"):
                return str(PurePosixPath(*path.parts[: index + 1]))
    return ""


def _is_app_frame(
    frame: dict[str, Any],
    process_name: str,
    app_binary_name: str,
    app_bundle_root: str = "",
) -> bool:
    binary = frame.get("binary") or {}
    binary_name = str(binary.get("name") or "")
    binary_path = str(binary.get("path") or "")
    if app_bundle_root and binary_path:
        return _path_is_within_bundle(binary_path, app_bundle_root)
    candidates = {name for name in (process_name, app_binary_name) if name}
    if binary_name in candidates:
        return True
    return ".app/" in binary_path and any(
        binary_path.endswith(f"/{candidate}") for candidate in candidates
    )


def parse_events(
    xml_path: Path,
    target_pid: int,
    app_binary_name: str = "",
    maximum_time_since_first_ms: float | None = None,
    app_bundle_root: str = "",
) -> list[dict[str, Any]]:
    registry = ValueRegistry(disk_backed=xml_path.stat().st_size >= 64 * 1024 * 1024)
    events: list[dict[str, Any]] = []
    resolved_bundle_root = app_bundle_root
    for _, element in ET.iterparse(xml_path, events=("end",)):
        _register(registry, element)
        if element.tag != "row":
            continue
        process = _child_value(registry, element, "process") or {}
        if process.get("pid") != target_pid:
            element.clear()
            continue
        operation = _child_value(registry, element, "vm-op") or ""
        if operation in MAJOR_OPERATIONS:
            fault_class = "Major"
        elif operation in MINOR_OPERATIONS:
            fault_class = "Minor"
        else:
            element.clear()
            continue

        trace_start_ns = _child_value(registry, element, "start-time") or 0
        address = _child_value(registry, element, "address") or 0
        process_name = str(process.get("name") or app_binary_name)
        thread = _child_value(registry, element, "thread") or {}
        tagged_backtrace = _child_value(registry, element, "tagged-backtrace") or {}
        frames = tagged_backtrace.get("frames") or []
        if not resolved_bundle_root:
            resolved_bundle_root = _infer_bundle_root(
                frames,
            )
        faulting = frames[0] if frames else {}
        symbolicated = _first_frame(
            frames,
            lambda frame: bool(frame.get("name"))
            and not str(frame.get("name")).startswith("0x"),
        )
        app_frame = _first_frame(
            frames,
            lambda frame: _is_app_frame(
                frame,
                process_name,
                app_binary_name,
                resolved_bundle_root,
            ),
        )

        faulting_binary = faulting.get("binary") or {}
        faulting_source = faulting.get("source") or {}
        symbolicated_binary = symbolicated.get("binary") or {}
        app_binary = app_frame.get("binary") or {}
        app_source = app_frame.get("source") or {}
        event = {
            "trace_start_ns": trace_start_ns,
            "fault_class": fault_class,
            "operation": operation,
            "process_name": process_name,
            "address": address,
            "address_hex": f"0x{address:x}",
            "size_bytes": _child_value(registry, element, "size-in-bytes") or 0,
            "duration_ns": _child_value(registry, element, "duration") or 0,
            "thread": thread.get("name", ""),
            "tid": thread.get("tid"),
            "faulting_frame": faulting.get("name")
            or faulting.get("address")
            or tagged_backtrace.get("label", ""),
            "faulting_instruction": faulting.get("address", ""),
            "faulting_binary": faulting_binary.get("name", ""),
            "faulting_binary_path": faulting_binary.get("path", ""),
            "faulting_binary_is_bundle_owned": _path_is_within_bundle(
                str(faulting_binary.get("path") or ""),
                resolved_bundle_root,
            ),
            "faulting_source_path": faulting_source.get("path", ""),
            "faulting_source_line": faulting_source.get("line", 0),
            "first_symbolicated_frame": symbolicated.get("name", ""),
            "first_symbolicated_binary": symbolicated_binary.get("name", ""),
            "first_app_frame": app_frame.get("name", ""),
            "first_app_binary": app_binary.get("name", ""),
            "first_app_source_path": app_source.get("path", ""),
            "first_app_source_line": app_source.get("line", 0),
            "stack_depth": len(frames),
            "stack": " ← ".join(_frame_label(frame) for frame in frames),
        }
        events.append(event)
        element.clear()

    if resolved_bundle_root:
        for event in events:
            event["faulting_binary_is_bundle_owned"] = _path_is_within_bundle(
                str(event.get("faulting_binary_path") or ""),
                resolved_bundle_root,
            )
    registry.close()
    events.sort(key=lambda event: event["trace_start_ns"])
    if not events:
        return []
    first_ns = events[0]["trace_start_ns"]
    if maximum_time_since_first_ms is not None:
        maximum_ns = maximum_time_since_first_ms * 1_000_000
        events = [
            event
            for event in events
            if event["trace_start_ns"] - first_ns <= maximum_ns
        ]
    for index, event in enumerate(events, start=1):
        event["event_index"] = index
        event["trace_time_seconds"] = event["trace_start_ns"] / 1_000_000_000
        event["time_since_first_fault_ms"] = (
            event["trace_start_ns"] - first_ns
        ) / 1_000_000
    return events


def summarize_major_faults(events: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: defaultdict[tuple[Any, ...], list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        faulting_frame = str(event.get("faulting_frame") or "")
        if (
            event["fault_class"] != "Major"
            or not event.get("faulting_binary_is_bundle_owned")
            or not faulting_frame
            or faulting_frame.startswith("0x")
            or faulting_frame in GENERIC_APP_ENTRY_POINTS
            or faulting_frame.startswith(GENERIC_APP_FRAME_PREFIXES)
        ):
            continue
        key = tuple(
            event[field]
            for field in (
                "faulting_frame",
                "faulting_instruction",
                "faulting_binary",
                "faulting_binary_path",
                "faulting_source_path",
                "faulting_source_line",
                "first_symbolicated_frame",
                "first_symbolicated_binary",
                "first_app_frame",
                "first_app_binary",
                "first_app_source_path",
                "first_app_source_line",
            )
        )
        grouped[key].append(event)

    summaries = []
    for key, rows in grouped.items():
        summary = dict(
            zip(
                (
                    "faulting_frame",
                    "faulting_instruction",
                    "faulting_binary",
                    "faulting_binary_path",
                    "faulting_source_path",
                    "faulting_source_line",
                    "first_symbolicated_frame",
                    "first_symbolicated_binary",
                    "first_app_frame",
                    "first_app_binary",
                    "first_app_source_path",
                    "first_app_source_line",
                ),
                key,
            )
        )
        summary.update(
            {
                "major_fault_count": len(rows),
                "unique_addresses": len({row["address"] for row in rows}),
                "first_fault_ms": min(row["time_since_first_fault_ms"] for row in rows),
                "last_fault_ms": max(row["time_since_first_fault_ms"] for row in rows),
                "total_fault_duration_ms": sum(row["duration_ns"] for row in rows)
                / 1_000_000,
                "example_address_hex": rows[0]["address_hex"],
                "example_stack": rows[0]["stack"],
            }
        )
        summaries.append(summary)
    summaries.sort(
        key=lambda row: (
            -row["major_fault_count"],
            row["first_fault_ms"],
            row["faulting_frame"],
        )
    )
    return summaries


def _write_csv(path: Path, rows: Iterable[dict[str, Any]], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def _write_sqlite(
    path: Path,
    events: list[dict[str, Any]],
    summaries: list[dict[str, Any]],
) -> None:
    if path.exists():
        path.unlink()
    connection = sqlite3.connect(path)
    try:
        event_columns = {
            field: (
                "REAL"
                if field in {"trace_time_seconds", "time_since_first_fault_ms"}
                else (
                    "INTEGER"
                    if field
                    in {
                        "event_index",
                        "size_bytes",
                        "duration_ns",
                        "tid",
                        "faulting_source_line",
                        "faulting_binary_is_bundle_owned",
                        "first_app_source_line",
                        "stack_depth",
                    }
                    else "TEXT"
                )
            )
            for field in EVENT_FIELDS
        }
        connection.execute(
            "CREATE TABLE page_fault_events ("
            + ", ".join(f'"{column}" {kind}' for column, kind in event_columns.items())
            + ")"
        )
        placeholders = ", ".join("?" for _ in EVENT_FIELDS)
        connection.executemany(
            f"INSERT INTO page_fault_events VALUES ({placeholders})",
            [
                tuple(
                    str(event.get(field)) if field == "address" else event.get(field)
                    for field in EVENT_FIELDS
                )
                for event in events
            ],
        )
        summary_columns = {
            field: (
                "REAL"
                if field
                in {
                    "first_fault_ms",
                    "last_fault_ms",
                    "total_fault_duration_ms",
                }
                else (
                    "INTEGER"
                    if field
                    in {
                        "major_fault_count",
                        "unique_addresses",
                        "faulting_source_line",
                        "first_app_source_line",
                    }
                    else "TEXT"
                )
            )
            for field in MAJOR_SUMMARY_FIELDS
        }
        connection.execute(
            "CREATE TABLE major_page_fault_code_summary ("
            + ", ".join(
                f'"{column}" {kind}' for column, kind in summary_columns.items()
            )
            + ")"
        )
        placeholders = ", ".join("?" for _ in MAJOR_SUMMARY_FIELDS)
        connection.executemany(
            f"INSERT INTO major_page_fault_code_summary VALUES ({placeholders})",
            [
                tuple(summary.get(field) for field in MAJOR_SUMMARY_FIELDS)
                for summary in summaries
            ],
        )
        connection.commit()
    finally:
        connection.close()


def process_virtual_memory_export(
    xml_path: Path,
    output_directory: Path,
    target_pid: int,
    app_binary_name: str = "",
    maximum_time_since_first_ms: float | None = None,
    app_bundle_root: str = "",
) -> dict[str, Any]:
    events = parse_events(
        xml_path,
        target_pid,
        app_binary_name,
        maximum_time_since_first_ms,
        app_bundle_root,
    )
    if not events:
        raise RuntimeError(
            f"No supported Virtual Memory fault rows were found for PID {target_pid}"
        )
    major = [event for event in events if event["fault_class"] == "Major"]
    summaries = summarize_major_faults(events)
    _write_csv(output_directory / "page_fault_events.csv", events, EVENT_FIELDS)
    _write_csv(output_directory / "major_page_fault_events.csv", major, EVENT_FIELDS)
    _write_csv(
        output_directory / "major_page_fault_code_summary.csv",
        summaries,
        MAJOR_SUMMARY_FIELDS,
    )
    _write_sqlite(output_directory / "page_faults.sqlite", events, summaries)

    class_counts = Counter(event["fault_class"] for event in events)
    operation_counts = Counter(event["operation"] for event in events)
    page_sizes = Counter(
        event["size_bytes"] for event in events if event["size_bytes"] > 0
    )
    stats = {
        "schema_version": 1,
        "target_pid": target_pid,
        "event_count": len(events),
        "class_counts": dict(sorted(class_counts.items())),
        "operation_counts": dict(sorted(operation_counts.items())),
        "capture_span_ms": events[-1]["time_since_first_fault_ms"],
        "analysis_window_ms": maximum_time_since_first_ms,
        "page_size_bytes": page_sizes.most_common(1)[0][0] if page_sizes else None,
        "major_faults_with_stack": sum(event["stack_depth"] > 0 for event in major),
        "major_faults_with_symbolicated_top_frame": sum(
            bool(event["faulting_frame"])
            and not str(event["faulting_frame"]).startswith("0x")
            for event in major
        ),
        "major_faults_with_app_frame": sum(
            bool(event["first_app_frame"]) for event in major
        ),
        "major_faults_with_bundle_owned_faulting_binary": sum(
            bool(event["faulting_binary_is_bundle_owned"]) for event in major
        ),
        "ordering_candidate_groups": len(summaries),
        "ordering_candidate_faults": sum(
            int(summary["major_fault_count"]) for summary in summaries
        ),
        "classification": {
            "Major": sorted(MAJOR_OPERATIONS),
            "Minor": sorted(MINOR_OPERATIONS),
        },
        "classification_note": (
            "Major/minor are analysis buckets inferred from Instruments VM "
            "operations, not kernel accounting labels emitted by iOS."
        ),
    }
    (output_directory / "page_fault_stats.json").write_text(
        json.dumps(stats, indent=2, sort_keys=True) + "\n"
    )
    return stats
