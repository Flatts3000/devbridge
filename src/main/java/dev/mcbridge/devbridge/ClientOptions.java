package dev.mcbridge.devbridge;

import net.minecraft.client.Minecraft;

/**
 * Keeps the game running while its window is in the background. Loaded only on a client - see
 * {@link ClientHandlers}.
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
                    + "window is in the background. Not saved: F3+P restores it for this session.");
        });
    }
}
