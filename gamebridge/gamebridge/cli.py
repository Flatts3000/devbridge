"""`gamebridge` - drive a running Minecraft instance from outside.

    gamebridge --devbridge $PORT ping             handshake: which game, which protocol
    gamebridge --devbridge $PORT --player @s cmd "time set noon"
    gamebridge --devbridge $PORT shot museum      screenshot, written before the reply
    gamebridge --devbridge $PORT log --level ERROR   has anything thrown
    gamebridge launch --instance DIR --port $PORT --world NAME --wait

**Options go before the subcommand.** `--devbridge`, `--player` and `--timeout` belong to the tool
rather than to the verb, so `cmd "..." --player @s` is an "unrecognized arguments" error and
`--player @s cmd "..."` is right.

Two transports, same verbs. RCON reaches a dedicated server and is the default; its settings come
from the server's own `server.properties`, found by walking up from the working directory. The
devbridge mod runs inside the game and is the only way to reach a SINGLEPLAYER world, take a
screenshot, or drive a GUI.

Claim a port per project rather than copying one: there is no default anywhere, and two projects on
one socket is how a verifier reports a clean pass about the wrong world.

Full guide: https://github.com/Flatts3000/devbridge/blob/main/docs/onboarding.md
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path

from .devbridge import PROTOCOL_VERSION, DevBridge, DevBridgeError
from .rcon import Rcon, RconError


def emit(args, payload: dict, human: str | None = None) -> None:
    """One line of output, in whichever shape the caller asked for.

    Every command routes through here so `--json` means the same thing everywhere rather than being
    a per-command afterthought. The human line stays the default because a person running this by
    hand should not have to read JSON to find out a screenshot landed.
    """
    if getattr(args, "json", False):
        print(json.dumps(payload))
    elif human is not None:
        print(human)


def _version() -> str:
    """The installed package's version, or a marker when running from a source tree.

    Read from installed metadata rather than a constant, so it cannot drift from pyproject.toml -
    which is the same failure the protocol check exists to prevent, one layer up.
    """
    try:
        from importlib.metadata import PackageNotFoundError, version
        return version("gamebridge")
    except Exception:
        return "unknown (not installed)"


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
            emit(args, {"up": True}, "server is up")
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
    emit(args, {"output": output}, output or None)
    return 0


def cmd_script(args) -> int:
    lines = Path(args.file).read_text(encoding="utf-8").splitlines()
    with connect(args) as rcon:
        pairs = (rcon.script(lines, args.player)
                 if args.player and hasattr(rcon, "hud") else rcon.script(lines))
    if getattr(args, "json", False):
        emit(args, {"ran": [{"command": c, "output": o} for c, o in pairs]})
    else:
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
        reply = bridge.screenshot(args.name, args.width, args.height)
    emit(args, reply, reply.get("path") or reply.get("message", "screenshot taken"))
    return 0


def cmd_hud(args) -> int:
    """Show or hide the HUD. Its own verb because grab captures the frame already drawn."""
    if args.devbridge is None:
        sys.exit("--devbridge required: the HUD is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        reply = bridge.hud(args.state == "on")
    emit(args, reply, f"hud {args.state}")
    return 0


def cmd_input(args) -> int:
    """Hand the mouse back, or take it again.

    The mod takes it on world load so a stray alt-tab cannot turn the camera between the command
    that framed a shot and the shot itself.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: the mouse is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        reply = bridge.input(args.state == "on")
    emit(args, reply, f"input {args.state}")
    return 0


def cmd_pause(args) -> int:
    """Restore pausing on lost focus, or turn it off again.

    The mod turns it off on world load. That is what lets an unfocused client answer at all: a
    paused singleplayer world stops ticking, so a queued command waits for somebody to click back in.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: pausing is a client thing and RCON talks to a server")
    with connect(args) as bridge:
        reply = bridge.pause(args.state == "on")
    emit(args, reply, f"pause-on-lost-focus {args.state}")
    return 0


def cmd_screen(args) -> int:
    """What GUI is open, or open the inventory / close what is open."""
    if args.devbridge is None:
        sys.exit("--devbridge required: screens are a client thing and RCON talks to a server")
    with connect(args) as bridge:
        reply = bridge.screen({"open": True, "close": False}.get(args.state))
    if getattr(args, "json", False):
        emit(args, reply)
    else:
        for key in ("screen", "title", "width", "height"):
            if key in reply:
                print(f"{key}: {reply[key]}")
    return 0


def cmd_cursor(args) -> int:
    """Move the pointer. Coordinates are GUI-scaled: see `screen` for the space they live in."""
    if args.devbridge is None:
        sys.exit("--devbridge required: the pointer is a client thing")
    with connect(args) as bridge:
        reply = bridge.cursor(args.x, args.y)
    emit(args, reply,
         f"cursor {reply['x']},{reply['y']} (raw {reply['rawX']:.0f},{reply['rawY']:.0f})")
    return 0


def cmd_click(args) -> int:
    """Press and release at a point. Non-zero if nothing took the click."""
    if args.devbridge is None:
        sys.exit("--devbridge required: clicking is a client thing")
    with connect(args) as bridge:
        reply = bridge.click(args.x, args.y, args.button)
    # ALWAYS 0 when the request itself succeeded. Nothing this verb learns synchronously is a
    # verdict: the booleans over-report (a creative inventory answers clicks on empty background)
    # and under-report (an FTB Quests tab switches chapters returning false), and even a screen
    # change lands late - clicking Respawn reported no change with the death screen still open, and
    # the screen was gone a moment later. Exiting non-zero on that would fail scripts whose click
    # worked. Check the consequence afterwards with `screen` or `shot`.
    detail = []
    if reply.get("onPress"):
        detail.append("press")
    if reply.get("onRelease"):
        detail.append("release")
    taken = ", ".join(detail) if detail else "nothing reported"
    changed = f", screen -> {reply['screen'].split('.')[-1]}" if reply.get("changedScreen") else ""
    emit(args, reply, f"click sent ({taken}{changed}); verify with screen or shot")
    return 0


def cmd_log(args) -> int:
    """What the game has logged, so a silent failure stops being silent.

    Reads the file rather than asking the game, which means it also works on a game that has already
    died. The game directory comes from `ping` unless you name one.
    """
    from .logs import LogError, read_since

    game_dir = args.game_dir
    if game_dir is None:
        if args.devbridge is None:
            sys.exit("--game-dir required, or --devbridge so ping can report it")
        with connect(args) as bridge:
            game_dir = bridge.ping().get("gameDir")
        if game_dir is None:
            sys.exit("that side reported no gameDir, so name one with --game-dir")

    try:
        result = read_since(game_dir, since=args.since, level=args.level)
    except LogError as exc:
        sys.exit(f"log: {exc}")

    if getattr(args, "json", False):
        # No stderr summary here: the marker and the counts are in the payload, and printing them
        # twice in two formats is how a caller ends up parsing the wrong one.
        emit(args, result)
    else:
        for line in result["lines"]:
            print(line)
        # The marker goes to stderr so `gamebridge log > errors.txt` keeps the log clean and the
        # cursor still reaches a human.
        print(f"log: marker {result['marker']}, {result['errors']} error(s), "
              f"{result['warnings']} warning(s)", file=sys.stderr)
    return 1 if (args.fail_on_error and result["errors"]) else 0


def cmd_stop(args) -> int:
    """Close the world and quit the game.

    devbridge only, and not the same thing as running `stop` as a command: that is a dedicated
    server's console command and does not exist in singleplayer, which is the case this whole tool
    is for. Without this an unattended loop can start a game and never close one.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: for a dedicated server, `cmd stop` is the console command")
    with connect(args) as bridge:
        reply = bridge.stop()
    emit(args, reply, "stopping")
    return 0


def cmd_ping(args) -> int:
    """Handshake, and the protocol version check.

    devbridge only. RCON has no equivalent: `wait` is the "is it up" primitive there, and it works
    by running a command rather than by asking anything about the other end.
    """
    if args.devbridge is None:
        sys.exit("--devbridge required: ping is a devbridge verb; use `wait` for RCON")
    with connect(args) as bridge:
        reply = bridge.ping(expect_instance=args.expect_instance)
    if getattr(args, "json", False):
        emit(args, reply)
    else:
        for key in ("protocol", "side", "mcVersion", "hasClient", "worldName", "mods",
                    "gameDir", "pauseOnLostFocus", "inputLocked"):
            if key in reply:
                print(f"{key}: {reply[key]}")
    return 0


def cmd_launch(args) -> int:
    """Start an instance with devbridge switched on, and optionally wait for it to answer.

    The last manual step in the loop. Everything after this already worked; starting the game was
    the part that needed a person.
    """
    from .launch import LaunchError, build_command, explain_exit, launch, reset_world, verify

    instance = Path(args.instance)

    if args.template and not args.world:
        sys.exit("launch: --template needs --world, to know which world it is replacing")

    try:
        command = build_command(
            instance=instance,
            port=args.port,
            world=args.world,
            username=args.username,
            width=args.width,
            height=args.height,
            install_root=Path(args.install_root) if args.install_root else None,
            memory_mb=args.memory,
            java=Path(args.java) if args.java else None,
            properties=args.property,
        )
    except LaunchError as exc:
        sys.exit(f"launch: {exc}")

    if args.dry_run:
        # Printed before the checks, and printed even when they fail. --dry-run exists to show why a
        # launch would not work, and withholding the command line in exactly the case somebody is
        # debugging is the wrong way round.
        #
        # Quoted the way the platform would, so a world name with a space in it reads as one
        # argument. The real launch passes a list and never goes near a shell.
        rendered = (subprocess.list2cmdline(command) if os.name == "nt"
                    else shlex.join(command))
        problems = verify(command)
        emit(args, {"command": command, "rendered": rendered, "problems": problems}, rendered)
        for problem in problems:
            print(f"launch: {problem}", file=sys.stderr)
        return 0

    problems = verify(command)
    if problems:
        for problem in problems:
            print(f"launch: {problem}", file=sys.stderr)
        sys.exit("launch: refusing to start with a classpath that does not resolve")

    # The world is replaced only once everything else has passed. Doing it earlier deleted a world
    # and then failed on the instance path, which is a bad trade for a caller who mistyped a flag.
    if args.template:
        try:
            reset_world(instance, args.world, Path(args.template))
        except LaunchError as exc:
            sys.exit(f"launch: {exc}")
        print(f"launch: reset world {args.world!r} from {args.template}", file=sys.stderr)

    started = time.time()
    process = launch(command, instance)
    started_info = {"pid": process.pid, "port": args.port}
    if not args.wait:
        emit(args, started_info, f"launched pid {process.pid} on port {args.port}")
        return 0
    # Under --json the launch line is held back and folded into the single object emitted below.
    # Two documents on one stdout is not JSON, and a caller doing json.load() gets a parse error
    # from the second one rather than the answer it asked for.
    if not getattr(args, "json", False):
        print(f"launched pid {process.pid} on port {args.port}")

    # Poll the socket rather than the process: a live pid means java started, not that the mod is
    # listening, and the gap between them is most of a minute.
    deadline = time.monotonic() + args.for_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            emit(args, {**started_info, "up": False, "exitCode": process.returncode})
            print(f"launch: the game exited with code {process.returncode} before answering",
                  file=sys.stderr)
            # The tool already knows why. Making the caller go and find the crash report is the
            # difference between a diagnosis and a symptom.
            explanation = explain_exit(instance, started)
            for line in explanation:
                print(f"launch: {line}", file=sys.stderr)
            if not explanation:
                # A JVM that never reached Minecraft writes neither a crash report nor a log, so
                # saying "nothing on disk" beats one bare exit code that looks like the whole answer.
                print("launch: no crash report or fresh log, so the game died before Minecraft "
                      "started. The java process's own output says why.", file=sys.stderr)
            return 1
        try:
            with DevBridge(port=args.port, timeout=5.0) as bridge:
                reply = bridge.ping()
            emit(args, {**started_info, "up": True, **reply},
                 f"up: {reply.get('side')}, protocol {reply.get('protocol')}")
            return 0
        except (OSError, DevBridgeError):
            time.sleep(3.0)
    emit(args, {**started_info, "up": False, "timedOut": True})
    print(f"launch: no answer on {args.port} within {args.for_seconds:g}s", file=sys.stderr)
    return 1


def cmd_probe(args) -> int:
    """What is at a position, as far as a command can tell you.

    **Commands cannot name an arbitrary block.** `data get block` reads block entity data and refuses
    anything else - "The target block is not a block entity" - which is most blocks. Stone, dirt, ore,
    logs. This verb shipped assuming otherwise, because it was written against a showcase scene of
    chests and paintings and never pointed at terrain.

    So there are two questions here and they take different routes. "What data does this block entity
    hold" is `data get block`. "Is this block X" is `execute if block`, which tests a guess rather
    than answering. Naming an unknown block needs a verb in the mod, which is unresolved (#13).
    """
    x, y, z = args.x, args.y, args.z

    if args.is_block:
        with connect(args) as rcon:
            output = rcon.command(f"execute if block {x} {y} {z} {args.is_block}")
        matches = output.startswith("Test passed")
        emit(args, {"x": x, "y": y, "z": z, "is": args.is_block, "matches": matches,
                    "output": output},
             f"{'yes' if matches else 'no ':3} {x} {y} {z} is {args.is_block}")
        return 0 if matches else 1

    with connect(args) as rcon:
        output = rcon.command(f"data get block {x} {y} {z}")

    # Matching on the success phrase rather than the failure one: there are many ways to fail and
    # exactly one shape of success. Still a string match against a localised message, which is the
    # honest cost of there being no command that answers this properly.
    has_data = "block data" in output
    payload = {"x": x, "y": y, "z": z, "blockEntity": has_data, "output": output}
    if has_data:
        emit(args, payload, output)
        return 0
    hint = (f"probe: no command can name an arbitrary block. To test a guess: "
            f"probe {x} {y} {z} --is minecraft:stone")
    emit(args, payload, f"{output}{os.linesep}{hint}")
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
    emit(args, {"condition": args.condition, "passed": passed, "count": count,
                "expected": args.count, "output": output},
         f"{label} {args.condition}{expected}")
    return 0 if passed else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gamebridge", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    # A tool that cannot say what it is cannot be told apart from an older copy, which is the same
    # class of problem the protocol version exists for. The protocol number is here too because it,
    # not the package version, is what has to match the mod.
    parser.add_argument("--version", action="version",
                        version=f"gamebridge {_version()} (wire protocol {PROTOCOL_VERSION})")
    parser.add_argument("--json", action="store_true",
                        help="print the reply object instead of a sentence. Sentences get reworded; "
                             "this is the surface to script against")
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
    shot.add_argument("--width", type=int, default=None)
    shot.add_argument("--height", type=int, default=None,
                      help="capture at exactly this size, resizing the window for the moment of "
                           "the shot and putting it back. Both or neither")
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
    ping.add_argument("--expect-instance", default=None, metavar="DIR",
                      help="fail unless the game is running out of this directory. Two clients of "
                           "one Minecraft version are otherwise indistinguishable")
    ping.set_defaults(func=cmd_ping)

    scr = subs.add_parser("screen", help="what GUI is open (devbridge only)")
    scr.add_argument("state", nargs="?", choices=["open", "close"], default=None,
                     help="open the inventory, or close whatever is open; omit to just report")
    scr.set_defaults(func=cmd_screen)

    cur = subs.add_parser("cursor", help="move the pointer, in GUI-scaled coordinates")
    cur.add_argument("x", type=float)
    cur.add_argument("y", type=float)
    cur.set_defaults(func=cmd_cursor)

    clk = subs.add_parser("click", help="press and release at a point")
    clk.add_argument("x", type=float)
    clk.add_argument("y", type=float)
    clk.add_argument("--button", type=int, default=0, help="0 left, 1 right, 2 middle")
    clk.set_defaults(func=cmd_click)

    log = subs.add_parser("log", help="what the game logged, since a marker")
    log.add_argument("--since", type=int, default=0, metavar="MARKER",
                     help="byte offset from a previous run of this command")
    log.add_argument("--level", default="WARN",
                     help="minimum level to print (default: WARN)")
    log.add_argument("--game-dir", default=None,
                     help="the instance directory; taken from ping when omitted")
    log.add_argument("--fail-on-error", action="store_true",
                     help="exit non-zero if anything logged at ERROR or above")
    log.set_defaults(func=cmd_log)

    halt = subs.add_parser("stop", help="close the world and quit the game (devbridge only)")
    halt.set_defaults(func=cmd_stop)

    start = subs.add_parser("launch", help="start a CurseForge instance with devbridge enabled")
    start.add_argument("--instance", required=True, metavar="DIR",
                       help="the instance directory, the one holding minecraftinstance.json")
    start.add_argument("--port", type=int, required=True,
                       help="devbridge port. Claim one per project; sharing a port is how a "
                            "verifier reports a clean pass about the wrong game")
    start.add_argument("--world", default=None, metavar="NAME",
                       help="boot straight into this singleplayer world")
    start.add_argument("--username", default="Dev",
                       help="offline player name (default: Dev). The UUID derives from it, so "
                            "keeping it stable keeps the same player across launches")
    start.add_argument("--width", type=int, default=None)
    start.add_argument("--height", type=int, default=None,
                       help="force a window size, for shots that need consistent framing")
    start.add_argument("--memory", type=int, default=None, metavar="MB",
                       help="override the instance's allocated memory")
    start.add_argument("--java", default=None, help="override the bundled JRE")
    start.add_argument("-D", "--property", action="append", default=[], metavar="K=V",
                       help="extra JVM system property, repeatable. A pack has no Gradle run "
                            "block, so this is how it reaches devbridge.lockInput and friends")
    start.add_argument("--install-root", default=None,
                       help="the CurseForge Install directory, if it is not beside the instance")
    start.add_argument("--template", default=None, metavar="DIR",
                       help="replace --world with a copy of this world directory before launching, "
                            "so every run starts from the same state. REPLACES the existing world")
    start.add_argument("--dry-run", action="store_true",
                       help="print the command line and exit, without starting anything")
    start.add_argument("--wait", action="store_true",
                       help="block until devbridge answers on the port")
    start.add_argument("--for", dest="for_seconds", type=float, default=300.0,
                       help="how long --wait waits (default: 300s)")
    start.set_defaults(func=cmd_launch)

    probe = subs.add_parser("probe",
                            help="read block entity data at a position, or test what block it is")
    for axis in ("x", "y", "z"):
        probe.add_argument(axis, type=int)
    probe.add_argument("--is", dest="is_block", default=None, metavar="BLOCK",
                       help="test whether the block is this, e.g. minecraft:stone. Non-zero when "
                            "it is not. Without this, only block entity data can be read")
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
