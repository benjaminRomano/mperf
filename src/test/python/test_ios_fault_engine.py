import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


IOS_ENGINE = Path(__file__).parents[2] / "main" / "resources" / "faults-engine" / "ios"
sys.path.insert(0, str(IOS_ENGINE))

FAULTS_SPEC = importlib.util.spec_from_file_location(
    "ios_faults", IOS_ENGINE / "faults.py"
)
assert FAULTS_SPEC is not None and FAULTS_SPEC.loader is not None
ios_faults = importlib.util.module_from_spec(FAULTS_SPEC)
sys.modules[FAULTS_SPEC.name] = ios_faults
FAULTS_SPEC.loader.exec_module(ios_faults)

from ios_fault_visualizer import instruments, parser, reporting  # noqa: E402
from ios_fault_visualizer.devices import Target  # noqa: E402


def xml_row(
    *,
    time: int,
    operation: str,
    binary: str,
    binary_path: str,
    frame: str,
    address: int,
    pid: int = 42,
    caller_binary: str = "",
    caller_path: str = "",
    caller_frame: str = "",
) -> str:
    caller = ""
    if caller_frame:
        caller = (
            f'<frame name="{caller_frame}" addr="0x2">'
            f'<binary name="{caller_binary}" path="{caller_path}"/></frame>'
        )
    return (
        f'<row><start-time>{time}</start-time><vm-op fmt="{operation}"/>'
        f'<process fmt="Example"><pid>{pid}</pid></process>'
        '<thread fmt="main"><tid>7</tid>'
        f'<process fmt="Example"><pid>{pid}</pid></process></thread>'
        f"<address>{address}</address><size-in-bytes>4096</size-in-bytes>"
        "<duration>100</duration><tagged-backtrace><backtrace>"
        f'<frame name="{frame}" addr="0x1">'
        f'<binary name="{binary}" path="{binary_path}"/></frame>'
        f"{caller}</backtrace></tagged-backtrace></row>"
    )


class RecordingWindowTests(unittest.TestCase):
    def test_recording_limit_must_cover_fractional_settle_window(self):
        with self.assertRaisesRegex(ValueError, "at least"):
            ios_faults.validate_recording_window(2.1, 2)
        ios_faults.validate_recording_window(2.1, 3)

    def test_early_recorder_exit_rejects_incomplete_post_launch_window(self):
        with tempfile.TemporaryDirectory() as directory:
            stderr_path = Path(directory) / "xctrace.stderr.log"
            stderr_path.write_text("")
            process = mock.Mock()
            process.wait.return_value = 0
            recording = mock.Mock(process=process, stderr_path=stderr_path)

            with self.assertRaisesRegex(
                RuntimeError, "before the requested post-launch analysis window"
            ):
                ios_faults.wait_for_post_launch_window(recording, 3.0)

        process.wait.assert_called_once_with(timeout=3.0)

    def test_running_recorder_covers_complete_post_launch_window(self):
        process = mock.Mock()
        process.wait.side_effect = ios_faults.subprocess.TimeoutExpired("xctrace", 3.0)
        recording = mock.Mock(process=process)

        ios_faults.wait_for_post_launch_window(recording, 3.0)

        process.wait.assert_called_once_with(timeout=3.0)

    def test_recording_window_values_must_be_positive(self):
        with self.assertRaisesRegex(ValueError, "settle-seconds"):
            ios_faults.validate_recording_window(0, 1)
        with self.assertRaisesRegex(ValueError, "time-limit"):
            ios_faults.validate_recording_window(1, 0)


class ReportInteractionTests(unittest.TestCase):
    def test_stack_drag_zoom_uses_fault_order_not_timestamp(self):
        template = reporting.REPORT_TEMPLATE

        self.assertIn(
            "orderStart=Math.min(a[E.index],b[E.index])",
            template,
        )
        self.assertIn(
            "orderEnd=Math.max(a[E.index],b[E.index])",
            template,
        )
        self.assertNotIn(
            "rangeStart=Math.min(a[E.time],b[E.time])",
            template,
        )


class BundleAttributionTests(unittest.TestCase):
    def test_framework_and_extension_are_owned_but_unrelated_app_is_not(self):
        root = "/tmp/Containers/Example.app"
        rows = [
            xml_row(
                time=100,
                operation="File Backed Page In",
                binary="Example",
                binary_path=f"{root}/Example",
                frame="-[App launch]",
                address=0x1000,
            ),
            xml_row(
                time=100,
                operation="File Backed Page In",
                binary="Feature",
                binary_path=f"{root}/Frameworks/Feature.framework/Feature",
                frame="Feature.load",
                address=0x2000,
            ),
            xml_row(
                time=110,
                operation="Page Cache Hit",
                binary="Widget",
                binary_path=f"{root}/PlugIns/Widget.appex/Widget",
                frame="Widget.start",
                address=0x3000,
            ),
            xml_row(
                time=120,
                operation="File Backed Page In",
                binary="Other",
                binary_path="/tmp/Containers/Other.app/Frameworks/Feature.framework/Feature",
                frame="Other.load",
                address=0x4000,
                caller_binary="Example",
                caller_path=f"{root}/Example",
                caller_frame="App.caller",
            ),
            xml_row(
                time=90,
                operation="File Backed Page In",
                binary="Example",
                binary_path=f"{root}/Example",
                frame="Wrong.process",
                address=0x5000,
                pid=99,
            ),
        ]
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "virtual-memory.xml"
            xml.write_text("<trace>" + "".join(rows) + "</trace>")
            events = parser.parse_events(
                xml,
                42,
                "Example",
                app_bundle_root=root,
            )

        self.assertEqual([1, 2, 3, 4], [event["event_index"] for event in events])
        self.assertEqual(
            [True, True, True, False],
            [event["faulting_binary_is_bundle_owned"] for event in events],
        )
        self.assertEqual("Feature.load", events[1]["first_app_frame"])
        self.assertEqual("Widget.start", events[2]["first_app_frame"])
        self.assertEqual("App.caller", events[3]["first_app_frame"])

    def test_ordering_candidates_require_the_faulting_binary_to_be_owned(self):
        def event(
            frame: str,
            path: str,
            first_ms: float,
            *,
            owned: bool = True,
        ):
            return {
                "fault_class": "Major",
                "faulting_binary_is_bundle_owned": owned,
                "faulting_frame": frame,
                "faulting_instruction": "0x1",
                "faulting_binary": Path(path).name,
                "faulting_binary_path": path,
                "faulting_source_path": "",
                "faulting_source_line": 0,
                "first_symbolicated_frame": frame,
                "first_symbolicated_binary": Path(path).name,
                "first_app_frame": "App.caller",
                "first_app_binary": "Example",
                "first_app_source_path": "",
                "first_app_source_line": 0,
                "time_since_first_fault_ms": first_ms,
                "duration_ns": 10,
                "address": int(first_ms) + 1,
                "address_hex": hex(int(first_ms) + 1),
                "stack": frame,
            }

        events = [
            event("Feature.load", "/Example.app/Frameworks/Feature", 8),
            event("Feature.load", "/Example.app/Frameworks/Feature", 4),
            event("main", "/Example.app/Example", 1),
            event("<deduplicated_symbol>", "/Example.app/Example", 1.5),
            event("__llvm_profile_initialize", "/Example.app/Example", 1.6),
            event("libsystem_pagein", "/usr/lib/libSystem.dylib", 2, owned=False),
        ]
        summaries = parser.summarize_major_faults(events)
        self.assertEqual(1, len(summaries))
        self.assertEqual("Feature.load", summaries[0]["faulting_frame"])
        self.assertEqual(2, summaries[0]["major_fault_count"])
        self.assertEqual(4, summaries[0]["first_fault_ms"])

    def test_physical_export_infers_bundle_root_from_main_executable(self):
        root = "/private/var/containers/Bundle/Application/UUID/Example.app"
        rows = [
            xml_row(
                time=100,
                operation="File Backed Page In",
                binary="Feature",
                binary_path=f"{root}/Frameworks/Feature.framework/Feature",
                frame="Feature.load",
                address=0x1000,
            ),
            xml_row(
                time=110,
                operation="Page Cache Hit",
                binary="Example",
                binary_path=f"{root}/Example",
                frame="App.start",
                address=0x2000,
            ),
        ]
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "virtual-memory.xml"
            xml.write_text("<trace>" + "".join(rows) + "</trace>")
            events = parser.parse_events(xml, 42, "Example")

        self.assertTrue(events[0]["faulting_binary_is_bundle_owned"])
        self.assertTrue(events[1]["faulting_binary_is_bundle_owned"])
        self.assertEqual("Feature.load", events[0]["first_app_frame"])


class InstrumentsLifecycleTests(unittest.TestCase):
    def test_start_recording_uses_one_shot_listener_and_named_option(self):
        target = Target("simulator", "UDID", "Simulator", "iOS")
        listener = mock.Mock()
        process = mock.Mock()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            notifyutil = root / "notifyutil"
            notifyutil.touch()
            with (
                mock.patch.object(instruments, "require_virtual_memory_instrument"),
                mock.patch.object(instruments, "NOTIFYUTIL_PATH", notifyutil),
                mock.patch.object(
                    instruments,
                    "xctrace_command",
                    side_effect=lambda *arguments: ["xctrace", *arguments],
                ),
                mock.patch.object(
                    instruments.subprocess,
                    "Popen",
                    side_effect=[listener, process],
                ) as popen,
            ):
                recording = instruments.start_recording(
                    target,
                    root / "capture.trace",
                    3,
                    root,
                )
            stdout = getattr(process, "_ios_fault_stdout")
            stderr = getattr(process, "_ios_fault_stderr")
            stdout.close()
            stderr.close()

        listener_command = popen.call_args_list[0].args[0]
        recorder_command = popen.call_args_list[1].args[0]
        self.assertEqual("-1", listener_command[1])
        readiness_options = [
            argument
            for argument in recorder_command
            if argument.startswith("--notify-tracing-started=")
        ]
        self.assertEqual(1, len(readiness_options))
        self.assertEqual(listener_command[2], readiness_options[0].split("=", 1)[1])
        self.assertIs(process, recording.process)

    def test_readiness_uses_the_explicit_xctrace_notification(self):
        process = mock.Mock()
        process.poll.return_value = None
        listener = mock.Mock()
        listener.poll.side_effect = [None, 0]
        recording = instruments.Recording(
            process=process,
            notification_listener=listener,
            command=["xctrace"],
            stdout_path=Path("/missing-stdout"),
            stderr_path=Path("/missing-stderr"),
        )
        with mock.patch.object(instruments.time, "sleep"):
            instruments.wait_until_recording(recording, timeout_seconds=1)
        self.assertEqual(2, listener.poll.call_count)

    def test_abort_stops_and_reaps_recorder_and_listener(self):
        process = mock.Mock(pid=123)
        process.poll.return_value = None
        process.wait.return_value = 0
        listener = mock.Mock()
        listener.poll.return_value = None
        recording = instruments.Recording(
            process=process,
            notification_listener=listener,
            command=["xctrace"],
            stdout_path=Path("/missing-stdout"),
            stderr_path=Path("/missing-stderr"),
        )
        with mock.patch.object(instruments.os, "killpg") as killpg:
            instruments.abort_recording(recording)

        killpg.assert_called_once_with(123, instruments.signal.SIGINT)
        process.wait.assert_called_once()
        listener.terminate.assert_called_once()
        listener.wait.assert_called_once()

    def test_launch_failure_aborts_recorder_after_readiness(self):
        target = Target("simulator", "UDID", "Simulator", "iOS")
        order: list[str] = []
        recording = mock.Mock()
        args = SimpleNamespace(
            settle_seconds=1.0,
            time_limit=2,
            output=Path("capture"),
            overwrite=False,
            device="booted",
            app_binary_name="Example",
            bundle_id="com.example.app",
            app=None,
            cache_policy="none",
            development_team=None,
            pressure_fraction=0.15,
            pressure_hold_seconds=1,
            pressure_megabytes=None,
            host_free_percent=50,
            max_host_pressure_delta=20,
            allow_host_pressure=False,
            residency_threshold=0.05,
            allow_unconfirmed_cache=False,
            require_cold_cache=False,
            app_argument=[],
        )

        with tempfile.TemporaryDirectory() as directory:
            args.output = Path(directory) / "capture"
            app_root = Path(directory) / "Example.app"
            app_root.mkdir()
            with (
                mock.patch.object(ios_faults, "resolve_target", return_value=target),
                mock.patch.object(
                    ios_faults, "simulator_app_container", return_value=app_root
                ),
                mock.patch.object(
                    ios_faults,
                    "_app_identity",
                    return_value=("com.example.app", "Example"),
                ),
                mock.patch.object(ios_faults, "terminate_app", return_value={}),
                mock.patch.object(
                    ios_faults,
                    "prepare_cache",
                    return_value=({"procedure": "none"}, [], []),
                ),
                mock.patch.object(
                    ios_faults,
                    "start_recording",
                    side_effect=lambda *unused: (order.append("start") or recording),
                ),
                mock.patch.object(
                    ios_faults,
                    "wait_until_recording",
                    side_effect=lambda unused: order.append("ready"),
                ),
                mock.patch.object(
                    ios_faults,
                    "should_validate_prelaunch_cache",
                    return_value=True,
                ),
                mock.patch.object(
                    ios_faults,
                    "validate_prelaunch_cache",
                    side_effect=lambda **unused: (order.append("cache") or []),
                ),
                mock.patch.object(
                    ios_faults,
                    "launch_app",
                    side_effect=lambda *unused, **kwargs: (
                        order.append("launch")
                        or (_ for _ in ()).throw(RuntimeError("launch failed"))
                    ),
                ),
                mock.patch.object(
                    ios_faults,
                    "abort_recording",
                    side_effect=lambda unused: order.append("abort"),
                ),
                mock.patch.object(ios_faults, "finish_recording") as finish,
            ):
                with self.assertRaisesRegex(RuntimeError, "launch failed"):
                    ios_faults.capture(args)

        self.assertEqual(["start", "ready", "cache", "launch", "abort"], order)
        finish.assert_not_called()


if __name__ == "__main__":
    unittest.main()
