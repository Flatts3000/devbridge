# devbridge

[![build](https://github.com/Flatts3000/devbridge/actions/workflows/build.yml/badge.svg)](https://github.com/Flatts3000/devbridge/actions/workflows/build.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft 26.1.2](https://img.shields.io/badge/minecraft-26.1.2-brightgreen.svg)](gradle.properties)
[![dev only: do not ship](https://img.shields.io/badge/dev%20only-do%20not%20ship-red.svg)](SECURITY.md)

A **development-only** NeoForge mod that lets tooling outside the game drive a running Minecraft
instance: run commands, take screenshots, and read the answers back. Plus `gamebridge`, the Python
CLI that speaks to it.

**Two artefacts, and only one of them is for installing.** The mod is a dev-only backdoor that must
never reach a player: see [SECURITY.md](SECURITY.md). The CLI is an ordinary tool you are meant to
install. They live together because they are two halves of one wire protocol, and keeping them in
separate repos is how a client quietly stops being able to speak to its own mod.

```
$ gamebridge --devbridge $PORT cmd "function mymod:showcase/museum"
Running function mymod:showcase/museum
$ gamebridge --devbridge $PORT shot museum
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

Grab the jar from [Releases](https://github.com/Flatts3000/devbridge/releases), or build it yourself
with `./gradlew build`. It is published to no Maven repository on purpose, so there is nothing to add
to a dependency block and no way for it to end up in one by accident.

Drop the jar in your dev run's `run/mods/`, then in your mod's `build.gradle`:

```groovy
runs {
    client {
        client()
        systemProperty 'devbridge.port', '<a port you claimed>'

        // Two behaviour switches, with different defaults on purpose.
        // keepTicking (ON):  keep the world running while the window is in the background.
        // lockInput  (OFF):  never grab the mouse, so a stray alt-tab cannot move the camera.
        // systemProperty 'devbridge.keepTicking', 'false'
        // systemProperty 'devbridge.lockInput', 'true'

        // Optional: boot straight into a world instead of stopping at the title screen.
        // NOTE: `--args` on the Gradle task does NOT do this - moddev takes it as a main class.
        programArguments.addAll '--quickPlaySingleplayer', 'My World'
    }
}
```

Then talk to it with any client that speaks newline-delimited JSON over TCP. The reference one is
`gamebridge`, in this repo:

```
pip install "gamebridge @ git+https://github.com/Flatts3000/devbridge.git#subdirectory=gamebridge"
gamebridge --devbridge <your port> ping
```

Stdlib-only, no dependencies. `pip install` puts no jar anywhere: the CLI and the mod reach you by
completely separate routes, which is why one can be installable while the other is never published.

**Pick your own port.** There is no default anywhere: the mod stays idle without one and the client
requires one. Every project that copies the same number out of a README lands on the same socket,
and a verifier that connects to a different game's dev client will report a clean pass about the
wrong world. It has happened. `gamebridge --devbridge <port> ping` reports which side answered and
the protocol version, so a wrong connection is at least visible.

**No build tool? The CLI can start the game itself.** A modpack has no Gradle run block, and the
CurseForge app offers no field for a system property, so `gamebridge launch` builds the command line
from the app's own install and runs it with the property set:

```
gamebridge launch --instance "C:/.../Instances/YourPack" --port <your port> --world "New World" --wait
```

`--wait` blocks until the mod answers and checks the protocol version.
[`docs/onboarding.md`](docs/onboarding.md) covers both routes properly.

**Loading a world turns off pause-on-lost-focus for you, the same as pressing F3+P.** Every use of
this mod involves the window not being focused, and a singleplayer world left to itself opens the
pause screen half a second after you click away - which stops the integrated server ticking. Your
next `cmd` would sit in the queue until somebody clicked back into the game, and your next
`screenshot` would be a picture of the pause menu. It is not saved to your options, so it lasts the
session and leaves nothing behind; press F3+P, send `{"verb":"pause"}`, or start with
`-Ddevbridge.keepTicking=false` if you want the pause back.

**It does not touch your mouse unless you ask.** Taking the pause away also took away the thing that
stopped a stray alt-tab turning the camera, so there is a lock available: with it on, the mouse is
simply never grabbed, and moving it over the window does what moving it over any other background
window does. Menus still take clicks.

Ask for it per run with `-Ddevbridge.lockInput=true`, or per moment with
`{"verb":"input","enabled":false}`. It is off by default because of who each default surprises. An
automated shoot knows it wants the camera held still and can say so; a person who opened the game to
frame something by eye has no idea a mod took their mouse, and a camera that will not turn reads as
a broken game rather than a setting.

## Protocol

One request per line, one reply per line.

| Verb | Runs on | Request fields | Reply |
|---|---|---|---|
| `ping` | either | none | `{"ok":true,"protocol":1,"side":"integrated","mcVersion":"26.1.2","hasClient":true,"worldName":"New World","mods":51,"gameDir":"...","pauseOnLostFocus":false,"inputLocked":false}` |
| `cmd` | server thread | `command`, optional `player` | `{"ok":true,"output":"..."}` - whatever the command printed |
| `screenshot` | client render thread | optional `name`, optional `width`+`height` | `{"ok":true,"message":"...","dir":"...","path":"..."}` once the file is on disk |
| `hud` | client render thread | optional `show` (default `true`) | `{"ok":true,"hudVisible":false}` |
| `input` | client render thread | optional `enabled` (default `true`) | `{"ok":true,"inputEnabled":true}` |
| `pause` | client render thread | optional `enabled` (default `true`) | `{"ok":true,"pauseOnLostFocus":true}` |
| `screen` | client render thread | optional `open` | `{"ok":true,"screen":"...","title":"...","width":480,"height":270}` |
| `cursor` | client render thread | `x`, `y` (GUI-scaled) | `{"ok":true,"x":167,"y":144,"rawX":668,"rawY":576}` |
| `click` | client render thread | `x`, `y`, optional `button` | `{"ok":true,"handled":true}` |
| `stop` | either | none | Closes the world. On a client this returns to the title screen; it does not quit the process |

`protocol` is the wire protocol's version, and `gamebridge` refuses to talk to a mod whose number it
does not recognise. It only changes when a verb or field is renamed, removed, or changes meaning:
adding either does not move it, because a client that has never heard of a new verb carries on
working. A reply with no `protocol` field at all is a mod from before the field existed.

`worldName`, `mods` and `gameDir` are there to answer "which game is this", which nothing else in
the reply does: two clients of the same Minecraft version are otherwise identical. `gamebridge ping
--expect-instance <dir>` refuses to continue against the wrong one.

The last three `ping` fields are client-only and absent on a dedicated server, where `hasClient` has
already said so. They are there because "will this client answer while I am looking at my terminal"
is a question worth being able to ask, rather than one you answer by waiting for a reply that never
comes.

**A toggle verb with no field puts the game back the way vanilla has it.** `{"verb":"hud"}` shows the
HUD, `{"verb":"input"}` gives the mouse back, `{"verb":"pause"}` restores pausing on lost focus. Pass
`false` to get the devbridge behaviour instead. Nothing to memorise: bare means vanilla.

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

## Getting started

[`docs/onboarding.md`](docs/onboarding.md) walks through both ways in: a mod repo, where a Gradle run
block owns the launch, and a modpack, where `gamebridge launch` starts the game instead. It also
lists the four things that reliably bite first.

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
