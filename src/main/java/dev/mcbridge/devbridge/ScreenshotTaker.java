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
        CompletableFuture<Void> resized = new CompletableFuture<>();
        client.execute(() -> {
            window.setWindowed(width, height);
            resized.complete(null);
        });
        resized.get(10, TimeUnit.SECONDS);

        try {
            awaitTarget(client, width, height);
            return take(name);
        } finally {
            CompletableFuture<Void> restored = new CompletableFuture<>();
            client.execute(() -> {
                window.setWindowed(previousWidth, previousHeight);
                restored.complete(null);
            });
            restored.get(10, TimeUnit.SECONDS);
        }
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
