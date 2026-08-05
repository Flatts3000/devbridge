# Onboarding: adding devbridge to a mod or a modpack

Two ways in, depending on what you have. A **mod repo** already has a build tool that owns the
launch, so devbridge slots into a Gradle run block. A **modpack** has no build tool, so the CLI
starts the game instead. Everything after startup is identical.

Read [SECURITY.md](../SECURITY.md) first if you have not. The short version: this executes arbitrary
commands on a loopback socket with no authentication, it is off unless you ask for it, and the jar
must never reach a player.

## Before either path

Install the client. It ships with the mod it talks to, so the two cannot drift:

```bash
pip install "gamebridge @ git+https://github.com/Flatts3000/devbridge.git#subdirectory=gamebridge"
```

**Claim a port per project, and do not reuse one you saw in somebody's README.** There is no default
anywhere: the mod stays idle without an explicit port, and the client requires one. Every project
that copies the same number lands on the same socket, which is not theoretical - two on one machine
both used 25580, and a pack's verifier connected to a different game's dev client and reported a
clean pass about the wrong world. That is why the examples below take a port rather than supply one.
`gamebridge --devbridge <port> ping` reports which side answered, so a wrong connection is at least
visible.

Get the jar from [Releases](https://github.com/Flatts3000/devbridge/releases), or build it with
`./gradlew build` and take it from `build/libs/`.

## Path A: a mod repo with a Gradle dev run

Put the jar in `run/mods/`, then in your `build.gradle`:

```groovy
runs {
    client {
        client()
        // Your claimed port. Deliberately not a real number here: a copyable one is how two
        // projects end up sharing a socket. A non-numeric value fails loudly at startup,
        // naming the property, rather than opening something you did not mean to open.
        systemProperty 'devbridge.port', '<your port>'

        // Optional: boot straight into a world instead of stopping at the title screen.
        programArguments.addAll '--quickPlaySingleplayer', 'My World'
    }
}
```

**`--args` on the Gradle task is not program arguments.** moddev takes it as a main class and the
launch dies before Minecraft starts. Use `programArguments`, as above.

Then `./gradlew runClient` and drive it:

```bash
gamebridge --devbridge <your port> ping
gamebridge --devbridge <your port> --player @s cmd "time set day"
gamebridge --devbridge <your port> shot my_scene
```

**Options go before the subcommand.** `--devbridge`, `--player` and `--timeout` belong to the tool,
not to the verb, so `cmd "..." --player @s` is an "unrecognized arguments" error.

## Path B: a modpack instance

A pack has no Gradle, and the CurseForge app owns the command line without offering a field for a
system property. `gamebridge launch` builds the command itself: it reads the app's own install
(`versions/`, `libraries/`, `assets/`, bundled JREs), downloads nothing, and never writes to
`minecraftinstance.json`.

1. Drop the jar in the instance's `mods/`.
2. Claim a port.
3. Launch:

```bash
PORT=8604   # the port THIS project claimed. Claim your own; there is no default.

gamebridge launch --instance "C:/Users/you/curseforge/minecraft/Instances/YourPack" \
                  --port "$PORT" --world "New World" --width 1280 --height 720 --wait
```

`--wait` blocks until the mod answers and checks the protocol version, so the next line of a script
can assume a game that is up and compatible. `--dry-run` prints the command line and starts nothing,
which is the fastest way to see why a launch would fail.

A full unattended loop looks like this:

```bash
gamebridge launch --instance "$INSTANCE" --port "$PORT" --world "New World" --wait
gamebridge --devbridge "$PORT" --player @s cmd "function yourpack:showcase/hall"
gamebridge --devbridge "$PORT" hud off
gamebridge --devbridge "$PORT" shot hall
gamebridge --devbridge "$PORT" stop
```

`stop` is a verb of its own, not `cmd stop`: the console `/stop` is a dedicated-server command and
does not exist in singleplayer, which is the case this tool is for.

## The commands

These are the CLI's subcommands. Most map to a protocol verb of the same name; `shot` is the
`screenshot` verb, and `launch` is not a verb at all because the game is not running yet.

| Command | What it does |
|---|---|
| `ping` | Handshake: protocol version, which side answered, and the client's pause and input state |
| `cmd` | Run one command and return what it printed. Takes an optional `player` |
| `shot` | Screenshot, written before the reply comes back |
| `hud` | Show or hide the HUD |
| `input` | Hand the mouse back, or take it |
| `pause` | Restore pausing on lost focus, or turn it off |
| `stop` | Close the world and quit |

**A toggle verb with no argument restores what vanilla does.** `hud on`, `input on`, `pause on` all
put the game back; `off` gives you the devbridge behaviour.

## Four things that will bite

**Connect to `localhost`, never the `127.0.0.1` literal.** The mod binds
`InetAddress.getLoopbackAddress()`, which on a JVM that prefers IPv6 is `::1`. Dialling the IPv4
literal then gets "connection refused" from a socket the log says is listening. For the same reason
`ports check` cannot see a devbridge listener at all, so a port registry may report it free while a
game is holding it.

**Commands run as the console unless you pass a `player`.** The console has no entity and sits at
world spawn, so `@s` matches nothing and `~` is spawn-relative. A function ending in `tp @s ...`
will place its scene perfectly and silently not move the camera. Pass `--player @s`, which takes the
only player online.

**`shot` only returns a path when you name the file.** Without a name the file gets Minecraft's
timestamped default, chosen inside the capture and never handed back, so the reply carries a message
and a directory and you are on your own to find it. Name your shots.

**Loading a world turns off pause-on-lost-focus and takes the mouse.** Both are deliberate: a
singleplayer world pauses half a second after you click away, which stops the integrated server
ticking, so an unfocused client would answer nothing. Taking the mouse then stops a stray alt-tab
turning the camera between the command that framed a shot and the shot itself. Opt out per launch
with `-Ddevbridge.keepTicking=false` or `-Ddevbridge.lockInput=false`, or per session with the
`pause` and `input` verbs.

## When it does not work

| Symptom | Cause |
|---|---|
| "connection refused" but the log says listening | You dialled `127.0.0.1`; use `localhost` |
| Nothing at all in the log about devbridge | No `-Ddevbridge.port`; the mod is present and idle by design |
| "protocol mismatch" | The jar and the CLI are from different commits. Rebuild the jar, or reinstall the CLI |
| A clean pass that describes the wrong world | Two projects sharing a port. `ping` reports which side answered |
| A command placed things but the camera did not move | Ran as the console; pass a `player` |
| `launch` refuses with missing classpath entries | Launch the instance once from the app so it fetches what it needs |

## Never ship it

The jar is a dev-only backdoor. It is published to no Maven repository precisely so it cannot become
a transitive dependency of something you release, and it reaches you as a Release attachment and
nothing else. If you maintain a pack, consider failing your build when `devbridge` appears in the
pack index; the Trashlands pack does exactly that.

The Python CLI is the opposite: an ordinary tool, meant to be installed. Both live in one repo
because they are two ends of one wire protocol, and keeping them apart is how a client quietly stops
being able to speak to its own mod.
