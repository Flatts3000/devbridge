# gamebridge

Run commands against a running Minecraft dev server and read what it says back.

**This is the half you install.** It shares a repo with the devbridge mod because they are two ends
of one wire protocol, but the mod is a dev-only backdoor that must never reach a player and is
published nowhere. Installing this puts no jar anywhere.

```
pip install "gamebridge @ git+https://github.com/Flatts3000/devbridge.git#subdirectory=gamebridge"
```

Stdlib only, no dependencies.

```
gamebridge wait                                       block until the server accepts RCON
gamebridge cmd "function recompile:showcase/museum"   run one command
gamebridge script scene.mcfunction                    run a file, a command per line
gamebridge probe 2 121 2                              what block entity data is at a position
gamebridge check "block 6 125 0 recompile:corrugated_metal"
gamebridge check "entity @e[type=minecraft:painting]" --count 6
```

Against the mod instead of RCON, pass `--devbridge <port>`. Pick a port per project rather than
copying one out of an example; two projects sharing a number is how a verifier reports a clean pass
about the wrong game.

```
gamebridge --devbridge 8604 ping                      handshake, side, and protocol check
gamebridge --devbridge 8604 cmd "time set day" --player @s
gamebridge --devbridge 8604 hud off                   keep the HUD out of the shot
gamebridge --devbridge 8604 shot museum               capture, once the file is on disk
gamebridge --devbridge 8604 input on                  hand the mouse back for manual framing
gamebridge --devbridge 8604 pause on                  restore vanilla pause-on-lost-focus
```

`check` exits non-zero when the condition does not hold, so scene verification is something a script
can gate on rather than something a person squints at in a screenshot.

Connection settings are read from the server's own `server.properties`, found by walking up from the
working directory. Nothing to configure and no password to keep in sync.

## Setting a repo up for it

In the mod's `run/server.properties`:

```
enable-rcon=true
rcon.password=<anything>
rcon.port=25575
```

then `./gradlew runServer`. `run/` is gitignored in these repos, so the password stays local.

## Two things that will bite

**A singleplayer world cannot be driven this way.** The integrated server does not listen on anything,
so there is no remote command interface at all; reaching one needs mod code running inside the game. A
dedicated server has RCON natively and needs none. If you want to *look* at what you built, connect a
client to `localhost` - the world is an ordinary world.

**Chunks unload when nobody is standing in them.** A playerless server will answer `data get block`
with "That position is not loaded" and quietly do nothing useful. Force the area open first:

```
gamebridge cmd "forceload add -16 -16 48 48"
```

This is the single most likely reason a command that should work appears to do nothing.
