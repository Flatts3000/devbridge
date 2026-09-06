package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Left-click and hold, from outside the game. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>Why this exists.</b> {@link HandUse} gave the protocol a right-click that reaches the world,
 * and {@link ScreenDriver} drives any GUI that is open. Between them the one thing left with no verb
 * at all was the most common interaction in Minecraft: holding left mouse on a block. {@code click}
 * is a GUI-widget driver and says so - it refuses outright when no screen is open - so anything
 * about breaking a block was untestable from outside.
 *
 * <p><b>The gap was found by a mod that could not test its own feature.</b> An auto-swap that puts
 * the right tool in your hand when you start mining is verifiable in a GameTest only up to the point
 * where the swap happens: a mock player's destroy loop is not driven by client packets, so the block
 * never breaks there however correct the code is. A real client would have answered it in one call,
 * and there was no call to make. That is the shape of every question this verb unblocks - tool
 * speed, durability, drops, dig progress, anything where the answer is "did the block actually go".
 *
 * <p><b>It drives the game mode's destroy loop, and the obvious alternative cannot work here.</b>
 * Holding the real {@code keyAttack} binding is what a held mouse button IS, and it was the first
 * implementation for exactly that reason. It breaks nothing and mines nothing:
 * {@code Minecraft.tick} calls {@code continueAttack} only when
 * {@code this.mouseHandler.isMouseGrabbed()}, and the mouse is grabbed only while the window is
 * focused and in game. An automation tool is used precisely when nobody is looking at the window,
 * and devbridge's own {@code lockInput} deliberately never grabs the mouse at all. So the faithful
 * path is closed to the one caller that needs it. Verified rather than reasoned: the key held for
 * five seconds against stone with a netherite pickaxe and the block did not move.
 *
 * <p>So this calls {@code startDestroyBlock} once and {@code continueDestroyBlock} once per client
 * tick, which is what {@code continueAttack} does with the button down. What is skipped is the miss
 * timer, the swing animation and the ordering against item use - none of which affect whether a
 * block breaks, and all of which would otherwise make the verb depend on window focus.
 */
final class Mining {

    private Mining() {
    }

    /** How long to hold before giving up, and how often to look at the block. */
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int POLL_MS = 50;

    /**
     * Hold left mouse at whatever the crosshair is on until it breaks.
     *
     * @param timeoutMs how long to keep holding; the key is always released afterwards
     */
    static JsonObject mine(Integer timeoutMs) throws Exception {
        int budget = timeoutMs == null ? DEFAULT_TIMEOUT_MS : Math.max(0, timeoutMs);

        JsonObject target = onClient(Mining::resolveTarget);
        if (!target.get("ok").getAsBoolean()) {
            return target;
        }
        final BlockPos pos = new BlockPos(
            target.get("x").getAsInt(), target.get("y").getAsInt(), target.get("z").getAsInt());
        String before = target.get("block").getAsString();
        // Reassigned when the crosshair moves onto a different block mid-hold.

        net.minecraft.core.Direction face = net.minecraft.core.Direction.valueOf(
            target.get("face").getAsString());

        long started = System.currentTimeMillis();
        long heldMs = 0;
        boolean broke = false;
        BlockPos broken = pos;
        digging = pos;
        try {
            onClient(client -> {
                client.gameMode.startDestroyBlock(pos, face);
                return Handlers.ok();
            });
            ensureListening();
            DIGGING = new Dig(pos, face);

            long deadline = started + budget;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS);
                // WATCHES WHAT IS BEING DUG NOW, not what was asked for. With the crosshair moved,
                // those differ, and the first is the one the answer is about.
                BlockPos watching = digging;
                if (watching == null) {
                    watching = pos;
                }
                if (!watching.equals(broken)) {
                    broken = watching;
                    before = blockAt(watching);
                }
                String now = blockAt(watching);
                // NOT "is it air". A block can break into another block - gravel falling, a bed
                // half going, a plant dropping to air and back - and a caller asking "did I break
                // it" means "is the thing I was hitting gone".
                if (!now.equals(before)) {
                    broke = true;
                    break;
                }
            }
            // Snapshotted here, before the stop round-trip and the blockNow read below. Those cost
            // two more hops through the render thread, and this number is sold as how long a tool
            // took - on a fast break they are most of it.
            heldMs = System.currentTimeMillis() - started;
        } finally {
            digging = null;
            // ALWAYS. A dig left running keeps breaking blocks for as long as the client does, and
            // there is no verb to stop it: the caller would have to close the game. An exception on
            // the poll thread must not be able to leave one going.
            DIGGING = null;
            onClient(client -> {
                client.gameMode.stopDestroyBlock();
                return Handlers.ok();
            });
        }

        JsonObject reply = Handlers.ok();
        // The block actually dug, which is the one asked for unless the crosshair moved.
        reply.addProperty("x", broken.getX());
        reply.addProperty("y", broken.getY());
        reply.addProperty("z", broken.getZ());
        reply.addProperty("block", before);
        reply.addProperty("blockNow", blockAt(broken));
        reply.addProperty("aimedAt", pos.toShortString());
        reply.addProperty("held", target.get("held").getAsString());
        reply.addProperty("broke", broke);
        reply.addProperty("heldMs", heldMs);
        return reply;
    }

    /** What the crosshair is on, plus the refusals that would otherwise be silent. Render thread. */
    private static JsonObject resolveTarget(Minecraft client) {
        if (client.player == null || client.gameMode == null || client.level == null) {
            return Handlers.error("no player in the world yet, so there is nothing to mine");
        }
        // A held mouse button with a GUI open goes to the GUI. Refusing beats quietly doing
        // something else, which is the same call `use` makes.
        if (client.screen != null) {
            return Handlers.error("a screen is open (" + client.screen.getClass().getName()
                + "), so mining would not reach the world. Close it, or drive it with `click`.");
        }
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return Handlers.error("the crosshair is not on a block, so there is nothing to break. "
                + "`look` reports what it is on; move or aim first.");
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        JsonObject found = Handlers.ok();
        found.addProperty("x", pos.getX());
        found.addProperty("y", pos.getY());
        found.addProperty("z", pos.getZ());
        found.addProperty("face", ((BlockHitResult) hit).getDirection().name());
        found.addProperty("block",
            BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString());
        found.addProperty("held",
            BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString());
        return found;
    }

    /** One dig in progress. Null when nothing is being mined. */
    private record Dig(BlockPos pos, net.minecraft.core.Direction face) {
    }

    /**
     * The block the tick handler last actually dug, which is not always the one asked for.
     *
     * <p>The hold follows the crosshair, so the block that breaks can be one the caller never named.
     * Reporting the position resolved at the start would then describe a block that is still
     * standing and say {@code broke: false} about a call that destroyed something - the exact shape
     * of wrong answer this tool exists to avoid.
     */
    private static volatile BlockPos digging;

    private static volatile Dig DIGGING;
    private static boolean listening;

    /**
     * Registered on first use rather than at construction, the way {@link InputLock} does it:
     * nothing here is wanted by a mod that is present but idle, and {@link DevBridge} cannot
     * register it itself without naming a client-only event class.
     */
    private static synchronized void ensureListening() {
        if (!listening) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(Mining::onClientTick);
            listening = true;
        }
    }

    /**
     * Continue the dig, once per client tick, which is the rate {@code continueAttack} uses.
     *
     * <p>Driven from the tick rather than from the polling thread on purpose. Calling it on a timer
     * would advance the dig at whatever rate the poll happens to run at, and anyone measuring how
     * long a tool takes would be measuring this class instead of the tool.
     */
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Dig dig = DIGGING;
        if (dig == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null || client.level == null || client.player == null) {
            return;
        }
        // FOLLOWS THE CROSSHAIR, because that is what a held mouse button does. The first version
        // pinned the block picked when the verb was called and kept sending that position, and it
        // was wrong in a way that only shows up in the one test worth running: point at a block,
        // hold, and turn. Vanilla's continueAttack re-reads minecraft.hitResult every tick and digs
        // whatever is under the crosshair NOW, so switching target mid-hold is ordinary play - and
        // a pinned target made it unreproducible, which is how a real bug in a mod under test
        // stayed hidden behind a tool that could not express the thing that triggered it.
        //
        // Falls back to the original block when the crosshair is on nothing, rather than stopping:
        // sweeping past a gap should not silently end the dig.
        if (client.hitResult instanceof BlockHitResult hit
                && client.hitResult.getType() == HitResult.Type.BLOCK) {
            digging = hit.getBlockPos().immutable();
            client.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
        } else {
            digging = dig.pos();
            client.gameMode.continueDestroyBlock(dig.pos(), dig.face());
        }
    }

    /** The block at a position, read on the render thread like every other level access here. */
    private static String blockAt(BlockPos pos) throws Exception {
        JsonObject carrier = onClient(client -> {
            JsonObject held = Handlers.ok();
            held.addProperty("value", client.level == null ? "" : BuiltInRegistries.BLOCK
                .getKey(client.level.getBlockState(pos).getBlock()).toString());
            return held;
        });
        return carrier.get("value").getAsString();
    }

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
