from __future__ import annotations

import subprocess
from pathlib import Path
from typing import IO, Iterable, Mapping, Optional


class CommandError(RuntimeError):
    pass


def command_text(command: Iterable[str]) -> str:
    return " ".join(str(part) for part in command)


def run(
    command: list[str],
    *,
    check: bool = True,
    cwd: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
    stdout: int | IO[str] = subprocess.PIPE,
    stderr: int | IO[str] = subprocess.PIPE,
    timeout: Optional[float] = None,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            text=True,
            stdout=stdout,
            stderr=stderr,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as error:
        detail = _timeout_text(error.stderr or error.stdout)
        if check:
            raise CommandError(
                f"Command timed out after {timeout}s: {command_text(command)}"
                + (f"\n{detail}" if detail else "")
            ) from error
        return subprocess.CompletedProcess(
            command,
            -9,
            _timeout_text(error.stdout),
            _timeout_text(error.stderr),
        )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise CommandError(
            f"Command failed ({result.returncode}): {command_text(command)}"
            + (f"\n{detail}" if detail else "")
        )
    return result


def _timeout_text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode(errors="replace")
    return value
