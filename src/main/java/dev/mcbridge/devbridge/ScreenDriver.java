package dev.mcbridge.devbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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

    /**
     * How deep to walk into nested containers. Four covers a list inside a tab inside a screen, which
     * is as nested as vanilla gets.
     */
    private static final int MAX_DEPTH = 4;

    /**
     * A ceiling on how many widgets one reply carries. A creative inventory's item grid is hundreds
     * of slots and nobody clicks them by name; the cap keeps a reply readable, and
     * {@code widgetsComplete} says when it bit rather than letting a short list read as a full one.
     */
    private static final int MAX_WIDGETS = 200;

    /**
     * What is open, how big its coordinate space is, and what is in it.
     *
     * <p><b>The widget list is what makes clicking scriptable.</b> Without it a caller works out
     * coordinates from outside the game: vanilla's death screen puts its buttons at
     * {@code height / 4 + 72}, so you can derive Respawn's centre by knowing Minecraft's source, and
     * a modded tab can only be measured off a screenshot and divided by the GUI scale. Both are
     * arithmetic about somebody else's layout, and neither survives a resize, a GUI scale change, or
     * the mod moving the widget. Asking the screen what it drew survives all three.
     *
     * <p><b>Bounds come from {@code getRectangle()}, which every child has</b> - it is a default
     * method on {@code GuiEventListener} rather than something only widgets implement, so a child
     * that is not an {@code AbstractWidget} still reports where it is. Its default is
     * {@code ScreenRectangle.empty()}, which is indistinguishable from a real zero-sized widget, so
     * an empty rectangle is reported as {@code null} bounds rather than as zeros. A caller that
     * clicks a reported centre then cannot be sent to (0, 0) by a widget that never said where it
     * was.
     *
     * <p><b>Coordinates are the same GUI-scaled space {@code click} and {@code cursor} take</b>, so a
     * centre from here can be handed straight back without conversion. That was worth stating: the
     * space was previously only inferable from the reported width and height.
     */
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

            JsonArray widgets = new JsonArray();
            boolean complete = collect(screen, widgets, 0);
            reply.add("widgets", widgets);
            reply.addProperty("widgetsComplete", complete);
            return reply;
        });
    }

    /**
     * Walk a container's children, appending one entry each.
     *
     * <p>Returns whether the walk finished. It stops short when the cap is hit or the depth limit
     * cuts off a container that still had children, and the caller reports that as
     * {@code widgetsComplete: false} - a truncated list that claims to be whole is how a caller
     * concludes a button does not exist.
     */
    private static boolean collect(GuiEventListener parent, JsonArray out, int depth) {
        List<? extends GuiEventListener> children;
        try {
            if (!(parent instanceof ContainerEventHandler container)) {
                return true;
            }
            children = container.children();
        } catch (Throwable t) {
            // A mod's screen deciding to throw here is its business, not a reason to fail the verb.
            return false;
        }

        boolean complete = true;
        for (GuiEventListener child : children) {
            if (out.size() >= MAX_WIDGETS) {
                return false;
            }
            out.add(describe(child, depth));
            if (depth + 1 < MAX_DEPTH) {
                complete &= collect(child, out, depth + 1);
            } else if (child instanceof ContainerEventHandler nested && !nested.children().isEmpty()) {
                complete = false;
            }
        }
        return complete;
    }

    /**
     * One widget, as much as it will say about itself.
     *
     * <p>Everything past the class name is guarded, because these are calls into arbitrary mod code:
     * a {@code getMessage} that throws would otherwise take out the whole listing, and a listing that
     * fails because one widget in a pack's quest book is unusual is worth less than a listing with
     * one thin entry in it.
     */
    private static JsonObject describe(GuiEventListener child, int depth) {
        JsonObject json = new JsonObject();
        Class<?> type = child.getClass();
        json.addProperty("type", shortName(type));
        json.addProperty("class", type.getName());
        json.addProperty("depth", depth);

        if (child instanceof AbstractWidget widget) {
            try {
                json.addProperty("text", widget.getMessage().getString());
            } catch (Throwable t) {
                json.add("text", null);
            }
            json.addProperty("active", widget.isActive());
            json.addProperty("visible", widget.visible);
            json.addProperty("focused", widget.isFocused());
            json.addProperty("hovered", widget.isHovered());
        }

        ScreenRectangle rect;
        try {
            rect = child.getRectangle();
        } catch (Throwable t) {
            rect = ScreenRectangle.empty();
        }
        if (rect.width() > 0 && rect.height() > 0) {
            json.addProperty("x", rect.left());
            json.addProperty("y", rect.top());
            json.addProperty("width", rect.width());
            json.addProperty("height", rect.height());
            json.addProperty("centerX", rect.left() + rect.width() / 2);
            json.addProperty("centerY", rect.top() + rect.height() / 2);
        } else {
            for (String key : new String[] {"x", "y", "width", "height", "centerX", "centerY"}) {
                json.add(key, null);
            }
        }
        return json;
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
     * <p>Both halves are sent, because a screen that only ever sees a press leaves a widget stuck
     * down, and the next thing to photograph it gets a button that looks pressed for no reason.
     *
     * <p><b>And both halves are reported, which the first version did not do.</b> It returned
     * {@code mouseClicked} alone and threw the release away, so a widget that acts on release did
     * its work while the verb said the click was ignored. Vanilla's {@code AbstractWidget} answers
     * the press, which is why buttons looked fine and an FTB Quests chapter tab did not: the same
     * coordinate reported differently depending on what was under it, which reads exactly like a
     * race and is not one.
     *
     * <p><b>Do not gate on the booleans alone.</b> They are what the screen chose to return, and
     * screens are inconsistent about it in both directions. Measured on a creative inventory: a
     * click on empty background reports {@code handled}, because that screen answers clicks
     * anywhere for item dragging. Reported from the other side, an FTB Quests chapter tab switched
     * chapters while reporting nothing took the click. A value that both over- and under-reports is
     * not a gate.
     *
     * <p>The reply also carries the screen before and after, which is closer to an observable fact -
     * but measured here it is still not a verdict, because <b>consequences are asynchronous</b>.
     * Clicking Respawn on the death screen reported {@code changedScreen: false} with the death
     * screen still open, and the screen was gone a fraction of a second later: the click sends a
     * packet and the screen closes when the server answers.
     *
     * <p>So nothing this verb can return synchronously is a gate, and it does not pretend otherwise.
     * It reports what it saw. A caller that needs to know whether the click worked asks afterwards -
     * poll {@code screen}, or take a picture - which is the only thing that actually observes the
     * consequence.
     */
    static JsonObject click(double x, double y, int button) throws Exception {
        return onClient(client -> {
            Screen screen = client.screen;
            if (screen == null) {
                return Handlers.error("no screen is open, so there is nothing to click");
            }
            String before = screen.getClass().getName();
            MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
            screen.mouseMoved(x, y);
            boolean onPress = screen.mouseClicked(event, false);
            boolean onRelease = screen.mouseReleased(event);
            String after = client.screen == null ? null : client.screen.getClass().getName();

            JsonObject reply = Handlers.ok();
            reply.addProperty("onPress", onPress);
            reply.addProperty("onRelease", onRelease);
            reply.addProperty("handled", onPress || onRelease);
            reply.addProperty("screenBefore", before);
            reply.addProperty("screen", after);
            reply.addProperty("changedScreen", !before.equals(after));
            return reply;
        });
    }

    /**
     * A name short enough to read and long enough to mean something.
     *
     * <p>The simple name alone is not either. Vanilla's ordinary button is {@code Button$Plain},
     * whose simple name is {@code Plain}, which names nothing; an anonymous subclass has no simple
     * name at all. So a nested class keeps its enclosing class, and an anonymous one falls back to
     * the binary name, which is ugly but identifies something.
     */
    private static String shortName(Class<?> type) {
        String simple = type.getSimpleName();
        if (simple.isEmpty()) {
            return type.getName();
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null ? simple : enclosing.getSimpleName() + "." + simple;
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
