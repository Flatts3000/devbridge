package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/**
 * The client-only verbs, behind a guard that never loads a client class on a server.
 *
 * <p><b>This class must not mention {@code Minecraft}.</b> A dedicated server has no client classes
 * at all, and the JVM resolves a method's types when it verifies the method - so a single reference
 * here would crash a server the moment {@code ping} touched this file. The actual capture lives in
 * {@link ScreenshotTaker}, which is only ever loaded after the check below has passed.
 *
 * <p>The check is {@code isDedicatedServer}, not an FML dist lookup. An integrated server exists
 * because a client started it, so the server already knows the answer and no loader API is involved.
 */
final class ClientHandlers {

    private ClientHandlers() {
    }

    static boolean available(MinecraftServer server) {
        return !server.isDedicatedServer();
    }

    static JsonObject screenshot(MinecraftServer server, String name) throws Exception {
        if (!available(server)) {
            // A clear refusal, not a hang. "There is no client here" is a far better answer than a
            // socket that never replies.
            return Handlers.error("no client on this side: a screenshot needs an integrated server, "
                + "and this is a dedicated one");
        }
        return ScreenshotTaker.take(name);
    }
}
