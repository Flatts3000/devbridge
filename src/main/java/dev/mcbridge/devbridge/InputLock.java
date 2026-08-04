package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Stops a stray hand moving the camera. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>This exists because {@link ClientOptions} took the pause away.</b> A world that keeps
 * ticking in the background is also a world you can alt-tab into and nudge: the mouse is grabbed,
 * so the smallest movement turns the camera, and a shot that was framed by a command is quietly no
 * longer the shot. The pause used to catch that by accident. Nothing does now.
 *
 * <p>The lock is just an ungrabbed mouse. {@code MouseHandler.turnPlayer} runs only while the mouse
 * is grabbed, so releasing it means mouse movement reaches nothing, and moving the mouse across the
 * window does exactly what moving it across any other background window does.
 *
 * <p>Keeping it released takes two listeners, because two things re-grab it:
 *
 * <ul>
 *   <li><b>A click in the world.</b> Cancelling {@code InputEvent.MouseButton.Pre} returns before
 *       the grab. Only when no screen is open: with a screen the mouse is already released and the
 *       camera cannot turn, and cancelling there would leave somebody unable to click Back to Game.
 *   <li><b>Closing a screen</b>, which grabs on the way out. There is no event before that, so the
 *       tick listener releases it again. One tick of a screen close is the only window left.
 * </ul>
 *
 * <p>Automatic on world load, because the accident it prevents is the default state of working this
 * way. Send {@code {"verb":"input"}} to hand the mouse back when you want to fly around and frame
 * something by hand.
 */
final class InputLock {

    /** Read on the render thread, written from the socket and server threads. */
    private static volatile boolean locked;

    private static boolean listening;

    private InputLock() {
    }

    /** The {@code input} verb. {@code enabled} is what the mouse does, so locking is disabling it. */
    static JsonObject set(boolean enabled) {
        apply(!enabled);
        JsonObject reply = Handlers.ok();
        reply.addProperty("inputEnabled", enabled);
        return reply;
    }

    static void lock() {
        if (!locked) {
            DevBridge.LOGGER.info(
                "devbridge locked mouse input, so alt-tabbing into the window cannot turn the "
                    + "camera. Send {\"verb\":\"input\"} to hand it back.");
        }
        apply(true);
    }

    private static synchronized void apply(boolean lock) {
        if (!listening) {
            // Registered on first use rather than at construction: nothing here is wanted by a mod
            // that is present but idle, and DevBridge cannot register them itself without naming a
            // client-only event class.
            NeoForge.EVENT_BUS.addListener(InputLock::onMouseButton);
            NeoForge.EVENT_BUS.addListener(InputLock::onClientTick);
            listening = true;
        }
        locked = lock;
        if (lock) {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> release(client));
        }
    }

    private static void release(Minecraft client) {
        if (client.mouseHandler.isMouseGrabbed()) {
            client.mouseHandler.releaseMouse();
        }
    }

    private static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft client = Minecraft.getInstance();
        if (locked && client.screen == null && client.getOverlay() == null) {
            event.setCanceled(true);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!locked) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            release(client);
        }
    }
}
