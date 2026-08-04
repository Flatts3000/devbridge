# Security policy

## Read this first: what devbridge is

devbridge executes arbitrary Minecraft commands sent to a socket, with no authentication. That is not
an oversight to be reported. It is the entire feature, stated plainly in the README, and it is the
reason the rest of this document is short and specific.

Three invariants make that acceptable. Together they are the whole security boundary:

1. **The socket binds `127.0.0.1` only, and that is not configurable.** Another machine has no
   legitimate reason to drive your dev client. Making it an option would make it a thing somebody
   sets wrong once.
2. **It is off unless asked.** Without `-Ddevbridge.port=<n>` the mod opens no socket and registers
   no handler. A jar sitting in a mods folder does nothing.
3. **It is never shipped.** A separate jar, published to no Maven repository, so it cannot become a
   transitive dependency of a released mod.

If all three hold, the exposure equals that of a terminal already open on the same machine. There is
no authentication because a token on a loopback-only socket defends against nothing loopback does not
already exclude, and it would be one more secret to keep in sync between the mod and the tool driving
it.

These are enforced, not merely documented. `.github/scripts/check_invariants.sh` fails CI if the bind
address changes, if a wildcard address appears anywhere, if the opt-in gate is removed, or if the
build starts publishing artifacts.

## What is a vulnerability

Report any of these:

- Anything that causes the listener to accept a connection from outside loopback, including an
  indirect path such as a rebind, a proxy, a second socket, or a dependency that opens one.
- Anything that opens the socket, or registers a handler, without `devbridge.port` being set.
- Anything that lets a request reach `Handlers.dispatch` without arriving on that loopback socket.
- A path by which the jar ends up on a published artifact's classpath, or resolvable from a public
  repository.
- A vulnerability in a dependency that is actually reachable from this code. The dependency surface
  is small on purpose: Gson, plus whatever NeoForge and Minecraft already load.

Any of the above breaks the boundary described above rather than operating inside it.

## What is not a vulnerability

- **It runs arbitrary commands.** Yes. That is what it is for.
- **It has no authentication or encryption.** Deliberate, and explained above.
- **A command executed through it wrecked a world, deleted chunks, or changed game state.** The tool
  faithfully ran what it was told to run.
- **Somebody bundled the jar into a modpack or a server and got compromised.** That is invariant 3
  being violated downstream rather than a flaw here. Please still tell us who is distributing it, so
  the situation can be corrected, but it is not a report against this code.

## Reporting

Use GitHub's private vulnerability reporting: the **Security** tab of this repository, then **Report a
vulnerability**. That opens a private advisory visible only to the maintainer.

Please do not open a public issue for a security report. The whole point of the invariants is that a
break in one is quietly dangerous, and a public issue is a public exploit note for anybody running a
dev client at that moment.

Include the launch arguments, the mod version, and how you reached the socket. A proof of concept is
welcome and never required.

This is a personal project maintained in spare time. Expect a best-effort reply, usually within a
week. No service level is promised, and pretending otherwise would help nobody.

## Supported versions

Only the latest release, tracking the current Minecraft version. This is a development tool with one
maintainer; there are no backports to older Minecraft versions. If you need an older one, the tag is
in the history and the mod is 350 lines.

| Version | Minecraft | Supported |
|---|---|---|
| 0.1.x | 26.1.2 | Yes |
| Older tags | Earlier | No |
