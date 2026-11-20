package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.modules.StashManager;
import com.porter.wesort.utils.InteractionPermits;
import com.porter.wesort.utils.InteractionPermits.Operation;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Fail-closed world-interaction policy for the dedicated agent profile (W-061).
 *
 * <p>When the bot is Manager-driven, every automatic block use, break or sign-write needs a live,
 * exact permit: one packed position, one operation, the expected block id, the authorizing job and
 * an expiry. WeSort action code issues short scoped permits around the single action it performs;
 * anything else — a foreign shulker, a base block, a door, redstone, a changed blockstate, an
 * expired job — is denied. There is no allow-by-type: even a common stone is refused without a
 * permit. Permits live only in memory, so a restart never restores an old authorization.
 *
 * <p>Outside the dedicated profile the guard passes through (the user is driving) and the
 * always-on {@code InventorySync} fail-closed fence still applies to WeSort's own packets.
 */
public final class WorldInteractionGuard {
    private static final InteractionPermits PERMITS = new InteractionPermits();
    /** How long an action permit lives past its scope as a failsafe (scopes revoke on close). */
    private static final long PERMIT_TTL_MS = 10_000;

    private WorldInteractionGuard() {}

    public static boolean dedicated() {
        var manager = Modules.get().get(StashManager.class);
        return manager != null && manager.dedicatedProfile();
    }

    /** The job tag authorizing current WeSort work: the active Manager order, else the activity. */
    public static String currentJob() {
        var manager = Modules.get().get(StashManager.class);
        if (manager == null) return "unknown";
        String order = manager.activeOrderId();
        return order != null && !order.isEmpty() ? order : manager.activeTask();
    }

    /**
     * Issues a scoped USE permit for one block interaction (open a container, press the one anvil
     * the job uses, click the face a placement goes on). Revoked on close.
     */
    public static AutoCloseable permitUse(BlockPos pos) {
        return PERMITS.permit(pos.asLong(), Operation.USE, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Issues a scoped BREAK permit for one destruction (only a shulker the bot itself placed). */
    public static AutoCloseable permitBreak(BlockPos pos) {
        return PERMITS.permit(pos.asLong(), Operation.BREAK, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Issues a scoped SIGN permit for one sign-text write at an already-placed sign. */
    public static AutoCloseable permitSign(BlockPos pos) {
        return PERMITS.permit(pos.asLong(), Operation.SIGN, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /**
     * Issues a scoped PLACE permit for one placement into an empty cell: the support click passes
     * while the permit lives. Used fire-and-forget at rotation-deferred call sites (the 10s TTL
     * covers the deferred click); synchronous sites may close the scope immediately.
     */
    public static AutoCloseable permitPlace(BlockPos emptyCell) {
        return PERMITS.permit(
                emptyCell.asLong(), Operation.PLACE, blockId(emptyCell), currentJob(), PERMIT_TTL_MS, now());
    }

    /**
     * Fire-and-forget USE authorization for rotation-deferred interactions: lives for the TTL only.
     * Prefer the scoped {@link #permitUse} for synchronous calls.
     */
    public static void authorizeUse(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.USE, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget BREAK authorization (see {@link #authorizeUse}). */
    public static void authorizeBreak(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.BREAK, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget PLACE authorization (see {@link #permitPlace}). */
    public static void authorizePlace(BlockPos emptyCell) {
        PERMITS.permit(emptyCell.asLong(), Operation.PLACE, blockId(emptyCell), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget SIGN authorization (see {@link #permitSign}). */
    public static void authorizeSign(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.SIGN, blockId(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    public static boolean allowUse(BlockHitResult hit) {
        if (!dedicated()) return true;
        // Fail-closed: without a live, known job no mutation is authorized,
        // even with a leftover permit (permits themselves also reject unknown).
        String job = currentJob();
        if (mc.level == null) {
            denied("use-no-level");
            return false;
        }
        var position = hit.getBlockPos();
        if (!mc.level.isLoaded(position)) {
            denied("use-unloaded");
            return false;
        }
        var target = position.relative(hit.getDirection());
        if (!mc.level.isLoaded(target)) {
            denied("use-target-unloaded");
            return false;
        }
        long now = now();
        // Activation: the clicked block itself is authorized for this job.
        boolean usePermit = PERMITS.allows(now, position.asLong(), Operation.USE, blockId(position), job);
        // Placement: the click lands on a support while the adjacent cell carries a live PLACE
        // permit naming the air the job puts its block into.
        boolean placePermit = PERMITS.allows(now, target.asLong(), Operation.PLACE, blockId(target), job);
        return useDecision(job, safeBlock(position), safeBlock(target), usePermit, placePermit);
    }

    public static boolean allowBreak(BlockPos position) {
        if (!dedicated()) return true;
        String job = currentJob();
        if (mc.level == null || !mc.level.isLoaded(position)) {
            denied("break-unloaded");
            return false;
        }
        return breakDecision(
                job,
                safeBlock(position),
                PERMITS.allows(now(), position.asLong(), Operation.BREAK, blockId(position), job));
    }

    /**
     * Pure use policy (no Minecraft access, unit-tested): safety-sensitive blocks are never
     * authorized — not even with a live permit. Main's fail-closed denial of doors, redstone and
     * trapped chests holds alongside the exact-permit check; a permit alone never opens them.
     */
    static boolean useDecision(
            String job, boolean safePos, boolean safeTarget, boolean usePermit, boolean placePermit) {
        if (job == null || job.isEmpty() || job.equals("unknown")) {
            denied("use-unknown-job");
            return false;
        }
        if (!safePos || !safeTarget) {
            denied("use-safety-sensitive");
            return false;
        }
        if (!(usePermit || placePermit)) denied("use-no-permit");
        return usePermit || placePermit;
    }

    /**
     * Pure break policy (no Minecraft access, unit-tested): safety-sensitive blocks are never
     * authorized for destruction, even with a live permit.
     */
    static boolean breakDecision(String job, boolean safePos, boolean breakPermit) {
        if (job == null || job.isEmpty() || job.equals("unknown")) {
            denied("break-unknown-job");
            return false;
        }
        if (!safePos) {
            denied("break-safety-sensitive");
            return false;
        }
        if (!breakPermit) denied("break-no-permit");
        return breakPermit;
    }

    /** Deny audit counters (exposed to the E2E driver state): why automatic mutations were refused. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> DENIALS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void denied(String reason) {
        DENIALS.merge(reason, 1L, Long::sum);
    }

    public static java.util.Map<String, Long> denialCounts() {
        return java.util.Map.copyOf(DENIALS);
    }

    /** Sign text goes in its own packet: it needs its own permit, like any other mutation. */
    public static boolean allowSign(BlockPos position) {
        if (!dedicated()) return true;
        String job = currentJob();
        if (job == null || job.isEmpty() || job.equals("unknown")) {
            denied("sign-unknown-job");
            return false;
        }
        if (mc.level == null || !mc.level.isLoaded(position)) {
            denied("sign-unloaded");
            return false;
        }
        if (!safeBlock(position)) {
            denied("sign-safety-sensitive");
            return false;
        }
        boolean ok = PERMITS.allows(now(), position.asLong(), Operation.SIGN, blockId(position), job);
        if (!ok) denied("sign-no-permit");
        return ok;
    }

    /** The authorizing job of the live permit, for deny audit (null when there is none). */
    public static String jobOfUse(BlockPos position) {
        return PERMITS.jobOf(now(), position.asLong(), Operation.USE);
    }

    static boolean safeBlock(BlockPos position) {
        if (mc.level == null || !mc.level.isLoaded(position)) return false;
        return !com.porter.wesort.data.BlockSafety.safetySensitive(blockId(position));
    }

    /** Full namespaced block id (minecraft:stone, never bare path): permits for one namespace never match another. */
    static String blockId(BlockPos position) {
        return BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(position).getBlock())
                .toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
