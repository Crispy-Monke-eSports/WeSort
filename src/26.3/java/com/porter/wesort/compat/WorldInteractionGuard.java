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
        return PERMITS.permit(pos.asLong(), Operation.USE, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Issues a scoped BREAK permit for one destruction (only a shulker the bot itself placed). */
    public static AutoCloseable permitBreak(BlockPos pos) {
        return PERMITS.permit(pos.asLong(), Operation.BREAK, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Issues a scoped SIGN permit for one sign-text write at an already-placed sign. */
    public static AutoCloseable permitSign(BlockPos pos) {
        return PERMITS.permit(pos.asLong(), Operation.SIGN, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /**
     * Issues a scoped PLACE permit for one placement into an empty cell: the support click passes
     * while the permit lives. Used fire-and-forget at rotation-deferred call sites (the 10s TTL
     * covers the deferred click); synchronous sites may close the scope immediately.
     */
    public static AutoCloseable permitPlace(BlockPos emptyCell) {
        return PERMITS.permit(
                emptyCell.asLong(), Operation.PLACE, blockPath(emptyCell), currentJob(), PERMIT_TTL_MS, now());
    }

    /**
     * Fire-and-forget USE authorization for rotation-deferred interactions: lives for the TTL only.
     * Prefer the scoped {@link #permitUse} for synchronous calls.
     */
    public static void authorizeUse(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.USE, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget BREAK authorization (see {@link #authorizeUse}). */
    public static void authorizeBreak(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.BREAK, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget PLACE authorization (see {@link #permitPlace}). */
    public static void authorizePlace(BlockPos emptyCell) {
        PERMITS.permit(emptyCell.asLong(), Operation.PLACE, blockPath(emptyCell), currentJob(), PERMIT_TTL_MS, now());
    }

    /** Fire-and-forget SIGN authorization (see {@link #permitSign}). */
    public static void authorizeSign(BlockPos pos) {
        PERMITS.permit(pos.asLong(), Operation.SIGN, blockPath(pos), currentJob(), PERMIT_TTL_MS, now());
    }

    public static boolean allowUse(BlockHitResult hit) {
        if (!dedicated()) return true;
        if (mc.level == null) return false;
        var position = hit.getBlockPos();
        if (!mc.level.isLoaded(position)) return false;
        long now = now();
        // Activation: the clicked block itself is authorized for this job.
        if (PERMITS.allows(now, position.asLong(), Operation.USE, blockPath(position))) return true;
        // Placement: the click lands on a support while the adjacent cell carries a live PLACE
        // permit naming the air the job puts its block into.
        var target = position.relative(hit.getDirection());
        return mc.level.isLoaded(target) && PERMITS.allows(now, target.asLong(), Operation.PLACE, blockPath(target));
    }

    public static boolean allowBreak(BlockPos position) {
        if (!dedicated()) return true;
        if (mc.level == null || !mc.level.isLoaded(position)) return false;
        return PERMITS.allows(now(), position.asLong(), Operation.BREAK, blockPath(position));
    }

    /** Sign text goes in its own packet: it needs its own permit, like any other mutation. */
    public static boolean allowSign(BlockPos position) {
        if (!dedicated()) return true;
        if (mc.level == null || !mc.level.isLoaded(position)) return false;
        return PERMITS.allows(now(), position.asLong(), Operation.SIGN, blockPath(position));
    }

    /** The authorizing job of the live permit, for deny audit (null when there is none). */
    public static String jobOfUse(BlockPos position) {
        return PERMITS.jobOf(now(), position.asLong(), Operation.USE);
    }

    static boolean safeBlock(BlockPos position) {
        if (mc.level == null || !mc.level.isLoaded(position)) return false;
        return !com.porter.wesort.data.BlockSafety.safetySensitive(blockPath(position));
    }

    static String blockPath(BlockPos position) {
        return BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(position).getBlock())
                .getPath();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
