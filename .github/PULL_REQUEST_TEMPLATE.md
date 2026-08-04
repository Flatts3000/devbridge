## What this changes

<!-- One or two sentences. If it closes an issue, say "Closes #N". -->

## The three rules

These are the security boundary, not style preferences. See SECURITY.md.

- [ ] The socket still binds loopback only, and the bind address is still not configurable.
- [ ] Nothing still happens without `-Ddevbridge.port`: no socket, no handler, no thread.
- [ ] The build still publishes no artifact to any Maven repository.
- [ ] `sh .github/scripts/check_invariants.sh` passes locally.

## Verified against a live instance

CI builds the jar and checks the invariants. It cannot launch Minecraft, so this part is on you.
Say what you actually ran, and delete the lines that do not apply.

- [ ] Built the jar and loaded it into a dev run with `-Ddevbridge.port` set
- [ ] `ping` answered
- [ ] `cmd` ran and returned the command's output
- [ ] `screenshot` wrote a file that existed by the time the reply arrived

Minecraft / NeoForge version tested:

<!-- If you could not test against a running instance, say so plainly here rather than ticking
     boxes. An untested PR that says it is untested is reviewable; one that claims otherwise is not. -->

## Scope

- [ ] This is not something a `cmd` verb can already do (see SPEC.md section 8).
- [ ] ASCII punctuation only: no em-dashes, no en-dashes, no emoji.
