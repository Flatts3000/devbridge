package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/**
 * Looks at, and drives, whatever GUI is open. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>Why this exists.</b> A pack's quest book, a recipe screen, a config menu: a lot of what
 * breaks in a modpack lives in a GUI, and none of it was reachable. Static checks can tell you an
 * item id resolves; only a picture tells you the chapter reads sensibly on the grid.
 *
 * <p><b>Hovering is a separate thing from clicking, and it is the one that matters most.</b> A
 * tooltip renders because the pointer is somewhere, not because anything was pressed. A click-only
 * API can push buttons and can never photograph a tooltip, which is most of what is worth checking
 * in a quest book: the description, the item name, the requirement text.
 *
 * <p><b>Coordinates are GUI-scaled, not pixels.</b> That is the space screens actually think in, and
 * it is why {@code screen} reports the current screen's width and height: a caller works out where
 * to point from those rather than from the window size, which is a different number whenever the GUI
 * scale is not 1.
 *
 * <p><b>This bypasses the mouse lock on purpose.</b> {@link InputLock} cancels real presses before
 * the game grabs the mouse; these calls go straight to the screen. A run that locked the mouse to
 * stop a stray hand still wants to drive the GUI it opened.
 */
final class ScreenDriver {

    private ScreenDriver() {
    }

    /** What is open, and how big its coordinate space is. */
    static JsonObject describe() throws Exception {
        return onClient(client -> {
            JsonObject reply = Handlers.ok();
            Screen screen = client.screen;
            if (screen == null) {
                reply.add("screen", null);
                return reply;
            }
            reply.addProperty("screen", screen.getClass().getName());
            reply.addProperty("title", screen.getTitle().getString());
            reply.addProperty("width", screen.width);
            reply.addProperty("height", screen.height);
            return reply;
        });
    }

    /**
     * Open the player's inventory, or close whatever is open.
     *
     * <p>Only the inventory, deliberately. Opening a named screen from another mod means knowing its
     * class and how it wants to be constructed, which is a different feature and a much larger one.
     * The inventory is the case that needs no such knowledge, and closing works for anything.
     */
    static JsonObject set(boolean open) throws Exception {
        return onClient(client -> {
            if (!open) {
                client.setScreen(null);
            } else if (client.player != null) {
                client.setScreen(new InventoryScreen(client.player));
            } else {
                return Handlers.error("no player, so there is no inventory to open");
            }
            JsonObject reply = Handlers.ok();
            reply.addProperty("screen",
                client.screen == null ? null : client.screen.getClass().getName());
            return reply;
        });
    }

    /**
     * Move the pointer, which is what makes a tooltip appear.
     *
     * <p><b>Both the screen and the real pointer.</b> Telling the screen alone is not enough, and
     * the first version of this got it wrong: a capture taken after pointing at the middle of the
     * inventory showed a JEI tooltip on the far right, because JEI's overlay reads the operating
     * system's pointer rather than the coordinates handed to {@code mouseMoved}. Anything drawn by
     * a mod overlay would have followed the real mouse and ignored us. So this moves the actual
     * cursor too, and the screen call stays because a screen that is mid-drag wants to be told
     * directly rather than waiting for the next frame to notice.
     */
    static JsonObject cursor(double x, double y) throws Exception {
        return onClient(client -> {
            Screen screen = client.screen;
            if (screen == null) {
                return Handlers.error("no screen is open, so there is nothing to point at");
            }
            Window window = client.getWindow();
            // The inverse of MouseHandler.getScaledXPos: screens think in GUI-scaled units and GLFW
            // thinks in pixels, and at GUI scale 4 those differ by a factor of four.
            double rawX = x * window.getScreenWidth() / window.getGuiScaledWidth();
            double rawY = y * window.getScreenHeight() / window.getGuiScaledHeight();
            GLFW.glfwSetCursorPos(window.handle(), rawX, rawY);
            screen.mouseMoved(x, y);

            JsonObject reply = Handlers.ok();
            reply.addProperty("x", x);
            reply.addProperty("y", y);
            reply.addProperty("rawX", rawX);
            reply.addProperty("rawY", rawY);
            return reply;
        });
    }

    /**
     * Press and release at a point.
     *
     * <p>Both halves, because a screen that only ever sees a press leaves a widget stuck down, and
     * the next thing to photograph it gets a button that looks pressed for no reason.
     */
    static JsonObject click(double x, double y, int button) throws Exception {
        return onClient(client -> {
            Screen screen = client.screen;
            if (screen == null) {
                return Handlers.error("no screen is open, so there is nothing to click");
            }
            MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
            screen.mouseMoved(x, y);
            boolean handled = screen.mouseClicked(event, false);
            screen.mouseReleased(event);
            JsonObject reply = Handlers.ok();
            reply.addProperty("handled", handled);
            // Whether anything took the click. A false here usually means the coordinates missed,
            // and saying so beats a caller photographing an unchanged screen and wondering.
            reply.addProperty("screen",
                client.screen == null ? null : client.screen.getClass().getName());
            return reply;
        });
    }

    /** Screens are render-thread state, so every one of these has to happen there and be waited on. */
    private static JsonObject onClient(java.util.function.Function<Minecraft, JsonObject> work)
            throws Exception {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> done = new CompletableFuture<>();
        client.execute(() -> {
            try {
                done.complete(work.apply(client));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(15, TimeUnit.SECONDS);
    }
}
