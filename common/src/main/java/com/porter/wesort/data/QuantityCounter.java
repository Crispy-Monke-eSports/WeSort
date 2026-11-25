package com.porter.wesort.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR7 (#268, steg 1): shared compact quantity counter with checked arithmetic.
 *
 * <p>A pure-Java {@code variantId → long} map with {@code Math.addExact} /
 * {@code Math.multiplyExact} everywhere — never {@code Long::sum} wraparound,
 * never {@code toIntExact} truncation. Counts stay {@code long} end to end;
 * native {@code int} counts/maxStacks are widened on entry, and JS
 * {@code MAX_SAFE_INTEGER} handling is the wire contract's job, not this map's.
 *
 * <p>Semantics owned elsewhere: this counter adds numbers. Whether two rows
 * may be added (ancestor/descendant in #269, stale vs fresh, shared vs owned)
 * is decided by the caller building the delta — typically one immutable
 * {@link SourceContribution} per verified source revision.
 */
public final class QuantityCounter {
    private final Map<String, Long> counts = new HashMap<>();

    public void add(String variantId, long amount) {
        if (variantId == null || variantId.isEmpty()) throw new IllegalArgumentException("variantId required");
        if (amount == 0) return;
        counts.merge(variantId, amount, Math::addExact);
        if (counts.get(variantId) == 0L) counts.remove(variantId);
    }

    public void addAll(Map<String, Long> delta) {
        if (delta == null) return;
        for (var e : delta.entrySet()) add(e.getKey(), e.getValue());
    }

    public void set(String variantId, long amount) {
        if (variantId == null || variantId.isEmpty()) throw new IllegalArgumentException("variantId required");
        if (amount < 0) throw new IllegalArgumentException("negative count: " + variantId);
        if (amount == 0) counts.remove(variantId);
        else counts.put(variantId, amount);
    }

    public long get(String variantId) {
        return counts.getOrDefault(variantId, 0L);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int size() {
        return counts.size();
    }

    /** Immutable, deterministically sorted snapshot for readers (never live entries). */
    public Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(counts));
    }

    /**
     * Applies an already-computed delta atomically in memory: validates the full
     * delta for overflow/negativity first, then commits. A failing delta leaves
     * this counter unchanged.
     */
    public void applyDelta(Map<String, Long> delta) {
        if (delta == null || delta.isEmpty()) return;
        Map<String, Long> next = new HashMap<>(counts);
        for (var e : delta.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("variantId required");
            long updated = Math.addExact(next.getOrDefault(key, 0L), e.getValue());
            if (updated < 0) throw new IllegalArgumentException("delta drives negative count: " + key);
            if (updated == 0L) next.remove(key);
            else next.put(key, updated);
        }
        counts.clear();
        counts.putAll(next);
    }

    public static long multiplyChecked(long copies, long count) {
        return Math.multiplyExact(copies, count);
    }
}
