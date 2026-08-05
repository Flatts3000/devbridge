package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Client-level state and lifecycle: whether the game keeps running unfocused, which directory it is
 * running out of, and shutting it down. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>This is what F3+P does, and it is automatic because every use of this mod involves the
 * window not being focused.</b> {@code Minecraft.pauseIfInactive} opens the pause screen half a
 * second after focus is lost, and in singleplayer the pause screen stops the integrated server
 * ticking. A queued command then does not run until somebody clicks back into the game, and a
 * screenshot captures the pause menu over a frozen world. Without this, the tool drives a game that
 * stops the moment you look away from it, which is the moment you are always looking away.
 *
 * <p>Set in memory and deliberately <b>not</b> saved. F3+P writes options.txt; doing the same here
 * would leave a mod's side effect sitting in the user's config after the mod is gone. Reasserting it
 * on every world load costs nothing and leaves no trace.
 */
final class ClientOptions {

    private ClientOptions() {
    }

    static void keepRunningUnfocused() {
        Minecraft client = Minecraft.getInstance();
        // Queued onto the render thread. pauseOnLostFocus is a plain non-volatile field read from
        // there every frame, so writing it from the server thread is a race with nothing to make
        // the write visible.
        client.execute(() -> {
            if (!client.options.pauseOnLostFocus) {
                return;   // already off, and saying so every world load is noise
            }
            client.options.pauseOnLostFocus = false;
            DevBridge.LOGGER.info(
                "devbridge turned off pause-on-lost-focus, so the world keeps ticking while the "
                    + "window is in the background. Not saved: F3+P restores it for this session. "
                    + "Turn this off with -D{}=false.", DevBridge.KEEP_TICKING_PROPERTY);
        });
    }

    /**
     * The {@code pause} verb. {@code enabled} is vanilla's behaviour, so enabling it is asking the
     * client to stop answering while you are looking at something else.
     */
    static JsonObject setPause(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.options.pauseOnLostFocus = enabled);
        JsonObject reply = Handlers.ok();
        reply.addProperty("pauseOnLostFocus", enabled);
        return reply;
    }

    /**
     * What the option says right now, for {@code ping}.
     *
     * <p>Read straight off the socket thread. It is a plain field, so this can lag a write by a
     * frame, and a status line is not worth a round trip to the render thread to avoid that. Reading
     * the real field rather than a mirror is also the only way to notice somebody pressing F3+P.
     */
    static boolean pausesOnLostFocus() {
        return Minecraft.getInstance().options.pauseOnLostFocus;
    }

    /**
     * Which directory this client is running out of, for {@code ping}.
     *
     * <p>The instance root, so it names the pack or dev run rather than a world inside it. This is
     * the field that distinguishes two clients of the same Minecraft version, which nothing else in
     * the reply does.
     */
    static String gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().toString();
    }

    /**
     * Close the game.
     *
     * <p>Queued onto the client thread, which is the only one allowed to end the run loop.
     * {@code Minecraft.stop()} does nothing but set a flag; the loop notices, unwinds, and the
     * process exits.
     */
    static void quit() {
        Minecraft client = Minecraft.getInstance();
        client.execute(client::stop);
    }
}
