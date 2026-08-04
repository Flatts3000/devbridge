# devbridge

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

| Verb | Runs on | Reply |
|---|---|---|
| `ping` | either | `{"ok":true,"side":"integrated","mcVersion":"26.1.2","hasClient":true}` |
| `cmd` | server thread | `{"ok":true,"output":"..."}` - whatever the command printed |
| `screenshot` | client render thread | `{"ok":true,"path":"..."}` once the file is on disk |
| `stop` | either | Closes the world and quits |

Failures are `{"ok":false,"error":"..."}`. An unknown verb fails rather than being ignored: silently
accepting a typo is the worst outcome for a tool whose whole job is reporting what happened.

## Three things that will bite

**Commands run as the console, so `@s` matches nothing and `~` is spawn-relative.** A function ending
in `tp @s ...` will place everything correctly and silently not move the camera.

**The HUD is in the screenshot.** Hotbar, crosshair and held item all render into the capture.

**`InetAddress.getLoopbackAddress()` can be `::1`.** If your client dials the IPv4 literal and gets
"connection refused" from a socket the log says is listening, that is why. Connect to `localhost` and
let the resolver try both families.

## Status

Built and verified on Minecraft 26.1.2 / NeoForge 26.1.2.76. `SPEC.md` carries the design, the
verified API details, and what is deliberately out of scope.

## Credits

The deferred screenshot approach - queue the request, capture on the client thread, reply only once
the file exists - is ported from [Jeroen-45/screenshot-bot](https://github.com/Jeroen-45/screenshot-bot)
(MIT). That mod binds every interface; this one does not.

MIT licensed.
