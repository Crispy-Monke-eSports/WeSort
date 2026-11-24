package com.porter.wesort.inventory;

import java.util.List;

/** Rename-only anvil costs are paid in levels, not raw XP points. */
public final class RenameBatchBudget {
    public static final int MAX_BOXES = 5;

    private RenameBatchBudget() {}

    public static int renameOnlyCost(int priorWorkPenalty) {
        // Vanilla caps a name-only operation at 39, even when prior work is high.
        return 1 + Math.min(38, Math.max(0, priorWorkPenalty));
    }

    public static int totalLevels(List<Integer> costs) {
        return costs.stream().limit(MAX_BOXES).mapToInt(Integer::intValue).sum();
    }

    /** Keep the first boxes in order if available XP cannot fund the complete batch. */
    public static int affordablePrefix(List<Integer> costs, int levels) {
        int count = 0;
        for (int cost : costs) {
            if (count == MAX_BOXES || cost > levels) break;
            levels -= cost;
            count++;
        }
        return count;
    }
}
