package com.porter.wesortmanager;

import java.util.List;

/**
 * PR4 (#263): revision-bound source observation batches (additive).
 *
 * <p>Replaces full-index pushes with bounded batches: an explicit
 * {@link Op#FULL_MEMBERSHIP} carries the complete member list atomically,
 * deltas carry only changed sources. An omitted source in a delta is
 * {@code UNCHANGED} — never withdrawn. Not yet wired into
 * {@code AddonRoutes}/{@code StockLedger.accept}; the validator and transport
 * land behind this contract.
 */
public record BatchEnvelope(
        int envelopeVersion,
        String server,
        String stash,
        String dimension,
        String agent,
        String session,
        long epoch,
        long baseRevision,
        String batchId,
        int batchIndex,
        int batchCount,
        List<Op> ops) {
    public BatchEnvelope {
        if (envelopeVersion != 1) throw new IllegalArgumentException("unsupported batch envelope version");
        if (server == null || server.isEmpty()) throw new IllegalArgumentException("server required");
        if (batchId == null || batchId.isEmpty()) throw new IllegalArgumentException("batchId required");
        if (batchIndex < 0 || batchCount <= 0 || batchIndex >= batchCount)
            throw new IllegalArgumentException("batch index out of range");
        ops = ops == null ? List.of() : List.copyOf(ops);
        if (ops.isEmpty()) throw new IllegalArgumentException("empty batch commits nothing");
    }

    public enum OpKind {
        FULL_MEMBERSHIP,
        SOURCE_UPSERT,
        SOURCE_INVALIDATE,
        SOURCE_WITHDRAW
    }

    public record Op(OpKind kind, String sourceId, long sourceRevision, String digest) {
        public Op {
            if (kind == null) throw new IllegalArgumentException("kind required");
            if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("sourceId required");
        }
    }

    /** Per-source ACK watermark the Manager returns per batch. */
    public record Ack(String sourceId, long sourceRevision, String digest, long verifiedAt) {}
}
