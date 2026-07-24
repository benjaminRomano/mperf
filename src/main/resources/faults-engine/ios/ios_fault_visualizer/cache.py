from __future__ import annotations

import csv
import ctypes
import hashlib
import json
import os
import plistlib
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from .subprocesses import run


@dataclass(frozen=True)
class Residency:
    phase: str
    file_name: str
    size_bytes: int
    total_pages: int
    resident_pages: int

    @property
    def resident_fraction(self) -> float:
        return self.resident_pages / self.total_pages if self.total_pages else 0.0


def parse_vm_stat(text: str) -> dict[str, int]:
    values: dict[str, int] = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        name, value = line.split(":", 1)
        digits = value.strip().rstrip(".").replace(".", "")
        if digits.isdigit():
            values[name.strip()] = int(digits)
    return values


def read_vm_stat() -> dict[str, int]:
    return parse_vm_stat(run(["/usr/bin/vm_stat"]).stdout)


def app_executable(app_bundle: Path) -> Path:
    info_path = app_bundle / "Info.plist"
    if not info_path.exists():
        raise RuntimeError(f"App bundle is missing Info.plist: {app_bundle}")
    with info_path.open("rb") as file:
        info = plistlib.load(file)
    executable = info.get("CFBundleExecutable")
    if not executable:
        raise RuntimeError(f"App bundle has no CFBundleExecutable: {app_bundle}")
    path = app_bundle / str(executable)
    if not path.is_file():
        raise RuntimeError(f"App executable does not exist: {path}")
    return path


def app_bundle_files(
    app_bundle: Path,
    executable: Path | None = None,
    maximum_files: int | None = None,
) -> list[Path]:
    executable = executable or app_executable(app_bundle)
    candidates = [
        path
        for path in app_bundle.rglob("*")
        if path.is_file() and not path.is_symlink() and path.stat().st_size > 0
    ]
    candidates.sort(
        key=lambda path: (
            path != executable,
            path.suffix.lower()
            not in {".dylib", ".metallib", ".car", ".strings", ".plist"},
            -path.stat().st_size,
            str(path),
        )
    )
    return candidates if maximum_files is None else candidates[:maximum_files]


def file_inventory(app_bundle: Path, paths: Iterable[Path]) -> dict[str, object]:
    manifest = []
    total_bytes = 0
    for path in sorted(paths):
        stat = path.stat()
        relative = str(path.relative_to(app_bundle))
        manifest.append(f"{relative}\0{stat.st_size}\0{stat.st_mtime_ns}")
        total_bytes += stat.st_size
    digest = hashlib.sha256("\n".join(manifest).encode()).hexdigest()
    return {
        "regular_file_count": len(manifest),
        "regular_file_bytes": total_bytes,
        "manifest_sha256": digest,
        "fingerprint_fields": ["relative_path", "size_bytes", "mtime_ns"],
        "coverage": "complete",
    }


def file_residency(path: Path, phase: str) -> Residency:
    size = path.stat().st_size
    page_size = os.sysconf("SC_PAGE_SIZE")
    page_count = (size + page_size - 1) // page_size
    if not size:
        return Residency(phase, str(path), 0, 0, 0)

    libc = ctypes.CDLL(None, use_errno=True)
    libc.mmap.restype = ctypes.c_void_p
    libc.mmap.argtypes = [
        ctypes.c_void_p,
        ctypes.c_size_t,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_int,
        ctypes.c_longlong,
    ]
    libc.mincore.argtypes = [
        ctypes.c_void_p,
        ctypes.c_size_t,
        ctypes.POINTER(ctypes.c_ubyte),
    ]
    libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]

    file_descriptor = os.open(path, os.O_RDONLY)
    try:
        address = libc.mmap(None, size, 1, 2, file_descriptor, 0)
        failed = ctypes.c_void_p(-1).value
        if address == failed:
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), str(path))
        try:
            vector = (ctypes.c_ubyte * page_count)()
            if libc.mincore(address, size, vector) != 0:
                error = ctypes.get_errno()
                raise OSError(error, os.strerror(error), str(path))
            resident = sum(1 for value in vector if value & 1)
        finally:
            libc.munmap(address, size)
    finally:
        os.close(file_descriptor)
    return Residency(phase, str(path), size, page_count, resident)


def measure_residency(
    paths: Iterable[Path],
    phase: str,
    errors: list[dict[str, str]] | None = None,
) -> list[Residency]:
    rows = []
    for path in paths:
        try:
            rows.append(file_residency(path, phase))
        except (OSError, PermissionError) as error:
            if errors is not None:
                errors.append(
                    {
                        "phase": phase,
                        "file_name": str(path),
                        "error": str(error),
                    }
                )
    return rows


def write_residency(path: Path, rows: Iterable[Residency]) -> None:
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(
            file,
            fieldnames=[
                "phase",
                "file_name",
                "size_bytes",
                "total_pages",
                "resident_pages",
                "resident_fraction",
            ],
        )
        writer.writeheader()
        for row in rows:
            values = asdict(row)
            values["resident_fraction"] = f"{row.resident_fraction:.6f}"
            writer.writerow(values)


def purge_host_cache() -> dict[str, object]:
    before = read_vm_stat()
    run(["/bin/sync"])
    result = run(["/usr/sbin/purge"], check=False)
    after = read_vm_stat()
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(
            "macOS purge failed"
            + (f": {detail}" if detail else f" ({result.returncode})")
        )
    return {
        "procedure": "macos-sync+purge",
        "command_succeeded": True,
        "vm_stat_before": before,
        "vm_stat_after": after,
    }


def pressure_host_cache(
    percent_free: int, maximum_delta_points: int = 20
) -> dict[str, object]:
    if not 10 <= percent_free <= 80:
        raise ValueError("Host percent-free target must be between 10 and 80")
    if not 5 <= maximum_delta_points <= 50:
        raise ValueError("Host pressure delta cap must be between 5 and 50")
    status = run(["/usr/bin/memory_pressure", "-w", "100", "-Q"]).stdout
    match = re.search(r"free percentage:\s*(\d+)%", status)
    if not match:
        raise RuntimeError("Unable to parse current free memory percentage")
    starting_percent_free = int(match.group(1))
    effective_percent_free = max(
        min(percent_free, starting_percent_free - 10),
        starting_percent_free - maximum_delta_points,
        10,
    )
    before = read_vm_stat()
    result = run(
        [
            "/usr/bin/memory_pressure",
            "-p",
            str(effective_percent_free),
            "-s",
            "1",
            "-y",
            "1",
            "-Q",
        ],
        check=False,
        timeout=60,
    )
    if result.returncode not in (0, -9):
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(
            f"memory_pressure failed ({result.returncode})"
            + (f": {detail}" if detail else "")
        )
    after = read_vm_stat()
    return {
        "procedure": "macos-memory-pressure",
        "command_succeeded": result.returncode == 0,
        "return_code": result.returncode,
        "terminated_by_signal": (-result.returncode if result.returncode < 0 else None),
        "requested_percent_free": percent_free,
        "maximum_delta_points": maximum_delta_points,
        "starting_percent_free": starting_percent_free,
        "target_percent_free": effective_percent_free,
        "command_output": (result.stdout or "").strip(),
        "vm_stat_before": before,
        "vm_stat_after": after,
    }


def summarize_residency(rows: Iterable[Residency], phase: str) -> dict[str, object]:
    selected = [row for row in rows if row.phase == phase]
    total_pages = sum(row.total_pages for row in selected)
    resident_pages = sum(row.resident_pages for row in selected)
    return {
        "phase": phase,
        "files": len(selected),
        "total_pages": total_pages,
        "resident_pages": resident_pages,
        "resident_fraction": (resident_pages / total_pages if total_pages else None),
    }


def write_cache_metadata(path: Path, metadata: dict[str, object]) -> None:
    path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
