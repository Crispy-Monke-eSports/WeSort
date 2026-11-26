package com.porter.wesort.craft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PR10 (#236, steg 1): revisionsstyrd preview-cache med single-flight (additiv).
 *
 * <p>A preview is a hint, never authority: submit/claim always revalidates and
 * reserves atomically. Entries pin the full generation they were computed
 * from (stock/revision digest, held-root generation, recipe digest, policy
 * generation, readiness/lease generation, request mode+target+amount); a
 * stale computation must never win over a newer generation (CAS publish).
 * Timeouts/queue pressure surface as explicit PENDING, never silent empty.
 *
 * @param <V> preview result type (immutable)
 */
public final class PreviewCache<V> {
    public record Key(
            String scope,
            String stockDigest,
            long heldGeneration,
            String recipeDigest,
            long policyGeneration,
            String mode,
            String target,
            long amount) {}

    private record Entry<V>(Key key, V value, long computedAtMs) {}

    private final ConcurrentHashMap<Key, Entry<V>> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Object> inFlight = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final java.util.function.LongSupplier clock;

    public PreviewCache(int maxEntries, java.util.function.LongSupplier clock) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries required");
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public PreviewCache(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    /** Returns the cached value only when the full generation still matches. */
    public V getIfCurrent(Key key) {
        var e = entries.get(key);
        return e == null ? null : e.value();
    }

    /**
     * Single-flight guard: at most one computation per key at a time.
     * Returns true when this caller won the computation slot.
     */
    public boolean beginCompute(Key key) {
        return inFlight.putIfAbsent(key, Boolean.TRUE) == null;
    }

    /** Publishes only when the generation still matches; evicts oldest on overflow. */
    public void publishIfCurrent(Key key, V value) {
        inFlight.remove(key);
        if (entries.size() >= maxEntries) {
            var oldest = entries.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(en -> en.getValue().computedAtMs()))
                    .map(Map.Entry::getKey);
            oldest.ifPresent(entries::remove);
        }
        entries.put(key, new Entry<>(key, value, clock.getAsLong()));
    }

    public void cancelCompute(Key key) {
        inFlight.remove(key);
    }

    public void invalidateScope(String scope) {
        entries.keySet().removeIf(k -> k.scope().equals(scope));
    }

    public int size() {
        return entries.size();
    }
}
