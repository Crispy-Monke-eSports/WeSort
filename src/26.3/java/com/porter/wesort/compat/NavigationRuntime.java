package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import baritone.api.pathing.goals.Goal;
import com.porter.wesort.inventory.NavigationWatchdog;
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
        if (ours == null)
            watchdog = new NavigationWatchdog(
                    StashIndex.dimension(),
                    mc.player.getX(),
                    mc.player.getY(),
                    mc.player.getZ(),
                    System.nanoTime(),
                    60_000_000_000L,
                    8_000_000_000L);
        ours = (Goal) goal;
        status = "RUNNING";
    }

    public static void released(Object goal) {
        if (ours == goal) {
            ours = null;
            watchdog = null;
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
        ours = null;
        watchdog = null;
        BaritoneHelper.releaseOurs(released);
        BaritoneHelper.unconfine();
        BaritoneHelper.unprotect();
        paused = pause;
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
        String failure = watchdog.check(
                StashIndex.dimension(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), System.nanoTime());
        if (!failure.isEmpty()) {
            finish(failure, true);
            return;
        }
        if (!InventorySync.ready()) {
            finish("PAUSED_NAVIGATION_INVENTORY", true);
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

    private boolean allowedRoute() {
        if (!NavigationGuard.permits(mc.player.blockPosition())) return false;
        var executor = BaritoneHelper.currentPath();
        if (executor == null) return true;
        var positions = executor.getPath().positions();
        int first = Math.max(0, executor.getPosition());
        for (int position = first; position < Math.min(first + 3, positions.size()); position++) {
            if (!NavigationGuard.permits(positions.get(position))) return false;
        }
        return true;
    }

    @EventHandler
    private void left(GameLeftEvent ignored) {
        if (ours != null) finish("PAUSED_NAVIGATION_DISCONNECTED", true);
    }
}
