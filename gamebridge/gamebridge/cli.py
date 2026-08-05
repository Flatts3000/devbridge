"""`gamebridge` - run commands against a Minecraft dev server and read what it says back.

    gamebridge wait                      block until the server is accepting RCON
    gamebridge cmd "time set noon"       run one command, print the output
    gamebridge script scene.mcfunction   run a file, one command per line
    gamebridge probe 10 64 10            what block is at these coordinates
    gamebridge check "entity @e[type=minecraft:painting]" --count 6

Two transports, same verbs. RCON reaches a dedicated server and is the default. The devbridge mod runs
inside the game and is the only way to reach a SINGLEPLAYER world or to take a screenshot:

    gamebridge --devbridge 25580 cmd "function recompile:showcase/museum"
    gamebridge --devbridge 25580 shot museum

Connection settings come from the server's own `server.properties`, found by walking up from the
working directory, so there is nothing to configure and no password to keep in sync. Override with
`--host`, `--port`, `--password` or `--properties`.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .devbridge import DevBridge, DevBridgeError
from .rcon import Rcon, RconError


def find_properties(start: Path | None = None) -> Path | None:
    """The nearest `server.properties`, looked for in the usual dev-run places."""
    here = (start or Path.cwd()).resolve()
    for directory in [here, *here.parents]:
        for candidate in (directory / "server.properties",
                          directory / "run" / "server.properties"):
            if candidate.is_file():
                return candidate
    return None


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def connect(args):
    """Whichever transport was asked for. Both answer `command` and `script` identically.

    devbridge is chosen explicitly rather than sniffed. Guessing would mean trying a port, failing,
    and trying another, which turns "the game is not running" into a confusing multi-second stall.
    """
    if args.devbridge is not None:
        # The default host differs by transport on purpose: see the note in devbridge.py about
        # loopback resolving to ::1.
        host = args.host if args.host != "127.0.0.1" else "localhost"
        return DevBridge(host=host, port=args.devbridge, timeout=args.timeout)

    host, port, password = args.host, args.port, args.password

    if password is None or port is None:
        path = Path(args.properties) if args.properties else find_properties()
        if path is None:
            sys.exit("no server.properties found; pass --password and --port, or --properties")
        props = read_properties(path)
        if props.get("enable-rcon", "false").lower() != "true":
            sys.exit(f"RCON is disabled in {path}. Set enable-rcon=true and a rcon.password, "
                     f"then restart the server.")
        port = port or int(props.get("rcon.port", 25575))
        password = password if password is not None else props.get("rcon.password", "")
        if not password:
            sys.exit(f"rcon.password is empty in {path}. A blank password is refused by the server.")

    return Rcon(host=host, port=port, password=password, timeout=args.timeout)


def cmd_wait(args) -> int:
    """Block until the server answers, because a freshly started one takes a while to be ready.

    Worth having as its own verb: without it every script that starts a server races it, and the
    failure is an obscure connection-refused rather than "not up yet".
    """
    deadline = time.monotonic() + args.for_seconds
    last: Exception | None = None
    while time.monotonic() < deadline:
        try:
            with connect(args) as rcon:
                rcon.command("list")
            print("server is up")
            return 0
        except (OSError, RconError, SystemExit) as exc:
            last = exc if not isinstance(exc, SystemExit) else last
            time.sleep(2.0)
    print(f"server did not become ready within {args.for_seconds:g}s ({last})", file=sys.stderr)
    return 1


def cmd_run(args) -> int:
    with connect(args) as rcon:
        output = (rcon.command(args.command, args.player)
                  if args.player and hasattr(rcon, "hud") else rcon.command(args.command))
    if output:
        print(output)
    return 0


def cmd_script(args) -> int:
    lines = Path(args.file).read_text(encoding="utf-8").splitlines()
    with connect(args) as rcon:
        pairs = (rcon.script(lines, args.player)
                 if args.player and hasattr(rcon, "hud") else rcon.script(lines))
        for command, output in pairs:
            print(f"> {command}")
            if output:
                for line in output.splitlines():
                    print(f"  {line}")
    return 0


def cmd_shot(args) -> int:
    """Take a screenshot. devbridge only, and it says so rather than hanging."""
    if args.devbridge is None:
        sys.exit("screenshots need --devbridge: RCON talks to a server, and a server has no client")
    with connect(args) as bridge:
        reply = bridge.screenshot(args.name)
    print(reply.get("path") or reply.get("message", "screenshot taken"))
    return 0


def cmd_hud(args) -> int:
    """Show or hide the HUD. Its own verb because grab captures the frame already drawn."""
    if args.devbridge is None:
        sys.exit("--devbridge required: the HUD is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        bridge.hud(args.state == "on")
    print(f"hud {args.state}")
    return 0


def cmd_input(args) -> int:
    """Hand the mouse back, or take it again.

    The mod takes it on world load so a stray alt-tab cannot turn the camera between the command
    that framed a shot and the shot itself.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: the mouse is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        bridge.input(args.state == "on")
    print(f"input {args.state}")
    return 0


def cmd_pause(args) -> int:
    """Restore pausing on lost focus, or turn it off again.

    The mod turns it off on world load. That is what lets an unfocused client answer at all: a
    paused singleplayer world stops ticking, so a queued command waits for somebody to click back in.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: pausing is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        bridge.pause(args.state == "on")
    print(f"pause-on-lost-focus {args.state}")
    return 0


def cmd_ping(args) -> int:
    """Handshake, and the protocol version check.

    devbridge only. RCON has no equivalent: `wait` is the "is it up" primitive there, and it works
    by running a command rather than by asking anything about the other end.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: ping is a devbridge verb; use `wait` for RCON")
    with connect(args) as bridge:
        reply = bridge.ping()
    for key in ("protocol", "side", "mcVersion", "hasClient", "pauseOnLostFocus", "inputLocked"):
        if key in reply:
            print(f"{key}: {reply[key]}")
    return 0


def cmd_probe(args) -> int:
    """What is actually at a position.

    This is the verb that makes the whole tool worth building. Placing a structure and looking at a
    screenshot tells you it went somewhere; asking the server what block is at a coordinate tells you
    whether it went where you meant.
    """
    x, y, z = args.x, args.y, args.z
    with connect(args) as rcon:
        print(rcon.command(f"data get block {x} {y} {z}"))
    return 0


def cmd_check(args) -> int:
    """Assert a condition about the world, and exit non-zero when it does not hold.

    Wraps `execute if`, whose replies are the only machine-readable answers the server gives: it says
    "Test passed", optionally with "Count: n", or "Test failed". That turns scene verification into
    something a script can gate on rather than something a person squints at in a screenshot.
    """
    with connect(args) as rcon:
        output = rcon.command(f"execute if {args.condition}")
    passed = output.startswith("Test passed")

    count = None
    if "Count:" in output:
        count = int(output.rsplit("Count:", 1)[1].strip().rstrip("."))
    if args.count is not None:
        passed = passed and count == args.count

    label = "ok  " if passed else "FAIL"
    expected = "" if args.count is None else f" (wanted {args.count}, got {count})"
    print(f"{label} {args.condition}{expected}")
    return 0 if passed else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gamebridge", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument("--properties", default=None,
                        help="path to server.properties (default: nearest one above cwd)")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--player", default=None, metavar="NAME",
                        help="run commands AS this player (devbridge only). Without it commands run "
                             "as the console, where @s matches nothing and ~ is spawn-relative")
    parser.add_argument("--devbridge", type=int, default=None, metavar="PORT",
                        help="talk to the devbridge mod on this port instead of RCON; "
                             "required for singleplayer and for screenshots")

    subs = parser.add_subparsers(dest="verb", required=True)

    wait = subs.add_parser("wait", help="block until the server accepts RCON")
    wait.add_argument("--for", dest="for_seconds", type=float, default=180.0)
    wait.set_defaults(func=cmd_wait)

    run = subs.add_parser("cmd", help="run one command")
    run.add_argument("command")
    run.set_defaults(func=cmd_run)

    script = subs.add_parser("script", help="run a file of commands")
    script.add_argument("file")
    script.set_defaults(func=cmd_script)

    check = subs.add_parser("check", help="assert an `execute if` condition; non-zero when it fails")
    check.add_argument("condition", help='e.g. "block 6 125 0 recompile:corrugated_metal"')
    check.add_argument("--count", type=int, default=None,
                       help="also require this exact match count")
    check.set_defaults(func=cmd_check)

    shot = subs.add_parser("shot", help="take a screenshot (devbridge only)")
    shot.add_argument("name", nargs="?", default=None,
                      help="file name without extension; omit for the timestamped default")
    shot.set_defaults(func=cmd_shot)

    hud = subs.add_parser("hud", help="show or hide the HUD (devbridge only)")
    hud.add_argument("state", choices=["on", "off"])
    hud.set_defaults(func=cmd_hud)

    mouse = subs.add_parser("input", help="hand the mouse back, or take it (devbridge only)")
    mouse.add_argument("state", choices=["on", "off"])
    mouse.set_defaults(func=cmd_input)

    pause = subs.add_parser("pause", help="pause on lost focus, or keep ticking (devbridge only)")
    pause.add_argument("state", choices=["on", "off"])
    pause.set_defaults(func=cmd_pause)

    ping = subs.add_parser("ping", help="handshake and protocol check (devbridge only)")
    ping.set_defaults(func=cmd_ping)

    probe = subs.add_parser("probe", help="report the block at a position")
    for axis in ("x", "y", "z"):
        probe.add_argument(axis, type=int)
    probe.set_defaults(func=cmd_probe)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except (RconError, DevBridgeError) as exc:
        print(f"bridge: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"bridge: could not connect ({exc})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
