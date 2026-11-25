package com.porter.wesort.data;

import java.util.List;
import java.util.Map;

/**
 * PR7 (#254/#268, steg 1): immutable per-source contribution.
 *
 * <p>One verified source revision contributes counts for its exact variants,
 * plus the classification the projector (not the counter) decided:
 * observed vs selectable vs held vs stale. Replacing a source is a whole-record
 * swap — the consumer subtracts the old record and adds the new one, so an
 * unchanged source costs no recompute and a changed source costs only its delta.
 *
 * <p>Never local predicted {@code ItemStack}s: a contribution is only built
 * from a verified server observation (SNBT digest + witness), and replay of the
 * same proof must not renew age.
 */
public record SourceContribution(
        String sourceId,
        long revision,
        String digest,
        Map<String, Long> observed,
        Map<String, Long> selectable,
        long observedAtMs) {
    public SourceContribution {
        if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("sourceId required");
        if (digest == null || digest.isEmpty()) throw new IllegalArgumentException("digest required");
        observed = observed == null ? Map.of() : Map.copyOf(observed);
        selectable = selectable == null ? Map.of() : Map.copyOf(selectable);
        for (var e : observed.entrySet()) {
            if (e.getValue() < 0) throw new IllegalArgumentException("negative observed count: " + e.getKey());
        }
        for (var e : selectable.entrySet()) {
            if (e.getValue() < 0) throw new IllegalArgumentException("negative selectable count: " + e.getKey());
        }
    }

    /** Delta of selectable counts from {@code old} to {@code next} (same source). */
    public static Map<String, Long> selectableDelta(SourceContribution old, SourceContribution next) {
        if (!old.sourceId().equals(next.sourceId()))
            throw new IllegalArgumentException("delta across sources");
        var delta = new java.util.HashMap<String, Long>();
        for (var e : old.selectable().entrySet()) delta.merge(e.getKey(), -e.getValue(), Math::addExact);
        for (var e : next.selectable().entrySet()) delta.merge(e.getKey(), e.getValue(), Math::addExact);
        delta.values().removeIf(v -> v == 0L);
        return Map.copyOf(delta);
    }

    public static SourceContribution empty(String sourceId, long revision, String digest, long observedAtMs) {
        return new SourceContribution(sourceId, revision, digest, Map.of(), Map.of(), observedAtMs);
    }

    public List<String> variantIds() {
        return List.copyOf(new java.util.TreeSet<>(selectable.keySet()));
    }
}
