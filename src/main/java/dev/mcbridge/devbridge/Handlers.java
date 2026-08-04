package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the verbs do, and the two threads they have to do it on.
 *
 * <p><b>Commands run on the server thread.</b> Executing one straight off the socket thread races
 * every tick in flight and will eventually corrupt a chunk. Each one is queued with
 * {@code server.execute} and the socket thread waits on a future.
 *
 * <p><b>Screenshots run on the client render thread</b>, and only once a frame has been drawn. That
 * lives in {@link ClientHandlers} so this class stays loadable on a dedicated server, where the
 * client classes do not exist at all. Touching {@code Minecraft} from here would crash a server the
 * moment the class was verified.
 */
final class Handlers {

    private Handlers() {
    }

    static JsonObject dispatch(JsonObject request, MinecraftServer server) throws Exception {
        String verb = request.has("verb") ? request.get("verb").getAsString() : "";
        return switch (verb) {
            case "ping" -> ping(server);
            case "cmd" -> command(server, request.get("command").getAsString(),
                request.has("player") ? request.get("player").getAsString() : null);
            case "screenshot" -> ClientHandlers.screenshot(server,
                request.has("name") ? request.get("name").getAsString() : null);
            case "hud" -> ClientHandlers.hud(server,
                !request.has("show") || request.get("show").getAsBoolean());
            case "input" -> ClientHandlers.input(server,
                !request.has("enabled") || request.get("enabled").getAsBoolean());
            case "stop" -> stop(server);
            // An unknown verb fails loudly. Silently accepting a typo is the worst outcome for a
            // tool whose entire job is reporting what happened.
            default -> error("unknown verb: '" + verb + "'");
        };
    }

    static JsonObject ok() {
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", true);
        return reply;
    }

    static JsonObject error(String message) {
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", false);
        reply.addProperty("error", message);
        return reply;
    }

    private static JsonObject ping(MinecraftServer server) {
        JsonObject reply = ok();
        reply.addProperty("side", server.isDedicatedServer() ? "dedicated" : "integrated");
        reply.addProperty("mcVersion", SharedConstants.getCurrentVersion().name());
        reply.addProperty("hasClient", ClientHandlers.available(server));
        return reply;
    }

    /** By name, or the only player online when asked for {@code "@s"} or an empty name. */
    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (name.isBlank() || name.equals("@s") || name.equals("@p")) {
            return players.isEmpty() ? null : players.get(0);
        }
        for (ServerPlayer player : players) {
            if (player.getGameProfile().name().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static JsonObject stop(MinecraftServer server) {
        JsonObject reply = ok();
        // Queued rather than called, so the reply is written before the world starts closing.
        server.execute(() -> server.halt(false));
        return reply;
    }

    /**
     * Run one command and return what it printed.
     *
     * <p>Output is the reason this is worth having over "run it and hope": the reply carries whatever
     * the command would have shown a player, so a tool can tell a placed structure from a silent
     * failure. Collecting it needs a {@link CommandSource} that keeps messages instead of the default
     * that drops them on the floor.
     */
    private static JsonObject command(MinecraftServer server, String command, String playerName)
            throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                messages.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;   // no need to broadcast tooling to anybody who happens to be online
            }
        };

        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                // NOT withSuppressedOutput(): that is exactly the switch that would stop the
                // messages this method exists to collect. createCommandSourceStack already carries
                // LevelBasedPermissionSet.OWNER, so no permission call is needed either.
                //
                // AS A PLAYER WHEN ASKED, and this is not a nicety. The console's stack has no entity
                // and sits at world spawn, so `@s` matches nothing and `~` is spawn-relative. A
                // function ending in `tp @s` then places everything correctly and silently fails to
                // move the camera, which reads as the command having half-worked.
                CommandSourceStack stack;
                if (playerName == null) {
                    stack = server.createCommandSourceStack();
                } else {
                    ServerPlayer player = findPlayer(server, playerName);
                    if (player == null) {
                        done.completeExceptionally(
                            new IllegalArgumentException("no player named '" + playerName + "'"));
                        return;
                    }
                    stack = player.createCommandSourceStack();
                }
                stack = stack.withSource(sink);
                server.getCommands().performPrefixedCommand(stack, command.replaceFirst("^/", ""));
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(30, TimeUnit.SECONDS);

        JsonObject reply = ok();
        reply.addProperty("output", String.join("\n", messages));
        return reply;
    }
}
