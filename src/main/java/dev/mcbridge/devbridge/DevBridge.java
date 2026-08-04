package dev.mcbridge.devbridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A development-only bridge: drives a running Minecraft instance from outside.
 *
 * <p><b>Why this exists.</b> A dedicated server can be driven over RCON and needs no mod at all, so
 * where RCON works it stays the answer. It cannot do two things, and both are structural. A
 * <b>singleplayer</b> world's integrated server does not listen on a socket, so there is nothing to
 * connect to. And a <b>screenshot</b> is a client concern: the dedicated server has no framebuffer to
 * capture. The second is the one worth building for - it turns "change a number, rebuild, reshoot,
 * look" from a round trip through a human into one command.
 *
 * <p><b>OFF UNLESS ASKED.</b> Nothing happens without {@code -Ddevbridge.port=<n>} on the command
 * line. A jar sitting in a mods folder opens no socket and registers no handler, which is the only
 * safe default for something whose whole job is executing arbitrary commands.
 *
 * <p><b>Loopback only, and not configurable.</b> See {@link BridgeServer}. This is remote code
 * execution by design and the bind address is the entire security boundary.
 *
 * <p><b>Never ship this.</b> It is not a dependency of anything released. It lives in a dev run's
 * mods folder, and the reason it is a separate jar rather than a source set inside a mod is that a
 * separate jar cannot accidentally end up in a release.
 */
@Mod(DevBridge.MOD_ID)
public class DevBridge {

    public static final String MOD_ID = "devbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger("devbridge");

    /** The system property that turns the whole thing on, and the port it listens on. */
    public static final String PORT_PROPERTY = "devbridge.port";

    /**
     * Keep the world ticking while the window is in the background. On unless set to {@code false}.
     */
    public static final String KEEP_TICKING_PROPERTY = "devbridge.keepTicking";

    /** Take the mouse on world load, so a stray hand cannot turn the camera. On unless {@code false}. */
    public static final String LOCK_INPUT_PROPERTY = "devbridge.lockInput";

    private static BridgeServer server;

    public DevBridge(IEventBus modBus) {
        int port = port();
        if (port <= 0) {
            LOGGER.info("devbridge is present but idle: set -D{}=<port> to enable it", PORT_PROPERTY);
            return;
        }
        // Started on ServerStarted rather than here, because `cmd` needs a MinecraftServer to run
        // against and construction happens long before one exists. A socket that accepts connections
        // it cannot serve is worse than one that is not open yet.
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private static int port() {
        try {
            return Integer.parseInt(System.getProperty(PORT_PROPERTY, "0").trim());
        } catch (NumberFormatException e) {
            LOGGER.error("{} is not a number: {}", PORT_PROPERTY, System.getProperty(PORT_PROPERTY));
            return 0;
        }
    }

    /**
     * A behaviour switch, on unless explicitly turned off.
     *
     * <p>These default to on because they are what makes a client answer at all from outside the
     * window, so the common case should need no arguments. Unlike {@link #PORT_PROPERTY} they are
     * ergonomics rather than the security boundary, which is why they are options and the bind
     * address is not.
     *
     * <p>Only {@code false} turns one off. Anything else is a typo that leaves the default in place
     * and says so, because silently disabling a default on a misspelling is how somebody spends an
     * afternoon wondering why their screenshots are of the pause menu.
     */
    private static boolean flag(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if (!"true".equalsIgnoreCase(trimmed)) {
            LOGGER.warn("{} is not true or false: '{}'. Leaving it on.", property, value);
        }
        return true;
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Before the socket check, so it is reasserted on every world load rather than only the
        // first. Both settings are per-instance and free to set again, and a caller who pressed
        // F3+P or unlocked the mouse in between should not have to work out why the tool went quiet
        // on the next world.
        ClientHandlers.prepareForRemoteControl(
            event.getServer(), flag(KEEP_TICKING_PROPERTY), flag(LOCK_INPUT_PROPERTY));

        if (server != null) {
            return;   // singleplayer opens and closes worlds repeatedly; keep the first socket
        }
        server = new BridgeServer(port(), event.getServer());
        server.start();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }
}
