"""Read the running game's log, so a silent failure stops being silent.

A command that places a scene and throws inside a mod's own code returns nothing over the wire: `cmd`
reports what the command printed, and a failure deep in someone else's code usually prints nothing at
all. An unattended run then reports success for a scene that is not there.

**Deliberately not a verb.** The obvious design was an in-game appender and a `log` verb, and it is
more machinery than the job needs. `ping` already reports `gameDir`, the game writes `logs/latest.log`
promptly, and devbridge is loopback-only by construction - the game is always on this machine. So the
client reads the file directly: no mod code, no log4j dependency, no protocol version bump, and it
works on a game that has already died, which an in-game verb cannot.

The cursor is a byte offset. It survives being passed back through a shell, it needs no clock, and
"how far had I read" is exactly what it means.
"""

from __future__ import annotations

import re
from pathlib import Path

#: `[04Aug2026 22:10:45.574] [Render thread/WARN] [ModernFix/]: ...`
_LEVEL = re.compile(r"^\[[^\]]*\]\s*\[[^\]/]*/(?P<level>[A-Z]+)\]")

_RANK = {"TRACE": 0, "DEBUG": 1, "INFO": 2, "WARN": 3, "ERROR": 4, "FATAL": 5}


class LogError(RuntimeError):
    pass


def log_path(game_dir: str | Path) -> Path:
    path = Path(game_dir) / "logs" / "latest.log"
    if not path.is_file():
        raise LogError(f"no log at {path}. Is that the game directory ping reported?")
    return path


def read_since(game_dir: str | Path, since: int = 0, level: str = "WARN") -> dict:
    """Lines at or above `level` written since byte offset `since`.

    A line with no recognisable level marker inherits the previous line's, because a stack trace is
    printed as many lines under one header and dropping its body would leave the header alone saying
    something threw without saying what.
    """
    wanted = _RANK.get(level.upper())
    if wanted is None:
        raise LogError(f"unknown level {level!r}: expected one of {', '.join(_RANK)}")

    path = log_path(game_dir)
    size = path.stat().st_size
    if since > size:
        # The file shrank, so it rotated or the game restarted. Starting over beats reading from an
        # offset into a different file, which would return whatever happens to be at that byte.
        since = 0

    with path.open("r", encoding="utf-8", errors="replace") as handle:
        handle.seek(since)
        text = handle.read()
        marker = handle.tell()

    kept: list[str] = []
    counts = {name: 0 for name in _RANK}
    current = None
    for line in text.splitlines():
        match = _LEVEL.match(line)
        if match:
            current = match.group("level")
            if current in counts:
                counts[current] += 1
        if current is not None and _RANK.get(current, -1) >= wanted:
            kept.append(line)

    return {
        "marker": marker,
        "lines": kept,
        "errors": counts["ERROR"] + counts["FATAL"],
        "warnings": counts["WARN"],
    }
