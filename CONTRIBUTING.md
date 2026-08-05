# Contributing

Thanks for looking. This is a small, deliberately limited tool, so the most useful thing this document
can do is tell you early which changes will be accepted and which will not.

## The three rules a pull request cannot break

These are the security boundary, not preferences. A PR touching any of them will be closed rather than
reviewed, whatever else it does well. `SECURITY.md` explains why each one exists.

1. **The socket binds loopback only.** `new ServerSocket(port, 1, InetAddress.getLoopbackAddress())`.
   Not a config option, not a constant somebody can override, not "just for LAN testing".
2. **Nothing happens without `-Ddevbridge.port`.** No socket, no handlers, no background thread.
3. **The jar is never published.** No `maven-publish`, no artifact on any repository. It reaches users
   as a GitHub Release attachment and nothing else.

`.github/scripts/check_invariants.sh` enforces all three. Run it before you push:

```sh
sh .github/scripts/check_invariants.sh
```

It is intentionally strict. If it fails on a reformat that is still correct, keep the formatting it
wants: a check that people learn to work around stops being a check.

## Build

JDK 25 for the mod, Python 3.11+ for the CLI. The Gradle wrapper handles the rest.

```bash
JAVA_HOME="/c/Program Files/Java/jdk-25" ./gradlew build
pip install -e ./gamebridge
```

If you change the wire protocol, change both halves. `Handlers.PROTOCOL_VERSION` and the
`PROTOCOL_VERSION` in `gamebridge/gamebridge/devbridge.py` have to match, and CI fails if they do
not. Bump it only when a verb or field is renamed, removed, or changes meaning; adding one is
backwards compatible and should not move the number.

Output lands at `build/libs/devbridge-26.1.2-<version>.jar`. Mod metadata, the Minecraft version and
the NeoForge version all live in `gradle.properties` and are expanded into `neoforge.mods.toml` at
build time. Change them there; never edit the toml directly.

The first build downloads and decompiles Minecraft and takes a while. Later ones do not.

## Verifying a change

**There is no test suite, and this is not an oversight waiting to be corrected.** Every meaningful
path here needs a running Minecraft: a server thread to execute a command on, a render thread with a
drawn frame to capture. A unit test of the JSON dispatch would assert that a switch statement is a
switch statement.

So verification is manual, and a PR should say what was done:

1. Build the jar and drop it in another mod's dev run at `run/mods/`.
2. Add `systemProperty 'devbridge.port', '<a port you claimed>'` to that project's client run.
3. Launch, then talk to `localhost:<that port>` with newline-delimited JSON. Connect to `localhost`, not
   `127.0.0.1`: `InetAddress.getLoopbackAddress()` can return `::1`, and dialling the IPv4 literal
   then reports "connection refused" from a socket that is working perfectly.
4. At minimum: `{"verb":"ping"}` for the handshake, `{"verb":"cmd","command":"time set day"}` for the
   server thread, and `{"verb":"screenshot","name":"test"}` for the render thread. If you touched
   command execution, also run one with `"player":"@s"`, which takes a different source stack than
   the console path and is the half that silently does nothing when it breaks.

CI builds the jar and checks the invariants. It cannot do step 4, which is why the PR template asks.

## Scope

`SPEC.md` section 8 is the out-of-scope list, section 9 records what has been resolved and why the
fix took the shape it did, and section 10 is what is still open. Read them before proposing a
feature.

The short version: **anything a command can already do is out of scope.** No verb for teleporting,
setting time, or changing weather; `cmd` covers all of them and every added verb is a thing to
maintain forever. The issue template asks why a `cmd` cannot do what you want, and that question is
the actual bar.

The verbs that do exist earn it by reaching something no command can: the render thread, the window,
or the lifecycle of the process. `hud` is the clearest example, and section 9 explains why it is its
own verb rather than a flag on `screenshot`.

## Style

- **ASCII punctuation only.** No em-dashes, no en-dashes, no emoji, anywhere: code, comments, commit
  messages, docs, issue text.
- **Comments explain the failure they prevent, not what the line does.** The existing class Javadoc is
  the standard. It is dense on purpose, because every paragraph in it is something that cost time to
  find out. Match that; do not strip it.
- Four-space indent, Java 25, no wildcard imports.
- Conventional commits: `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`.
- Files stay small. The whole mod is eight short classes and should stay legible in one sitting.

## Pull requests

Fork, branch, and open the PR against `main`. The template has a short checklist covering the three
rules, the invariant script, and how you verified against a live instance. CI runs the build and the
invariant check on every PR.

By contributing you agree your work is licensed under the MIT license, same as the rest of the repo.
