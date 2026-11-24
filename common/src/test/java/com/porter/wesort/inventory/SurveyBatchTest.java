package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SurveyBatchTest {
    @Test
    void neverPollAgainBeforeFiveMinutesEvenWithOldConfiguration() {
        assertTrue(SurveyBatch.due(1000, 0, 0, 30_000));
        assertFalse(SurveyBatch.due(300_999, 1000, 0, 30_000));
        assertTrue(SurveyBatch.due(301_000, 1000, 0, 30_000));
        assertFalse(SurveyBatch.due(301_000, 1000, 2000, 30_000));
        assertFalse(SurveyBatch.due(301_000, 1000, 0, 600_000));
    }

    @Test
    void explicitRescanAndRequiredReceiptsAreNotRoutinePolling() {
        assertTrue(SurveyBatch.due(2000, 1000, 1000, 300_000, true));
        assertFalse(SurveyBatch.due(2000, 1000, 1000, 300_000, false));
    }

    @Test
    void oldestTenAreSelectedAndSmallerBatchesAreAllowed() {
        List<Integer> candidates =
                IntStream.rangeClosed(1, 13).map(i -> 14 - i).boxed().toList();
        assertEquals(
                IntStream.rangeClosed(1, 10).boxed().toList(),
                SurveyBatch.oldest(candidates, Integer::longValue, ignored -> 0));
        assertEquals(List.of(1, 3), SurveyBatch.oldest(List.of(3, 1), Integer::longValue, ignored -> 0));
    }

    @Test
    void equalReadTimesPreferTheLeastRecentlyAttempted() {
        assertEquals(List.of(1, 2, 3), SurveyBatch.oldest(List.of(3, 1, 2), ignored -> 0, Integer::longValue));
    }
}
