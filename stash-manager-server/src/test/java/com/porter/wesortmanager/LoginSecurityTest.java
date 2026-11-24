package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rate limiter, new-password rules, the setup code and sudo mode. */
class LoginSecurityTest {
    // These tests exercise sequential failure accounting; concurrent admission
    // and release are covered separately in LoginLimiterTest.
    private static long sequentialAttempt(LoginLimiter limiter, String key, boolean loopback) {
        long wait = limiter.begin(key, key, loopback);
        if (wait == 0) limiter.end(key, loopback);
        return wait;
    }

    @Test
    void fiveFailuresLockAnAddressWithDoublingLockouts() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, sequentialAttempt(limiter, "10.0.0.5", false));
            limiter.failure("10.0.0.5", false);
        }
        assertEquals(60_000, sequentialAttempt(limiter, "10.0.0.5", false));
        assertEquals(0, sequentialAttempt(limiter, "10.0.0.6", false), "other addresses are not affected");

        now.addAndGet(60_000);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, sequentialAttempt(limiter, "10.0.0.5", false));
            limiter.failure("10.0.0.5", false);
        }
        assertEquals(120_000, sequentialAttempt(limiter, "10.0.0.5", false), "the second lockout doubles");
    }

    @Test
    void perAddressLockoutStopsAt15Minutes() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 5; i++) {
                sequentialAttempt(limiter, "a", false);
                limiter.failure("a", false);
            }
            if (round < 5) now.addAndGet(LoginLimiter.GLOBAL_WINDOW_MS + LoginLimiter.MAX_LOCK_MS);
        }
        assertEquals(LoginLimiter.MAX_LOCK_MS, sequentialAttempt(limiter, "a", false));
    }

    @Test
    void successResetsTheAddress() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int i = 0; i < 4; i++) {
            sequentialAttempt(limiter, "a", false);
            limiter.failure("a", false);
        }
        sequentialAttempt(limiter, "a", false);
        limiter.success("a");
        for (int i = 0; i < 4; i++) {
            assertEquals(0, sequentialAttempt(limiter, "a", false));
            limiter.failure("a", false);
        }
        assertEquals(0, sequentialAttempt(limiter, "a", false), "the count started again after the success");
    }

    @Test
    void globalPauseAfter20FailuresIn10MinutesEscalates() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        // 20 failures over 5 addresses, 4 each, so no single address locks
        for (int i = 0; i < 20; i++) {
            String ip = "10.0.3." + (i / 4);
            assertEquals(0, sequentialAttempt(limiter, ip, false));
            limiter.failure(ip, false);
            now.addAndGet(1000);
        }
        long wait = sequentialAttempt(limiter, "10.9.9.9", false);
        assertTrue(
                wait > LoginLimiter.GLOBAL_LOCK_MS - 2000 && wait <= LoginLimiter.GLOBAL_LOCK_MS,
                "everyone else is paused for 15 minutes: " + wait);
        assertEquals(0, sequentialAttempt(limiter, "127.0.0.1", true), "the server PC itself is exempt");

        now.addAndGet(LoginLimiter.GLOBAL_LOCK_MS);
        for (int i = 0; i < 20; i++) {
            String ip = "10.0.4." + (i / 4);
            sequentialAttempt(limiter, ip, false);
            limiter.failure(ip, false);
        }
        assertEquals(
                2 * LoginLimiter.GLOBAL_LOCK_MS,
                sequentialAttempt(limiter, "10.9.9.9", false),
                "the next pause doubles");
    }

    @Test
    void successfulAttemptsDontCountTowardTheGlobalPause() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, sequentialAttempt(limiter, "10.0.6." + i, false), "attempt " + i);
            limiter.success("10.0.6." + i);
        }
    }

    @Test
    void failuresSpreadOver10MinutesDontPause() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int i = 0; i < 40; i++) {
            String ip = "10.0.5." + i;
            assertEquals(0, sequentialAttempt(limiter, ip, false), "attempt " + i);
            limiter.failure(ip, false);
            now.addAndGet(LoginLimiter.GLOBAL_WINDOW_MS / 19 + 1);
        }
    }

    @Test
    void loopbackIsNeverLimited() {
        AtomicLong now = new AtomicLong(0);
        LoginLimiter limiter = new LoginLimiter(now::get);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, sequentialAttempt(limiter, "127.0.0.1", true));
            limiter.failure("127.0.0.1", true);
        }
        assertEquals(
                0,
                sequentialAttempt(limiter, "10.0.0.1", false),
                "loopback failures don't count toward the global pause");
    }

    @Test
    void ipv6IsKeyedPer64() throws Exception {
        String a = LoginLimiter.key(InetAddress.getByName("2001:db8:1:2::1"));
        String b = LoginLimiter.key(InetAddress.getByName("2001:db8:1:2:ffff::9"));
        String c = LoginLimiter.key(InetAddress.getByName("2001:db8:1:3::1"));
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals("10.0.0.7", LoginLimiter.key(InetAddress.getByName("10.0.0.7")));
    }

    @Test
    void newPasswordRules() {
        assertTrue(Auth.checkNew("short").contains("12"));
        assertTrue(Auth.checkNew("elevenchars").contains("12"));
        assertNull(Auth.checkNew("correct horse battery"));
        assertTrue(Auth.checkNew("password1234").contains("common"));
        assertTrue(Auth.checkNew("PassWord1234").contains("common"), "case-insensitive");
        assertTrue(Auth.checkNew("123456789012").contains("common"));
        assertNotNull(Auth.checkNew(null));
    }

    @Test
    void setupCodes() {
        String code = Auth.newSetupCode();
        assertTrue(code.matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"), code);
        assertNotEquals(code, Auth.newSetupCode());
        assertTrue(Auth.codeMatches(code, code));
        assertTrue(
                Auth.codeMatches(code.toLowerCase().replace("-", " "), code), "case, spaces and dashes don't matter");
        assertTrue(Auth.codeMatches(code.replace("-", ""), code));
        assertFalse(Auth.codeMatches(code.equals("AAAA-AAAA") ? "BBBB-BBBB" : "AAAA-AAAA", code));
        assertFalse(Auth.codeMatches("", code));
        assertFalse(Auth.codeMatches(null, code));
        assertFalse(Auth.codeMatches(code, null), "no code once the password is set");
    }

    @Test
    void sudoModeLasts15MinutesPerSession(@TempDir Path dir) throws Exception {
        AtomicLong now = new AtomicLong(1000);
        SessionStore store = new SessionStore(dir, now::get);
        String a = store.create();
        String b = store.create();
        assertFalse(store.elevated(a));
        store.elevate(a);
        assertTrue(store.elevated(a));
        assertFalse(store.elevated(b), "only the session that typed the password");
        now.addAndGet(SessionStore.ELEVATED_MS);
        assertFalse(store.elevated(a));
        store.elevate(a);
        store.remove(a);
        assertFalse(store.elevated(a), "logging out ends it");
    }
}
