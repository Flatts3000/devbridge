package dev.mcbridge.devbridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.loading.FMLEnvironment;
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

    /**
     * Take the mouse on world load, so a stray hand cannot turn the camera. <b>Off</b> unless set to
     * {@code true}, unlike the other switches.
     *
     * <p>It defaults off because of who each default surprises. An unattended run knows it wants the
     * camera held still and can ask for it, either with this property or with the {@code input}
     * verb. A person who opened the game to fly around and frame something by eye has no idea a mod
     * took their mouse, and the symptom - the camera simply not moving - looks like the game is
     * broken rather than like a setting. A default should favour the case that cannot ask.
     */
    public static final String LOCK_INPUT_PROPERTY = "devbridge.lockInput";

    private static BridgeServer server;

    public DevBridge(IEventBus modBus) {
        int port = port();
        if (port <= 0) {
            LOGGER.info("devbridge is present but idle: set -D{}=<port> to enable it", PORT_PROPERTY);
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        // ON A CLIENT, OPEN NOW RATHER THAN WAITING FOR A WORLD.
        //
        // This used to wait for ServerStarted, reasoning that `cmd` needs a MinecraftServer and
        // that a socket which accepts connections it cannot serve is worse than one not yet open.
        // The first half is still true and `cmd` still refuses without a world. The second half was
        // wrong in one specific way: half the verbs - screen, click, cursor, key, screenshot, hud,
        // input - drive the CLIENT and never needed a server for anything except asking whether a
        // client existed. Withholding the socket until a world loaded made them unreachable at the
        // one screen every session begins on.
        //
        // That is not hypothetical. A pack whose title screen is replaced by FancyMenu swallows
        // --quickPlaySingleplayer, so the game sits at the menu, no world ever loads, and the tool
        // that exists to drive GUIs cannot connect to click the button that would load one.
        //
        // A socket that serves what it can and says plainly what it cannot beats a refused
        // connection, which is indistinguishable from a game that has not finished starting.
        if (isClient()) {
            server = new BridgeServer(port, null);
            server.start();
        }
    }

    /**
     * Whether a GUI can be driven here, asked before any world exists.
     *
     * <p>This is the one place an FML dist lookup is right, and the class note about preferring
     * {@code isDedicatedServer} does not apply: that answer comes from a
     * {@code MinecraftServer}, and the entire point of this check is that it runs before one is
     * created.
     *
     * <p>It also may not ask the classloader. A first attempt used
     * {@code Class.forName("net.minecraft.client.Minecraft", false, ...)}, which is safe in
     * itself - the name is a string and nothing is initialised - but `check_invariants.sh` greps
     * for client classes named outside the client-only files and failed the build. The check
     * cannot tell a string from a type reference, and a guard that has to reason about which
     * mentions are safe is not a guard. The dist is the same answer without the argument.
     */
    private static boolean isClient() {
        return FMLEnvironment.getDist().isClient();
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
     * <p>Each switch carries its own {@code unset} default rather than sharing one, because the two
     * answer different questions. Keeping the world ticking is what makes a client answer at all
     * from outside the window, so it is on and the common case needs no arguments. Taking the mouse
     * only helps a run that already knows it wants the camera held still, and that run can ask; so
     * it is off, and a person who did not ask keeps their mouse.
     *
     * <p>Unlike {@link #PORT_PROPERTY} these are ergonomics rather than the security boundary,
     * which is why they are options at all and the bind address is not.
     *
     * <p>Only an exact {@code true} or {@code false} is honoured. Anything else keeps the default
     * and says so, because silently flipping a switch on a misspelling is how somebody spends an
     * afternoon wondering why their screenshots are of the pause menu.
     */
    private static boolean flag(String property, boolean unset) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return unset;
        }
        String trimmed = value.trim();
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        LOGGER.warn("{} is not true or false: '{}'. Leaving it {}.",
            property, value, unset ? "on" : "off");
        return unset;
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Before the socket check, so it is reasserted on every world load rather than only the
        // first. Both settings are per-instance and free to set again, and a caller who pressed
        // F3+P or unlocked the mouse in between should not have to work out why the tool went quiet
        // on the next world.
        ClientHandlers.prepareForRemoteControl(
            event.getServer(),
            flag(KEEP_TICKING_PROPERTY, true),
            flag(LOCK_INPUT_PROPERTY, false));

        if (server != null) {
            // Singleplayer opens and closes worlds repeatedly; keep the first socket and hand it
            // the new world. On a client the socket was already open before any world existed.
            server.attach(event.getServer());
            return;
        }
        server = new BridgeServer(port(), event.getServer());
        server.start();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (server != null && isClient()) {
            // Leaving a world returns a client to the title screen, and the socket outlives that:
            // drop the world, keep listening. Closing here would make quitting to the menu look
            // exactly like the game having exited, and would strand a caller that wanted to load a
            // different world next.
            server.attach(null);
        } else if (server != null) {
            server.shutdown();
            server = null;
        }
    }
}
