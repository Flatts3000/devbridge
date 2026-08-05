package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Takes the picture. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>The reply waits for the file, not for the request.</b> {@code Screenshot.grab} hands back
 * immediately and writes on a worker; replying at that point would give a tool a path to a file that
 * does not exist yet, which is the class of bug that only shows up when something reads the file
 * straight after. The future is completed from grab's own callback instead.
 *
 * <p>Approach ported from Jeroen-45/screenshot-bot (MIT), which queues the request and captures on
 * the client thread rather than from the socket. Its socket binds every interface; ours does not.
 */
final class ScreenshotTaker {

    private ScreenshotTaker() {
    }

    /**
     * Show or hide the HUD.
     *
     * <p>Its own verb rather than a flag on {@code screenshot}, because {@code Screenshot.grab}
     * captures the framebuffer as it already is: hiding the HUD and grabbing in the same call would
     * still catch the frame that was drawn with it. The caller hides it, takes the shot, and shows it
     * again, which also lets several shots share one toggle.
     */
    static JsonObject hud(boolean show) throws Exception {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<Void> applied = new CompletableFuture<>();
        client.execute(() -> {
            client.options.hideGui = !show;
            applied.complete(null);
        });
        applied.get(10, TimeUnit.SECONDS);
        JsonObject reply = Handlers.ok();
        reply.addProperty("hudVisible", show);
        return reply;
    }

    /**
     * Capture at an exact size, by resizing the window and putting it back.
     *
     * <p><b>Not an offscreen render, and the difference is worth knowing.</b> {@code Screenshot.grab}
     * captures whatever is already in a render target, so handing it a bigger one would produce a
     * bigger picture of nothing: the scene has to be drawn at that size first. Vanilla has no way to
     * render the level into an arbitrary target on demand, so the honest mechanism is to make the
     * real framebuffer that size for a moment. The window visibly changes shape while this runs.
     *
     * <p>The wait is on the framebuffer's own dimensions rather than on a frame count. A resize is
     * asynchronous - {@code setWindowed} asks the OS and the framebuffer follows some frames later -
     * so grabbing too early captures the old size, which is exactly the bug this verb exists to
     * remove. Unlike waiting for chunk meshing, "has the target reached this width" is directly
     * observable, so this needs no heuristic.
     */
    static JsonObject take(String name, int width, int height) throws Exception {
        Minecraft client = Minecraft.getInstance();
        Window window = client.getWindow();

        if (window.isFullscreen()) {
            return Handlers.error("cannot resize a fullscreen window: leave fullscreen, or take the "
                + "shot at whatever size it already is");
        }

        int previousWidth = window.getWidth();
        int previousHeight = window.getHeight();
        CompletableFuture<int[]> before = new CompletableFuture<>();
        CompletableFuture<Void> resized = new CompletableFuture<>();
        client.execute(() -> {
            // Read the layout before asking for the resize, in the same task, so nothing can lay the
            // screen out again between the two.
            before.complete(layout(client));
            window.setWindowed(width, height);
            resized.complete(null);
        });
        resized.get(10, TimeUnit.SECONDS);

        try {
            awaitTarget(client, width, height);
            CompletableFuture<int[]> during = new CompletableFuture<>();
            client.execute(() -> during.complete(layout(client)));
            int[] captured = during.get(10, TimeUnit.SECONDS);

            JsonObject reply = take(name);
            reportRelayout(reply, before.get(10, TimeUnit.SECONDS), captured);
            return reply;
        } finally {
            CompletableFuture<Void> restored = new CompletableFuture<>();
            client.execute(() -> {
                window.setWindowed(previousWidth, previousHeight);
                restored.complete(null);
            });
            restored.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * The open screen's GUI-scaled size, or a marker that nothing is open. Render thread only.
     *
     * <p>An int array rather than an object because it crosses a future twice and one of the two
     * readings is taken in the middle of the task that starts the resize.
     */
    private static int[] layout(Minecraft client) {
        net.minecraft.client.gui.screens.Screen screen = client.screen;
        return screen == null
            ? new int[] {0, 0, 0}
            : new int[] {1, screen.width, screen.height};
    }

    /**
     * Say so when the resize moved the GUI, and say what it moved to.
     *
     * <p><b>Resizing for a capture silently changes the answer for a GUI shot.</b> The window is a
     * different shape for the moment of the capture, so the screen is laid out again and the GUI
     * scale can change with it. Measured: a screen reporting 480x270 inside a 1920x1080 window,
     * captured at 1024x768, put the slot under the cursor somewhere else - and coordinates read off
     * that image pointed at the wrong place when fed back, because they were measured in a layout
     * that existed only during the capture. The world-shot path, which is the common one, is
     * unaffected: there is no layout to move.
     *
     * <p>So the reply carries both layouts. Not so they can be converted between - a screen
     * re-laying-out is not a scale, widgets move relative to each other - but so a caller measuring
     * off the image can see that the thing it measured is not the thing that is on screen now. The
     * answer to "where do I click" is {@code screen}'s widget bounds, which are reported in the live
     * layout and need no measuring at all.
     *
     * <p>Nothing is added when no screen was open, because a flag that is always present on the
     * common path is noise rather than a warning.
     */
    private static void reportRelayout(JsonObject reply, int[] before, int[] during) {
        if (before[0] == 0 && during[0] == 0) {
            return;
        }
        boolean moved = before[1] != during[1] || before[2] != during[2];
        reply.addProperty("guiRelayout", moved);
        JsonObject was = new JsonObject();
        was.addProperty("width", before[1]);
        was.addProperty("height", before[2]);
        reply.add("screenBefore", was);
        JsonObject now = new JsonObject();
        now.addProperty("width", during[1]);
        now.addProperty("height", during[2]);
        reply.add("screenAtCapture", now);
    }

    /** Block until the main render target actually reports the size we asked for. */
    private static void awaitTarget(Minecraft client, int width, int height) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            RenderTarget target = client.getMainRenderTarget();
            if (target.width == width && target.height == height) {
                return;
            }
            Thread.sleep(10);
        }
        // The window manager may refuse a size: too large for the display, or below the minimum
        // setWindowed clamps to. Saying so beats returning a picture of the wrong dimensions.
        RenderTarget target = client.getMainRenderTarget();
        throw new IllegalStateException("the window did not reach " + width + "x" + height
            + "; it is " + target.width + "x" + target.height
            + ". The display may be smaller than that, or the size below the window minimum.");
    }

    static JsonObject take(String name) throws Exception {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<String> written = new CompletableFuture<>();

        // Queued onto the render thread: grabbing the framebuffer from the socket thread reads a
        // target that another thread is drawing into.
        client.execute(() -> {
            try {
                Screenshot.grab(
                    client.gameDirectory,
                    name == null || name.isBlank() ? null : name + ".png",
                    client.getMainRenderTarget(),
                    1,
                    component -> written.complete(component.getString()));
            } catch (Throwable t) {
                written.completeExceptionally(t);
            }
        });

        String message = written.get(30, TimeUnit.SECONDS);
        // ABSOLUTE, always. In a Gradle dev run `gameDirectory` is the relative path `run`, so the
        // reply used to hand back a relative "screenshots/<name>.png", which only opens if the
        // caller happens to share the game's working directory. A tool on the other end of a socket
        // does not, and the failure is a FileNotFoundError pointing at a file that plainly exists.
        java.nio.file.Path shots =
            client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("screenshots");
        JsonObject reply = Handlers.ok();
        reply.addProperty("message", message);
        reply.addProperty("dir", shots.toString());
        if (name != null && !name.isBlank()) {
            reply.addProperty("path", shots.resolve(name + ".png").toString());
        }
        return reply;
    }
}
