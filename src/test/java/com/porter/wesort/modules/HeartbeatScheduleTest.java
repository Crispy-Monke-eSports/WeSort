package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeartbeatScheduleTest {
    @Test
    void heartbeatCadenceIsIndependentOfControlSchedule() {
        var heartbeat = new ControlRetrySchedule(5_000);
        var control = new ControlRetrySchedule();
        heartbeat.started(0);
        control.started(0);
        heartbeat.succeeded(100);
        control.failed(100);
        assertTrue(heartbeat.recentlyVerified(100));
        assertFalse(heartbeat.due(5_099));
        assertTrue(heartbeat.due(5_100));
        assertFalse(control.due(5_100));
        heartbeat.started(5_100);
        heartbeat.failed(10_100);
        assertFalse(heartbeat.recentlyVerified(10_100));
        assertFalse(heartbeat.due(20_099));
        assertTrue(heartbeat.due(20_100));
        assertTrue(control.due(15_000));
    }
}
