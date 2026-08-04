# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**What this is:** a development-only NeoForge mod (MC 26.1.2) that lets tooling outside the game drive
a running Minecraft instance over a loopback socket. Run commands, take screenshots, read the answers.
Mod id / package: `devbridge` / `dev.mcbridge.devbridge`.

**It exists because RCON cannot reach a singleplayer world and cannot take a screenshot.** Both are
structural: an integrated server listens on nothing, and a dedicated server has no framebuffer. Where
RCON does work it stays the better answer.

## Build

```bash
JAVA_HOME="/c/Program Files/Java/jdk-25" ./gradlew build
```

Java 25 toolchain, NeoForge moddev plugin. Output is `build/libs/devbridge-26.1.2-0.1.0.jar`
(`archivesName` is `<mod_id>-<minecraft_version>`, version appended). There are no tests and no lint
task, so `build` means compile plus jar.

There are no run configs. This mod is meant to be dropped into *another* mod's dev run, so a client
here would only ever launch an empty world.

### Verifying a change

The only real test is against a live instance. Build, drop the jar in the target mod's `run/mods/`,
add `systemProperty 'devbridge.port', '25580'` to that project's client run, launch, then speak
newline-delimited JSON at `localhost:25580`. `{"verb":"ping"}` confirms the socket, the side, and
whether a client is present. The reference client is `gamebridge`, a Python CLI living outside this
repo.

Version, mod metadata, and MC/NeoForge coordinates all live in `gradle.properties` and are expanded
into `neoforge.mods.toml` at build time. Change them there, never in the toml.

### CI and releases

`.github/workflows/build.yml` runs the invariant check, then `./gradlew build`, on every push to
`main` and every PR. `.github/scripts/check_invariants.sh` is the security boundary made executable:
it fails if a `ServerSocket` is constructed without `InetAddress.getLoopbackAddress()` on the same
line, if a wildcard address appears anywhere, if `DevBridge` stops reading `devbridge.port`, or if
`build.gradle` gains a publishing block. Run it locally with `sh .github/scripts/check_invariants.sh`
before pushing. It is strict by design and false-positives on reformatting; keep the formatting it
wants rather than loosening the check.

Releases are tag-driven: push a `v*` tag and `release.yml` builds and attaches the jar. Nothing is
published to any Maven repository, deliberately.

## The rules that are not negotiable

- **Loopback only.** `new ServerSocket(port, 1, InetAddress.getLoopbackAddress())`. Never
  `new ServerSocket(port)`, which binds every interface and publishes an arbitrary-command endpoint to
  the network. Not exposed as an option.
- **Off unless asked.** No `-Ddevbridge.port` means no socket and no handlers.
- **Never ship it.** Separate jar, not a dependency of anything released.

## Architecture

Five classes, and the layering between the last three is a load-bearing safety property, not taste.

- **`DevBridge`** - the `@Mod` entry point. Reads `devbridge.port`; if unset or unparseable it logs
  and registers nothing at all. Otherwise it subscribes to `ServerStartedEvent` / `ServerStoppingEvent`
  rather than opening the socket in the constructor, because `cmd` needs a `MinecraftServer` that does
  not exist yet at construction. Singleplayer opens and closes worlds repeatedly, so the started
  handler keeps the first socket and ignores later events.
- **`BridgeServer`** - a daemon `Thread` owning the `ServerSocket`. One connection at a time, read a
  line, parse JSON, hand to `Handlers.dispatch`, write one line back. Any exception becomes an error
  reply rather than a dropped connection: a tool that gets an error line can say what went wrong, one
  whose socket died can only say "broken".
- **`Handlers`** - the verb switch (`ping`, `cmd`, `stop`, plus the two that delegate to the client)
  and the `ok()` / `error()` reply builders. Owns the command-output capture and `findPlayer`.
  Unknown verbs fail loudly.
- **`ClientHandlers`** - the dist guard. Answers `available()` and refuses `screenshot` and `hud`
  with a message when there is no client.
- **`ScreenshotTaker`** - the only class that touches `Minecraft`. Holds both client verbs, the
  capture and the HUD toggle. Reached solely through `ClientHandlers` after the guard passes.

Request flow: socket thread parses, `Handlers` queues onto the server thread (`server.execute`) or the
render thread (`Minecraft.execute`), socket thread blocks on a `CompletableFuture` with a 30s timeout,
then writes the reply.

Protocol shape and verb table are in `README.md`; `SPEC.md` carries the design rationale, section 8
is what is deliberately out of scope, section 9 is what has been resolved and why, and section 10 is
the remaining open work (forcing a screenshot resolution).

## Things that cost time to rediscover

- **`withSuppressedOutput()` silences the command output the whole tool exists to collect.** The
  source stack from `createCommandSourceStack()` already carries `OWNER` permission, so neither that
  nor `withPermission` is needed - only `withSource`.
- **A class a dedicated server might verify must not name `Minecraft`.** The JVM resolves method types
  at verification, so one reference crashes a server. Client code lives in `ScreenshotTaker`, loaded
  only after `ClientHandlers` has checked. That check is `!server.isDedicatedServer()` and needs no FML
  dist API, because an integrated server exists precisely because a client started it.
- **Threading.** Commands go through `server.execute`, screenshots through `Minecraft.execute`. The
  screenshot reply is sent from `Screenshot.grab`'s callback, not when the request is accepted, or a
  caller gets a path to a file that does not exist yet.
- **`InetAddress.getLoopbackAddress()` may return `::1`**, so a client dialling `127.0.0.1` gets
  "connection refused" from a healthy socket. Clients should connect to `localhost`.
- **`--args` on a moddev Gradle run is not program arguments** - it is taken as a main class and the
  launch dies before Minecraft starts. Use `programArguments`.
- **The screenshot reply only carries `path` when the request named the file.** Unnamed requests get
  `message` (the chat component's text) and `dir` only, because the timestamped default name is chosen
  inside `Screenshot.grab` and never handed back. README and SPEC show the named case; do not assume
  `path` is always present.
- **Commands run as the console unless the request names a `player`.** The console stack has no
  entity and sits at world spawn, so `@s` matches nothing and `~` is spawn-relative: a function
  ending in `tp @s ...` places its scene correctly and silently fails to move the camera. With a
  `player`, `findPlayer` resolves the name (or takes the only player online for `@s` / `@p` / blank)
  and the command runs on that player's own source stack.
- **`hud` is a separate verb, not a flag on `screenshot`.** `Screenshot.grab` captures the
  framebuffer as it already is, so hiding the HUD and grabbing in the same call still catches the
  frame drawn with it up. Anything that "simplifies" this by folding the toggle into the capture is
  reintroducing the bug.

## Conventions

- **No em-dashes or en-dashes, no emoji** in any authored text. ASCII punctuation only.
- Conventional commits (`feat:`, `fix:`, `docs:`).
- Comments explain why, not what. The existing class Javadoc is the standard: every non-obvious
  choice states the failure it avoids. Match that density rather than stripping it.
