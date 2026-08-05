# Handoff: nobody can start the game from outside

**Written 2026-08-04 from the Trashlands session that used devbridge to verify a quest book.**
Everything below was verified on this machine. This is a task brief, not a design; the design
decisions are yours.

## The ask

Drive a full verification loop with no human in it:

```
launch the instance  ->  load a world  ->  run commands, take shots  ->  stop
                                            ^^^^^^^^^^^^^^^^^^^^^^^      ^^^^
                                            devbridge does this          `stop` does this
```

The two arrows on the right exist. The two on the left do not, and every automated run stalls on a
person alt-tabbing to a launcher.

## devbridge structurally cannot do this, and that is the finding

Not an omission. Two hard reasons:

**1. The mod is not running yet.** devbridge opens no socket without `-Ddevbridge.port=<n>`, by
design (SPEC section 2). The thing being asked for is *setting that property*, so anything inside the
game is on the wrong side of its own switch.

**2. Loading a world is a launch argument, not a mod feature.** Vanilla already has
`--quickPlaySingleplayer <world name>`, which is what the Recompile dev run uses
(`build.gradle`, the `client` block). A mod could plausibly drive the title screen instead, but it
would be reimplementing an argument the game already takes, and it still needs reason 1 solved
first.

So the missing piece is **launcher-side**, and probably belongs in `gamebridge` rather than here -
it is Python, it already owns transport selection, and `gamebridge wait` is already the "is it up
yet" primitive a launcher would poll. Adding a verb to the mod cannot work. That is a
recommendation, not a decision.

## Why this has not bitten before

**Because gradle owns the launch in a mod repo.** Recompile's run block sets the system property, the
quick-play argument and a forced window size in one place, so `./gradlew runClient` already produces
exactly the state a harness wants. Nothing was missing because the build tool was the launcher.

**A pack has no gradle.** The CurseForge app owns the command line, and it is the one thing in the
loop that has no CLI. That is the entire gap: the same capability, minus a build tool.

## What the launcher has to produce

Verified against `C:\Users\User\curseforge\minecraft\Instances\Trashlands`:

| Needed | Where it comes from now |
|---|---|
| `-Ddevbridge.port=<claimed>` | a human, in CurseForge app -> Instance settings -> Java |
| `--quickPlaySingleplayer <world>` | nothing; the app offers no such field |
| forced window size | nothing; the default 854x480 is unusable for shots |
| the instance's mods, config, java version | the app |

`minecraftinstance.json` in the instance directory carries `gameVersion`, `allocatedMemory` and a
`javaArgsOverride` field (currently `null`). **Writing that file directly is a known way to break an
instance** - the CurseForge app owns it, and the Trashlands `tools/sync_instance.py` already tells
users to fix loader mismatches in the app rather than in json. A launcher that edits it inherits that
problem.

## Three routes, with the trade nobody has picked yet

1. **Construct the launch directly.** Read `minecraftinstance.json`, build the classpath and JVM args,
   run java. Full control over every argument, no app involvement. Costs: reimplementing what a
   launcher does, an offline/dummy auth path for singleplayer, and it breaks whenever the CF app
   changes that file's shape.
2. **Drive an existing launcher CLI.** Prism and similar expose `--launch <instance>`. Cheap and
   robust where the user has one; useless for a CurseForge-app instance, which is the case that
   prompted this.
3. **A tiny launcher shim in the instance.** Generate a `run.bat`/`run.sh` beside the instance that
   invokes java with the right arguments, produced once from the app's own recorded launch. Least
   code, but it is a snapshot that goes stale when the app updates the instance.

Route 1 is the only one that covers the CurseForge case unaided. It is also the most work, which is
why this is a brief and not a patch.

## What done looks like

```
gamebridge launch --instance "C:/.../Instances/Trashlands" --port 8604 --world "New World"
gamebridge wait --devbridge 8604
gamebridge cmd --devbridge 8604 "ftbquests open <player>"
gamebridge --devbridge 8604 hud off && gamebridge --devbridge 8604 shot quest_book
gamebridge cmd --devbridge 8604 stop
```

Acceptance is that sequence running unattended from a cold machine and leaving a PNG behind.

## Context, in case it changes the priority

This came out of writing a quest book for the Trashlands pack. The tooling around it verifies what it
can statically - ids are well formed, dependencies resolve, no text is stranded in the wrong file -
but the questions that actually matter need a running game: does the book load, does every item id
still exist, does the chapter read sensibly on the grid. Two of those are already automated against
devbridge and neither has run yet, because starting the game is manual.

One thing worth knowing while you are here. Two projects on this machine both landed on port 25580,
and the first run of the pack's verifier connected to the Recompile dev client instead, reporting a
clean pass about the wrong game. Trashlands has since claimed 8604 via the `ports` helper.

> **Correction, 2026-08-04.** This section originally said the default was "baked into the mod" and
> recommended that the mod refuse to bind a port it was not explicitly given. Checked, and both
> halves were wrong. The mod reads `System.getProperty("devbridge.port", "0")` and stays idle at 0,
> so it already refuses; there is nothing to fix there. The default was in `DevBridge.__init__` in
> the Python client (`port: int = 25580`), and *no caller ever used it* - all three passed a port
> explicitly - so it was a default that existed only to be inherited by a future caller. What
> actually caused the collision was every code sample in README, SPEC, CLAUDE.md, CONTRIBUTING and
> the CLI's own docstring using the same number.
>
> Fixed by making `port` a required argument of `DevBridge` and by telling readers, where the
> examples are, to pick their own. Acting on the original recommendation would have changed code
> that was already correct and left the real cause in place.

Related: `ports check` cannot see a devbridge listener at all. It enumerates IPv4, and the mod binds
`InetAddress.getLoopbackAddress()`, which is `::1` on a JVM that prefers IPv6 - the same fact that
makes `gamebridge` dial `localhost` rather than the v4 literal. So the machine's port registry
reports the port free while a game is holding it.

> **Confirmed and broader, 2026-08-04.** `ports.py:122` runs `netstat -ano -p TCP`, and on this
> machine that returns zero IPv6 rows while `netstat -ano -p TCPv6` returns 33. So `ports check` is
> blind to *every* IPv6-only listener, not just devbridge's, and will report those ports free for
> any project. The fix belongs in `~/.claude/scripts/ports.py` rather than here.

## Where things are

| Thing | Path |
| --- | --- |
| A gradle run that already does all of this | `F:\minecraft-repos\recompile\build.gradle`, `client` block |
| The CLI a launcher would live in | `gamebridge/` in this repo (moved here 2026-08-04) |
| The pack instance in question | `C:\Users\User\curseforge\minecraft\Instances\Trashlands` |
| The verifier waiting on this | `F:\minecraft-repos\trashlands\tools\verify_quests.py` |
| Port registry | `~/.claude/port_registry.yaml`, via `ports` |
