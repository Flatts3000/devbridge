package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Waits until the client has finished drawing what a command just placed. Loaded only on a client -
 * see {@link ClientHandlers}.
 *
 * <p><b>This replaces a guess.</b> Every screenshot script so far has done some version of
 * {@code sleep(2)} with a comment about letting chunk meshes rebuild. Too short and the capture
 * catches a half-built world; too long and every shoot pays for it. Neither is detectable
 * afterwards, because a partly-meshed chunk photographs as a hole rather than as an error.
 *
 * <p>There is no server-side signal for this. The server considers a {@code setblock} finished the
 * instant it happens, which is exactly the moment the mesh has <i>not</i> been rebuilt, so waiting
 * on a command's reply proves nothing about what is on screen.
 *
 * <p><b>Two conditions, because either alone is a lie.</b> An empty compile queue with no frame
 * drawn since means the work is done but not yet shown. Frames alone say nothing about whether
 * there is outstanding work. So this waits for the queue to be empty across several consecutive
 * frames.
 *
 * <p><b>It says when it could not see the queue.</b> Sodium and friends replace the chunk renderer
 * wholesale, and vanilla's dispatcher is then absent or permanently idle. Rather than report a
 * confident "settled" that means nothing, the reply carries {@code queue} as {@code "empty"} or
 * {@code "unavailable"}, and an unavailable queue degrades this to a frame wait that says so.
 */
final class Settle {

    /** Written on the render thread, read on the socket thread. */
    private static volatile long frames;

    /** Compile queue depth as of the last frame, or -1 when there is no dispatcher to ask. */
    private static volatile int queueDepth = -1;

    private static boolean listening;

    private Settle() {
    }

    static JsonObject settle(long timeoutMs, int quietFrames) throws Exception {
        listen();

        long start = System.nanoTime();
        long deadline = start + timeoutMs * 1_000_000L;
        long lastSeen = frames;
        int quiet = 0;
        int peak = 0;

        while (System.nanoTime() < deadline) {
            long now = frames;
            if (now != lastSeen) {
                lastSeen = now;
                peak = Math.max(peak, queueDepth);
                // -1 is "no dispatcher to ask", which counts as quiet: with a replaced chunk
                // renderer there is nothing here to wait on, and the reply says so.
                quiet = queueDepth <= 0 ? quiet + 1 : 0;
                if (quiet >= quietFrames) {
                    return reply(true, start, quietFrames, peak);
                }
            }
            Thread.sleep(2);
        }
        return reply(false, start, quiet, peak);
    }

    private static JsonObject reply(boolean settled, long startNanos, int quiet, int peak) {
        JsonObject out = Handlers.ok();
        out.addProperty("settled", settled);
        out.addProperty("waitedMs", (System.nanoTime() - startNanos) / 1_000_000L);
        out.addProperty("quietFrames", quiet);
        // Reporting how long it waited is the point of returning a number at all: a caller that
        // sees 4000ms learns something the sleep it replaced would have hidden either way.
        out.addProperty("queue", queueDepth < 0 ? "unavailable" : "empty");
        // The deepest the queue got while waiting. A zero here means this call never saw any work,
        // which is worth knowing: it usually means it was asked too early, before the client had
        // processed the block updates the command caused, and the wait proved nothing.
        out.addProperty("maxQueue", peak);
        if (!settled) {
            out.addProperty("error", "timed out before the chunk queue went quiet");
        }
        return out;
    }

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        // Registered on first use, like the input lock: a mod that is present but idle should not
        // be sampling the renderer every frame for nobody.
        NeoForge.EVENT_BUS.addListener(Settle::onFrame);
        listening = true;
    }

    private static void onFrame(RenderFrameEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        SectionRenderDispatcher dispatcher = client.levelRenderer == null
            ? null
            : client.levelRenderer.getSectionRenderDispatcher();
        queueDepth = dispatcher == null ? -1 : dispatcher.getCompileQueueSize();
        frames++;
    }
}
