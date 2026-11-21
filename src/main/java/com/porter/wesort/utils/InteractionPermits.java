package com.porter.wesort.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed interaction permits for the dedicated agent profile: every automatic block use, break or
 * sign-write needs a live, exact permit. There is no wildcard, no category and no "harmless type": a
 * permit names one packed position, one operation, the expected block id and the authorizing job, and it
 * expires. Anything without a matching live permit is denied. Pure Java (positions are packed longs), so
 * the semantics are unit-tested without Minecraft.
 */
public final class InteractionPermits {
    /** The exact handling a permit covers. USE opens/activates the block; BREAK destroys it; SIGN writes text. */
    public enum Operation {
        USE,
        BREAK,
        SIGN,
        /**
         * Uses this block as the support to place into the adjacent cell: the clicked block keeps its
         * USE-gate for activation, while one placement into the named air cell is allowed.
         */
        PLACE
    }

    /** A live authorization: one position, one operation, the block id expected there, the job, an expiry. */
    public record Permit(long pos, Operation op, String expectedBlock, String job, long expiresAt) {}

    private record Key(long pos, Operation op) {}

    private final Map<Key, Permit> permits = new LinkedHashMap<>();

    /**
     * Issues a permit and returns a scope that revokes it on close. Permits also expire, so a leaked
     * scope can never authorize forever; a restart clears everything (in-memory only).
     */
    public AutoCloseable permit(long pos, Operation op, String expectedBlock, String job, long ttlMs, long now) {
        String block = expectedBlock == null ? "" : expectedBlock;
        String owner = job == null || job.isEmpty() ? "unknown" : job;
        long expiresAt = now + Math.max(1, ttlMs);
        Key key = new Key(pos, op);
        permits.put(key, new Permit(pos, op, block, owner, expiresAt));
        return () -> permits.remove(key);
    }

    /**
     * True only with a live permit for the exact position, operation and block id
     * whose authorizing job is exactly the job asking now. Expired permits are
     * pruned on read. The job is a verified boundary, not metadata: a permit for
     * job A never authorizes job B, a job change or abort invalidates every
     * permit of the old job at once, and "unknown" never authorizes anything.
     *
     * <p>Permits are deliberately NOT consumed on first approval. One logical
     * action is checked up to three times (mixin entry, packet gate,
     * stopDestroyBlock re-check), so consuming would self-deny legitimate work.
     * Reuse is still safe: destructive operations self-invalidate because the
     * block no longer matches (break leaves air, placement occupies the cell),
     * re-activation by the same live job is idempotent, and the permit expires
     * within seconds, lives only in memory and is replaced on reissue.
     */
    public boolean allows(long now, long pos, Operation op, String actualBlock, String job) {
        prune(now);
        Permit p = permits.get(new Key(pos, op));
        if (p == null || now >= p.expiresAt()) return false;
        if (p.job().equals("unknown")) return false;
        if (job == null || job.isEmpty() || job.equals("unknown")) return false;
        if (!p.job().equals(job)) return false;
        return Objects.equals(p.expectedBlock(), actualBlock == null ? "" : actualBlock);
    }

    /** The authorizing job of the live permit, or null when there is none (for deny audit). */
    public String jobOf(long now, long pos, Operation op) {
        prune(now);
        Permit p = permits.get(new Key(pos, op));
        return p == null || now >= p.expiresAt() ? null : p.job();
    }

    /** The expected block of the live permit, or null when there is none (for deny audit). */
    public String expected(long now, long pos, Operation op) {
        prune(now);
        Permit p = permits.get(new Key(pos, op));
        return p == null || now >= p.expiresAt() ? null : p.expectedBlock();
    }

    public int live(long now) {
        prune(now);
        return permits.size();
    }

    private void prune(long now) {
        Iterator<Permit> it = permits.values().iterator();
        while (it.hasNext()) if (now >= it.next().expiresAt()) it.remove();
    }
}
