import importlib.util
import subprocess
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ENGINE = (
    Path(__file__).parents[2]
    / "main"
    / "resources"
    / "faults-engine"
    / "android"
    / "faults.py"
)
SPEC = importlib.util.spec_from_file_location("android_faults", ENGINE)
assert SPEC is not None and SPEC.loader is not None
faults = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = faults
SPEC.loader.exec_module(faults)


def fake_dex() -> bytes:
    data = bytearray(112)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<I", data, 32, len(data))
    return bytes(data)


def android10_vdex(checksums: list[int]) -> bytes:
    dex_payloads = b"".join(struct.pack("<I", 0) + fake_dex() for _ in checksums)
    header = b"vdex" + b"021\0" + b"002\0"
    header += struct.pack("<4I", len(checksums), 0, 0, 0)
    header += struct.pack(f"<{len(checksums)}I", *checksums)
    header += struct.pack("<3I", len(dex_payloads), 0, 0)
    return header + dex_payloads


def sectioned_vdex(checksums: list[int], include_dex: bool = True) -> bytes:
    section_count = 4
    table_end = 12 + section_count * 12
    checksum_bytes = struct.pack(f"<{len(checksums)}I", *checksums)
    dex_bytes = b"".join(fake_dex() for _ in checksums) if include_dex else b""
    checksum_offset = table_end
    dex_offset = checksum_offset + len(checksum_bytes)
    verifier_offset = dex_offset + len(dex_bytes)
    sections = [
        (0, checksum_offset, len(checksum_bytes)),
        (1, dex_offset if dex_bytes else 0, len(dex_bytes)),
        (2, verifier_offset, 0),
        (3, verifier_offset, 0),
    ]
    return (
        b"vdex"
        + b"027\0"
        + struct.pack("<I", section_count)
        + b"".join(struct.pack("<3I", *section) for section in sections)
        + checksum_bytes
        + dex_bytes
    )


class VdexTests(unittest.TestCase):
    def parse(self, data: bytes, identities):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "base.vdex"
            path.write_bytes(data)
            return faults.read_vdex(path, identities)

    def test_android10_names_require_complete_checksum_match(self):
        checksums = [0x12345678, 0x9ABCDEF0]
        analysis = self.parse(
            android10_vdex(checksums),
            [("classes.dex", checksums[0]), ("classes2.dex", checksums[1])],
        )
        self.assertIsNotNone(analysis)
        self.assertTrue(analysis.identities_verified)
        self.assertEqual(
            ["classes.dex", "classes2.dex"],
            [entry.file_name for entry in analysis.dex_ranges],
        )

    def test_one_mismatch_suppresses_every_apk_dex_identity(self):
        checksums = [0x12345678, 0x9ABCDEF0]
        analysis = self.parse(
            sectioned_vdex(checksums),
            [("classes.dex", checksums[0]), ("classes2.dex", 7)],
        )
        self.assertIsNotNone(analysis)
        self.assertFalse(analysis.identities_verified)
        self.assertNotIn(
            "classes",
            " ".join(entry.file_name for entry in analysis.dex_ranges),
        )

    def test_sectioned_027_with_embedded_dex_is_supported(self):
        checksum = 0xAABBCCDD
        analysis = self.parse(
            sectioned_vdex([checksum]),
            [("classes.dex", checksum)],
        )
        self.assertIsNotNone(analysis)
        self.assertEqual("027", analysis.format_version)
        self.assertTrue(analysis.identities_verified)
        self.assertEqual("classes.dex", analysis.dex_ranges[0].file_name)
        self.assertEqual(64, analysis.dex_ranges[0].data_offset)

    def test_sectioned_027_without_embedded_dex_preserves_verification(self):
        checksum = 0xAABBCCDD
        analysis = self.parse(
            sectioned_vdex([checksum], include_dex=False),
            [("classes.dex", checksum)],
        )
        self.assertIsNotNone(analysis)
        self.assertTrue(analysis.identities_verified)
        self.assertEqual((), analysis.dex_ranges)

    def test_unknown_vdex_version_is_not_guessed(self):
        analysis = self.parse(b"vdex999\0" + bytes(128), None)
        self.assertIsNone(analysis)


class CacheAttributionTests(unittest.TestCase):
    def test_collector_command_preserves_successful_skip_diagnostics(self):
        adb = mock.Mock()
        adb.root_shell.return_value = subprocess.CompletedProcess(
            args=["collector"],
            returncode=0,
            stdout="file_name,size_bytes,total_pages,resident_pages\n",
            stderr="Skipping file that disappeared during residency check: /tmp/race",
        )
        diagnostics: list[dict[str, object]] = []

        output = faults.run_collector_file_command(
            adb,
            "--residency",
            ["/tmp/race"],
            diagnostics,
        )

        self.assertTrue(output.startswith("file_name,size_bytes"))
        self.assertEqual(0, diagnostics[0]["exit_status"])
        self.assertIn("/tmp/race", str(diagnostics[0]["stderr"]))

    def test_query_keeps_kernel_workers_and_exact_inode_targets(self):
        with mock.patch.object(faults, "run_trace_query", return_value=[]) as query:
            faults.query_page_cache_events(
                Path("trace"),
                123,
                1000,
                2000,
                {(8, 101), (9, 202)},
            )
        sql = query.call_args.args[1]
        self.assertIn("VALUES (8, 101),", sql)
        self.assertIn("(9, 202)", sql)
        self.assertIn("LEFT JOIN thread", sql)
        self.assertIn("LEFT JOIN process", sql)
        self.assertIn("process.pid = 123", sql)
        self.assertIn("app_inodes.inode = cache_events.inode", sql)
        self.assertNotIn("LIKE", sql.upper())

    def test_optional_disappearing_residency_file_is_a_warning(self):
        text = (
            "file_name,size_bytes,total_pages,resident_pages\n"
            '"/data/app/pkg/base.apk",4096,1,0\n'
        )
        warnings: list[str] = []
        rows = faults.parse_residency(
            text,
            "before_launch",
            ["/data/app/pkg/base.apk", "/data/user/0/pkg/temp"],
            ["/data/app/pkg/base.apk"],
            warnings,
        )
        self.assertEqual(1, len(rows))
        self.assertIn("/data/user/0/pkg/temp", warnings[0])

    def test_missing_installed_apk_residency_is_a_hard_failure(self):
        text = "file_name,size_bytes,total_pages,resident_pages\n"
        with self.assertRaisesRegex(RuntimeError, "missing_required"):
            faults.parse_residency(
                text,
                "before_launch",
                ["/data/app/pkg/base.apk"],
                ["/data/app/pkg/base.apk"],
                [],
            )

    def test_similarly_named_package_is_not_app_owned(self):
        package = "com.example.app"
        self.assertTrue(
            faults.is_app_owned_path(
                "/data/app/~~hash/com.example.app-random/base.apk", package
            )
        )
        self.assertTrue(
            faults.is_app_owned_path(
                "/data/user/0/com.example.app/files/cache", package
            )
        )
        self.assertFalse(
            faults.is_app_owned_path(
                "/data/app/~~hash/com.example.application-random/base.apk",
                package,
            )
        )
        self.assertFalse(
            faults.is_app_owned_path(
                "/data/user/0/com.example.application/files/cache", package
            )
        )


class CpuListTests(unittest.TestCase):
    def test_noncontiguous_linux_cpu_list(self):
        header = ENGINE.parent / "native" / "cpu_list.h"
        source = f"""
#include <stdio.h>
#include <stdlib.h>
#include "{header}"
int main(void) {{
  int *cpus = NULL;
  size_t count = 0;
  char error[160] = {{0}};
  if (parse_cpu_list("0-2,8,10-11\\n", &cpus, &count, error, sizeof(error))) {{
    fprintf(stderr, "%s\\n", error);
    return 1;
  }}
  for (size_t i = 0; i < count; ++i) printf("%s%d", i ? "," : "", cpus[i]);
  free(cpus);
  cpus = NULL;
  count = 0;
  if (parse_cpu_list("1048576", &cpus, &count, error, sizeof(error)) == 0) {{
    free(cpus);
    return 2;
  }}
  return 0;
}}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            harness = root / "cpu_list_test.c"
            executable = root / "cpu_list_test"
            harness.write_text(source)
            subprocess.run(
                [
                    "cc",
                    "-std=c11",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    str(harness),
                    "-o",
                    str(executable),
                ],
                check=True,
            )
            result = subprocess.run(
                [str(executable)], check=True, capture_output=True, text=True
            )
        self.assertEqual("0,1,2,8,10,11", result.stdout)


if __name__ == "__main__":
    unittest.main()
