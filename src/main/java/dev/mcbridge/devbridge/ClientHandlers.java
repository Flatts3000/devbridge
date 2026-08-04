package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/**
 * The client-only verbs, behind a guard that never loads a client class on a server.
 *
 * <p><b>This class must not mention {@code Minecraft}.</b> A dedicated server has no client classes
 * at all, and the JVM resolves a method's types when it verifies the method - so a single reference
 * here would crash a server the moment {@code ping} touched this file. The actual capture lives in
 * {@link ScreenshotTaker}, the option tweaks in {@link ClientOptions} and the mouse lock in
 * {@link InputLock}, none of which are ever loaded until the check below has passed.
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

    static JsonObject hud(MinecraftServer server, boolean show) throws Exception {
        if (!available(server)) {
            return Handlers.error("no client on this side: the HUD is a client thing");
        }
        return ScreenshotTaker.hud(show);
    }

    static JsonObject input(MinecraftServer server, boolean enabled) throws Exception {
        if (!available(server)) {
            return Handlers.error("no client on this side: there is no mouse to lock");
        }
        return InputLock.set(enabled);
    }

    static JsonObject pause(MinecraftServer server, boolean enabled) throws Exception {
        if (!available(server)) {
            return Handlers.error("no client on this side: a dedicated server has no window to "
                + "lose focus and never pauses for one");
        }
        return ClientOptions.setPause(enabled);
    }

    /**
     * Stop the game pausing itself when its window loses focus, and stop a stray hand turning the
     * camera now that nothing pauses. Each half obeys its own system property. Silent on a
     * dedicated server, which has neither a window to lose focus nor a mouse to grab.
     */
    static void prepareForRemoteControl(MinecraftServer server, boolean keepTicking,
            boolean lockInput) {
        if (!available(server)) {
            return;
        }
        if (keepTicking) {
            ClientOptions.keepRunningUnfocused();
        }
        if (lockInput) {
            InputLock.lock();
        }
    }

    /**
     * Add whatever client state a caller would otherwise have to discover by timing out. Does
     * nothing on a dedicated server, where {@code hasClient} has already said as much.
     */
    static void describe(JsonObject reply, MinecraftServer server) {
        if (available(server)) {
            reply.addProperty("pauseOnLostFocus", ClientOptions.pausesOnLostFocus());
            reply.addProperty("inputLocked", InputLock.isLocked());
        }
    }
}
