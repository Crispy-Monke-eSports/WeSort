package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginLimiterTest {
    @Test
    void remoteAttemptsCannotRaceAcrossAccountsOnTheSameAddress() {
        var clock = new FakeClock(1_000);
        var limiter = new LoginLimiter(clock);
        String address = "remote";
        String account = address + "|member";
        for (int i = 0; i < LoginLimiter.FAILURES_BEFORE_LOCK; i++) {
            assertEquals(0, limiter.begin(account, address, false));
            assertTrue(limiter.begin(address + "|other", address, false) > 0);
            limiter.failure(account, false);
            limiter.addressFailure(address, false);
            limiter.end(address, false);
        }
        assertEquals(LoginLimiter.FIRST_LOCK_MS, limiter.begin(account, address, false));
        clock.advance(LoginLimiter.FIRST_LOCK_MS);
        assertEquals(0, limiter.begin(account, address, false));
        limiter.end(address, false);
    }

    @Test
    void distributedAttemptsHaveABoundedNumberOfSimultaneousPasswordChecks() {
        var limiter = new LoginLimiter(new FakeClock(1_000L));
        for (int i = 0; i < 8; i++) assertEquals(0, limiter.begin("a" + i, "ip" + i, false));
        assertTrue(limiter.begin("ninth", "ip8", false) > 0);
        assertEquals(0, limiter.begin("owner", "loopback", true));
        limiter.end("ip0", false);
        assertEquals(0, limiter.begin("ninth", "ip8", false));
    }

    @Test
    void releaseDoesNotClearFailureHistoryOrUnlockOtherAddresses() {
        var limiter = new LoginLimiter(new FakeClock(1_000L));
        assertEquals(0, limiter.begin("a", "ip1", false));
        assertEquals(0, limiter.begin("b", "ip2", false));
        limiter.end("ip1", false);
        assertTrue(limiter.begin("b", "ip2", false) > 0);
        assertEquals(0, limiter.begin("a", "ip1", false));
    }
}
