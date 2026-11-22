package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import baritone.api.pathing.goals.Goal;
import com.porter.wesort.WeSortAddon;
import com.porter.wesort.inventory.NavigationWatchdog;
import com.porter.wesort.inventory.PortalNavigationPolicy;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.utils.PortalEntry;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import com.porter.wesort.stash.StashIndex;
import com.porter.wesort.utils.BaritoneHelper;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;

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
    public static void watchPortalApproach(Object goal, BlockPos portal, BlockPos stand) {
        boolean validPortal = mc.level != null
                && portal != null
                && mc.level.isLoaded(portal)
                && mc.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)
                && !mc.level.getBlockState(portal.below()).is(Blocks.NETHER_PORTAL);
        boolean adjacent = false;
        if (validPortal && stand != null) {
            Direction.Axis axis = mc.level.getBlockState(portal).getValue(NetherPortalBlock.AXIS);
            adjacent = stand.equals(PortalEntry.approach(portal, axis, 0, 0, false))
                    || stand.equals(PortalEntry.approach(portal, axis, 0, 0, true));
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
                    portal ? 20_000_000_000L : 8_000_000_000L);
        ours = (Goal) goal;
        portalApproachGoal = portal;
        portalStartLevel = portal ? mc.level : null;
        portalStartDimension = portal ? StashIndex.dimension() : "";
        status = "RUNNING";
    }

    public static void released(Object goal) {
        if (ours == goal) {
            ours = null;
            watchdog = null;
            portalApproachGoal = false;
            portalStartLevel = null;
            portalStartDimension = "";
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
        BaritoneHelper.releaseOurs(released);
        BaritoneHelper.unconfine();
        BaritoneHelper.unprotect();
        paused = pause;
        pausedPortalRoute = pause && wasPortal;
        status = outcome;
        if (pause)
            MeteorClient.LOG.warn(
                    "WeSort navigation stopped: {}. Inspect the route, then use .wesort-navigation resume", outcome);
    }

    @EventHandler
    private void tick(TickEvent.Pre ignored) {
        if (WorldInteractionGuard.dedicated()) {
            BaritoneHelper.enforceAgentProfile();
            if (BaritoneHelper.controlledByOther(ours)) {
                BaritoneHelper.stop();
                if (!paused || ours != null) finish("PAUSED_NAVIGATION_OTHER_PROCESS", true);
                return;
            }
        }
        if (ours == null) return;
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
        if (WorldInteractionGuard.dedicated() && !allowedRoute()) {
            finish("PAUSED_NAVIGATION_UNSAFE_BLOCK", true);
            return;
        }
        if (ours.isInGoal(mc.player.blockPosition())) {
            finish("ARRIVED", false);
            return;
        }
        if (!BaritoneHelper.goalIs(ours)) {
            finish("PAUSED_NAVIGATION_RELEASED", true);
            return;
        }
        if (BaritoneHelper.controlledByOther(ours)) finish("PAUSED_NAVIGATION_OTHER_PROCESS", true);
    }

    private static boolean touchingPortal() {
        BlockPos feet = mc.player.blockPosition();
        return isPortal(feet) || isPortal(feet.above());
    }

    private static boolean isPortal(BlockPos pos) {
        return mc.level.isLoaded(pos) && mc.level.getBlockState(pos).is(Blocks.NETHER_PORTAL);
    }

    private boolean allowedRoute() {
        String blocked = NavigationGuard.denial(mc.player.blockPosition());
        if (blocked != null) {
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
