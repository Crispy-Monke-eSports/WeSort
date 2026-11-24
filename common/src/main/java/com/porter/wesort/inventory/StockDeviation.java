package com.porter.wesort.inventory;

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
        return sourceConfirmed && journalClear && workspaceMatches && cursorEmpty && transfers == 0 && !alreadyReturning;
    }
}
