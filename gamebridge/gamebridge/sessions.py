"""What was launched, so a death is a result rather than a surprise.

Without this, every call after a game dies fails with `connection refused`, and that message is
ambiguous in three directions the tool could distinguish: not started yet, crashed, or wrong port.
A caller then works backwards from a symptom, and an unattended run that dies at step four leaves
four passing assertions and a mystery.

A session is a small file per port recording what was started. It is written by `launch` and read by
anything that fails to connect, which turns "connection refused" into "the client exited".

**Liveness is checked by identity, not by a pid alone.** Pids are recycled, and a recycled one
belonging to some unrelated program would read as a healthy game. So the check asks whether a
process with that pid is running *this port's* game, by looking for `-Ddevbridge.port=<port>` in its
command line. A pid that has been reused fails that test for the same reason a wrong game fails
`ping --expect-instance`.

**And never with os.kill.** The usual `os.kill(pid, 0)` liveness probe is a POSIX idiom; on Windows
Python maps any signal other than the CTRL_* events to TerminateProcess, so the probe would kill the
game it was asking about.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path


def directory() -> Path:
    """Where sessions live. Per user rather than per instance, because a caller that failed to
    connect knows only the port."""
    root = Path(os.environ.get("GAMEBRIDGE_HOME") or (Path.home() / ".gamebridge"))
    return root / "sessions"


def path_for(port: int) -> Path:
    return directory() / f"{port}.json"


def record(port: int, pid: int, instance: Path, world: str | None, log: Path | None) -> Path:
    session = {
        "port": port,
        "pid": pid,
        "instance": str(Path(instance).resolve()),
        "world": world,
        "log": str(log) if log else None,
        "started": time.time(),
    }
    target = path_for(port)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(session, indent=2), encoding="utf-8")
    return target


def load(port: int) -> dict | None:
    target = path_for(port)
    if not target.is_file():
        return None
    try:
        return json.loads(target.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        # A half-written or hand-edited file should not take a caller down; it just means we know
        # nothing, which is the state we were in before sessions existed.
        return None


def forget(port: int) -> None:
    path_for(port).unlink(missing_ok=True)


def all_sessions() -> list[dict]:
    if not directory().is_dir():
        return []
    out = []
    for entry in sorted(directory().glob("*.json")):
        try:
            out.append(json.loads(entry.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            continue
    return out


def status(session: dict) -> str:
    """`running`, `exited`, or `unknown` when the platform cannot be asked cheaply."""
    pid, port = session.get("pid"), session.get("port")
    if not pid or not port:
        return "unknown"
    marker = f"devbridge.port={port}"

    if sys.platform == "win32":
        try:
            probe = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 f"$p = Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\";"
                 f" if ($p -and $p.CommandLine -like '*{marker}*') {{ 'running' }} else {{ 'exited' }}"],
                capture_output=True, text=True, timeout=20, check=False)
            answer = probe.stdout.strip().splitlines()[-1].strip() if probe.stdout.strip() else ""
            return answer if answer in ("running", "exited") else "unknown"
        except (OSError, subprocess.SubprocessError, IndexError):
            return "unknown"

    # POSIX: read the command line straight off /proc, which is the same identity check without a
    # subprocess. Falling back to unknown rather than to os.kill, which is a probe with a side effect.
    cmdline = Path(f"/proc/{pid}/cmdline")
    if not cmdline.exists():
        return "exited"
    try:
        return "running" if marker in cmdline.read_bytes().decode("utf-8", "replace") else "exited"
    except OSError:
        return "unknown"


def explain_refusal(port: int) -> str | None:
    """A sentence saying what happened, for a caller that just got `connection refused`.

    Returns None when there is nothing recorded, because inventing an explanation is worse than the
    bare refusal - the caller may simply have pointed at a game somebody else started.
    """
    session = load(port)
    if session is None:
        return None
    state = status(session)
    if state == "running":
        return (f"a game for port {port} is running as pid {session['pid']}, so it is probably "
                f"still starting up. Its log: {session.get('log')}")
    if state == "exited":
        return (f"the client exited. It was pid {session['pid']}, launched from "
                f"{session.get('instance')} at world {session.get('world')!r}. "
                f"Its log: {session.get('log')}")
    return f"a game was launched on port {port} as pid {session['pid']}; its state is unknown here"
