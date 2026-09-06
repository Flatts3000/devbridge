package dev.mcbridge.devbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Press a key, from outside the game. Loaded only on a client - see {@link ClientHandlers}.
 *
 * <p><b>Why this exists.</b> A keybind was the last input a mod could ship and not test. {@code cmd}
 * reaches the server, {@code click} drives a GUI widget, {@code use} and {@code mine} reach the
 * world - and a mod whose feature is bound to a key had no call to make at all. The seam that goes
 * untested is a real one: a key mapping registered in the wrong category, bound to a key another mod
 * already owns, or never polled because its tick handler is on the wrong bus, all compile, all pass
 * every headless test, and all mean nothing happens when a player presses it.
 *
 * <p><b>It does what vanilla's own keyboard handler does, which is the point.</b>
 * {@code KeyboardHandler} presses a key with {@code KeyMapping.set(key, true)} followed by
 * {@code KeyMapping.click(key)}, and releases it with {@code set(key, false)}. This calls the same
 * two statics on the render thread. {@code click} is the half that matters for a tap: it increments
 * the count that {@code consumeClick} drains, which is how nearly every mod reads a keybind, and a
 * press that only set the held flag would look right and do nothing.
 *
 * <p><b>Unlike {@link Mining}, faking the input IS the faithful path here.</b> That verb could not
 * hold the attack key because {@code Minecraft.tick} gates a held attack on
 * {@code mouseHandler.isMouseGrabbed()} and this bridge never grabs the mouse. Keybinds have no such
 * gate: a mod polls {@code consumeClick()} on its own client tick whatever the window is doing. So
 * the two verbs look inconsistent and are not - each drives the game at the lowest level that is
 * actually reached when a player does the thing.
 *
 * <p><b>It reports what is bound to the key, and that is the useful half of the answer.</b> A press
 * that reaches nothing is the failure worth catching, and it is indistinguishable from a press that
 * worked unless the reply says which mappings the key actually owns. An empty list is a finding.
 */
final class Keys {

    private Keys() {
    }

    /**
     * Press and release a key.
     *
     * @param name  a key name as vanilla spells it, e.g. {@code key.keyboard.v}. A bare letter or
     *              digit is accepted too and expanded, because {@code v} is what a person types.
     * @param holdTicks how many client ticks to hold it down before releasing. Zero is a tap, which
     *              is what a keybind read through {@code consumeClick} wants; a mapping read with
     *              {@code isDown()} needs at least one tick to see it held.
     * @param checkOnly report what the key is bound to and press nothing. <b>Asking what a key
     *              already owns is the reason to reach for this verb before choosing a default
     *              binding</b>, and finding out by pressing it means firing whatever is there.
     */
    static JsonObject press(String name, Integer holdTicks, boolean checkOnly)
            throws Exception {
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(expand(name));
        } catch (IllegalArgumentException e) {
            return Handlers.error("no such key: " + name + ". Vanilla spells them "
                + "key.keyboard.<name> and key.mouse.<name>; a bare letter or digit is expanded for "
                + "you. A key with no name here is one no keyboard sends.");
        }
        int hold = holdTicks == null ? 0 : Math.max(0, holdTicks);

        JsonObject reply = onClient(client -> {
            JsonObject bound = new JsonObject();
            JsonArray mappings = new JsonArray();
            for (String id : boundTo(client, key)) {
                mappings.add(id);
            }
            bound.add("boundTo", mappings);
            if (!checkOnly) {
                KeyMapping.set(key, true);
                KeyMapping.click(key);
            }
            return bound;
        });

        if (!checkOnly) {
            if (hold > 0) {
                Thread.sleep(Math.max(50L, hold * 50L));
            }
            onClient(client -> {
                KeyMapping.set(key, false);
                return Handlers.ok();
            });
        }

        reply.addProperty("ok", true);
        reply.addProperty("key", key.getName());
        reply.addProperty("heldTicks", checkOnly ? 0 : hold);
        reply.addProperty("pressed", !checkOnly);
        return reply;
    }

    /** Every key mapping currently bound to this key, by its translation key. */
    private static List<String> boundTo(Minecraft client, InputConstants.Key key) {
        List<String> names = new ArrayList<>();
        for (KeyMapping mapping : client.options.keyMappings) {
            if (mapping.getKey().equals(key)) {
                names.add(mapping.getName());
            }
        }
        return names;
    }

    /**
     * {@code v} means {@code key.keyboard.v}.
     *
     * <p>A convenience with a real reason: the caller is a person at a shell writing what they would
     * press, and making them spell the internal name is how a verb goes unused.
     */
    private static String expand(String name) {
        if (name.startsWith("key.")) {
            return name;
        }
        return "key.keyboard." + name.toLowerCase(java.util.Locale.ROOT);
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
