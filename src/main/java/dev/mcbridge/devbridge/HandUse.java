package dev.mcbridge.devbridge;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
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
 * <p><b>Ordering mirrors vanilla's own.</b> {@code Minecraft.startUseItem} tries the thing under the
 * crosshair first and the item in the air second, so a machine opens when you are looking at it and
 * a book opens when you are not. This does the same by default. {@code target: "item"} forces the
 * second case, which is what you want for a book while standing in front of something interactive -
 * otherwise the block wins and the reply is about the wrong thing entirely.
 */
final class HandUse {

    private HandUse() {
    }

    /**
     * Use what is held, the way a right-click would.
     *
     * @param offhand use the off hand rather than the main hand
     * @param target  {@code "auto"} to prefer whatever the crosshair is on, {@code "item"} to ignore
     *                it and use the item in the air
     */
    static JsonObject use(boolean offhand, String target) throws Exception {
        String mode = target == null ? "auto" : target.toLowerCase(Locale.ROOT);
        if (!mode.equals("auto") && !mode.equals("item")) {
            return Handlers.error("target must be 'auto' or 'item', not '" + target + "'");
        }
        return onClient(client -> {
            if (client.player == null || client.gameMode == null) {
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
            reply.addProperty("held", held.isEmpty() ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem())
                    .toString());
            reply.addProperty("heldName", held.isEmpty() ? null : held.getHoverName().getString());

            String screenBefore = null;   // always null here: refused above if one was open
            InteractionResult result;
            String against;
            if (mode.equals("auto") && hit instanceof BlockHitResult block
                && hit.getType() == HitResult.Type.BLOCK) {
                result = client.gameMode.useItemOn(client.player, hand, block);
                against = "block";
            } else {
                if (held.isEmpty()) {
                    // Nothing in hand and nothing to use it on. Said plainly, because the caller is
                    // usually one `give` away from what they wanted and a bare "nothing happened"
                    // sends them looking at the item instead of at the hotbar.
                    return Handlers.error("nothing in the " + (offhand ? "off" : "main")
                        + " hand, so there is nothing to use. If a screen was expected, check the "
                        + "item reached the SELECTED hotbar slot.");
                }
                result = client.gameMode.useItem(client.player, hand);
                against = "item";
            }

            reply.addProperty("against", against);
            reply.addProperty("result", result.getClass().getSimpleName());
            reply.addProperty("consumed", result.consumesAction());
            reply.addProperty("screenBefore", screenBefore);
            reply.addProperty("screen",
                client.screen == null ? null : client.screen.getClass().getName());
            reply.addProperty("openedScreen", client.screen != null);
            return reply;
        });
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
