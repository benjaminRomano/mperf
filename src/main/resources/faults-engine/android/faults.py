import argparse
import bisect
import csv
import hashlib
import io
import json
import os
import platform
import re
import shlex
import shutil
import signal
import struct
import subprocess
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Optional
from zipfile import ZIP_STORED, ZipFile


ROOT_DIR = Path(__file__).resolve().parent
TRACE_PROCESSOR = ROOT_DIR / "trace_processor"
TRACE_CONFIG = ROOT_DIR / "ftrace.config"
COLLECTOR_SOURCE = ROOT_DIR / "native" / "page_fault_collector.c"
REMOTE_DIR = "/data/local/tmp/android-fault-visualizer"
REMOTE_COLLECTOR = f"{REMOTE_DIR}/page_fault_collector"
REMOTE_FAULTS = f"{REMOTE_DIR}/fault_events.csv"
REMOTE_MAPPINGS = f"{REMOTE_DIR}/mapping_events.csv"
CAPTURE_MARKER = ".android-fault-visualizer-capture"
CAPTURE_MARKER_CONTENT = "android-fault-visualizer capture v1\n"
CACHE_RESIDENCY_FIELDS = [
    "phase",
    "file_name",
    "size_bytes",
    "total_pages",
    "resident_pages",
]


@dataclass(frozen=True)
class MapEntry:
    begin_address: int
    end_address: int
    permissions: str
    file_offset: int
    device: int
    inode: int
    file_name: Optional[str]


@dataclass(frozen=True)
class ZipEntry:
    file_name: str
    header_offset: int
    data_offset: int
    data_end: int
    compressed_size: int
    uncompressed_size: int
    compression: str


@dataclass(frozen=True)
class MappingEvent:
    timestamp_ns: int
    mapping: MapEntry


@dataclass(frozen=True)
class VdexAnalysis:
    format_version: str
    stored_checksums: tuple[int, ...]
    dex_ranges: tuple[ZipEntry, ...]
    identities_verified: bool
    verification_note: str


class Adb:
    def __init__(self, serial: Optional[str] = None):
        self.serial = self._resolve_serial(serial)
        self._root_template: Optional[str] = None

    @staticmethod
    def _resolve_serial(requested: Optional[str]) -> str:
        result = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, check=True
        )
        devices = []
        for line in result.stdout.splitlines()[1:]:
            columns = line.split()
            if len(columns) >= 2 and columns[1] == "device":
                devices.append(columns[0])

        if requested:
            if requested not in devices:
                raise RuntimeError(
                    f"ADB device {requested!r} is not connected. Connected: {devices}"
                )
            return requested
        if not devices:
            raise RuntimeError("No connected ADB device found")
        if len(devices) > 1:
            raise RuntimeError(
                "Multiple ADB devices are connected; select one with --serial: "
                + ", ".join(devices)
            )
        return devices[0]

    @property
    def base_command(self) -> list[str]:
        return ["adb", "-s", self.serial]

    def run(self, args: list[str], **kwargs) -> subprocess.CompletedProcess:
        return subprocess.run(self.base_command + args, **kwargs)

    def shell(self, args: list[str], **kwargs) -> subprocess.CompletedProcess:
        return self.run(["shell", *args], **kwargs)

    def shell_text(self, command: str, **kwargs) -> subprocess.CompletedProcess:
        return self.run(["shell", command], **kwargs)

    def ensure_root(self) -> None:
        root_result = self.run(["root"], capture_output=True, text=True)
        if root_result.returncode == 0:
            self.run(["wait-for-device"], check=True)

        candidates = [
            "sh -c {command}",
            "su 0 sh -c {command}",
            "su -c {command}",
        ]
        for template in candidates:
            command = template.format(command=shlex.quote("id"))
            result = self.shell_text(command, capture_output=True, text=True)
            if result.returncode == 0 and "uid=0" in result.stdout:
                self._root_template = template
                return
        raise RuntimeError(
            "Unable to acquire a root shell. Use a userdebug emulator/image or "
            "a rooted device with adb-accessible su."
        )

    def root_command(self, command: str) -> list[str]:
        if self._root_template is None:
            raise RuntimeError("ensure_root() must be called first")
        remote_command = self._root_template.format(command=shlex.quote(command))
        return self.base_command + ["shell", remote_command]

    def root_shell(self, command: str, **kwargs) -> subprocess.CompletedProcess:
        return subprocess.run(self.root_command(command), **kwargs)

    def getprop(self, name: str) -> str:
        return self.shell(
            ["getprop", name], capture_output=True, text=True, check=True
        ).stdout.strip()

    def reboot_and_wait(self, timeout_seconds: int = 180) -> None:
        self.run(["reboot"], check=True)
        self.run(["wait-for-device"], check=True)
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            result = self.shell(
                ["getprop", "sys.boot_completed"],
                capture_output=True,
                text=True,
            )
            if result.returncode == 0 and result.stdout.strip() == "1":
                return
            time.sleep(1)
        raise RuntimeError("Timed out waiting for Android to finish rebooting")

    def pull_with_root_fallback(self, remote: str, local: Path) -> None:
        result = self.run(
            ["pull", remote, str(local)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        if result.returncode == 0:
            return
        if self._root_template is None:
            raise RuntimeError(f"Unable to pull {remote}: {result.stderr.strip()}")
        remote_command = self._root_template.format(
            command=shlex.quote(f"cat {shlex.quote(remote)}")
        )
        with local.open("wb") as output:
            fallback = subprocess.run(
                self.base_command + ["exec-out", remote_command],
                stdout=output,
                stderr=subprocess.PIPE,
            )
        if fallback.returncode != 0:
            local.unlink(missing_ok=True)
            raise RuntimeError(
                f"Unable to pull {remote} with root: "
                + fallback.stderr.decode(errors="replace").strip()
            )


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def is_app_owned_path(file_name: str, package: str) -> bool:
    """Match an exact package path segment or hashed /data/app install segment."""
    escaped = re.escape(package)
    return re.search(rf"(?:^|/){escaped}(?:/|-[^/]+(?:/|$)|$)", file_name) is not None


def linux_device_number(device: str) -> int:
    major_text, minor_text = device.split(":", 1)
    major = int(major_text, 16)
    minor = int(minor_text, 16)
    return (minor & 0xFF) | (major << 8) | ((minor & ~0xFF) << 12)


MAPS_PATTERN = re.compile(
    r"^(?P<begin>[0-9a-fA-F]+)-(?P<end>[0-9a-fA-F]+)\s+"
    r"(?P<permissions>\S+)\s+(?P<offset>[0-9a-fA-F]+)\s+"
    r"(?P<device>[0-9a-fA-F]+:[0-9a-fA-F]+)\s+"
    r"(?P<inode>\d+)(?:\s+(?P<path>.*))?$"
)


def parse_maps_text(text: str) -> list[MapEntry]:
    entries = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        match = MAPS_PATTERN.match(line)
        if not match:
            raise ValueError(f"Malformed maps line {line_number}: {line!r}")
        path = match.group("path")
        entries.append(
            MapEntry(
                begin_address=int(match.group("begin"), 16),
                end_address=int(match.group("end"), 16),
                permissions=match.group("permissions"),
                file_offset=int(match.group("offset"), 16),
                device=linux_device_number(match.group("device")),
                inode=int(match.group("inode")),
                file_name=path,
            )
        )
    return sorted(entries, key=lambda entry: entry.begin_address)


def parse_maps(output_dir: str | Path) -> list[MapEntry]:
    return parse_maps_text((Path(output_dir) / "maps.txt").read_text())


def find_map_entry(map_entries: list[MapEntry], address: int) -> Optional[MapEntry]:
    if not map_entries:
        return None
    begins = [entry.begin_address for entry in map_entries]
    index = bisect.bisect_right(begins, address) - 1
    if index < 0:
        return None
    entry = map_entries[index]
    # /proc/<pid>/maps ranges are half-open: [begin, end).
    return entry if entry.begin_address <= address < entry.end_address else None


def parse_mapping_events(output_dir: Path, pid: int) -> list[MappingEvent]:
    events = []
    with (output_dir / "mapping_events.csv").open() as file:
        for row in csv.DictReader(file):
            if int(row["pid"]) != pid:
                continue
            protection = int(row["protection"])
            permissions = "".join(
                [
                    "r" if protection & 1 else "-",
                    "w" if protection & 2 else "-",
                    "x" if protection & 4 else "-",
                    "s" if int(row["flags"]) & 1 else "p",
                ]
            )
            begin = int(row["address"], 16)
            events.append(
                MappingEvent(
                    timestamp_ns=int(row["timestamp_ns"]),
                    mapping=MapEntry(
                        begin_address=begin,
                        end_address=begin + int(row["length"], 16),
                        permissions=permissions,
                        file_offset=int(row["file_offset"], 16),
                        device=linux_device_number(
                            f"{int(row['device_major']):x}:"
                            f"{int(row['device_minor']):x}"
                        ),
                        inode=int(row["inode"]),
                        file_name=row["file_name"] or None,
                    ),
                )
            )
    return sorted(events, key=lambda event: event.timestamp_ns)


def find_map_entry_at(
    snapshot: list[MapEntry],
    events: list[MappingEvent],
    address: int,
    timestamp_ns: int,
) -> Optional[MapEntry]:
    matching_events = [
        event
        for event in events
        if event.mapping.begin_address <= address < event.mapping.end_address
    ]
    for event in reversed(matching_events):
        if event.timestamp_ns <= timestamp_ns:
            return event.mapping
    # A future event for this address means the final snapshot may describe a
    # mapping that did not exist yet. Snapshot fallback is only safe for
    # inherited mappings that produced no MMAP2 event after collection began.
    return None if matching_events else find_map_entry(snapshot, address)


def read_zip_entries(path: Path) -> list[ZipEntry]:
    entries = []
    with ZipFile(path) as archive, path.open("rb") as raw_file:
        for info in archive.infolist():
            raw_file.seek(info.header_offset)
            header = raw_file.read(30)
            if len(header) != 30:
                raise ValueError(f"Truncated local ZIP header for {info.filename}")
            (
                signature,
                _version,
                _flags,
                _method,
                _time,
                _date,
                _crc,
                _compressed_size,
                _uncompressed_size,
                name_length,
                extra_length,
            ) = struct.unpack("<IHHHHHIIIHH", header)
            if signature != 0x04034B50:
                raise ValueError(f"Invalid local ZIP header for {info.filename}")
            data_offset = info.header_offset + 30 + name_length + extra_length
            entries.append(
                ZipEntry(
                    file_name=info.filename,
                    header_offset=info.header_offset,
                    data_offset=data_offset,
                    data_end=data_offset + info.compress_size,
                    compressed_size=info.compress_size,
                    uncompressed_size=info.file_size,
                    compression=(
                        "stored"
                        if info.compress_type == ZIP_STORED
                        else f"method-{info.compress_type}"
                    ),
                )
            )
    return sorted(entries, key=lambda entry: entry.data_offset)


def _verified_vdex_names(
    stored_checksums: list[int],
    apk_dex_identities: Optional[list[tuple[str, int]]],
) -> list[str]:
    if apk_dex_identities is None:
        return []
    apk_checksums = [checksum for _, checksum in apk_dex_identities]
    if len(apk_checksums) != len(stored_checksums):
        return []
    if apk_checksums != stored_checksums:
        return []
    return [name for name, _ in apk_dex_identities]


def _read_vdex_dex_ranges(
    data: bytes,
    dex_begin: int,
    dex_end: int,
    number_of_dex_files: int,
    verified_names: list[str],
    *,
    quickening_prefix_size: int,
    compression_prefix: str,
) -> list[ZipEntry]:
    entries: list[ZipEntry] = []
    cursor = dex_begin
    for index in range(number_of_dex_files):
        dex_start = cursor + quickening_prefix_size
        if dex_start + 36 > dex_end:
            return []
        magic = data[dex_start : dex_start + 4]
        if magic not in (b"dex\n", b"cdex"):
            return []
        file_size = struct.unpack_from("<I", data, dex_start + 32)[0]
        dex_payload_end = dex_start + file_size
        if file_size < 112 or dex_payload_end > dex_end:
            return []
        verified_name = verified_names[index] if index < len(verified_names) else None
        section_name = verified_name or f"dex #{index + 1} (identity unverified)"
        entries.append(
            ZipEntry(
                file_name=section_name,
                header_offset=cursor,
                data_offset=dex_start,
                data_end=dex_payload_end,
                compressed_size=file_size,
                uncompressed_size=file_size,
                compression=(
                    f"{compression_prefix}-compact"
                    if magic == b"cdex"
                    else f"{compression_prefix}-standard"
                ),
            )
        )
        cursor = (dex_payload_end + 3) & ~3
    return entries if cursor == dex_end else []


def _read_android10_vdex(
    path: Path,
    apk_dex_identities: Optional[list[tuple[str, int]]] = None,
) -> Optional[VdexAnalysis]:
    data = path.read_bytes()
    header_size = 28
    if (
        len(data) < header_size
        or data[:4] != b"vdex"
        or data[4:8] != b"021\0"
        or data[8:12] != b"002\0"
    ):
        return None

    number_of_dex_files, _, _, _ = struct.unpack_from("<4I", data, 12)
    if number_of_dex_files == 0 or number_of_dex_files > 10_000:
        return None
    if header_size + 4 * number_of_dex_files > len(data):
        return None
    vdex_checksums = list(
        struct.unpack_from(f"<{number_of_dex_files}I", data, header_size)
    )
    verified_dex_names = _verified_vdex_names(vdex_checksums, apk_dex_identities)
    dex_section_header_offset = header_size + 4 * number_of_dex_files
    if dex_section_header_offset + 12 > len(data):
        return None
    dex_size, dex_shared_data_size, _ = struct.unpack_from(
        "<3I", data, dex_section_header_offset
    )
    dex_begin = dex_section_header_offset + 12
    dex_end = dex_begin + dex_size
    shared_end = dex_end + dex_shared_data_size
    if dex_end > len(data) or shared_end > len(data):
        return None

    entries = _read_vdex_dex_ranges(
        data,
        dex_begin,
        dex_end,
        number_of_dex_files,
        verified_dex_names,
        quickening_prefix_size=4,
        compression_prefix="vdex-021",
    )
    if not entries and dex_size:
        return None
    verified = len(verified_dex_names) == number_of_dex_files
    return VdexAnalysis(
        format_version="021/002",
        stored_checksums=tuple(vdex_checksums),
        dex_ranges=tuple(entries),
        identities_verified=verified,
        verification_note=(
            "All ART location checksums match the APK dex entries in order."
            if verified
            else "APK dex identity was not assigned because the complete ordered "
            "checksum set did not match."
        ),
    )


def _read_sectioned_vdex(
    path: Path,
    apk_dex_identities: Optional[list[tuple[str, int]]] = None,
) -> Optional[VdexAnalysis]:
    """Read ART's sectioned VDEX 027 format used by Android 12 through 16."""
    data = path.read_bytes()
    header_size = 12
    if len(data) < header_size or data[:4] != b"vdex" or data[4:8] != b"027\0":
        return None
    number_of_sections = struct.unpack_from("<I", data, 8)[0]
    if number_of_sections < 3 or number_of_sections > 64:
        return None
    section_table_end = header_size + number_of_sections * 12
    if section_table_end > len(data):
        return None

    sections: dict[int, tuple[int, int]] = {}
    occupied: list[tuple[int, int]] = []
    for index in range(number_of_sections):
        kind, offset, size = struct.unpack_from("<3I", data, header_size + index * 12)
        if kind in sections or offset > len(data) or size > len(data) - offset:
            return None
        if size and offset < section_table_end:
            return None
        sections[kind] = (offset, size)
        if size:
            occupied.append((offset, offset + size))
    occupied.sort()
    if any(left[1] > right[0] for left, right in zip(occupied, occupied[1:])):
        return None

    checksum_section = sections.get(0)
    dex_section = sections.get(1)
    verifier_section = sections.get(2)
    if checksum_section is None or dex_section is None or verifier_section is None:
        return None
    checksum_offset, checksum_size = checksum_section
    if checksum_size % 4:
        return None
    number_of_dex_files = checksum_size // 4
    stored_checksums = list(
        struct.unpack_from(f"<{number_of_dex_files}I", data, checksum_offset)
    )
    verified_names = _verified_vdex_names(stored_checksums, apk_dex_identities)
    dex_offset, dex_size = dex_section
    entries: list[ZipEntry] = []
    if dex_size:
        entries = _read_vdex_dex_ranges(
            data,
            dex_offset,
            dex_offset + dex_size,
            number_of_dex_files,
            verified_names,
            quickening_prefix_size=0,
            compression_prefix="vdex-027",
        )
        if len(entries) != number_of_dex_files:
            return None
    verified = number_of_dex_files > 0 and len(verified_names) == number_of_dex_files
    return VdexAnalysis(
        format_version="027",
        stored_checksums=tuple(stored_checksums),
        dex_ranges=tuple(entries),
        identities_verified=verified,
        verification_note=(
            "All ART location checksums match the APK dex entries in order."
            if verified
            else "APK dex identity was not assigned because the complete ordered "
            "checksum set did not match."
        ),
    )


def read_vdex(
    path: Path,
    apk_dex_identities: Optional[list[tuple[str, int]]] = None,
) -> Optional[VdexAnalysis]:
    """Parse supported VDEX formats without guessing at unknown layouts."""
    return _read_android10_vdex(path, apk_dex_identities) or _read_sectioned_vdex(
        path, apk_dex_identities
    )


def read_apk_dex_identities(path: Path) -> list[tuple[str, int]]:
    """Read APK multidex names/location checksums in ART's canonical order.

    ART's location checksum for a dex opened from a ZIP is the ZIP entry's
    CRC32, not the Adler-32 checksum embedded in the DEX header.
    """
    with ZipFile(path) as archive:
        dex_entries = sorted(
            [
                info
                for info in archive.infolist()
                if re.fullmatch(r"classes(?:\d+)?\.dex", info.filename)
            ],
            key=lambda info: (
                1 if info.filename == "classes.dex" else int(info.filename[7:-4])
            ),
        )
        return [(info.filename, info.CRC) for info in dex_entries]


def find_zip_entry(zip_entries: list[ZipEntry], offset: int) -> Optional[ZipEntry]:
    if not zip_entries:
        return None
    starts = [entry.data_offset for entry in zip_entries]
    index = bisect.bisect_right(starts, offset) - 1
    if index < 0:
        return None
    entry = zip_entries[index]
    return entry if entry.data_offset <= offset < entry.data_end else None


def run_trace_query(trace: Path, query: str) -> list[dict[str, str]]:
    result = subprocess.run(
        [str(TRACE_PROCESSOR), "-Q", query, str(trace)],
        capture_output=True,
        text=True,
        check=True,
    )
    return list(csv.DictReader(io.StringIO(result.stdout.lstrip())))


def query_startup(trace: Path, package: str) -> dict[str, str]:
    rows = run_trace_query(
        trace,
        f"""
        INCLUDE PERFETTO MODULE android.startup.startups;
        SELECT startup_id, ts, ts_end, dur, package, startup_type
        FROM android_startups
        WHERE package = {sql_string(package)}
        ORDER BY ts;
        """,
    )
    if len(rows) != 1:
        raise RuntimeError(
            f"Expected exactly one startup for {package}, found {len(rows)}. "
            "Collect one cold launch per output directory."
        )
    return rows[0]


def query_trace_integrity(trace: Path) -> list[dict[str, str]]:
    return run_trace_query(
        trace,
        """
        SELECT name, severity, source, value
        FROM stats
        WHERE severity IN ('error', 'data_loss')
          AND value != 0
        ORDER BY name, source;
        """,
    )


def query_thread_names(trace: Path, pid: int) -> dict[int, str]:
    rows = run_trace_query(
        trace,
        f"""
        SELECT thread.tid, COALESCE(thread.name, '') AS thread_name
        FROM thread
        JOIN process USING (upid)
        WHERE process.pid = {pid}
        GROUP BY thread.tid
        ORDER BY thread.tid;
        """,
    )
    return {int(row["tid"]): row["thread_name"] for row in rows if row["tid"]}


def query_page_cache_events(
    trace: Path,
    pid: int,
    startup_start: int,
    startup_end: int,
    app_inode_keys: set[tuple[int, int]],
) -> list[dict[str, str]]:
    inode_values = ",\n".join(
        f"({device}, {inode})" for device, inode in sorted(app_inode_keys)
    )
    if not inode_values:
        inode_values = "(-1, -1)"
    return run_trace_query(
        trace,
        f"""
        WITH
          app_inodes(sdev, inode) AS (
            VALUES {inode_values}
          ),
          cache_events AS (
            SELECT
              ftrace_event.ts,
              ftrace_event.utid,
              EXTRACT_ARG(ftrace_event.arg_set_id, 's_dev') AS sdev,
              EXTRACT_ARG(ftrace_event.arg_set_id, 'i_ino') AS inode,
              EXTRACT_ARG(ftrace_event.arg_set_id, 'index') AS page_index,
              COALESCE(EXTRACT_ARG(ftrace_event.arg_set_id, 'order'), 0)
                AS page_order
            FROM ftrace_event
            WHERE ftrace_event.name = 'mm_filemap_add_to_page_cache'
              AND ftrace_event.ts >= {startup_start}
              AND ftrace_event.ts < {startup_end}
          )
        SELECT
          cache_events.ts,
          COALESCE(process.name, '[kernel worker]') AS process_name,
          CASE
            WHEN thread.name IS NOT NULL THEN thread.name
            WHEN process.pid IS NULL THEN '[kernel worker]'
            ELSE '[unnamed thread]'
          END AS thread_name,
          COALESCE(thread.tid, 0) AS tid,
          cache_events.sdev,
          cache_events.inode,
          cache_events.page_index,
          cache_events.page_order
        FROM cache_events
        LEFT JOIN thread USING (utid)
        LEFT JOIN process USING (upid)
        WHERE process.pid = {pid}
           OR EXISTS (
             SELECT 1
             FROM app_inodes
             WHERE app_inodes.sdev = cache_events.sdev
               AND app_inodes.inode = cache_events.inode
           )
        ORDER BY cache_events.ts;
        """,
    )


def find_ndk() -> Path:
    explicit = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if explicit:
        path = Path(explicit)
        if path.is_dir():
            return path

    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        sdk_root = str(Path.home() / "Library" / "Android" / "sdk")
    ndk_root = Path(sdk_root) / "ndk"
    candidates = [path for path in ndk_root.iterdir()] if ndk_root.is_dir() else []
    candidates = [path for path in candidates if path.is_dir()]
    if not candidates:
        raise RuntimeError(
            "Android NDK not found. Install an NDK or set ANDROID_NDK_HOME."
        )

    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"\d+", path.name))

    return max(candidates, key=version_key)


def find_compiler(ndk: Path, abi: str, sdk: int) -> Path:
    prebuilt_root = ndk / "toolchains" / "llvm" / "prebuilt"
    prebuilts = [path for path in prebuilt_root.iterdir() if path.is_dir()]
    if not prebuilts:
        raise RuntimeError(f"No LLVM prebuilt found in {ndk}")
    host_name = platform.system().lower()
    preferred = [
        path
        for path in prebuilts
        if (
            (host_name == "darwin" and path.name.startswith("darwin"))
            or (host_name == "linux" and path.name.startswith("linux"))
            or (host_name == "windows" and path.name.startswith("windows"))
        )
    ]
    prebuilt = preferred[0] if preferred else prebuilts[0]
    prefixes = {
        "arm64-v8a": "aarch64-linux-android",
        "armeabi-v7a": "armv7a-linux-androideabi",
        "x86_64": "x86_64-linux-android",
        "x86": "i686-linux-android",
    }
    if abi not in prefixes:
        raise RuntimeError(f"Unsupported Android ABI for collector: {abi}")
    prefix = prefixes[abi]
    compiler = prebuilt / "bin" / f"{prefix}{max(21, sdk)}-clang"
    if compiler.exists():
        return compiler

    matches = list((prebuilt / "bin").glob(f"{prefix}*-clang"))
    if not matches:
        raise RuntimeError(f"No {prefix} compiler found in {prebuilt / 'bin'}")

    def compiler_api(path: Path) -> int:
        match = re.search(r"(\d+)-clang$", path.name)
        return int(match.group(1)) if match else 0

    compatible = [path for path in matches if compiler_api(path) <= sdk]
    return max(compatible or matches, key=compiler_api)


def build_and_push_collector(
    adb: Adb, output_dir: Path, abi: str, sdk: int
) -> dict[str, str]:
    ndk = find_ndk()
    compiler = find_compiler(ndk, abi, sdk)
    local_binary = output_dir / "page_fault_collector"
    subprocess.run(
        [
            str(compiler),
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            str(COLLECTOR_SOURCE),
            "-o",
            str(local_binary),
        ],
        check=True,
    )
    adb.root_shell(f"mkdir -p {shlex.quote(REMOTE_DIR)}", check=True)
    adb.run(
        ["push", str(local_binary), REMOTE_COLLECTOR],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    adb.root_shell(f"chmod 755 {shlex.quote(REMOTE_COLLECTOR)}", check=True)
    return {
        "ndk": ndk.name,
        "compiler": compiler.name,
        "collector_source_sha256": hashlib.sha256(
            COLLECTOR_SOURCE.read_bytes()
        ).hexdigest(),
        "collector_binary_sha256": hashlib.sha256(
            local_binary.read_bytes()
        ).hexdigest(),
    }


def package_paths(adb: Adb, package: str) -> list[str]:
    result = adb.shell(
        ["pm", "path", package], capture_output=True, text=True, check=True
    )
    paths = []
    for line in result.stdout.splitlines():
        if line.startswith("package:"):
            paths.append(line.removeprefix("package:").strip())
    if not paths:
        raise RuntimeError(f"No installed APK paths found for {package}")
    return paths


def package_files(adb: Adb, package: str, apk_paths: list[str]) -> list[str]:
    roots = sorted({str(Path(path).parent) for path in apk_paths})
    data_roots = [f"/data/user/0/{package}", f"/data/user_de/0/{package}"]
    commands = [
        f"test -f {shlex.quote(path)} || "
        f"{{ echo 'Installed APK disappeared: {shlex.quote(path)}' >&2; exit 74; }};"
        for path in apk_paths
    ]
    for root in [*roots, *data_roots]:
        commands.append(
            f"if [ -d {shlex.quote(root)} ]; then "
            f"find {shlex.quote(root)} -type f -print0 2>/dev/null || true; fi;"
        )
    result = adb.root_shell(
        " ".join(commands), capture_output=True, text=True, check=True
    )
    return sorted(set(apk_paths) | set(filter(None, result.stdout.split("\0"))))


def cache_targets(files: Iterable[str]) -> list[str]:
    return sorted(set(filter(None, files)))


def run_collector_file_command(
    adb: Adb,
    mode: str,
    files: list[str],
    diagnostics: Optional[list[dict[str, object]]] = None,
) -> str:
    if not files:
        return ""
    if mode not in ("--residency", "--evict"):
        raise ValueError(f"Unsupported collector file mode: {mode}")

    batches: list[list[str]] = []
    batch: list[str] = []
    batch_length = len(REMOTE_COLLECTOR) + len(mode) + 2
    for file_name in files:
        quoted_length = len(shlex.quote(file_name)) + 1
        if batch and batch_length + quoted_length > 24_000:
            batches.append(batch)
            batch = []
            batch_length = len(REMOTE_COLLECTOR) + len(mode) + 2
        batch.append(file_name)
        batch_length += quoted_length
    if batch:
        batches.append(batch)

    outputs: list[str] = []
    residency_header = "file_name,size_bytes,total_pages,resident_pages\n"
    for batch_index, file_batch in enumerate(batches):
        command = " ".join(
            [
                shlex.quote(REMOTE_COLLECTOR),
                mode,
                *(shlex.quote(file_name) for file_name in file_batch),
            ]
        )
        result = adb.root_shell(command, capture_output=True, text=True)
        if diagnostics is not None:
            diagnostics.append(
                {
                    "batch": batch_index,
                    "target_count": len(file_batch),
                    "exit_status": result.returncode,
                    "stderr": result.stderr.strip(),
                }
            )
        result.check_returncode()
        stdout = result.stdout
        if mode == "--residency":
            if not stdout.startswith(residency_header):
                raise RuntimeError("Residency collector returned an invalid CSV header")
            if batch_index > 0:
                stdout = stdout[len(residency_header) :]
        outputs.append(stdout)
    return "".join(outputs)


def parse_residency(
    text: str,
    phase: str,
    expected_files: Optional[Iterable[str]] = None,
    required_files: Optional[Iterable[str]] = None,
    warnings: Optional[list[str]] = None,
) -> list[dict[str, object]]:
    if not text:
        return []
    rows = []
    reader = csv.DictReader(io.StringIO(text))
    expected_header = [
        "file_name",
        "size_bytes",
        "total_pages",
        "resident_pages",
    ]
    if reader.fieldnames != expected_header:
        raise RuntimeError(f"Invalid cache-residency CSV header: {reader.fieldnames!r}")
    seen = set()
    for row in reader:
        if None in row or any(value is None for value in row.values()):
            raise RuntimeError(f"Malformed cache-residency row: {row!r}")
        file_name = row["file_name"]
        if file_name in seen:
            raise RuntimeError(f"Duplicate cache-residency row for {file_name!r}")
        seen.add(file_name)
        size_bytes = int(row["size_bytes"])
        total_pages = int(row["total_pages"])
        resident_pages = int(row["resident_pages"])
        if (
            size_bytes < 0
            or total_pages < 0
            or resident_pages < 0
            or resident_pages > total_pages
        ):
            raise RuntimeError(f"Invalid cache-residency counts: {row!r}")
        rows.append(
            {
                "phase": phase,
                "file_name": file_name,
                "size_bytes": size_bytes,
                "total_pages": total_pages,
                "resident_pages": resident_pages,
            }
        )
    if expected_files is not None:
        expected = set(expected_files)
        missing = sorted(expected - seen)
        unexpected = sorted(seen - expected)
        required_missing = sorted(set(required_files or ()) - seen)
        if required_missing or unexpected:
            raise RuntimeError(
                "Cache-residency coverage mismatch: "
                f"missing_required={required_missing!r}, "
                f"unexpected={unexpected!r}"
            )
        if missing and warnings is not None:
            warnings.append(
                f"{phase}: ignored {len(missing)} non-essential app files that "
                "disappeared during the residency check: " + ", ".join(missing)
            )
    return rows


def write_residency(output_dir: Path, rows: list[dict[str, object]]) -> None:
    with (output_dir / "cache_residency.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=CACHE_RESIDENCY_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def verify_cache_residency(
    rows: list[dict[str, object]], max_resident_pages: int, phase: str
) -> None:
    phase_rows = [row for row in rows if row["phase"] == phase]
    if not phase_rows:
        raise RuntimeError(f"No cache-residency evidence was captured for {phase}")
    resident_pages = sum(int(row["resident_pages"]) for row in phase_rows)
    if resident_pages <= max_resident_pages:
        return
    resident_files = [
        f"{row['file_name']}={row['resident_pages']}/{row['total_pages']} pages"
        for row in phase_rows
        if int(row["resident_pages"]) > 0
    ]
    raise RuntimeError(
        f"Page-cache eviction verification failed at {phase}: "
        f"{resident_pages} resident pages exceeds --max-resident-pages="
        f"{max_resident_pages}. "
        + "; ".join(resident_files)
        + ". Reboot the target before the next iteration, or use an explicit "
        "nonzero threshold only if a partially warm cache is intentional."
    )


def cache_verification_summary(
    rows: list[dict[str, object]], phase: str
) -> dict[str, object]:
    phase_rows = [row for row in rows if row["phase"] == phase]
    return {
        "phase": phase,
        "files_checked": len(phase_rows),
        "resident_pages": sum(int(row["resident_pages"]) for row in phase_rows),
        "total_pages": sum(int(row["total_pages"]) for row in phase_rows),
        "fully_evicted_files": sum(
            int(row["resident_pages"]) == 0 for row in phase_rows
        ),
    }


def write_capture_metadata(output_dir: Path, metadata: dict[str, object]) -> None:
    (output_dir / "capture_metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n"
    )


def record_cache_gate(
    output_dir: Path,
    metadata: dict[str, object],
    rows: list[dict[str, object]],
    max_resident_pages: int,
    phase: str,
) -> None:
    metadata["cache_verification"] = cache_verification_summary(rows, phase)
    try:
        verify_cache_residency(rows, max_resident_pages, phase)
    except RuntimeError as error:
        metadata["capture_status"] = "cache_verification_failed"
        metadata["failure"] = str(error)
        write_capture_metadata(output_dir, metadata)
        raise
    metadata["capture_status"] = f"cache_verified_{phase}"
    metadata.pop("failure", None)
    write_capture_metadata(output_dir, metadata)


def wait_for_package_stopped(adb: Adb, package: str, timeout_seconds: int = 10) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = adb.shell(
            ["ps", "-A", "-o", "NAME"], capture_output=True, text=True, check=True
        )
        names = [line.strip() for line in result.stdout.splitlines()[1:]]
        remaining = [
            name for name in names if name == package or name.startswith(f"{package}:")
        ]
        if not remaining:
            return
        time.sleep(0.05)
    raise RuntimeError(
        f"Package processes did not stop within {timeout_seconds}s: "
        + ", ".join(remaining)
    )


def stop_and_enumerate_cache_targets(
    adb: Adb, package: str, apk_paths: list[str]
) -> list[str]:
    adb.shell(["am", "force-stop", package], check=True)
    wait_for_package_stopped(adb, package)
    return cache_targets(package_files(adb, package, apk_paths))


def drop_caches(adb: Adb, sdk: int, files: list[str]) -> None:
    adb.root_shell("sync", check=True)
    direct = adb.root_shell(
        "echo 3 > /proc/sys/vm/drop_caches",
        capture_output=True,
        text=True,
    )
    if direct.returncode != 0:
        if sdk < 31:
            raise RuntimeError(
                "drop_caches failed: " + (direct.stderr or direct.stdout).strip()
            )
        # Android deliberately grants this property to the adb shell domain.
        # On the common production-adbd + Magisk/KernelSU arrangement, an
        # explicit root_shell() would enter a third-party domain that SELinux
        # may not permit to set perf_drop_caches_prop. Plain adb shell preserves
        # the permitted domain there. A root-adbd userdebug image can keep a
        # root SELinux domain even for this call, but normally succeeds through
        # the direct sysctl path above; either failure remains fatal.
        adb.shell(["setprop", "perf.drop_caches", "3"], check=True)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            value = adb.getprop("perf.drop_caches")
            if value == "0":
                break
            time.sleep(0.1)
        else:
            raise RuntimeError("Timed out waiting for perf.drop_caches to complete")

    # This is file-scoped and harmless if global reclaim already evicted a page.
    run_collector_file_command(adb, "--evict", files)


def resolve_activity(adb: Adb, package: str, requested: Optional[str]) -> str:
    if requested:
        if "/" in requested:
            return requested
        return f"{package}/{requested}"
    result = adb.shell(
        ["cmd", "package", "resolve-activity", "--brief", package],
        capture_output=True,
        text=True,
        check=True,
    )
    candidates = [line.strip() for line in result.stdout.splitlines() if "/" in line]
    if not candidates:
        raise RuntimeError(f"Unable to resolve launcher activity for {package}")
    return candidates[-1]


def start_perfetto(adb: Adb, remote_trace: str) -> tuple[subprocess.Popen, int]:
    existing = adb.shell(
        ["pidof", "perfetto"], capture_output=True, text=True
    ).stdout.strip()
    if existing:
        raise RuntimeError(
            f"Another Perfetto command is already running ({existing}); "
            "refusing to stop an unrelated trace."
        )
    config = TRACE_CONFIG.read_text()
    process = subprocess.Popen(
        adb.base_command
        + ["shell", "perfetto", "--txt", "-c", "-", "-o", remote_trace],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    assert process.stdin is not None
    process.stdin.write(config)
    process.stdin.close()
    process.stdin = None
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            stdout = process.stdout.read() if process.stdout else ""
            raise RuntimeError("Unable to start Perfetto: " + stdout.strip())
        pid_text = adb.shell(
            ["pidof", "-s", "perfetto"], capture_output=True, text=True
        ).stdout.strip()
        tracing_state = adb.root_shell(
            "cat /sys/kernel/tracing/tracing_on 2>/dev/null || "
            "cat /sys/kernel/debug/tracing/tracing_on 2>/dev/null",
            capture_output=True,
            text=True,
        ).stdout.strip()
        event_state = adb.root_shell(
            "cat /sys/kernel/tracing/events/filemap/"
            "mm_filemap_add_to_page_cache/enable 2>/dev/null || "
            "cat /sys/kernel/debug/tracing/events/filemap/"
            "mm_filemap_add_to_page_cache/enable 2>/dev/null",
            capture_output=True,
            text=True,
        ).stdout.strip()
        if pid_text and tracing_state == "1" and event_state == "1":
            return process, int(pid_text)
        time.sleep(0.05)
    process.terminate()
    raise RuntimeError("Timed out waiting for Perfetto to start")


def stop_perfetto(adb: Adb, process: subprocess.Popen, device_pid: int) -> None:
    adb.root_shell(f"kill -INT {device_pid}", check=False)
    stdout, _ = process.communicate(timeout=20)
    if process.returncode not in (0, 130):
        raise RuntimeError("Unable to stop Perfetto cleanly: " + stdout.strip())


def start_fault_collector(adb: Adb) -> tuple[subprocess.Popen, int, int, str]:
    command = (
        f"{shlex.quote(REMOTE_COLLECTOR)} "
        f"--output {shlex.quote(REMOTE_FAULTS)} "
        f"--mappings-output {shlex.quote(REMOTE_MAPPINGS)} "
        "--duration-ms 60000"
    )
    process = subprocess.Popen(
        adb.root_command(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert process.stdout is not None
    output = []
    for line in process.stdout:
        output.append(line)
        match = re.search(
            r"READY pid=(?P<pid>\d+) capture_start_ns=(?P<start>\d+) "
            r"online_cpus=(?P<online_cpus>[0-9,-]+)",
            line,
        )
        if match:
            return (
                process,
                int(match.group("pid")),
                int(match.group("start")),
                match.group("online_cpus"),
            )
    raise RuntimeError("Fault collector failed to start:\n" + "".join(output))


def stop_fault_collector(
    adb: Adb, process: subprocess.Popen, device_pid: int
) -> tuple[int, dict[str, int]]:
    adb.root_shell(f"kill -INT {device_pid}", check=False)
    stdout, _ = process.communicate(timeout=20)
    match = re.search(
        r"capture_start_ns=(?P<start>\d+) "
        r"capture_end_ns=(?P<end>\d+) "
        r"samples=(?P<samples>\d+) mappings=(?P<mappings>\d+) "
        r"lost=(?P<lost>\d+) integrity_errors=(?P<integrity_errors>\d+) "
        r"throttled=(?P<throttled>\d+)",
        stdout,
    )
    if not match:
        raise RuntimeError("Fault collector returned invalid metadata:\n" + stdout)
    metadata = {key: int(value) for key, value in match.groupdict().items()}
    return process.returncode or 0, metadata


def dump_process_state(adb: Adb, package: str, output_dir: Path) -> int:
    pid_text = adb.shell(
        ["pidof", "-s", package], capture_output=True, text=True, check=True
    ).stdout.strip()
    if not pid_text:
        raise RuntimeError(f"Process {package} exited before state capture")
    pid = int(pid_text)
    maps = adb.root_shell(
        f"cat /proc/{pid}/maps", capture_output=True, text=True, check=True
    ).stdout
    (output_dir / "maps.txt").write_text(maps)
    stat = adb.root_shell(
        f"cat /proc/{pid}/stat", capture_output=True, text=True, check=True
    ).stdout.strip()
    (output_dir / "process_stat.txt").write_text(stat + "\n")
    return pid


def dump_inode_mapping(
    adb: Adb,
    package: str,
    apk_paths: list[str],
    output_dir: Path,
    *,
    append: bool = False,
) -> None:
    roots = sorted({str(Path(path).parent) for path in apk_paths})
    roots.extend([f"/data/user/0/{package}", f"/data/user_de/0/{package}"])
    parts = [
        (
            f"stat -c '%d|%i|%s|%n' {shlex.quote(apk_path)} || "
            f"{{ echo 'Unable to identify installed APK inode: "
            f"{shlex.quote(apk_path)}' >&2; exit 75; }};"
        )
        for apk_path in apk_paths
    ]
    for root in roots:
        parts.append(
            f"if [ -d {shlex.quote(root)} ]; then "
            f"find {shlex.quote(root)} -type f "
            "-exec sh -c 'for file do "
            "stat -c '\"'\"'%d|%i|%s|%n'\"'\"' \"$file\" 2>/dev/null || true; "
            "done' sh {} + 2>/dev/null || true; fi;"
        )
    result = adb.root_shell(
        "{ " + " ".join(parts) + " }",
        capture_output=True,
        text=True,
        check=True,
    )
    with (output_dir / "inodes.txt").open("a" if append else "w") as output:
        output.write(result.stdout)


def pull_artifacts(
    adb: Adb, apk_paths: list[str], abi: str, output_dir: Path
) -> dict[str, str]:
    artifacts_dir = output_dir / "artifacts"
    artifacts_dir.mkdir(exist_ok=True)
    mapping = {}
    remote_paths = list(apk_paths)
    oat_arch = {
        "armeabi-v7a": "arm",
        "arm64-v8a": "arm64",
        "x86": "x86",
        "x86_64": "x86_64",
    }.get(abi, abi)
    for apk_path in apk_paths:
        oat_directory = Path(apk_path).parent / "oat" / oat_arch
        artifact_stem = Path(apk_path).stem
        candidates = [
            str(oat_directory / f"{artifact_stem}{suffix}")
            for suffix in (".odex", ".vdex", ".art")
        ]
        command = " ".join(
            [
                "for candidate in",
                *(shlex.quote(candidate) for candidate in candidates),
                '; do [ -f "$candidate" ] && printf "%s\\n" "$candidate"; done; true',
            ]
        )
        result = adb.root_shell(command, capture_output=True, text=True, check=True)
        remote_paths.extend(result.stdout.splitlines())
    for remote_path in dict.fromkeys(remote_paths):
        digest = hashlib.sha256(remote_path.encode()).hexdigest()[:10]
        local_path = artifacts_dir / f"{digest}-{Path(remote_path).name}"
        adb.pull_with_root_fallback(remote_path, local_path)
        mapping[remote_path] = str(local_path.relative_to(output_dir))
    (output_dir / "artifacts.json").write_text(
        json.dumps(mapping, indent=2, sort_keys=True) + "\n"
    )
    return mapping


def collect(
    adb: Adb,
    package: str,
    activity: Optional[str],
    output_dir: Path,
    settle_ms: int,
    should_pull_apks: bool,
    max_resident_pages: int,
    rebooted_before_collect: bool,
) -> None:
    adb.ensure_root()
    sdk = int(adb.getprop("ro.build.version.sdk"))
    abi = adb.getprop("ro.product.cpu.abi")
    page_size = int(
        adb.shell(
            ["getconf", "PAGESIZE"], capture_output=True, text=True, check=True
        ).stdout.strip()
    )
    apk_paths = package_paths(adb, package)
    activity_name = resolve_activity(adb, package, activity)
    build_info = build_and_push_collector(adb, output_dir, abi, sdk)

    metadata: dict[str, object] = {
        "schema_version": 5,
        "package": package,
        "activity": activity_name,
        "serial": adb.serial,
        "sdk": sdk,
        "release": adb.getprop("ro.build.version.release"),
        "build_fingerprint": adb.getprop("ro.build.fingerprint"),
        "device": adb.getprop("ro.product.device"),
        "kernel": adb.shell(
            ["uname", "-r"], capture_output=True, text=True, check=True
        ).stdout.strip(),
        "abi": abi,
        "page_size": page_size,
        "online_cpus_sysfs": adb.shell(
            ["cat", "/sys/devices/system/cpu/online"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip(),
        "collector": "perf-software-page-fault-events",
        "collector_version": 3,
        "cache_procedure": (
            "force-stop-wait+stable-target-set+sync+drop_caches+fadvise+mincore-v3"
        ),
        "cache_max_resident_pages": max_resident_pages,
        "reboot_before_collect": rebooted_before_collect,
        "boot_id": adb.shell(
            ["cat", "/proc/sys/kernel/random/boot_id"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip(),
        "device_uptime_seconds": float(
            adb.shell(
                ["cat", "/proc/uptime"],
                capture_output=True,
                text=True,
                check=True,
            ).stdout.split()[0]
        ),
        "trace_config_sha256": hashlib.sha256(TRACE_CONFIG.read_bytes()).hexdigest(),
        "capture_status": "preparing",
        **build_info,
    }
    capture_warnings: list[str] = []
    metadata["warnings"] = capture_warnings
    write_capture_metadata(output_dir, metadata)

    targets = stop_and_enumerate_cache_targets(adb, package, apk_paths)
    dump_inode_mapping(adb, package, apk_paths, output_dir)

    residency_rows = []
    residency_rows.extend(
        parse_residency(
            run_collector_file_command(adb, "--residency", targets),
            "before_drop",
            targets,
            apk_paths,
            capture_warnings,
        )
    )
    write_residency(output_dir, residency_rows)

    drop_caches(adb, sdk, targets)
    residency_rows.extend(
        parse_residency(
            run_collector_file_command(adb, "--residency", targets),
            "after_drop",
            targets,
            apk_paths,
            capture_warnings,
        )
    )
    write_residency(output_dir, residency_rows)
    record_cache_gate(
        output_dir,
        metadata,
        residency_rows,
        max_resident_pages,
        "after_drop",
    )

    trace_key = f"afv_{os.getpid()}_{int(time.time())}"
    remote_trace = f"/data/misc/perfetto-traces/{trace_key}.pftrace"
    collector_process = None
    collector_pid = None
    perfetto_process = None
    perfetto_pid = None
    try:
        perfetto_process, perfetto_pid = start_perfetto(adb, remote_trace)
        (
            collector_process,
            collector_pid,
            collector_start,
            collector_online_cpus,
        ) = start_fault_collector(adb)
        metadata["collector_start_ns"] = collector_start
        metadata["collector_online_cpus"] = collector_online_cpus
        if collector_online_cpus != metadata["online_cpus_sysfs"]:
            raise RuntimeError(
                "CPU topology changed while starting the collector: "
                f"sysfs={metadata['online_cpus_sysfs']!r}, "
                f"collector={collector_online_cpus!r}"
            )

        current_targets = cache_targets(
            package_files(adb, package, package_paths(adb, package))
        )
        missing_required = sorted(set(apk_paths) - set(current_targets))
        if missing_required:
            raise RuntimeError(
                "Installed APK cache targets disappeared before launch: "
                + ", ".join(missing_required)
            )
        removed_targets = sorted(set(targets) - set(current_targets))
        added_targets = sorted(set(current_targets) - set(targets))
        if removed_targets:
            capture_warnings.append(
                "Before launch, ignored non-essential app files that "
                "disappeared: " + ", ".join(removed_targets)
            )
        if added_targets:
            capture_warnings.append(
                "Before launch, evicted and verified newly discovered app "
                "files: " + ", ".join(added_targets)
            )
            run_collector_file_command(adb, "--evict", added_targets)
        targets = current_targets

        residency_rows.extend(
            parse_residency(
                run_collector_file_command(adb, "--residency", targets),
                "before_launch",
                targets,
                apk_paths,
                capture_warnings,
            )
        )
        write_residency(output_dir, residency_rows)
        record_cache_gate(
            output_dir,
            metadata,
            residency_rows,
            max_resident_pages,
            "before_launch",
        )

        launch = adb.shell(
            ["am", "start", "-W", "-n", activity_name],
            capture_output=True,
            text=True,
            check=True,
        )
        (output_dir / "launch.txt").write_text(launch.stdout)
        if "Status: ok" not in launch.stdout:
            raise RuntimeError("Activity launch failed:\n" + launch.stdout)

        pid = dump_process_state(adb, package, output_dir)
        metadata["pid"] = pid
        dump_inode_mapping(adb, package, apk_paths, output_dir, append=True)

        time.sleep(settle_ms / 1000)
        post_launch_diagnostics: list[dict[str, object]] = []
        post_launch_warnings: list[str] = []
        try:
            residency_rows.extend(
                parse_residency(
                    run_collector_file_command(
                        adb,
                        "--residency",
                        targets,
                        post_launch_diagnostics,
                    ),
                    "after_launch",
                    targets,
                    (),
                    post_launch_warnings,
                )
            )
            write_residency(output_dir, residency_rows)
            skipped_details = [
                str(command["stderr"])
                for command in post_launch_diagnostics
                if command["stderr"]
            ] + post_launch_warnings
            if skipped_details:
                capture_warnings.append(
                    "Post-launch residency check completed with collector exit "
                    "status 0 and skipped changing files: "
                    + " | ".join(skipped_details)
                )
        except (subprocess.CalledProcessError, RuntimeError) as error:
            exit_status = (
                error.returncode
                if isinstance(error, subprocess.CalledProcessError)
                else 0
            )
            capture_warnings.append(
                "Post-launch residency check failed after the startup trace was "
                f"captured (collector exit status {exit_status}: {error}); the "
                "capture was preserved. Pre-launch eviction verification was "
                "not affected."
            )
            metadata["post_launch_residency_exit_status"] = exit_status
            metadata["post_launch_residency_error"] = str(error)
        finally:
            metadata["post_launch_residency_commands"] = post_launch_diagnostics
            write_capture_metadata(output_dir, metadata)
    finally:
        if collector_process is not None and collector_pid is not None:
            return_code, collector_metadata = stop_fault_collector(
                adb, collector_process, collector_pid
            )
            metadata.update(
                {f"collector_{key}": value for key, value in collector_metadata.items()}
            )
            metadata["collector_return_code"] = return_code
        if perfetto_process is not None and perfetto_pid is not None:
            stop_perfetto(adb, perfetto_process, perfetto_pid)

    adb.pull_with_root_fallback(REMOTE_FAULTS, output_dir / "fault_events.csv")
    adb.pull_with_root_fallback(REMOTE_MAPPINGS, output_dir / "mapping_events.csv")
    adb.pull_with_root_fallback(remote_trace, output_dir / "faults.pftrace")
    adb.root_shell(
        f"rm -f {shlex.quote(REMOTE_FAULTS)} "
        f"{shlex.quote(REMOTE_MAPPINGS)} {shlex.quote(remote_trace)}",
        check=True,
    )

    if should_pull_apks:
        pull_artifacts(adb, apk_paths, abi, output_dir)

    integrity_failures = {
        key: int(metadata.get(f"collector_{key}", 0))
        for key in ("lost", "integrity_errors", "throttled")
    }
    if int(metadata.get("collector_return_code", 0)) != 0 or any(
        integrity_failures.values()
    ):
        raise RuntimeError(
            "Fault collector integrity failure: "
            + ", ".join(f"{key}={value}" for key, value in integrity_failures.items())
        )
    metadata["capture_status"] = "collected"
    write_capture_metadata(output_dir, metadata)


def parse_inode_mapping(output_dir: Path, map_entries: list[MapEntry]) -> tuple[
    dict[tuple[int, int], str],
    dict[str, int],
    set[tuple[int, int]],
]:
    inode_paths: dict[tuple[int, int], str] = {}
    file_sizes: dict[str, int] = {}
    app_inode_keys: set[tuple[int, int]] = set()
    for entry in map_entries:
        if entry.inode and entry.file_name and not entry.file_name.startswith("["):
            inode_paths[(entry.device, entry.inode)] = entry.file_name
            file_sizes[entry.file_name] = max(
                file_sizes.get(entry.file_name, 0),
                entry.file_offset + (entry.end_address - entry.begin_address),
            )

    inode_path = output_dir / "inodes.txt"
    if inode_path.exists():
        for line in inode_path.read_text().splitlines():
            columns = line.split("|", 3)
            if len(columns) != 4:
                continue
            device, inode, size, file_name = columns
            key = (int(device), int(inode))
            inode_paths.setdefault(key, file_name)
            file_sizes[file_name] = int(size)
            app_inode_keys.add(key)
    return inode_paths, file_sizes, app_inode_keys


def load_artifacts(output_dir: Path) -> dict[str, Path]:
    path = output_dir / "artifacts.json"
    if not path.exists():
        return {}
    return {
        remote: output_dir / local
        for remote, local in json.loads(path.read_text()).items()
    }


IMAGE_SUFFIXES = (
    ".avif",
    ".gif",
    ".heic",
    ".jpeg",
    ".jpg",
    ".png",
    ".svg",
    ".webp",
)


def classify_source(file_name: Optional[str], zip_entry: Optional[str]) -> str:
    target = (zip_entry or file_name or "").lower()
    if zip_entry:
        if target.endswith(IMAGE_SUFFIXES):
            return "image"
        if re.search(r"(?:^|[/ ·])classes\d*\.dex(?:\s|$)", target) or (
            "compactdex data" in target
        ):
            return "dex"
        if target.endswith(".so"):
            return "native_code"
        if target == "resources.arsc" or target.startswith("res/"):
            return "resources"
        if target.startswith("assets/"):
            return "asset"
        if target.startswith("meta-inf/"):
            return "metadata"
        return "apk_other"
    if target.endswith((".odex", ".oat")):
        return "compiled_code"
    if target.endswith(".art"):
        return "art_image"
    if target.endswith((".vdex", ".dex")):
        return "dex"
    if target.endswith(".so"):
        return "native_code"
    if target.endswith(IMAGE_SUFFIXES):
        return "image"
    if target.endswith(".apk"):
        return "apk_container"
    if not file_name or file_name.startswith("["):
        return "anonymous"
    if file_name.startswith(("/system/", "/apex/", "/vendor/", "/product/")):
        return "system"
    return "other_file"


def write_fault_csvs(
    output_dir: Path,
    metadata: dict[str, object],
    startup: dict[str, str],
    map_entries: list[MapEntry],
    mapping_events: list[MappingEvent],
    zip_entries: dict[str, list[ZipEntry]],
    thread_names: dict[int, str],
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    pid = int(metadata["pid"])
    page_size = int(metadata["page_size"])
    startup_start = int(startup["ts"])
    startup_end = int(startup["ts_end"])
    raw_rows = []
    with (output_dir / "fault_events.csv").open() as file:
        for row in csv.DictReader(file):
            if int(row["pid"]) != pid:
                continue
            timestamp = int(row["timestamp_ns"])
            if not startup_start <= timestamp < startup_end:
                continue
            raw_rows.append(row)

    all_faults = []
    mapped_faults = []
    for sequence, row in enumerate(raw_rows):
        timestamp = int(row["timestamp_ns"])
        tid = int(row["tid"])
        address = int(row["address"], 16)
        ip = int(row["ip"], 16)
        map_entry = find_map_entry_at(map_entries, mapping_events, address, timestamp)
        file_name = None
        file_offset = None
        mapping_kind = "unmapped"
        zip_entry = None
        zip_offset = None
        if map_entry is not None:
            if (
                map_entry.inode
                and map_entry.file_name
                and not map_entry.file_name.startswith(("[", "/dev/", "/memfd:"))
            ):
                mapping_kind = "file"
                file_name = map_entry.file_name.removesuffix(" (deleted)")
                file_offset = map_entry.file_offset + address - map_entry.begin_address
                if file_name in zip_entries:
                    zip_entry = find_zip_entry(zip_entries[file_name], file_offset)
                    if zip_entry:
                        zip_offset = file_offset - zip_entry.data_offset
            else:
                mapping_kind = "anonymous"
                file_name = map_entry.file_name

        fault = {
            "ts": timestamp,
            "elapsed_ms": (timestamp - startup_start) / 1_000_000,
            "sequence": sequence,
            "process_name": metadata["package"],
            "thread_name": thread_names.get(tid, ""),
            "tid": tid,
            "address": address,
            "ip": ip,
            "event_type": row["event_type"],
            "is_major": row["event_type"] == "major",
            "mapping_kind": mapping_kind,
            "file_name": file_name,
            "zip_entry_name": zip_entry.file_name if zip_entry else None,
            "offset": file_offset,
            "page_index": (
                file_offset // page_size if file_offset is not None else None
            ),
            "zip_entry_offset": zip_offset,
            "category": classify_source(
                file_name, zip_entry.file_name if zip_entry else None
            ),
        }
        all_faults.append(fault)
        if mapping_kind == "file":
            mapped_faults.append(fault)

    raw_fields = [
        "ts",
        "process_name",
        "thread_name",
        "address",
        "ip",
        "event_type",
        "tid",
        "is_major",
    ]
    with (output_dir / "faults.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=raw_fields)
        writer.writeheader()
        for row in all_faults:
            writer.writerow({field: row[field] for field in raw_fields})

    fields = [
        "ts",
        "process_name",
        "thread_name",
        "file_name",
        "zip_entry_name",
        "offset",
        "is_major",
        "event_type",
        "elapsed_ms",
        "sequence",
        "tid",
        "address",
        "ip",
        "mapping_kind",
        "page_index",
        "zip_entry_offset",
        "category",
    ]
    for name, rows in [
        ("all_faults.csv", all_faults),
        ("mapped_faults.csv", mapped_faults),
    ]:
        with (output_dir / name).open("w", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
    return all_faults, mapped_faults


def write_page_cache_events(
    output_dir: Path,
    trace: Path,
    metadata: dict[str, object],
    startup: dict[str, str],
    inode_paths: dict[tuple[int, int], str],
    app_inode_keys: set[tuple[int, int]],
    zip_entries: dict[str, list[ZipEntry]],
) -> list[dict[str, object]]:
    page_size = int(metadata["page_size"])
    rows = query_page_cache_events(
        trace,
        int(metadata["pid"]),
        int(startup["ts"]),
        int(startup["ts_end"]),
        app_inode_keys,
    )
    events = []
    for row in rows:
        key = (int(row["sdev"]), int(row["inode"]))
        file_name = inode_paths.get(key)
        page_index = int(row["page_index"])
        file_offset = page_index * page_size
        zip_entry = (
            find_zip_entry(zip_entries.get(file_name, []), file_offset)
            if file_name
            else None
        )
        events.append(
            {
                "ts": int(row["ts"]),
                "elapsed_ms": (int(row["ts"]) - int(startup["ts"])) / 1_000_000,
                "process_name": row["process_name"],
                "thread_name": row["thread_name"],
                "tid": int(row["tid"]),
                "device": key[0],
                "inode": key[1],
                "file_name": file_name,
                "zip_entry_name": zip_entry.file_name if zip_entry else None,
                "offset": file_offset,
                "page_index": page_index,
                "page_order": int(row["page_order"]),
                "page_count": 1 << int(row["page_order"]),
                "category": classify_source(
                    file_name, zip_entry.file_name if zip_entry else None
                ),
            }
        )
    fields = (
        list(events[0].keys())
        if events
        else [
            "ts",
            "elapsed_ms",
            "process_name",
            "thread_name",
            "tid",
            "device",
            "inode",
            "file_name",
            "zip_entry_name",
            "offset",
            "page_index",
            "page_order",
            "page_count",
            "category",
        ]
    )
    with (output_dir / "page_cache_events.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(events)
    return events


def write_vdex_analysis(
    output_dir: Path,
    analyses: dict[str, VdexAnalysis],
    page_size: int,
) -> list[dict[str, object]]:
    fields = [
        "file_name",
        "dex_name",
        "start_offset",
        "page_index",
        "checksum",
        "format_version",
        "identity_verified",
    ]
    rows: list[dict[str, object]] = []
    for file_name, analysis in sorted(analyses.items()):
        if not analysis.identities_verified:
            continue
        for index, dex_range in enumerate(analysis.dex_ranges):
            rows.append(
                {
                    "file_name": file_name,
                    "dex_name": dex_range.file_name,
                    "start_offset": dex_range.data_offset,
                    "page_index": dex_range.data_offset // page_size,
                    "checksum": f"0x{analysis.stored_checksums[index]:08x}",
                    "format_version": analysis.format_version,
                    "identity_verified": True,
                }
            )
    with (output_dir / "vdex_dex_boundaries.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    return rows


def write_file_sizes(
    output_dir: Path,
    file_sizes: dict[str, int],
    zip_entries: dict[str, list[ZipEntry]],
) -> None:
    fields = [
        "file_name",
        "zip_entry_name",
        "size",
        "file_offset",
        "uncompressed_size",
        "data_end",
        "compression",
    ]
    with (output_dir / "file_sizes.csv").open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for file_name in sorted(file_sizes):
            writer.writerow(
                {
                    "file_name": file_name,
                    "zip_entry_name": None,
                    "size": file_sizes[file_name],
                    "file_offset": 0,
                    "uncompressed_size": file_sizes[file_name],
                    "data_end": file_sizes[file_name],
                    "compression": "file",
                }
            )
            for entry in zip_entries.get(file_name, []):
                writer.writerow(
                    {
                        "file_name": file_name,
                        "zip_entry_name": entry.file_name,
                        "size": entry.compressed_size,
                        "file_offset": entry.data_offset,
                        "uncompressed_size": entry.uncompressed_size,
                        "data_end": entry.data_end,
                        "compression": entry.compression,
                    }
                )


def process_capture(output_dir: Path) -> None:
    metadata_path = output_dir / "capture_metadata.json"
    if not metadata_path.exists():
        raise RuntimeError(
            "This output predates the exact perf-event collector. Recollect it; "
            "legacy page-cache events cannot be converted into true faults."
        )
    metadata = json.loads(metadata_path.read_text())
    if metadata.get("schema_version") != 5:
        raise RuntimeError(
            "This capture predates timestamped mapping attribution. "
            "Recollect it with the current collector."
        )
    trace = output_dir / "faults.pftrace"
    startup = query_startup(trace, str(metadata["package"]))
    trace_integrity = query_trace_integrity(trace)
    if trace_integrity:
        details = ", ".join(f"{row['name']}={row['value']}" for row in trace_integrity)
        raise RuntimeError(f"Perfetto reported trace integrity failures: {details}")
    metadata["trace_integrity"] = {"errors_or_data_loss": 0}
    metadata["startup"] = {
        "id": int(startup["startup_id"]),
        "ts": int(startup["ts"]),
        "ts_end": int(startup["ts_end"]),
        "duration_ns": int(startup["dur"]),
        "type": (
            None
            if startup["startup_type"] in ("", "[NULL]")
            else startup["startup_type"]
        ),
    }

    map_entries = parse_maps(output_dir)
    mapping_events = parse_mapping_events(output_dir, int(metadata["pid"]))
    inode_paths, file_sizes, app_inode_keys = parse_inode_mapping(
        output_dir, map_entries
    )
    artifacts = load_artifacts(output_dir)
    file_sections = {
        remote_path: read_zip_entries(local_path)
        for remote_path, local_path in artifacts.items()
        if remote_path.endswith((".apk", ".jar", ".zip"))
    }
    apk_dex_identities: dict[str, list[tuple[str, int]]] = {
        remote_path: read_apk_dex_identities(artifacts[remote_path])
        for remote_path in file_sections
        if remote_path.endswith(".apk")
    }
    vdex_analyses: dict[str, VdexAnalysis] = {}
    for remote_path, local_path in artifacts.items():
        if not remote_path.endswith(".vdex"):
            continue
        parents = Path(remote_path).parents
        artifact_root = str(parents[2]) if len(parents) > 2 else ""
        apk_path = next(
            (
                candidate
                for candidate in apk_dex_identities
                if str(Path(candidate).parent) == artifact_root
                and Path(candidate).stem == Path(remote_path).stem
            ),
            None,
        )
        analysis = read_vdex(local_path, apk_dex_identities.get(apk_path))
        if analysis is not None:
            vdex_analyses[remote_path] = analysis
    metadata["vdex_files"] = [
        {
            "file_name": remote_path,
            "format_version": analysis.format_version,
            "stored_dex_checksums": [
                f"0x{checksum:08x}" for checksum in analysis.stored_checksums
            ],
            "embedded_dex_files": len(analysis.dex_ranges),
            "dex_identities_verified": analysis.identities_verified,
            "verification_note": analysis.verification_note,
        }
        for remote_path, analysis in sorted(vdex_analyses.items())
    ]
    write_vdex_analysis(output_dir, vdex_analyses, int(metadata["page_size"]))
    thread_names = query_thread_names(trace, int(metadata["pid"]))
    all_faults, mapped_faults = write_fault_csvs(
        output_dir,
        metadata,
        startup,
        map_entries,
        mapping_events,
        file_sections,
        thread_names,
    )
    page_cache_events = write_page_cache_events(
        output_dir,
        trace,
        metadata,
        startup,
        inode_paths,
        app_inode_keys,
        file_sections,
    )
    write_file_sizes(output_dir, file_sizes, file_sections)

    residency = list(csv.DictReader((output_dir / "cache_residency.csv").open()))
    before_launch = [row for row in residency if row["phase"] == "before_launch"]
    metadata["cache_verification"] = {
        "files_checked": len(before_launch),
        "resident_pages": sum(int(row["resident_pages"]) for row in before_launch),
        "total_pages": sum(int(row["total_pages"]) for row in before_launch),
        "fully_evicted_files": sum(
            int(row["resident_pages"]) == 0 for row in before_launch
        ),
    }
    metadata["results"] = {
        "all_faults": len(all_faults),
        "file_backed_faults": len(mapped_faults),
        "major_file_backed_faults": sum(bool(row["is_major"]) for row in mapped_faults),
        "minor_file_backed_faults": sum(
            not bool(row["is_major"]) for row in mapped_faults
        ),
        "page_cache_insertions": len(page_cache_events),
    }
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")


def reset_output_directory(output_dir: Path, overwrite: bool) -> None:
    if output_dir.is_symlink():
        raise ValueError(f"Refusing a symlink output directory: {output_dir}")
    resolved = output_dir.resolve()
    protected = {Path("/").resolve(), Path.home().resolve(), ROOT_DIR.resolve()}
    if len(resolved.parts) < 3 or any(
        protected_path == resolved or protected_path.is_relative_to(resolved)
        for protected_path in protected
    ):
        raise ValueError(
            f"Refusing to replace protected output directory: {resolved}. "
            "Choose a dedicated capture directory."
        )
    if resolved.exists() and not resolved.is_dir():
        raise ValueError(f"Output path is not a directory: {resolved}")
    if resolved.exists() and any(resolved.iterdir()):
        if not overwrite:
            raise RuntimeError(
                f"Output directory is not empty: {resolved}\n"
                "Pass --overwrite to replace this capture directory."
            )
        marker = resolved / CAPTURE_MARKER
        if (
            not marker.is_file()
            or marker.read_text(errors="replace") != CAPTURE_MARKER_CONTENT
        ):
            raise ValueError(
                "Refusing to overwrite a non-capture directory without the "
                f"{CAPTURE_MARKER} ownership marker: {resolved}"
            )
        shutil.rmtree(resolved)
    resolved.mkdir(parents=True, exist_ok=True)
    (resolved / CAPTURE_MARKER).write_text(CAPTURE_MARKER_CONTENT)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Collect exact Android startup major/minor page faults and map "
            "file-backed addresses to files."
        )
    )
    parser.add_argument(
        "--package",
        help="Installed package (required only when collecting a new capture)",
    )
    parser.add_argument("--activity")
    parser.add_argument("--output", default="output")
    parser.add_argument("--serial")
    parser.add_argument(
        "--settle-ms",
        type=int,
        default=750,
        help="Time to keep collecting after am start -W completes (default: 750)",
    )
    parser.add_argument(
        "--pull-apks",
        action=argparse.BooleanOptionalAction,
        default=True,
        help=(
            "Pull APKs and app ART artifacts for archive/VDEX attribution "
            "(default: true)"
        ),
    )
    parser.add_argument(
        "--skip-collect",
        action="store_true",
        help="Reprocess an exact capture already in --output",
    )
    parser.add_argument(
        "--max-resident-pages",
        type=int,
        default=0,
        help=(
            "Maximum verified resident app-file pages allowed before launch "
            "(default: 0; nonzero values intentionally permit a partially warm cache)"
        ),
    )
    parser.add_argument(
        "--reboot-before-collect",
        action="store_true",
        help="Reboot the adb target and wait for boot completion before collection",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace a non-empty output carrying this tool's ownership marker",
    )
    args = parser.parse_args()
    if args.settle_ms < 0 or args.settle_ms > 10_000:
        parser.error("--settle-ms must be between 0 and 10000")
    if args.max_resident_pages < 0:
        parser.error("--max-resident-pages must be nonnegative")
    if not args.skip_collect and not args.package:
        parser.error("--package is required unless --skip-collect is used")

    output_dir = Path(args.output)
    if not args.skip_collect:
        adb = Adb(args.serial)
        reset_output_directory(output_dir, args.overwrite)
        if args.reboot_before_collect:
            adb.reboot_and_wait()
        collect(
            adb,
            args.package,
            args.activity,
            output_dir,
            args.settle_ms,
            args.pull_apks,
            args.max_resident_pages,
            args.reboot_before_collect,
        )
    process_capture(output_dir)
    print(f"Analysis complete: {output_dir.resolve()}")


if __name__ == "__main__":
    main()
