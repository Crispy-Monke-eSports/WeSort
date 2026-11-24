package com.porter.wesort.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/** Routine inventory observations are rate-limited independently of physical-operation verification. */
public final class SurveyBatch {
    public static final long MIN_INTERVAL_MS = 300_000;
    public static final int MAX_BOXES = 10;

    private SurveyBatch() {}

    public static boolean due(long now, long lastRead, long lastAttempt, long intervalMs) {
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
