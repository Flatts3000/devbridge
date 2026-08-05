package dev.mcbridge.devbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the shot is taken from, and what the crosshair is on. Loaded only on a client - see
 * {@link ClientHandlers}.
 *
 * <p><b>This deliberately reports nothing a command already answers.</b> Measured against a running
 * client rather than assumed: {@code /data get entity @s} returns position, rotation, on-ground, and
 * - the surprise - real {@code Motion} for a player, despite movement being client-authoritative. A
 * telemetry verb covering those would duplicate a command, and duplicating commands is how a small
 * tool stops being small. So this verb is the residue: the two things nothing else can answer.
 *
 * <p><b>The camera is not the player.</b> In third person, in spectator, or under any mod that
 * detaches the view, the screenshot is taken from somewhere the player entity is not. For a tool
 * whose main job is framing pictures, "where was this actually shot from" had no answer at all.
 *
 * <p><b>What the crosshair is on has no command.</b> There is no vanilla raycast. The nearest
 * approximation is stepping along {@code ^ ^ ^n} with {@code anchored eyes} testing each step, which
 * is painful and is not the same thing: {@code Minecraft.hitResult} is the game's own pick, so it
 * already respects reach, fluid rules and entity hitboxes. Naming an arbitrary block is not
 * command-reachable either - {@code /data get block} refuses on anything that is not a block entity -
 * so the hit carries the block's canonical {@code id[properties]} form, which is also what
 * {@code /setblock} would take back.
 */
final class Sightline {

    /**
     * Positions and distances are rounded to millimetres, angles to hundredths of a degree.
     *
     * <p>Raw doubles here are render-thread interpolation output: they carry a dozen digits of noise
     * that change every frame and mean nothing. Rounding makes two reports of a stationary camera
     * comparable, which is the whole point of reporting it.
     */
    private static final double POSITION_SCALE = 1000.0;
    private static final double ANGLE_SCALE = 100.0;

    private Sightline() {
    }

    static JsonObject look() throws Exception {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> done = new CompletableFuture<>();
        // The render thread owns both of these: hitResult is recomputed every frame, and the camera
        // is mid-interpolation anywhere else.
        client.execute(() -> {
            try {
                done.complete(collect(client));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(15, TimeUnit.SECONDS);
    }

    private static JsonObject collect(Minecraft client) {
        JsonObject reply = Handlers.ok();

        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            // Before the first frame of a level there is no camera to speak of, and reporting the
            // uninitialised default as a position would be a confident lie about where the shot came
            // from.
            reply.add("camera", null);
        } else {
            JsonObject json = new JsonObject();
            json.add("pos", vector(camera.position()));
            // Wrapped to -180..180, which is the range /data get entity @s Rotation reports, so the
            // two are comparable without the caller knowing which one wrapped.
            json.addProperty("yaw", round(camera.yaw(), ANGLE_SCALE));
            json.addProperty("pitch", round(camera.xRot(), ANGLE_SCALE));
            json.addProperty("fov", round(camera.getFov(), ANGLE_SCALE));
            json.addProperty("detached", camera.isDetached());
            Entity entity = camera.entity();
            json.addProperty("entity", entity == null ? null : entityId(entity));
            reply.add("camera", json);
        }

        reply.add("hit", hit(client, camera));
        return reply;
    }

    /**
     * What the crosshair is on, or null when the game has not picked anything yet.
     *
     * <p>A miss is reported as a hit of type {@code miss} rather than as null, because they are
     * different answers: a miss says the ray reached its limit through open air, and null says the
     * game has not run a frame to ask. Collapsing them would let a caller poll a game that is still
     * loading and conclude it is looking at nothing.
     */
    private static JsonObject hit(Minecraft client, Camera camera) {
        HitResult result = client.hitResult;
        if (result == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", result.getType().name().toLowerCase(Locale.ROOT));
        json.add("at", vector(result.getLocation()));
        if (camera.isInitialized()) {
            json.addProperty("distance",
                round(camera.position().distanceTo(result.getLocation()), POSITION_SCALE));
        }

        if (result instanceof BlockHitResult block && result.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            JsonArray at = new JsonArray();
            at.add(pos.getX());
            at.add(pos.getY());
            at.add(pos.getZ());
            json.add("pos", at);
            json.addProperty("face", block.getDirection().getName());
            json.addProperty("inside", block.isInside());
            if (client.level != null) {
                // The canonical id[properties] form, which is what /setblock takes back. A bare id
                // would not distinguish the stair that is upside down from the one that is not,
                // which is exactly the kind of thing a screenshot is being checked for.
                json.addProperty("block",
                    BlockStateParser.serialize(client.level.getBlockState(pos)));
            }
        } else if (result instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            json.addProperty("entity", entityId(entity));
            json.addProperty("name", entity.getName().getString());
            json.addProperty("uuid", entity.getUUID().toString());
            json.add("pos", vector(entity.position()));
        }
        return json;
    }

    private static String entityId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static JsonArray vector(Vec3 vec) {
        JsonArray json = new JsonArray();
        json.add(round(vec.x, POSITION_SCALE));
        json.add(round(vec.y, POSITION_SCALE));
        json.add(round(vec.z, POSITION_SCALE));
        return json;
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
