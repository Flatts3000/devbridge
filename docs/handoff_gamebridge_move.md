# Handoff: move gamebridge into this repo

> **Done, 2026-08-04.** `gamebridge/` is in this repo, `ping` carries a protocol version, and
> `check_invariants.sh` fails the build if the mod's and the client's version constants disagree.
> Kept as the record of why, since the reasoning outlived the task. The one thing this brief did not
> consider was giving gamebridge its own repo; that was weighed and rejected, because the risk it
> raises (an installable package beside a never-ship jar) is a wording problem, while the risk it
> solves (a client that cannot speak to its own mod) had already happened.

**Written 2026-08-04 from the Trashlands session that built a quest verifier on top of both.**
Paths and consumers below were checked on this machine. Companion to
[`HANDOFF_LAUNCH.md`](./HANDOFF_LAUNCH.md); they are likely one piece of work, because the launcher
lands in gamebridge and would rather land here than in its current home.

## The proposal

Move `F:\minecraft-repos\mc-pack-toolkit\gamebridge\` to `gamebridge/` in this repo. The mod stays
where it is; the Python CLI joins it.

## Why

**One wire protocol, two repos, no version field.** `ping` reports the Minecraft version and which
side answered. It does not report a protocol version, so a mod and a CLI that disagree fail in
whatever way the mismatch happens to produce, with nothing detecting it. Today the only thing keeping
them in step is that the same person changes both. In one repo they ship together and the risk mostly
evaporates. Adding a protocol version to `ping` is worth doing either way, and is much easier to keep
honest once both implementations live in the same tree.

**gamebridge is not a pack-authoring tool.** mc-pack-toolkit is a voice system plus texgen: things
that help write and illustrate a modpack. gamebridge is a client for a protocol defined in this
repo, filed next to a corpus study because that is where it was written.

**mc-pack-toolkit has no git remote.** Nothing in it can be installed from a URL, so it is reachable
only from this machine. That has already shaped two decisions in Trashlands: its
`.github/workflows/validate-quests.yml` carries a comment explaining that the voice linter cannot run
in CI for exactly this reason, and `verify_quests.py` inherits the same limit. This repo is published
at `github.com/Flatts3000/devbridge`, so a package inside it is installable:

```
pip install "gamebridge @ git+https://github.com/Flatts3000/devbridge.git#subdirectory=gamebridge"
```

**Be precise about what that buys, though.** It does *not* make `verify_quests.py` runnable in
CI - that needs a live game, which Actions does not have. What it removes is "reachable only from
one machine" as a property of the tool.

**It makes this repo a complete thing.** A mod that opens a socket is half a tool. Published with the
client that speaks to it, it is something a person can actually use.

## What moves

`gamebridge/` is self-contained: `__init__.py`, `cli.py`, `rcon.py`, `devbridge.py`, `README.md`,
`pyproject.toml`. Package name `gamebridge`, version 0.1.0, entry point `gamebridge.cli:main`,
**zero dependencies** and stdlib-only, so nothing about the move touches a dependency tree.

A Java repo gaining a Python subdirectory needs a little furniture: `__pycache__/` and
`*.egg-info/` in `.gitignore`, and a decision about whether `.github/scripts/check_invariants.sh`
should grow a Python-side invariant.

## The three consumers, all of which break

| File | Line | What it does |
|---|---|---|
| `F:\minecraft-repos\trashlands\tools\verify_quests.py` | 64, 67 | imports `gamebridge.devbridge`, and prints the old `pip install -e` path in its error message |
| `F:\minecraft-repos\recompile\tools\shoot_scenes.py` | 22-23 | **hardcodes** `sys.path.insert(0, "F:/minecraft-repos/mc-pack-toolkit/gamebridge")` |
| `F:\minecraft-repos\recompile\tools\verify_showcase.sh` | 8 | `python -m gamebridge.cli` |

`shoot_scenes.py` is the one that breaks hardest, since it reaches for an absolute path rather than
an installed package. Worth taking the chance to delete that line and depend on the install instead.

Also update `HANDOFF_LAUNCH.md` in this repo, which names the old path in its final table.

## One risk the merge creates

This repo would then hold **one artefact that must never ship** and **one that people are meant to
install**. The jar is a dev-only backdoor; the README, SECURITY.md and SPEC all say so. A Python
package next to it, with install instructions, muddies a boundary that is currently obvious from the
repo name alone.

Worth deciding up front how that stays legible - most cheaply, a line at the top of the CLI's README
saying the mod is not for players and the CLI is, plus keeping the mod's warning where it is. The
Trashlands pack already enforces its half: `tools/check_pack_deps.py` fails the build if `devbridge`
appears in the pack index, and it runs in both that repo's workflows.

## Done looks like

1. `gamebridge/` present here, git history preserved if convenient, discarded if not.
2. A clean-environment `pip install` from the git URL above produces a working `gamebridge` command.
3. All three consumers updated, and `shoot_scenes.py` no longer inserts a path.
4. `pip install -e F:/minecraft-repos/mc-pack-toolkit/gamebridge` appears nowhere.
5. The old directory is gone rather than left as a copy that will drift.
6. Ideally: `ping` gained a protocol version, since this is the moment it becomes cheap.

## Prior art on this machine, for the trade

mc-pack-toolkit already runs as a multi-island monorepo: quest-voice is stdlib-only Python, texgen is
a separate dependency island with Pillow and the OpenAI SDK, and its CLAUDE.md says so plainly. So
"one repo, several unrelated toolchains" is an accepted pattern here and works. The argument for
moving gamebridge is not that the pattern is wrong; it is that gamebridge belongs to the protocol,
and the protocol lives here.
