"""A minimal RCON client, which is how you talk to a running Minecraft server.

RCON is Valve's remote console protocol and Minecraft's dedicated server speaks it natively. The whole
thing is four fields on a TCP socket, so this is hand-rolled rather than pulling a dependency:

    int32 length      of everything after this field
    int32 request id  echoed back, and set to -1 by the server to mean "auth failed"
    int32 type        3 login, 2 command, 0 response
    body              ASCII, null terminated
    null              a second terminator, which the spec requires and servers do check for

**Why this exists.** A singleplayer client has no remote command interface at all - the integrated
server does not listen on anything - so driving one from outside needs mod code. A dedicated server
needs none of that, and it also *returns command output*, which is the half that makes this worth
having: a scene can be placed and then verified, rather than placed and hoped about.
"""

from __future__ import annotations

import socket
import struct

LOGIN, COMMAND, RESPONSE = 3, 2, 0


class RconError(RuntimeError):
    pass


class Rcon:
    """One RCON connection. Use as a context manager."""

    def __init__(self, host: str = "127.0.0.1", port: int = 25575, password: str = "",
                 timeout: float = 10.0) -> None:
        self.host, self.port, self.password, self.timeout = host, port, password, timeout
        self._sock: socket.socket | None = None
        self._next_id = 0

    # -------------------------------------------------------------- lifecycle

    def __enter__(self) -> "Rcon":
        self.connect()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def connect(self) -> None:
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        request_id = self._send(LOGIN, self.password)
        packet_id, _, _ = self._read()
        # The server answers a failed login with id -1 rather than an error, so a wrong password
        # looks exactly like a successful connection until the first command silently does nothing.
        if packet_id == -1:
            raise RconError("RCON authentication failed: wrong password")
        if packet_id != request_id:
            raise RconError(f"RCON handshake out of step: sent {request_id}, got {packet_id}")

    def close(self) -> None:
        if self._sock is not None:
            self._sock.close()
            self._sock = None

    # -------------------------------------------------------------- protocol

    def _send(self, kind: int, body: str) -> int:
        assert self._sock is not None, "not connected"
        self._next_id += 1
        payload = struct.pack("<ii", self._next_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self._sock.sendall(struct.pack("<i", len(payload)) + payload)
        return self._next_id

    def _recv_exactly(self, count: int) -> bytes:
        assert self._sock is not None, "not connected"
        chunks = []
        while count:
            chunk = self._sock.recv(count)
            if not chunk:
                raise RconError("the server closed the connection")
            chunks.append(chunk)
            count -= len(chunk)
        return b"".join(chunks)

    def _read(self) -> tuple[int, int, str]:
        length = struct.unpack("<i", self._recv_exactly(4))[0]
        packet_id, kind = struct.unpack("<ii", self._recv_exactly(8))
        body = self._recv_exactly(length - 8)
        return packet_id, kind, body.rstrip(b"\x00").decode("utf-8", "replace")

    # -------------------------------------------------------------- commands

    def command(self, line: str) -> str:
        """Run one command and return what the server printed.

        A response longer than 4096 bytes arrives split across packets with no marker on the last one.
        The standard trick, used here, is to send a second harmless packet straight after: everything
        that arrives before ITS reply belongs to the first command.
        """
        line = line.lstrip("/")
        request_id = self._send(COMMAND, line)
        sentinel_id = self._send(COMMAND, "")

        parts: list[str] = []
        while True:
            packet_id, _, body = self._read()
            if packet_id == sentinel_id:
                break
            if packet_id == request_id:
                parts.append(body)
        return "".join(parts).strip()

    def script(self, lines) -> list[tuple[str, str]]:
        """Run several commands in order, returning (command, output) for each.

        Comments and blank lines are skipped so an .mcfunction file can be fed in directly.
        """
        out = []
        for raw in lines:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            out.append((line, self.command(line)))
        return out
