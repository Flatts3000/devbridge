package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Right-click, from outside the game. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>Why this exists.</b> {@link ScreenDriver} can drive any GUI that is already open, and that
 * covers most of what breaks in a modpack - but nothing could OPEN one. A guide book, a quest book,
 * a machine: every one of them is behind a right-click, and a right-click was the one thing the
 * protocol had no verb for. Callers were left synthesizing OS-level mouse events, which needs the
 * game window foregrounded, silently does nothing when it is not, and cannot be run headless or on
 * a shared machine.
 *
 * <p><b>The failure it is really built to remove is a silent one.</b> An item that fails to open its
 * screen and an empty hand report exactly the same thing: no screen. So this reply always names what
 * was in hand and what the game returned, and says whether a screen appeared as a result. A caller
 * can then tell "the book is not in the selected slot" apart from "the book opened nothing", which
 * from the outside used to be indistinguishable.
 *
 * <p><b>{@link #interact} is vanilla's {@code Minecraft.startUseItem} for one hand, and the shape of
 * it is load-bearing.</b> Entity first, then block, then the item in the air - and critically, a
 * block interaction that returns {@code Pass} FALLS THROUGH to the item while a {@code Fail} stops.
 * The first version of this returned unconditionally after the block, so the documented fallback
 * could never fire: holding a book and looking at ordinary stone, which returns {@code Pass}, the
 * book simply never opened. That is the verb's flagship case failing wherever the crosshair happens
 * to land on terrain, and it passed the one test written for it because that test forced
 * {@code target: "item"}.
 */
final class HandUse {

    private HandUse() {
    }

    /**
     * How long to keep looking for a screen after the use, and how often.
     *
     * <p><b>A menu does not open synchronously and reading {@code client.screen} straight after the
     * interaction reports every one of them as absent.</b> {@code useItemOn} predicts client-side and
     * sends a packet; a container's screen arrives later on {@code ClientboundOpenScreenPacket}. So
     * a chest, a furnace or any {@code MenuProvider} machine answered {@code openedScreen: false} on
     * a use that worked perfectly - and the CLI's {@code --expect-screen} then failed the run. The
     * client-side book this verb was first tested against is the one category that happens to open
     * in the same tick, which is why the first version looked correct.
     */
    private static final int DEFAULT_WAIT_MS = 1500;
    private static final int POLL_MS = 50;

    /**
     * Use what is held, the way a right-click would.
     *
     * @param offhand use the off hand rather than the main hand
     * @param target  {@code "auto"} to mirror vanilla - entity, then block, then the item in the air -
     *                or {@code "item"} to ignore what the crosshair is on entirely
     * @param waitMs  how long to wait for a screen to appear afterwards; 0 to report immediately
     */
    static JsonObject use(boolean offhand, String target, Integer waitMs) throws Exception {
        String mode = target == null ? "auto" : target.toLowerCase(Locale.ROOT);
        if (!mode.equals("auto") && !mode.equals("item")) {
            return Handlers.error("target must be 'auto' or 'item', not '" + target + "'");
        }
        int budget = waitMs == null ? DEFAULT_WAIT_MS : Math.max(0, waitMs);

        JsonObject reply = onClient(client -> interact(client, offhand, mode));
        if (!reply.get("ok").getAsBoolean()) {
            return reply;
        }

        // Poll for the screen rather than sampling once. See DEFAULT_WAIT_MS.
        long deadline = System.currentTimeMillis() + budget;
        String screen;
        long waited = 0;
        while (true) {
            screen = currentScreen();
            if (screen != null || System.currentTimeMillis() >= deadline) {
                break;
            }
            Thread.sleep(POLL_MS);
            waited += POLL_MS;
        }
        reply.addProperty("screen", screen);
        reply.addProperty("openedScreen", screen != null);
        reply.addProperty("waitedMs", waited);
        return reply;
    }

    /** The open screen's class name, or null. Read on the render thread, like everything else here. */
    private static String currentScreen() throws Exception {
        JsonObject carrier = onClient(client -> {
            JsonObject held = Handlers.ok();
            held.addProperty("value",
                client.screen == null ? null : client.screen.getClass().getName());
            return held;
        });
        return carrier.get("value").isJsonNull() ? null : carrier.get("value").getAsString();
    }

    /**
     * One hand's worth of vanilla's {@code startUseItem}, in vanilla's order. Runs on the render
     * thread.
     */
    private static JsonObject interact(Minecraft client, boolean offhand, String mode) {
        if (client.player == null || client.gameMode == null || client.level == null) {
            return Handlers.error("no player in the world yet, so there is no hand to use");
        }
        // A CLICK WITH A GUI OPEN WOULD GO TO THE GUI, not the world, and the caller almost
        // certainly meant the world. Refusing beats quietly doing something else - and `screen`
        // plus `click` is the right pair once something is open.
        if (client.screen != null) {
            return Handlers.error("a screen is open (" + client.screen.getClass().getName()
                + "), so a use would not reach the world. Close it, or drive it with `click`.");
        }

        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack held = client.player.getItemInHand(hand);
        HitResult hit = client.hitResult;

        JsonObject reply = Handlers.ok();
        reply.addProperty("hand", offhand ? "off" : "main");
        reply.addProperty("held",
            held.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        reply.addProperty("heldName", held.isEmpty() ? null : held.getHoverName().getString());

        if (mode.equals("auto") && hit != null && hit.getType() == HitResult.Type.ENTITY
            && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (client.level.getWorldBorder().isWithinBounds(entity.blockPosition())
                && client.player.isWithinEntityInteractionRange(entity, 0.0)) {
                InteractionResult result = client.gameMode.interact(client.player, entity,
                    entityHit, hand);
                if (result instanceof InteractionResult.Success success) {
                    swing(client, hand, success, false);
                    return done(reply, "entity", result,
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                }
            }
        }

        if (mode.equals("auto") && hit != null && hit.getType() == HitResult.Type.BLOCK
            && hit instanceof BlockHitResult blockHit) {
            int before = held.getCount();
            InteractionResult result = client.gameMode.useItemOn(client.player, hand, blockHit);
            if (result instanceof InteractionResult.Success success) {
                swing(client, hand, success, !held.isEmpty()
                    && (held.getCount() != before || client.player.hasInfiniteMaterials()));
                return done(reply, "block", result,
                    client.level.getBlockState(blockHit.getBlockPos()).getBlock()
                        .getDescriptionId());
            }
            // FAIL STOPS, PASS FALLS THROUGH. Vanilla's ordering, and the half the first version
            // got wrong.
            if (result instanceof InteractionResult.Fail) {
                return done(reply, "block", result, null);
            }
        }

        if (held.isEmpty()) {
            // Nothing in hand and nothing it could be used on. Said plainly, because the caller is
            // usually one `give` away from what they wanted and a bare "nothing happened" sends
            // them looking at the item instead of at the hotbar.
            return Handlers.error("nothing in the " + (offhand ? "off" : "main")
                + " hand, so there is nothing to use. If a screen was expected, check the item "
                + "reached the SELECTED hotbar slot.");
        }
        InteractionResult result = client.gameMode.useItem(client.player, hand);
        if (result instanceof InteractionResult.Success success) {
            swing(client, hand, success, true);
        }
        return done(reply, "item", result, null);
    }

    /**
     * The arm animation and the held-item bob, on the same terms vanilla plays them.
     *
     * <p>Not cosmetic in a tool whose main output is a picture: without it a {@code use} followed by
     * a {@code shot} captures a pose the game would never actually show, and no swing packet reaches
     * the server.
     */
    private static void swing(Minecraft client, InteractionHand hand,
            InteractionResult.Success success, boolean itemUsed) {
        if (success.swingSource() == InteractionResult.SwingSource.CLIENT && client.player != null) {
            client.player.swing(hand);
            if (itemUsed && client.gameRenderer != null) {
                client.gameRenderer.itemInHandRenderer.itemUsed(hand);
            }
        }
    }

    /**
     * Finish the reply, naming the outcome precisely.
     *
     * <p><b>{@code getSimpleName()} is not enough.</b> {@code SUCCESS}, {@code SUCCESS_SERVER} and
     * {@code CONSUME} are all {@code Success} records differing only in their swing source, so it
     * reported {@code "Success"} for all three - and {@code consumesAction()} is true for exactly
     * those, so a second boolean added nothing. A caller could not tell "the client acted" from "the
     * server will act" from "consumed, no visible effect", which is the kind of distinction the rest
     * of this reply exists to preserve.
     */
    private static JsonObject done(JsonObject reply, String against, InteractionResult result,
            String what) {
        reply.addProperty("against", against);
        reply.addProperty("target", what);
        reply.addProperty("result", name(result));
        reply.addProperty("consumed", result.consumesAction());
        return reply;
    }

    private static String name(InteractionResult result) {
        if (result instanceof InteractionResult.Success success) {
            return switch (success.swingSource()) {
                case CLIENT -> "SUCCESS";
                case SERVER -> "SUCCESS_SERVER";
                case NONE -> "CONSUME";
            };
        }
        if (result instanceof InteractionResult.Fail) {
            return "FAIL";
        }
        if (result instanceof InteractionResult.Pass) {
            return "PASS";
        }
        return "TRY_WITH_EMPTY_HAND";
    }

    /**
     * Run on the render thread and wait for the answer.
     *
     * <p>Same shape as {@link ScreenDriver}'s: everything here touches client state that only that
     * thread may read, and the timeout stops a wedged client hanging the socket.
     */
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
