package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ControlRetryScheduleTest {
    @Test
    void retriesAtFixedFifteenSecondStartIntervalsEvenAfterManyFailures() {
        var schedule = new ControlRetrySchedule();
        long start = -100_000; // nanoTime has no guaranteed positive origin.
        for (int i = 0; i < 20; i++) {
            assertTrue(schedule.due(start));
            schedule.started(start);
            schedule.failed(start + ControlRetrySchedule.REQUEST_TIMEOUT_MS);
            assertFalse(schedule.due(start + 14_999));
            assertTrue(schedule.due(start + 15_000));
            start += 15_000;
        }
    }

    @Test
    void verifiedReplyRestoresFastPollingAndExpiresPresence() {
        var schedule = new ControlRetrySchedule();
        schedule.started(0);
        schedule.succeeded(100);
        assertTrue(schedule.recentlyVerified(100));
        assertFalse(schedule.due(2_099));
        assertTrue(schedule.due(2_100));
        assertFalse(schedule.recentlyVerified(35_100));
        schedule.started(35_100);
        schedule.failed(35_200);
        assertFalse(schedule.recentlyVerified(35_200));
        schedule.reset();
        assertTrue(schedule.due(35_201));
    }

    @Test
    void localFailureDoesNotKeepSlidingTheRetryDeadline() {
        var schedule = new ControlRetrySchedule();
        schedule.failed(1_000);
        schedule.failed(2_000);
        schedule.failed(15_999);
        assertTrue(schedule.due(16_000));
    }
}
