package com.porter.wesort.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/** Routine inventory observations are rate-limited independently of physical-operation verification.
 * Invariant: MIN_INTERVAL_MS must stay well below the stock TTL (WESORT_STOCK_MAX_AGE_SECONDS):
 * steady-state requires TTL >= 4 * MIN_INTERVAL to absorb a full pass (69 containers) plus retries.
 * See issues #199/#201. */
public final class SurveyBatch {
    public static final long MIN_INTERVAL_MS = 60_000;
    public static final int MAX_BOXES = 10;

    private SurveyBatch() {}

    public static boolean due(long now, long lastRead, long lastAttempt, long intervalMs) {
        return due(now, lastRead, lastAttempt, intervalMs, false);
    }

    /** A one-shot explicit rescan or a required physical receipt is not background polling. */
    public static boolean due(long now, long lastRead, long lastAttempt, long intervalMs, boolean requiredReceipt) {
        if (requiredReceipt) return true;
        long last = Math.max(lastRead, lastAttempt);
        return last <= 0 || now >= last && now - last >= Math.max(MIN_INTERVAL_MS, intervalMs);
    }

    public static <T> List<T> oldest(List<T> candidates, ToLongFunction<T> lastRead, ToLongFunction<T> lastAttempt) {
        return candidates.stream()
                .sorted(Comparator.comparingLong(lastRead).thenComparingLong(lastAttempt))
                .limit(MAX_BOXES)
                .toList();
    }
}
