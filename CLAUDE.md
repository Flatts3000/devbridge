# CLAUDE.md

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

There are no run configs. This mod is meant to be dropped into *another* mod's dev run, so a client
here would only ever launch an empty world.

## The rules that are not negotiable

- **Loopback only.** `new ServerSocket(port, 1, InetAddress.getLoopbackAddress())`. Never
  `new ServerSocket(port)`, which binds every interface and publishes an arbitrary-command endpoint to
  the network. Not exposed as an option.
- **Off unless asked.** No `-Ddevbridge.port` means no socket and no handlers.
- **Never ship it.** Separate jar, not a dependency of anything released.

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

## Conventions

- **No em-dashes or en-dashes, no emoji** in any authored text. ASCII punctuation only.
- Conventional commits (`feat:`, `fix:`, `docs:`).
