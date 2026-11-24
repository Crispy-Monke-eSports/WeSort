package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import baritone.api.pathing.goals.Goal;
import com.porter.wesort.WeSortAddon;
import com.porter.wesort.inventory.NavigationWatchdog;
import com.porter.wesort.inventory.NavigationRecovery;
import com.porter.wesort.inventory.PortalNavigationPolicy;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.stash.StashIndex;
import com.porter.wesort.utils.BaritoneHelper;
import com.porter.wesort.utils.PortalEntry;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;

/** Limits only goals started by WeSort. A failure requires an explicit player resume. */
public final class NavigationRuntime {
    private static final NavigationRuntime INSTANCE = new NavigationRuntime();
    private static Goal ours;
    private static NavigationWatchdog watchdog;
    private static boolean paused;
    private static boolean portalApproachGoal;
    private static boolean pausedPortalRoute;
    private static Object portalStartLevel;
    private static String portalStartDimension = "";
    private static String status = "IDLE";
    // Engine-dropped goals are reasserted a few times before latching; only an
    // operator-replaced goal latches at once. Counts and grace reset on every register.
    private static int dropRetries;
    private static long reassertGraceUntil;
    private static long lastDropMs;
    // Position at register time: a goal the engine drops before the bot moved at all
    // (sealed start, e.g. walled in) latches immediately instead of reasserting, so a
    // retry can never mine through the very barrier that made the goal unreachable.
    private static double dropAnchorX, dropAnchorY, dropAnchorZ;
    // Transient pauses re-evaluate themselves instead of latching forever.
    private static String pauseDetail = "";
    private static long lastPauseLogMs;
    private static long lastTransientCheckMs, retryAt, lastRetryAt;
    private static int transientFailures;
    private static final java.util.Map<String, Integer> denialCounts = new java.util.LinkedHashMap<>();

    private NavigationRuntime() {}

    public static void initialize() {
        if (FabricLoader.getInstance().isModLoaded("baritone-meteor")) MeteorClient.EVENT_BUS.subscribe(INSTANCE);
    }

    public static void watch(Object goal) {
        if (paused || mc.player == null || mc.level == null || !InventorySync.ready()) {
            BaritoneHelper.unconfine();
            throw new IllegalStateException("WeSort navigation requires an unpaused connected verified inventory");
        }
        register(goal, false);
    }

    /**
     * Exactly one navigation-only exception to inventory readback: a validated walkable
     * approach next to a real lobby Nether portal. Ordinary routes never inherit this scope.
     */
    /** Used only to filter candidate entrance stands; it does not authorize a route or any block use. */
    public static boolean safePortalApproach(BlockPos stand) {
        return stand != null && mc.level != null && NavigationGuard.permits(stand);
    }

    public static void watchPortalApproach(Object goal, BlockPos portal, BlockPos stand) {
        boolean validPortal = mc.level != null
                && portal != null
                && mc.level.isLoaded(portal)
                && mc.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)
                && !mc.level.getBlockState(portal.below()).is(Blocks.NETHER_PORTAL);
        boolean adjacent = false;
        if (validPortal && stand != null) {
            Direction.Axis axis = mc.level.getBlockState(portal).getValue(NetherPortalBlock.AXIS);
            adjacent = PortalEntry.adjacentStand(portal, axis, stand);
        }
        StashManager manager = Modules.get().get(StashManager.class);
        StashSorter sorter = WeSortAddon.sorter();
        boolean allowed = PortalNavigationPolicy.mayBootstrap(
                manager != null && manager.dedicatedProfile(),
                sorter != null && sorter.portalAwaiting(),
                validPortal,
                adjacent,
                stand != null && mc.player != null && mc.level != null && NavigationGuard.permits(stand),
                !PhysicalJournalRuntime.blocked() && !PhysicalJournalRuntime.awaitingMirror());
        if (paused || mc.player == null || mc.level == null || !allowed) {
            BaritoneHelper.unconfine();
            throw new IllegalStateException("Unverified or unsafe lobby portal approach");
        }
        register(goal, true);
    }

    private static void register(Object goal, boolean portal) {
        if (ours == null)
            watchdog = new NavigationWatchdog(
                    StashIndex.dimension(),
                    mc.player.getX(),
                    mc.player.getY(),
                    mc.player.getZ(),
                    System.nanoTime(),
                    60_000_000_000L,
                    portal ? 20_000_000_000L : BaritoneHelper.isReachGoal(goal) ? 30_000_000_000L : 8_000_000_000L);
        ours = (Goal) goal;
        portalApproachGoal = portal;
        portalStartLevel = portal ? mc.level : null;
        portalStartDimension = portal ? StashIndex.dimension() : "";
        dropRetries = 0;
        reassertGraceUntil = 0;
        if (mc.player != null) {
            dropAnchorX = mc.player.getX();
            dropAnchorY = mc.player.getY();
            dropAnchorZ = mc.player.getZ();
        }
        pauseDetail = "";
        status = "RUNNING";
    }

    public static void released(Object goal) {
        if (ours == goal) {
            ours = null;
            watchdog = null;
            portalApproachGoal = false;
            portalStartLevel = null;
            portalStartDimension = "";
            dropRetries = 0;
            reassertGraceUntil = 0;
            status = "IDLE";
        }
    }

    public static boolean paused() {
        return paused;
    }

    public static String status() {
        return status;
    }

    public static void cancel() {
        if (ours != null) finish("PAUSED_NAVIGATION_CANCELLED", true);
    }

    public static boolean resume() {
        if (ours != null || mc.player == null || mc.level == null || !InventorySync.ready()) return false;
        paused = false;
        status = "IDLE";
        return true;
    }

    private static void finish(String outcome, boolean pause) {
        Goal released = ours;
        boolean wasPortal = portalApproachGoal;
        ours = null;
        watchdog = null;
        portalApproachGoal = false;
        portalStartLevel = null;
        portalStartDimension = "";
        dropRetries = 0;
        reassertGraceUntil = 0;
        BaritoneHelper.releaseOurs(released);
        BaritoneHelper.unconfine();
        BaritoneHelper.unprotect();
        paused = pause;
        pausedPortalRoute = pause && wasPortal;
        if (!pause || !outcome.equals("PAUSED_NAVIGATION_UNSAFE_BLOCK")) pauseDetail = "";
        else if (pauseDetail.isEmpty() && mc.player != null && mc.level != null) {
            String denial = NavigationGuard.denial(mc.player.blockPosition());
            pauseDetail = denial == null ? "" : denial;
        }
        if (pause && NavigationRecovery.retryable(outcome, pauseDetail)) {
            long now = System.currentTimeMillis();
            if (now - lastRetryAt > 120_000) transientFailures = 0;
            retryAt = now + NavigationRecovery.delayMs(++transientFailures);
            lastRetryAt = now;
        } else if (!pause) transientFailures = 0;
        denialCounts.merge(outcome, 1, Integer::sum);
        status = outcome;
        if (pause)
            MeteorClient.LOG.warn(
                    "WeSort navigation stopped: {}.{} Inspect the route, then use .wesort-navigation resume",
                    outcome,
                    baritoneState());
    }

    /** Baritone liveness at pause time, for timeout diagnostics. Never throws. */
    private static String baritoneState() {
        try {
            return " Baritone active=" + BaritoneHelper.isActive() + " calculating=" + BaritoneHelper.isCalculating()
                    + ".";
        } catch (RuntimeException unavailable) {
            return " Baritone unavailable.";
        }
    }

    /**
     * A warehouse route pause is dropped when the client is back in the 6b6t lobby: the
     * latched warehouse route must not fence the lobby transit forever. Genuine portal-route
     * pauses stay latched.
     */
    public static void clearWarehousePause() {
        if (paused && !pausedPortalRoute) {
            paused = false;
            pauseDetail = "";
            dropRetries = 0;
            reassertGraceUntil = 0;
            status = "IDLE";
            MeteorClient.LOG.info("WeSort navigation: cleared a latched warehouse pause for lobby transit.");
        }
    }

    /** How often each pause outcome has latched, for timeout diagnostics. */
    public static String denialSummary() {
        return denialCounts.isEmpty() ? "no pauses" : denialCounts.toString();
    }

    /**
     * While latched with no goal: say so every minute (a silent pause looks exactly like a
     * dead bot), and auto-clear obviously-transient causes — an unloaded chunk that loaded,
     * or an inventory fence that healed — instead of waiting for a manual resume.
     */
    private static void pauseTick() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPauseLogMs >= 60_000) {
            lastPauseLogMs = nowMs;
            MeteorClient.LOG.info(
                    "WeSort navigation paused ({}); pauses so far {}; use .wesort-navigation resume",
                    status,
                    denialSummary());
        }
        if (nowMs - lastTransientCheckMs < 5_000) return;
        lastTransientCheckMs = nowMs;
        boolean transientPause = WorldInteractionGuard.dedicated()
                ? NavigationRecovery.retryable(status, pauseDetail)
                : status.equals("PAUSED_NAVIGATION_INVENTORY")
                        || (status.equals("PAUSED_NAVIGATION_UNSAFE_BLOCK") && pauseDetail.startsWith("unloaded "));
        if (!transientPause || nowMs < retryAt || mc.player == null || mc.level == null) return;
        if (!InventorySync.ready() || PhysicalJournalRuntime.inspecting()) return;
        if (status.equals("PAUSED_NAVIGATION_PHYSICAL_UNCERTAINTY")
                && (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror())) return;
        if (!NavigationGuard.permits(mc.player.blockPosition())) return;
        // The previous goal was released; the sorter replans under all normal guards.
        // No old path or physical action is replayed here.
        paused = false;
        pauseDetail = "";
        dropRetries = 0;
        status = "IDLE";
        MeteorClient.LOG.info("WeSort navigation auto-resumed: the transient pause cause cleared.");
    }

    @EventHandler
    private void tick(TickEvent.Pre ignored) {
        boolean dedicated = WorldInteractionGuard.dedicated();
        boolean foreign = false;
        if (dedicated) {
            BaritoneHelper.enforceAgentProfile();
            foreign = BaritoneHelper.controlledByOther(ours);
            if (foreign) {
                BaritoneHelper.stop();
                if (ours == null && !paused) status = "FOREIGN_PROCESS_CANCELLED";
            }
        } else {
            BaritoneHelper.restoreAgentProfile();
        }
        if (ours == null) {
            if (paused) pauseTick();
            return;
        }
        if (mc.player == null || mc.level == null) {
            finish("PAUSED_NAVIGATION_DISCONNECTED", true);
            return;
        }
        // A proxy world replacement after entering a portal is successful transit,
        // not an unsafe dimension error. The sorter confirms actual survival separately.
        if (portalApproachGoal
                && (portalStartLevel != mc.level || !portalStartDimension.equals(StashIndex.dimension()))) {
            finish("PORTAL_WORLD_TRANSFER", false);
            return;
        }
        // Portal contact is the intended end of this special route: release Baritone
        // before the generic route guard sees the portal's safety-sensitive block.
        if (portalApproachGoal && touchingPortal()) {
            finish("PORTAL_CONTACT", false);
            return;
        }
        String failure = watchdog.check(
                StashIndex.dimension(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), System.nanoTime());
        if (!failure.isEmpty()) {
            // Baritone still working out the path is thinking, not stuck.
            if (failure.equals("PAUSED_NAVIGATION_STUCK") && BaritoneHelper.isCalculating()) return;
            // A just-reasserted engine-dropped goal gets a grace period before the deadline bites.
            if (System.nanoTime() < reassertGraceUntil
                    && (failure.equals("PAUSED_NAVIGATION_STUCK") || failure.equals("PAUSED_NAVIGATION_TIMEOUT")))
                return;
            finish(failure, true);
            return;
        }
        if (!InventorySync.ready() && !portalApproachGoal) {
            finish("PAUSED_NAVIGATION_INVENTORY", true);
            return;
        }
        if (portalApproachGoal && (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror())) {
            finish("PAUSED_NAVIGATION_PHYSICAL_UNCERTAINTY", true);
            return;
        }
        // Do not inspect a foreign executor's path. The current position still
        // needs to be safe, and the watchdog/inventory checks above still run.
        if (dedicated && foreign) {
            if (!NavigationGuard.permits(mc.player.blockPosition())) {
                finish("PAUSED_NAVIGATION_UNSAFE_BLOCK", true);
                return;
            }
            if (!paused) {
                // Re-asserting must never widen permissions: confine first so a retry
                // cannot mine through the barrier that made the goal unreachable.
                BaritoneHelper.confine(true);
                BaritoneHelper.reassertGoal(ours);
            }
            return;
        }
        if (dedicated && !allowedRoute()) {
            finish("PAUSED_NAVIGATION_UNSAFE_BLOCK", true);
            return;
        }
        if (ours.isInGoal(mc.player.blockPosition())) {
            finish("ARRIVED", false);
            return;
        }
        if (!BaritoneHelper.goalIs(ours)) {
            // An operator-replaced goal latches at once (correct: never fight the player).
            // An engine-dropped goal is reasserted a few times first; only a route the
            // engine keeps dropping latches, like an unreachable one.
            if (BaritoneHelper.controlledByOther(ours)) {
                dropRetries = 0;
                finish("PAUSED_NAVIGATION_OTHER_PROCESS", true);
            } else {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastDropMs > 60_000) dropRetries = 0;
                lastDropMs = nowMs;
                boolean progressed =
                        mc.player != null && (mc.player.distanceToSqr(dropAnchorX, dropAnchorY, dropAnchorZ) > 0.25);
                if (progressed && ++dropRetries <= 3) {
                    // Re-asserting must never widen permissions: confine first so a retry
                    // cannot mine through the barrier that made the goal unreachable.
                    BaritoneHelper.confine(true);
                    BaritoneHelper.reassertGoal(ours);
                    reassertGraceUntil = System.nanoTime() + 10_000_000_000L;
                    MeteorClient.LOG.warn(
                            "WeSort navigation goal dropped by the engine, reasserting ({}/3).", dropRetries);
                } else {
                    dropRetries = 0;
                    finish("PAUSED_NAVIGATION_RELEASED", true);
                }
            }
            return;
        }
        if (!dedicated && BaritoneHelper.controlledByOther(ours)) finish("PAUSED_NAVIGATION_OTHER_PROCESS", true);
    }

    private static boolean touchingPortal() {
        BlockPos feet = mc.player.blockPosition();
        return isPortal(feet) || isPortal(feet.above());
    }

    private static boolean isPortal(BlockPos pos) {
        return mc.level.isLoaded(pos) && mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
    }

    private boolean allowedRoute() {
        pauseDetail = "";
        String blocked = NavigationGuard.denial(mc.player.blockPosition());
        if (blocked != null) {
            pauseDetail = blocked;
            MeteorClient.LOG.warn("WeSort blocked route under player: {}", blocked);
            return false;
        }
        var executor = BaritoneHelper.currentPath();
        if (executor == null) return true;
        var positions = executor.getPath().positions();
        int first = Math.max(0, executor.getPosition());
        for (int position = first; position < Math.min(first + 3, positions.size()); position++) {
            blocked = NavigationGuard.denial(positions.get(position));
            if (blocked != null) {
                pauseDetail = blocked;
                MeteorClient.LOG.warn("WeSort blocked route at path position {}: {}", position, blocked);
                return false;
            }
        }
        return true;
    }

    @EventHandler
    private void left(GameLeftEvent ignored) {
        if (ours != null) finish("PAUSED_NAVIGATION_DISCONNECTED", true);
        // The failed, client-owned portal route no longer exists after logout. The
        // next login must pass fresh portal/approach/route guards; all warehouse
        // safety pauses remain latched until the operator explicitly resumes.
        if (PortalNavigationPolicy.clearPauseOnDisconnect(paused, pausedPortalRoute)) {
            paused = false;
            pausedPortalRoute = false;
            status = "IDLE";
        }
    }
}
