package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashSorter;
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

    // A client-predicted break may turn the block into air before vanilla sends
    // STOP_DESTROY_BLOCK. Remember only the last successfully authorized start:
    // STOP can finish that precise break, never authorize a new target or job.
    private record AuthorizedBreak(long pos, String block, String job, long at) {}

    private static AuthorizedBreak authorizedBreak;

    private WorldInteractionGuard() {}

    public static boolean dedicated() {
        var manager = Modules.get().get(StashManager.class);
        return manager != null && manager.dedicatedProfile();
    }

    /**
     * A transient transport status (online / awaiting server inventory) must not
     * rename a live repack job between an authorized block interaction and its
     * deferred packet or STOP. Keep the UUID of the physical operation stable;
     * actual Manager order IDs retain precedence.
     */
    public static String currentJob() {
        var manager = Modules.get().get(StashManager.class);
        if (manager == null) return "unknown";
        var sorter = Modules.get().get(StashSorter.class);
        return jobTag(
                manager.activeOrderId(), sorter == null ? null : sorter.activeRepackJobId(), manager.activeTask());
    }

    static String jobTag(String order, String repackId, String fallback) {
        if (order != null && !order.isEmpty()) return order;
        if (repackId != null && !repackId.isEmpty()) return "repack:" + repackId;
        return fallback == null || fallback.isEmpty() ? "unknown" : fallback;
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
        return allowUse(hit, null, null);
    }

    /**
     * The client may predict a placement before PacketEvent.Send. Only the exact
     * preflight snapshot from this same useItemOn invocation may be used to
     * re-check its PLACE permit; no guessed air or generic shulker exception.
     */
    public static boolean allowUse(BlockHitResult hit, String beforeClicked, String beforeAdjacent) {
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
        // A support-face click places into the adjacent cell. Meteor can also
        // click an already-replaceable destination directly (air-place fallback).
        // Both forms require the same exact, job-bound PLACE permit.
        boolean placePermit = PERMITS.allows(now, target.asLong(), Operation.PLACE, blockId(target), job);
        if (!placePermit && beforeAdjacent != null)
            placePermit = PERMITS.allows(now, target.asLong(), Operation.PLACE, beforeAdjacent, job);
        if (!placePermit && (mc.level.getBlockState(position).canBeReplaced() || beforeClicked != null))
            placePermit = PERMITS.allows(
                    now,
                    position.asLong(),
                    Operation.PLACE,
                    beforeClicked == null ? blockId(position) : beforeClicked,
                    job);
        return useDecision(job, safeBlock(position), safeBlock(target), usePermit, placePermit);
    }

    public static boolean allowBreak(BlockPos position) {
        if (!dedicated()) return true;
        String job = currentJob();
        if (mc.level == null || !mc.level.isLoaded(position)) {
            denied("break-unloaded");
            return false;
        }
        // Refine the no-permit audit so a stall names its cause: no live permit here at all
        // (absent or TTL-expired) vs a live permit naming a different block (client prediction
        // vs server state, or a stale break target). Unknown-job and safety-sensitive denials
        // keep their own counters via breakDecision below.
        long now = now();
        String observed = blockId(position);
        boolean permit = PERMITS.allows(now, position.asLong(), Operation.BREAK, observed, job);
        if (permit && safeBlock(position)) authorizedBreak = new AuthorizedBreak(position.asLong(), observed, job, now);
        if (!permit && job != null && !job.isEmpty() && !job.equals("unknown") && safeBlock(position)) {
            String holder = PERMITS.jobOf(now, position.asLong(), Operation.BREAK);
            denied(holder == null ? "break-no-permit-expired" : "break-no-permit-block");
            return false;
        }
        return breakDecision(job, safeBlock(position), permit);
    }

    /**
     * Finish only a previously permitted break at the very same position and job.
     * Minecraft may predict AIR before STOP_DESTROY_BLOCK reaches the server.
     * The original live permit must still name the original shulker block; an
     * unrelated changed block, expired permission or restart never qualifies.
     */
    public static boolean allowBreakStop(BlockPos position) {
        if (!dedicated()) return true;
        if (position == null || mc.level == null || PhysicalJournalRuntime.blocked()) {
            denied("break-stop-unavailable");
            return false;
        }
        // One atomic chunk read for the whole verdict: loaded state, live block and
        // safety classification all come from the same lookup, so a chunk-map change
        // between a separate isLoaded check and a later block read can never split
        // this decision. A missing/unreadable chunk fails closed as unavailable.
        net.minecraft.world.level.block.state.BlockState live = snapshotState(position);
        if (live == null) {
            denied("break-stop-unavailable");
            return false;
        }
        long time = now();
        String job = currentJob();
        var previous = authorizedBreak;
        String block = BuiltInRegistries.BLOCK.getKey(live.getBlock()).toString();
        boolean safe = !com.porter.wesort.data.BlockSafety.safetySensitive(block);
        boolean posMatch = previous != null && previous.pos() == position.asLong();
        boolean jobMatch = previous != null && previous.job().equals(job);
        boolean fresh = previous != null && time >= previous.at() && time - previous.at() < PERMIT_TTL_MS;
        boolean wasShulker = previous != null && previous.block().endsWith("_shulker_box");
        boolean blockMatch = previous != null && (block.equals(previous.block()) || block.equals("minecraft:air"));
        boolean permitForPrevious = previous != null
                && PERMITS.allows(time, position.asLong(), Operation.BREAK, previous.block(), job);
        boolean permittedStandingBlock = block.endsWith("_shulker_box")
                && PERMITS.allows(time, position.asLong(), Operation.BREAK, block, job);
        boolean permittedPredictedBreak =
                posMatch && jobMatch && fresh && wasShulker && blockMatch && permitForPrevious;
        boolean allowed = safe && (permittedStandingBlock || permittedPredictedBreak);
        if (!allowed) {
            denied("break-stop-no-authorized-start");
            if (BREAK_STOP_TRACE.incrementAndGet() <= 4)
                meteordevelopment.meteorclient.MeteorClient.LOG.warn(
                        "WeSort break stop denied at {} live {} previous {} permitExpected {} permitForPrevious {}"
                                + " jobMatch {} fresh {} holder {} job {} blocked {} safe {} standing {} predicted {}"
                                + " thread {}",
                        position.toShortString(),
                        block,
                        previous,
                        breakPermitExpected(position),
                        permitForPrevious,
                        jobMatch,
                        fresh,
                        PERMITS.jobOf(time, position.asLong(), Operation.BREAK),
                        job,
                        PhysicalJournalRuntime.blocked(),
                        safe,
                        permittedStandingBlock,
                        permittedPredictedBreak,
                        Thread.currentThread().getName());
        }
        return allowed;
    }

    /** One atomic chunk read for guard verdicts (null when missing, out of bounds or unreadable). */
    static net.minecraft.world.level.block.state.BlockState snapshotState(BlockPos position) {
        if (position == null || mc.level == null) return null;
        try {
            var chunk = mc.level.getChunkSource().getChunk(
                    net.minecraft.core.SectionPos.blockToSectionCoord(position.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(position.getZ()),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                    false);
            if (chunk == null) return null;
            return chunk.getBlockState(position);
        } catch (RuntimeException crashed) {
            return null;
        }
    }

    /** Expected block of the live BREAK permit for diagnostics (null when there is none). */
    static String breakPermitExpected(BlockPos position) {
        return PERMITS.expected(now(), position.asLong(), Operation.BREAK);
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

    /**
     * Whether the target cell carries a live PLACE permit for the current job. Opens authorize
     * only the USE permit on the clicked block; placements additionally announce a PLACE permit
     * on the target cell (authorizePlace + expectPlacement together). A use against a cell with
     * a live PLACE permit is therefore a placement whose durable intent must be recorded — a
     * mismatch there is a lost intent, never a read.
     */
    public static boolean hasLivePlacePermit(BlockPos target) {
        return hasLivePlacePermit(target, null);
    }

    /** Use a captured preflight block identity only for the same pending interaction. */
    public static boolean hasLivePlacePermit(BlockPos target, String beforeBlock) {
        if (!dedicated() || mc.level == null || !mc.level.isLoaded(target)) return false;
        String job = currentJob();
        if (job == null || job.isEmpty() || job.equals("unknown")) return false;
        long now = now();
        return PERMITS.allows(now, target.asLong(), Operation.PLACE, blockId(target), job)
                || (beforeBlock != null && PERMITS.allows(now, target.asLong(), Operation.PLACE, beforeBlock, job));
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

    /** Bounded native break-stop diagnostic (first four denials per client). */
    private static final java.util.concurrent.atomic.AtomicLong BREAK_STOP_TRACE =
            new java.util.concurrent.atomic.AtomicLong();

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
