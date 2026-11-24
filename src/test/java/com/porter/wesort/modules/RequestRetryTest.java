package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestRetryTest {
    @Test
    void independentChannelsBackOffAndRecoverWithoutStarvingEachOther() {
        var sorting = new RequestRetry();
        var control = new RequestRetry();
        assertEquals(5_000, sorting.failureDelayMs());
        assertEquals(10_000, sorting.failureDelayMs());
        assertEquals(5_000, control.failureDelayMs());
        control.reset();
        assertEquals(20_000, sorting.failureDelayMs());
        assertEquals(40_000, sorting.failureDelayMs());
        assertEquals(60_000, sorting.failureDelayMs());
        assertEquals(60_000, sorting.failureDelayMs());
        assertEquals(5_000, control.failureDelayMs());
        sorting.reset();
        assertEquals(5_000, sorting.failureDelayMs());
    }
}
