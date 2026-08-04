# devbridge

[![build](https://github.com/Flatts3000/devbridge/actions/workflows/build.yml/badge.svg)](https://github.com/Flatts3000/devbridge/actions/workflows/build.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft 26.1.2](https://img.shields.io/badge/minecraft-26.1.2-brightgreen.svg)](gradle.properties)
[![dev only: do not ship](https://img.shields.io/badge/dev%20only-do%20not%20ship-red.svg)](SECURITY.md)

A **development-only** NeoForge mod that lets tooling outside the game drive a running Minecraft
instance: run commands, take screenshots, and read the answers back.

```
$ gamebridge --devbridge 25580 cmd "function mymod:showcase/museum"
Running function mymod:showcase/museum
$ gamebridge --devbridge 25580 shot museum
.\screenshots\museum.png
```

## Why

A dedicated server can already be driven over RCON, and where RCON works it should stay the answer.
It cannot do two things, and both are structural rather than missing features:

- **Singleplayer.** A singleplayer world's integrated server does not listen on a socket at all, so
  there is nothing to connect to. Reaching it needs code running inside the game.
- **Screenshots.** RCON talks to a server, and a dedicated server has no client, no framebuffer and
  nothing to capture.

The second is the one worth building for. It turns "change a number, rebuild, reshoot, look" from a
round trip through a human into one command.

## Security, read this part

**This is remote code execution by design.** It executes arbitrary commands, and commands can rewrite
the world. Three rules make that acceptable, and none of them are optional:

1. **It binds `127.0.0.1` only, and that is not configurable.** There is no legitimate reason for
   another machine to drive your dev client. An option is a thing somebody sets wrong once.
2. **It is off unless asked.** Without `-Ddevbridge.port=<n>` the mod opens no socket and registers
   no handler. A jar sitting in a mods folder does nothing.
3. **Never ship it.** It is a separate jar precisely so it cannot end up in a release by accident.

There is no authentication, and that is a consequence of the above rather than an oversight: a token
on a loopback-only socket defends against nothing loopback does not already exclude.

## Use

Drop the jar in your dev run's `run/mods/`, then in your mod's `build.gradle`:

```groovy
runs {
    client {
        client()
        systemProperty 'devbridge.port', '25580'
        // Optional: boot straight into a world instead of stopping at the title screen.
        // NOTE: `--args` on the Gradle task does NOT do this - moddev takes it as a main class.
        programArguments.addAll '--quickPlaySingleplayer', 'My World'
    }
}
```

Then talk to it with any client that speaks newline-delimited JSON over TCP. The reference one is
`gamebridge`, a small Python CLI.

## Protocol

One request per line, one reply per line.

| Verb | Runs on | Request fields | Reply |
|---|---|---|---|
| `ping` | either | none | `{"ok":true,"side":"integrated","mcVersion":"26.1.2","hasClient":true}` |
| `cmd` | server thread | `command`, optional `player` | `{"ok":true,"output":"..."}` - whatever the command printed |
| `screenshot` | client render thread | optional `name` | `{"ok":true,"message":"...","dir":"...","path":"..."}` once the file is on disk |
| `hud` | client render thread | optional `show` (default `true`) | `{"ok":true,"hudVisible":false}` |
| `stop` | either | none | Closes the world and quits |

Failures are `{"ok":false,"error":"..."}`. An unknown verb fails rather than being ignored: silently
accepting a typo is the worst outcome for a tool whose whole job is reporting what happened.

**`cmd` runs as the server console unless you name a `player`.** The console's source stack has no
entity and sits at world spawn, so `@s` matches nothing and `~` is spawn-relative: a function ending
in `tp @s ...` will place its scene correctly and silently fail to move the camera. Pass `player` to
run as somebody instead. A name matches that player; `"@s"`, `"@p"` or an empty string take the only
player online, which is what a singleplayer dev run always has.

```json
{"verb": "cmd", "command": "function mymod:showcase/museum", "player": "@s"}
```

**`screenshot` only returns `path` when you name the file.** Without a `name` the file gets
Minecraft's timestamped default, which is chosen inside the capture and never handed back, so the
reply carries `message` and `dir` and you are on your own to find it. Name your shots.

**`hud` is a separate verb, not a flag on `screenshot`.** The capture takes the framebuffer as it
already is, so hiding the HUD and grabbing in one call would still catch the frame that was drawn
with the hotbar up. Hide it, shoot, show it again - which also lets a run of shots share one toggle.

## The one that will bite

**`InetAddress.getLoopbackAddress()` can be `::1`.** If your client dials the IPv4 literal and gets
"connection refused" from a socket the log says is listening, that is why. Connect to `localhost` and
let the resolver try both families.

The other two that used to live here - commands running as the console, and the HUD appearing in
every capture - are now the `player` field and the `hud` verb described above.

## Status

Built and verified on Minecraft 26.1.2 / NeoForge 26.1.2.76. `SPEC.md` carries the design, the
verified API details, and what is deliberately out of scope.

Releases attach the jar and nothing else. It is published to no Maven repository on purpose: a
resolvable artifact is one `runtimeOnly` away from ending up inside somebody's shipped mod.

## Contributing

`CONTRIBUTING.md` covers the build, how to verify a change against a live instance, and the three
rules a pull request cannot break. `SECURITY.md` explains why those three rules are the entire
security boundary, and what does and does not count as a vulnerability. Report vulnerabilities
through the Security tab, never a public issue.

The three rules are enforced rather than trusted: `.github/scripts/check_invariants.sh` fails CI if
the bind address changes, if a wildcard address appears anywhere, if the opt-in gate goes away, or if
the build starts publishing artifacts.

## Credits

The deferred screenshot approach - queue the request, capture on the client thread, reply only once
the file exists - is ported from [Jeroen-45/screenshot-bot](https://github.com/Jeroen-45/screenshot-bot)
(MIT). That mod binds every interface; this one does not.

MIT licensed.
