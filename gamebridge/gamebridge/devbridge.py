"""The devbridge transport: newline-delimited JSON over a loopback socket.

The other half of `gamebridge`. RCON reaches a dedicated server and cannot reach a singleplayer world
or take a screenshot; devbridge is a dev-only mod running inside the game that can do both. The CLI
speaks whichever it is pointed at, so verification scripts do not care which one is behind them.
"""

from __future__ import annotations

import json
import socket


class DevBridgeError(RuntimeError):
    pass


#: The wire protocol this client speaks. Must equal Handlers.PROTOCOL_VERSION in the mod, and
#: .github/scripts/check_invariants.sh fails the build if the two drift.
PROTOCOL_VERSION = 1


class DevBridge:
    """One connection to a running instance with the devbridge mod loaded."""

    # "localhost", NOT "127.0.0.1". The mod binds InetAddress.getLoopbackAddress(), which on a JVM
    # that prefers IPv6 is ::1 - so a client dialling the v4 literal gets "connection refused" from a
    # socket that is up and healthy. A hostname lets getaddrinfo try both families and works whichever
    # one the JVM picked.
    #
    # `port` is required and deliberately has no default. It used to default to 25580, and because
    # every example in the docs uses that number too, two projects on one machine ended up sharing
    # it: a pack's verifier connected to a different game's dev client and reported a clean pass
    # about the wrong world. A default here makes the safe thing the thing you have to remember.
    def __init__(self, port: int, host: str = "localhost", timeout: float = 60.0) -> None:
        self.host, self.port, self.timeout = host, port, timeout
        self._sock: socket.socket | None = None
        self._buffer = b""

    def __enter__(self) -> "DevBridge":
        self.connect()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def connect(self) -> None:
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)

    def close(self) -> None:
        if self._sock is not None:
            self._sock.close()
            self._sock = None

    def request(self, **payload) -> dict:
        assert self._sock is not None, "not connected"
        self._sock.sendall(json.dumps(payload).encode("utf-8") + b"\n")

        # Read until a newline: a reply can be longer than one recv, and a screenshot reply is only
        # sent once the file is actually on disk, so this can legitimately wait a while.
        while b"\n" not in self._buffer:
            chunk = self._sock.recv(4096)
            if not chunk:
                raise DevBridgeError("the game closed the connection")
            self._buffer += chunk
        line, _, self._buffer = self._buffer.partition(b"\n")

        reply = json.loads(line.decode("utf-8"))
        if not reply.get("ok"):
            raise DevBridgeError(reply.get("error", "unknown error"))
        return reply

    # -------------------------------------------------------------- verbs

    def command(self, line: str, player: str | None = None) -> str:
        payload = {"verb": "cmd", "command": line}
        if player is not None:
            payload["player"] = player
        return self.request(**payload).get("output", "")

    def script(self, lines, player: str | None = None) -> list[tuple[str, str]]:
        out = []
        for raw in lines:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            out.append((line, self.command(line, player)))
        return out

    def hud(self, show: bool) -> dict:
        return self.request(verb="hud", show=show)

    def input(self, enabled: bool) -> dict:
        """Hand the mouse back, or take it again.

        The mod locks it on world load so a stray alt-tab cannot turn the camera between framing a
        shot and taking it. Enable it when you want to fly around and frame something by eye.
        """
        return self.request(verb="input", enabled=enabled)

    def pause(self, enabled: bool) -> dict:
        """Restore pausing on lost focus, or turn it off again.

        The mod turns it off on world load, which is what lets an unfocused client answer at all: a
        paused singleplayer world stops ticking, so a queued command waits for a human.
        """
        return self.request(verb="pause", enabled=enabled)

    def screenshot(self, name: str | None = None) -> dict:
        return self.request(verb="screenshot", **({"name": name} if name else {}))

    def ping(self, check_protocol: bool = True) -> dict:
        """Handshake, and by default the version check.

        A mod older than this client has no `protocol` field at all, which is itself the answer:
        anything without one predates the field and cannot be speaking version 1.
        """
        reply = self.request(verb="ping")
        if check_protocol:
            theirs = reply.get("protocol")
            if theirs != PROTOCOL_VERSION:
                described = theirs if theirs is not None else "a version predating the field"
                raise DevBridgeError(
                    f"protocol mismatch: this client speaks {PROTOCOL_VERSION}, the mod on port "
                    f"{self.port} speaks {described}. They ship from the same repo, so one of them "
                    f"is stale: rebuild the jar, or reinstall gamebridge from the same commit."
                )
        return reply

    def stop(self) -> dict:
        return self.request(verb="stop")
