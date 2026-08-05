"""Start a CurseForge instance from outside, with devbridge switched on.

The last manual step in an automated verification loop. Everything after launch already works:
devbridge runs commands and takes screenshots, and `stop` closes the game. Starting it was the one
part that needed a person alt-tabbing to a launcher, which is enough to stop any of it running
unattended.

**The mod cannot do this and never will.** devbridge opens no socket without `-Ddevbridge.port`, so
anything inside the game is on the wrong side of its own switch. Loading a world is likewise a launch
argument (`--quickPlaySingleplayer`), not a mod feature. Both belong out here.

**Nothing is downloaded and nothing is written to the instance.** The CurseForge app already keeps a
vanilla-shaped install - `versions/`, `libraries/`, `assets/`, `natives/` and bundled JREs under
`Install/` - so this reads its manifests and builds the same command line the app would. Editing
`minecraftinstance.json` is a known way to break an instance; this only ever reads it.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import subprocess
import uuid
from pathlib import Path


class LaunchError(RuntimeError):
    pass


# ------------------------------------------------------------------ locating things

def find_install_root(instance: Path) -> Path:
    """The `Install` directory that owns an instance.

    An instance lives at `<root>/Instances/<name>`, and the shared install sits beside it at
    `<root>/Install`. Walking up beats hardcoding a path under the user profile, which is wrong the
    moment somebody moved their CurseForge folder to another drive.
    """
    for parent in instance.resolve().parents:
        candidate = parent / "Install"
        if (candidate / "versions").is_dir():
            return candidate
    raise LaunchError(
        f"no CurseForge Install directory above {instance}. Pass --install-root if the layout is "
        f"not <root>/Instances/<name> beside <root>/Install."
    )


def read_instance(instance: Path) -> dict:
    path = instance / "minecraftinstance.json"
    if not path.is_file():
        raise LaunchError(f"{path} does not exist, so this is not a CurseForge instance directory")
    # utf-8-sig: the app writes a BOM, and plain utf-8 chokes on it with a confusing error.
    return json.loads(path.read_text(encoding="utf-8-sig"))


# ------------------------------------------------------------------ version manifests

def load_version(install_root: Path, version_id: str) -> dict:
    """One version manifest, merged with everything it inherits from.

    A loader manifest carries `inheritsFrom` and only its own additions: NeoForge lists 26 libraries
    where vanilla lists 107, and takes `assetIndex` and `javaVersion` entirely from the parent. The
    child wins on scalars, and library and argument lists are concatenated child-first so the
    loader's entries take classpath precedence.
    """
    seen: list[str] = []
    merged: dict = {}
    current: str | None = version_id

    while current:
        if current in seen:
            raise LaunchError(f"circular inheritsFrom: {' -> '.join(seen + [current])}")
        seen.append(current)

        path = install_root / "versions" / current / f"{current}.json"
        if not path.is_file():
            raise LaunchError(f"missing version manifest {path}")
        data = json.loads(path.read_text(encoding="utf-8"))

        if not merged:
            merged = dict(data)
        else:
            for key, value in data.items():
                if key == "libraries":
                    merged["libraries"] = merged.get("libraries", []) + value
                elif key == "arguments":
                    for section in ("game", "jvm"):
                        merged.setdefault("arguments", {}).setdefault(section, [])
                        merged["arguments"][section] = (
                            value.get(section, []) + merged["arguments"][section]
                        )
                elif merged.get(key) is None:
                    # Present-but-null counts as absent. The NeoForge manifest carries
                    # "assetIndex": null, and treating that as the child's answer loses vanilla's
                    # real one, which surfaces much later as a game with no assets.
                    merged[key] = value

        current = data.get("inheritsFrom")

    merged["_chain"] = seen
    return merged


# ------------------------------------------------------------------ rules

def _os_name() -> str:
    return {"Windows": "windows", "Darwin": "osx", "Linux": "linux"}.get(platform.system(), "linux")


def rules_allow(rules: list[dict] | None, features: dict[str, bool]) -> bool:
    """Vanilla's allow/disallow rule evaluation.

    57 of the 107 libraries in 26.1.2 carry rules, almost all of them naming another operating
    system. Ignoring rules would put macOS-only natives on a Windows classpath, so this is not
    optional decoration.
    """
    if not rules:
        return True
    allowed = False
    for rule in rules:
        applies = True
        if "os" in rule:
            spec = rule["os"]
            if "name" in spec and spec["name"] != _os_name():
                applies = False
            if "arch" in spec and spec["arch"] not in platform.machine().lower():
                applies = False
        if "features" in rule:
            for feature, wanted in rule["features"].items():
                if features.get(feature, False) != wanted:
                    applies = False
        if applies:
            allowed = rule.get("action") == "allow"
    return allowed


# ------------------------------------------------------------------ classpath

def classpath(version: dict, install_root: Path, features: dict[str, bool]) -> list[str]:
    entries: list[str] = []
    for library in version.get("libraries", []):
        if not rules_allow(library.get("rules"), features):
            continue
        artifact = library.get("downloads", {}).get("artifact")
        relative = artifact["path"] if artifact else _maven_path(library["name"])
        path = install_root / "libraries" / relative
        if str(path) not in entries:
            entries.append(str(path))

    # The game jar itself. A loader manifest names the jar it wants via `jar`; without one the
    # version supplies its own. `or` rather than a get default, because these manifests spell
    # "not set" as an explicit null more often than by leaving the key out.
    jar_id = version.get("jar") or version["id"]
    entries.append(str(install_root / "versions" / jar_id / f"{jar_id}.jar"))
    return entries


def _maven_path(coordinate: str) -> str:
    """Maven coordinates to a repository path, for libraries with no explicit download entry."""
    parts = coordinate.split(":")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return str(Path(*group.split("."), artifact, version, f"{artifact}-{version}{classifier}.jar"))


# ------------------------------------------------------------------ offline identity

def offline_uuid(username: str) -> str:
    """The same offline UUID the vanilla server derives, so the world sees a stable player.

    Without this every launch is a different player: a new inventory, a new spawn, and a `player`
    field on `cmd` that matches nobody it matched last time.
    """
    # md5 because that is the algorithm the answer has to match, not because anything here is a
    # security decision. usedforsecurity=False says so, and keeps this working on a FIPS-mode
    # interpreter where a plain md5() refuses to run at all.
    raw = hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8"), usedforsecurity=False)
    digest = bytearray(raw.digest())
    digest[6] = (digest[6] & 0x0F) | 0x30   # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80   # RFC 4122 variant
    return str(uuid.UUID(bytes=bytes(digest)))


# ------------------------------------------------------------------ the command line

def build_command(
    instance: Path,
    port: int,
    world: str | None = None,
    username: str = "Dev",
    width: int | None = None,
    height: int | None = None,
    install_root: Path | None = None,
    memory_mb: int | None = None,
    java: Path | None = None,
) -> list[str]:
    instance = Path(instance)
    root = Path(install_root) if install_root else find_install_root(instance)
    meta = read_instance(instance)

    loader = (meta.get("baseModLoader") or {}).get("name")
    version_id = loader or meta.get("gameVersion")
    if not version_id:
        raise LaunchError("minecraftinstance.json names neither a mod loader nor a game version")
    version = load_version(root, version_id)

    features = {"is_demo_user": False, "has_custom_resolution": bool(width and height),
                "has_quick_plays_support": bool(world), "is_quick_play_singleplayer": bool(world),
                "is_quick_play_multiplayer": False, "is_quick_play_realms": False}

    java_binary = Path(java) if java else _bundled_java(root, version)
    memory = memory_mb or meta.get("allocatedMemory") or 4096
    natives = root / "natives" / version["id"]

    context = {
        "auth_player_name": username,
        "auth_uuid": offline_uuid(username).replace("-", ""),
        "auth_access_token": "0",
        "auth_session": "0",
        "user_type": "legacy",
        "user_properties": "{}",
        "version_name": version["id"],
        "version_type": version.get("type") or "release",
        "game_directory": str(instance),
        "assets_root": str(root / "assets"),
        "game_assets": str(root / "assets"),
        "assets_index_name": version.get("assets") or (version.get("assetIndex") or {}).get("id", ""),
        "natives_directory": str(natives),
        "launcher_name": "gamebridge",
        "launcher_version": "0.1.0",
        "classpath": os.pathsep.join(classpath(version, root, features)),
        "classpath_separator": os.pathsep,
        "library_directory": str(root / "libraries"),
        "resolution_width": str(width or ""),
        "resolution_height": str(height or ""),
        "quickPlayPath": str(root.parent / "Quickplay" / f"{meta.get('guid', 'gamebridge')}.json"),
        "quickPlaySingleplayer": world or "",
    }

    command = [str(java_binary), f"-Xmx{memory}m"]
    command += _arguments(version, "jvm", context, features)

    # The whole point. Set before the main class, because it is a JVM property and not a game
    # argument: devbridge reads it with System.getProperty at mod construction.
    command.append(f"-Ddevbridge.port={port}")

    command.append(version["mainClass"])
    command += _arguments(version, "game", context, features)
    return command


def _arguments(version: dict, section: str, context: dict, features: dict) -> list[str]:
    """One argument section, with rules applied and `${placeholders}` filled in.

    Entries are either plain strings or objects carrying their own rules, which is how vanilla gates
    `--demo`, `--width`/`--height` and the three quick-play flags. Those come from here and nowhere
    else: appending them by hand as well got `--quickPlaySingleplayer` passed twice, and jopt-simple
    rejects a repeated single-value option with "Found multiple arguments for option", which reads
    like a malformed world name rather than a duplicated flag. The manifest already knows the flag
    names; the features dict decides whether they appear.
    """
    out: list[str] = []
    raw = version.get("arguments", {}).get(section)
    if raw is None:
        # Pre-1.13 manifests use a flat `minecraftArguments` string. NeoForge still emits one.
        if section == "game" and version.get("minecraftArguments"):
            raw = version["minecraftArguments"].split()
        else:
            raw = []

    for entry in raw:
        if isinstance(entry, str):
            out.append(_substitute(entry, context))
        elif isinstance(entry, dict) and rules_allow(entry.get("rules"), features):
            value = entry.get("value", [])
            for item in [value] if isinstance(value, str) else value:
                out.append(_substitute(item, context))
    return out


def _substitute(text: str, context: dict) -> str:
    for key, value in context.items():
        text = text.replace("${" + key + "}", str(value))
    return text


def _bundled_java(install_root: Path, version: dict) -> Path:
    """The JRE the manifest asks for, from the app's own bundle.

    26.1.2 wants `java-runtime-epsilon` (Java 25). Falling back to whatever `java` is on PATH is how
    a launch dies with an unsupported class version, so this prefers the exact runtime and says what
    it wanted when it cannot find it.
    """
    component = version.get("javaVersion", {}).get("component")
    if component:
        for name in ("javaw.exe", "java.exe", "java"):
            candidate = install_root / "java" / component / "bin" / name
            if candidate.is_file():
                return candidate
        raise LaunchError(
            f"the manifest asks for the {component} runtime and it is not under "
            f"{install_root / 'java'}. Launch the instance once from the app to fetch it, or pass "
            f"--java."
        )
    return Path("java")


# ------------------------------------------------------------------ running it

def verify(command: list[str]) -> list[str]:
    """Every classpath entry and the java binary actually exist.

    A missing jar surfaces as a NoClassDefFoundError several seconds into a launch, in a window that
    may already have closed. Checking first turns that into one line naming the file.
    """
    problems: list[str] = []
    java = command[0]
    # A bare name is resolved on PATH, not treated as a relative file. `_bundled_java` falls back to
    # plain "java" when a manifest names no runtime, and --java accepts a command name, so testing
    # is_file() on those reports "not found" for a java that works perfectly.
    found = shutil.which(java) if os.path.basename(java) == java else (
        java if Path(java).is_file() else None)
    if not found:
        problems.append(f"java binary not found: {java}")
    for index, argument in enumerate(command):
        if argument == "-cp" and index + 1 < len(command):
            for entry in command[index + 1].split(os.pathsep):
                if entry and not Path(entry).is_file():
                    problems.append(f"missing classpath entry: {entry}")
    return problems


def launch(command: list[str], instance: Path) -> subprocess.Popen:
    natives = None
    for argument in command:
        if argument.startswith("-Djava.library.path="):
            natives = Path(argument.split("=", 1)[1])
    if natives:
        natives.mkdir(parents=True, exist_ok=True)
    # cwd is the instance: the game resolves saves, config and mods relative to it, and --gameDir
    # alone is not enough for every mod that reads a relative path.
    return subprocess.Popen(command, cwd=str(instance))
