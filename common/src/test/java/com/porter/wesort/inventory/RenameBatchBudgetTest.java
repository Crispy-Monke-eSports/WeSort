package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RenameBatchBudgetTest {
    @Test
    void fiveOrdinaryBoxesRequireFiveLevels() {
        assertEquals(1, RenameBatchBudget.renameOnlyCost(0));
        assertEquals(5, RenameBatchBudget.totalLevels(List.of(1, 1, 1, 1, 1)));
        assertEquals(5, RenameBatchBudget.totalLevels(List.of(1, 1, 1, 1, 1, 1)));
        assertEquals(5, RenameBatchBudget.affordablePrefix(List.of(1, 1, 1, 1, 1, 1), 10));
    }

    @Test
    void priorWorkRaisesTheBudgetUpToTheRenameOnlyCap() {
        assertEquals(4, RenameBatchBudget.renameOnlyCost(3));
        assertEquals(39, RenameBatchBudget.renameOnlyCost(Integer.MAX_VALUE));
        assertEquals(7, RenameBatchBudget.totalLevels(List.of(1, 4, 2)));
    }

    @Test
    void partialBudgetNeverStartsAnUnaffordableRename() {
        assertEquals(2, RenameBatchBudget.affordablePrefix(List.of(1, 2, 4, 1), 3));
        assertEquals(0, RenameBatchBudget.affordablePrefix(List.of(4, 1), 3));
        assertEquals(0, RenameBatchBudget.affordablePrefix(List.of(1), 0));
        assertEquals(0, RenameBatchBudget.affordablePrefix(List.of(), 5));
    }
}
