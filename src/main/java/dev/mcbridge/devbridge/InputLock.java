package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
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
 *       the grab. Only presses, and only when no screen is open: with a screen the mouse is already
 *       released and the camera cannot turn, and cancelling there would leave somebody unable to
 *       click Back to Game.
 *   <li><b>Closing a screen</b>, which grabs on the way out. There is no event before that, so the
 *       tick listener releases it again. One tick of a screen close is the only window left.
 * </ul>
 *
 * <p><b>Off by default.</b> Ask for it with {@code -Ddevbridge.lockInput=true} for a whole run, or
 * {@code {"verb":"input","enabled":false}} for as long as you need it. The lock is worth having
 * around an automated shoot and surprising anywhere else: a run that wants the camera held still
 * knows it and can say so, while a person who opened the game to frame something by eye has no idea
 * a mod took their mouse, and a camera that will not turn reads as a broken game rather than a
 * setting.
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
                    + "camera. You asked for this with -D{}=true; send {\"verb\":\"input\"} to hand "
                    + "the mouse back.", DevBridge.LOCK_INPUT_PROPERTY);
        }
        apply(true);
    }

    /** For {@code ping}. Volatile, so this is the state the render thread is actually acting on. */
    static boolean isLocked() {
        return locked;
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
        // Presses only, and that is not tidiness. Cancelling this event returns before the vanilla
        // fallback that runs KeyMapping.set(key, pressed), so swallowing a release leaves the button
        // logically held: the player mines or uses an item forever, in the world this lock exists to
        // hold still. Only a press can grab the mouse, so refusing presses is the whole job anyway.
        if (event.getAction() != InputConstants.PRESS) {
            return;
        }
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
