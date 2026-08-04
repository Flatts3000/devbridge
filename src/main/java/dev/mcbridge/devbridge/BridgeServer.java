package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.minecraft.server.MinecraftServer;

/**
 * The socket. One request per line of JSON, one reply per line.
 *
 * <p><b>Bound to loopback, deliberately, and not configurable.</b> {@code new ServerSocket(port)}
 * binds every interface, which on a laptop on a shared network publishes an arbitrary-command
 * endpoint to everyone on it. The third argument below pins it to {@code 127.0.0.1}. There is no
 * legitimate reason for another machine to drive your dev client, so this is not exposed as an
 * option: an option is a thing somebody sets wrong once.
 *
 * <p>No authentication, and that is a consequence rather than an oversight. A token on a
 * loopback-only socket defends against nothing that loopback does not already exclude, and would be
 * one more secret to keep in sync between the mod and the tool driving it.
 *
 * <p>Single connection at a time, handled on its own thread. The tool driving this is a CLI issuing
 * one command at a time; concurrency here would buy nothing and would make the threading rules in
 * {@link Handlers} harder to reason about.
 */
final class BridgeServer extends Thread {

    private final int port;
    private final MinecraftServer minecraftServer;
    private volatile boolean running = true;
    private ServerSocket socket;

    BridgeServer(int port, MinecraftServer minecraftServer) {
        super("devbridge");
        this.port = port;
        this.minecraftServer = minecraftServer;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            // Backlog 1 and an explicit loopback address. Both matter: see the class note.
            socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            DevBridge.LOGGER.error("devbridge could not listen on 127.0.0.1:{}", port, e);
            return;
        }
        DevBridge.LOGGER.info("devbridge listening on 127.0.0.1:{}", port);

        while (running) {
            try (Socket client = socket.accept()) {
                serve(client);
            } catch (IOException e) {
                if (running) {
                    DevBridge.LOGGER.warn("devbridge connection failed", e);
                }
            }
        }
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));

        String line;
        while (running && (line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject reply;
            try {
                reply = Handlers.dispatch(
                    JsonParser.parseString(line).getAsJsonObject(), minecraftServer);
            } catch (Exception e) {
                // Any failure is reported rather than closing the connection. A tool that gets an
                // error line can say what went wrong; one whose socket died can only say "broken".
                reply = Handlers.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.write(reply.toString());
            out.write('\n');
            out.flush();
        }
    }

    void shutdown() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Closing the socket is what wakes accept(); a failure here means it was already shut.
        }
    }
}
