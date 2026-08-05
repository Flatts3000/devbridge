# devbridge - spec

**Status: built, all four phases.** Verified end to end on 26.1.2, from a cold machine: the CLI
started the game, quick-played into a world as an offline player, ran a command, took a screenshot at
a forced resolution, and quit, with nobody touching the game at any point. A dev-only mod that lets
tooling outside the game drive a running Minecraft instance, plus `gamebridge`, the CLI that drives
it.

Open work lives in [the issue tracker](https://github.com/Flatts3000/devbridge/issues) rather than in
this document. Section 10 records what is still open at the design level.

## 0. Why, given gamebridge already exists

`gamebridge` talks to a **dedicated server** over RCON. That covers a lot and needs no mod code, and
where it works it should stay the answer. It cannot do two things, and both are structural rather than
missing features:

- **Singleplayer.** The integrated server does not listen on a socket at all. There is no interface to
  reach, so reaching one means code running inside the game.
- **Screenshots.** RCON talks to a server. A screenshot is a client concern, and the dedicated server
  has no client, no framebuffer and nothing to capture.

The second is the one that matters. The showcase loop is currently *place, verify, then ask a human to
press F2*, and the human is the slow part - every reframing of the museum cost a round trip. With
screenshots in the loop, "change a number, rebuild, reshoot, look" is one command.

## 1. Decisions

| Decision | Answer | Why |
|---|---|---|
| Shape | **A separate mod jar**, not a source set inside a mod | It cannot leak into a release if it is never part of one. A `src/dev` folder relies on somebody keeping it out of the build forever |
| Home | **Its own public repo**, `github.com/Flatts3000/devbridge`, holding both the mod and the CLI | Started life inside `mc-pack-toolkit` and moved out for being a standalone mod meant to be published. The CLI followed in #4, for the opposite half of the same reason: it is not a pack-authoring tool, it is one end of a protocol whose other end is this mod. Any mod's dev run can drop the jar in; nothing about it is Recompile-specific |
| Transport | **Loopback TCP, newline-delimited JSON** | Same shape as RCON so `gamebridge` can speak both, but trivially implementable on both sides. JSON because a screenshot reply carries a path and an error carries a message |
| Off by default | **Requires `-Ddevbridge.port=<n>`** | Present-but-inert is the safe default. With no property the mod registers nothing and opens no socket |
| Binding | **127.0.0.1 only, never 0.0.0.0** | This executes arbitrary commands. See the security note, which is not a formality |
| Auth | **None, and that is why binding matters** | A token on a loopback-only socket protects against nothing that loopback does not already exclude, and would be one more thing to keep in sync |
| Client vs server verbs | **Both, in one mod** | Commands need the server thread, screenshots need the client render thread. In singleplayer both live in one process, which is exactly the case RCON cannot serve |
| Focus pause | **Turned off on world load. `devbridge.keepTicking=false` opts out** | The pause screen stops the integrated server ticking, so an unfocused client answers nothing. Every use of this mod is from outside the window, so the pause is almost never what anybody wanted. Set in memory rather than saved, so it does not outlive the mod |
| Mouse input | **Available, off by default. `devbridge.lockInput=true` asks for it** | Removing the pause removed the thing that stopped a stray alt-tab turning the camera mid-shoot, so the lock exists. It shipped on by default and that was wrong: an automated shoot knows it wants the camera held still and can ask, while a person framing a shot by hand has no idea a mod took their mouse, and a camera that will not turn reads as a broken game. A default should favour whoever cannot ask |
| Where the client lives | **`gamebridge/` in this repo, one project with the mod** | Two halves of one wire protocol with no version field, in two repos, one of which had no git remote. The drift was not hypothetical: three verbs and two `ping` fields shipped in the mod while the client knew about none of them. `ping` now carries a protocol version and CI fails if the two constants disagree. The published-vs-never-published boundary is held by wording and by the no-Maven invariant, not by repo separation |
| Behaviour switches | **A system property and a verb each, defaulting per switch rather than uniformly** | These are ergonomics, not the security boundary, which is why they are options when the bind address is not. Each default answers "who does the wrong answer surprise": keeping the world ticking is what makes an unfocused client answer at all, so it is on; the mouse lock only helps a run that already knows it wants it, so it is off. A launch property for a whole run, a verb for a moment. Only an exact `true` or `false` is honoured, so a typo keeps the default rather than silently flipping it |

## 2. Security, stated plainly

**This is remote code execution by design.** `/execute` can run any command, commands can rewrite the
world, and NeoForge dev runs often have a filesystem the game can reach. Three rules follow, and none
of them are optional:

1. **Bind to `127.0.0.1`.** Not a configurable host. There is no legitimate reason for another machine
   to drive your dev client, and "it is only my LAN" is how this becomes a story.
2. **Never ship the jar.** It lives in `run/mods/` or a dev-only dependency. It is not published, and
   it is not a `runtimeOnly` of anything that gets released.
3. **Opt in per launch** with `-Ddevbridge.port`. A jar left in a folder does nothing on its own.

If those three hold, the exposure is the same as a terminal on the same machine already having.

## 3. Protocol

Newline-delimited JSON, one request per line, one reply per line.

```json
{"verb": "cmd", "command": "function recompile:showcase/museum"}
{"ok": true, "output": "Running function recompile:showcase/museum"}

{"verb": "screenshot", "name": "museum"}
{"ok": true, "path": "C:/.../run/screenshots/museum.png", "dir": "C:/.../run/screenshots"}

{"verb": "ping"}
{"ok": true, "protocol": 2, "side": "integrated", "mcVersion": "26.1.2", "hasClient": true, "worldName": "New World", "mods": 51, "gameDir": "...", "pauseOnLostFocus": false, "inputLocked": false}
```

`{"ok": false, "error": "..."}` on failure. Unknown verbs fail rather than being ignored, because a
silently accepted typo is the worst outcome for a tool whose whole job is telling you what happened.

### Verbs

| Verb | Runs on | Notes |
|---|---|---|
| `ping` | either | Reports which side answered, the MC version, which world and how many mods, and on a client its game directory plus whether it pauses when unfocused and whether the mouse is locked. The handshake `gamebridge wait` polls |
| `cmd` | server thread | Executes with output captured. As the server console by default, or as a named `player` - see section 9 |
| `screenshot` | client render thread | Names the file rather than taking the timestamped default, so a tool can find what it just took |
| `hud` | client render thread | Shows or hides the HUD. Separate from `screenshot` because the capture takes the framebuffer as it already is |
| `input` | client render thread | Hands the mouse back, or takes it again. Not locked unless asked; see the decisions table |
| `pause` | client render thread | Restores pausing on lost focus, or turns it off again. Off automatically on world load |
| `screen` | client render thread | Reports the open GUI, its title, its GUI-scaled size, and the widgets in it - each with its label, bounds and a click point in that same space. Opens the inventory or closes anything |
| `cursor` | client render thread | Moves the pointer, which is what renders a tooltip. Moves the real OS cursor, not just the screen's idea of it |
| `click` | client render thread | Press and release at a point. Reports what it saw, none of which is a verdict: screens over- and under-report, and consequences land asynchronously. Verify with `screen` or a picture |
| `look` | client render thread | Where the camera is - which is not the player in third person or spectator - and what the crosshair is on, as a block with its full state or an entity. Reports nothing a command already answers: position, rotation, velocity and on-ground all come back from `data get entity` |
| `stop` | either | Halts the world, and on a client quits the game. Quitting matters: a client left at the title screen keeps the world's file locks, and the next launch fails looking like a corrupt save |

## 4. The two threading traps

**Commands must run on the server thread.** Executing one straight off the socket thread races
everything and will eventually corrupt a chunk. The handler queues onto the server via
`server.execute(...)` and blocks the socket thread on a future until it completes.

**Screenshots must run on the client render thread**, and only after a frame has been drawn. The
capture goes through `Minecraft.getInstance().execute(...)`, and the reply is sent when the write
callback fires, not when the request was accepted. Replying early would hand a tool a path to a file
that does not exist yet, which is the kind of bug that only shows up under load.

**Capturing command output** needs a `CommandSource` wrapper that collects `sendSystemMessage` rather
than the default that discards it. Without that, `cmd` can only report that something ran, which is the
half `gamebridge` already provides and not the half worth building.

## 5. Loading it into a dev run

```
run/mods/devbridge-0.1.0.jar
```

then launch with `-Ddevbridge.port=<a port you claimed>`. In a Gradle moddev run:

```groovy
runs {
    client {
        client()
        systemProperty 'devbridge.port', '<a port you claimed>'
    }
}
```

Combined with vanilla's `--quickPlaySingleplayer <world>` launch argument, a client boots straight into
a named singleplayer world with the bridge open, and no human has touched anything.

## 6. gamebridge speaks both

`gamebridge` gains a transport flag. Everything else about the CLI is unchanged, so the same verify
scripts work against either:

```
gamebridge --devbridge $PORT cmd "function recompile:showcase/museum"
gamebridge --devbridge $PORT shot museum
```

RCON stays the default for dedicated servers. The two transports share `cmd` and the assertions
built on it. **Everything that touches the client is devbridge-only** - `screenshot`, `hud`, `input`,
`pause` and `stop` - and each fails with a clear message over RCON rather than hanging. (This
originally said only `screenshot` was devbridge-only, which stopped being true as the client verbs
were added.)

`launch` is not a verb at all. It starts the game, so there is nothing listening yet to send it to;
it lives only in the CLI. See section 7, phase 4.

## 7. Build order

1. **`ping` and `cmd` over the socket, server side only.** Proves the transport and the threading, and
   is already useful: it is RCON for singleplayer.
2. **`screenshot`.** The reason to build this at all.
3. **`gamebridge` transport flag**, so existing scripts keep working.
4. **A one-command showcase loop**: launch, quickplay into the studio world, place a scene, shoot it,
   quit. This is the point the whole thing has been aiming at.

**All four shipped.** Phase 4 landed as `gamebridge launch` (#5, #6), which builds the same command
line the CurseForge app would from the app's own install and needs no launcher of its own. Verified
by running exactly that sequence against the Trashlands pack: launch, `cmd` as `@s`, `hud off`,
`screenshot` (a valid 1280x720 PNG), `stop`.

## 8. Out of scope

- **Anything a command can already do.** No bespoke verbs for teleporting or setting time; `cmd` covers
  them and every one added is a thing to maintain. A worked example: a `freeze` verb was proposed
  (#15) to pin particles, clouds and animation for reproducible shots, and `cmd "tick freeze"` turns
  out to make two captures byte-identical, animated textures included. Measured, then closed.
- **Running in production.** Not a debug tool for shipped mods, not a server admin tool. RCON exists.
- **Rendering without a window.** Minecraft needs a real GL context; there is no headless client. The
  window opens, it just does not need anybody looking at it.

## 9. Resolved, and what the fix was

Both of these were found by the first automated screenshots rather than by reasoning about the code,
which is the argument for building the loop before polishing it.

- **`cmd` running as the console.** Commands executed with no entity and at world spawn, so `@s`
  matched nothing and `~` was spawn-relative: running the museum function over the bridge placed the
  set correctly and silently did nothing for its `tp @s`, leaving the camera wherever it already was.
  Every showcase function ends in a `tp @s`, so this was the difference between "place a scene" and
  "take the shot". Fixed by a `player` field on the request, resolved to a `ServerPlayer` whose own
  command source stack is used. A name matches that player; `@s`, `@p` or blank take the only player
  online. Omitting the field still runs as the console.
- **The HUD was in every picture.** Hotbar, crosshair and held item all rendered into the capture,
  which made it unusable for a gallery. Fixed by a `hud` verb over
  `Minecraft.getInstance().options.hideGui`. Deliberately *not* a flag on `screenshot`:
  `Screenshot.grab` takes the framebuffer as it already is, so hiding and grabbing in one call would
  still catch the frame that was drawn with the HUD up. A caller hides, shoots, and shows again, and
  a run of shots shares one toggle.

## 10. Open

**Nothing.** The screenshot-resolution question, open since the spec was written, was answered by
[#14](https://github.com/Flatts3000/devbridge/issues/14): `screenshot` takes a width and height and
resizes the window for the moment of the capture. Not the offscreen render the question imagined,
because `Screenshot.grab` captures what is already in a target and vanilla cannot draw the level
into an arbitrary one on demand.

Open work lives in [the issue tracker](https://github.com/Flatts3000/devbridge/issues). This section
is for questions the design itself has not answered, and there are none.
