package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
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
        JsonObject reply = Handlers.ok();
        reply.addProperty("message", message);
        reply.addProperty("dir", client.gameDirectory.toPath().resolve("screenshots").toString());
        if (name != null && !name.isBlank()) {
            reply.addProperty("path", client.gameDirectory.toPath()
                .resolve("screenshots").resolve(name + ".png").toString());
        }
        return reply;
    }
}
