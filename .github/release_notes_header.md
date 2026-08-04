## Development tool. Do not ship this jar.

devbridge executes arbitrary Minecraft commands sent to a loopback socket, with no authentication.
That is the feature. It is safe because the socket cannot be reached from another machine and does
not open at all unless you ask for it.

**Put this jar in a development run's `run/mods/` and nowhere else.** Not in a modpack, not on a
server, not as a dependency of a mod you publish. See
[SECURITY.md](https://github.com/Flatts3000/devbridge/blob/main/SECURITY.md).

Enable it with `-Ddevbridge.port=<port>`. Without that property it opens no socket and registers no
handler.

---
