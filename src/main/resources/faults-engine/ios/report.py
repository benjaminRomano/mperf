#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path

from ios_fault_visualizer.reporting import build_report


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate a self-contained iOS page-fault report"
    )
    parser.add_argument("capture", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    capture = args.capture.resolve()
    output = (args.output or capture / "report.html").resolve()
    if output.parent != capture:
        raise SystemExit(
            "--output must be inside the capture directory so artifact links "
            "remain valid"
        )
    build_report(capture, output)
    print(f"Report written: {output.resolve()}")


if __name__ == "__main__":
    main()
