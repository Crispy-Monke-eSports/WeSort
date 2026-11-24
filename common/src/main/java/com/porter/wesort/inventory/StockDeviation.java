package com.porter.wesort.inventory;

import java.util.Map;
import java.util.TreeMap;

/** A new source observation is not evidence that an uncertain mutation succeeded. */
public final class StockDeviation {
    private StockDeviation() {}

    public static boolean mayReturnIntact(
            boolean sourceConfirmed,
            boolean journalClear,
            boolean workspaceMatches,
            boolean cursorEmpty,
            int transfers,
            boolean alreadyReturning) {
        return sourceConfirmed
                && journalClear
                && workspaceMatches
                && cursorEmpty
                && transfers == 0
                && !alreadyReturning;
    }

    /** Only the verified source contents may change; unrelated inventory differences are not forgiven. */
    public static boolean matchesWithObservedSource(
            Map<String, Long> baseline,
            Map<String, Long> previousSource,
            Map<String, Long> observedSource,
            Map<String, Long> actual) {
        var expected = new TreeMap<>(baseline);
        for (var entry : previousSource.entrySet()) {
            Long total = expected.get(entry.getKey());
            if (total == null || total < entry.getValue()) return false;
            long rest = total - entry.getValue();
            if (rest == 0) expected.remove(entry.getKey());
            else expected.put(entry.getKey(), rest);
        }
        for (var entry : observedSource.entrySet()) expected.merge(entry.getKey(), entry.getValue(), Math::addExact);
        return expected.equals(actual);
    }
}
