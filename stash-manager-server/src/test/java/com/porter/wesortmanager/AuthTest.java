package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {
    // ---------- password hash ----------

    @Test
    void hashVerifiesOnlyTheRightPassword() {
        String stored = Auth.hash("correct horse", 1000);
        assertTrue(stored.startsWith("pbkdf2-sha256$1000$"));
        assertTrue(Auth.verify("correct horse", stored));
        assertFalse(Auth.verify("correct horsf", stored));
        assertFalse(Auth.verify("", stored));
        assertFalse(Auth.verify(null, stored));
    }

    @Test
    void defaultHashUses600kIterationsAndARandomSalt() {
        String a = Auth.hash("password1");
        String b = Auth.hash("password1");
        assertTrue(a.startsWith("pbkdf2-sha256$600000$"));
        assertNotEquals(a, b, "the salt must differ");
        assertTrue(Auth.verify("password1", a));
        String[] parts = a.split("\\$");
        assertEquals(16, java.util.Base64.getDecoder().decode(parts[2]).length);
        assertEquals(32, java.util.Base64.getDecoder().decode(parts[3]).length);
    }

    @Test
    void malformedStoredStringsNeverMatch() {
        assertFalse(Auth.verify("x", null));
        assertFalse(Auth.verify("x", ""));
        assertFalse(Auth.verify("x", "plain-text-password"));
        assertFalse(Auth.verify("x", "pbkdf2-sha256$abc$AAAA$AAAA"));
        assertFalse(Auth.verify("x", "pbkdf2-sha256$99999999999$AAAA$AAAA"));
        assertFalse(Auth.verify("x", "pbkdf2-sha256$1000$%%%$AAAA"));
        assertFalse(Auth.wellFormed("nope"));
        assertTrue(Auth.wellFormed(Auth.hash("x", 10)));
    }

    // ---------- sessions ----------

    @Test
    void sessionsExpireAfter30Days(@TempDir Path dir) throws Exception {
        AtomicLong now = new AtomicLong(1_000_000);
        SessionStore store = new SessionStore(dir, now::get);
        String token = store.create();
        assertTrue(store.valid(token));
        assertFalse(store.valid(token + "x"));
        now.addAndGet(SessionStore.LIFETIME_MS - 1);
        assertTrue(store.valid(token));
        now.addAndGet(1);
        assertFalse(store.valid(token));
        assertEquals(0, store.size());
    }

    @Test
    void sessionsSurviveARestartAndOnlyHashesAreStored(@TempDir Path dir) throws Exception {
        AtomicLong now = new AtomicLong(5_000);
        SessionStore store = new SessionStore(dir, now::get);
        String token = store.create();
        String file = Files.readString(dir.resolve("sessions.json"));
        assertFalse(file.contains(token), "the token itself must not be written");
        assertTrue(file.contains(SessionStore.hash(token)));

        SessionStore reloaded = new SessionStore(dir, now::get);
        reloaded.load();
        assertTrue(reloaded.valid(token));

        now.addAndGet(SessionStore.LIFETIME_MS);
        SessionStore later = new SessionStore(dir, now::get);
        later.load();
        assertEquals(0, later.size(), "expired sessions are pruned on load");
    }

    @Test
    void failedSessionWritePreservesTheOldSessionAndItsElevation(@TempDir Path dir) throws Exception {
        var clock = new AtomicLong(1);
        var store = new SessionStore(dir, clock::get);
        String oldest = store.create();
        store.elevate(oldest);
        for (int i = 1; i < SessionStore.MAX_PER_USER; i++) {
            clock.incrementAndGet();
            store.create();
        }
        Path sessionsFile = dir.resolve("sessions.json");
        Path saved = Files.move(sessionsFile, dir.resolve("previous.json"));
        Files.createDirectory(sessionsFile);
        assertThrows(java.io.IOException.class, store::create);
        assertEquals(SessionStore.MAX_PER_USER, store.size());
        assertTrue(store.valid(oldest));
        assertTrue(store.elevated(oldest));
        Files.delete(sessionsFile);
        Files.move(saved, sessionsFile);
        var restarted = new SessionStore(dir, clock::get);
        restarted.load();
        assertTrue(restarted.valid(oldest));
    }

    @Test
    void logoutAndLogoutEverywhere(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir, () -> 1);
        String a = store.create();
        String b = store.create();
        String c = store.create();
        store.remove(a);
        assertFalse(store.valid(a));
        assertTrue(store.valid(b));
        assertEquals(1, store.removeAllExcept(b));
        assertTrue(store.valid(b));
        assertFalse(store.valid(c));
        assertEquals(1, store.removeAllExcept(null));
        assertFalse(store.valid(b));
    }

    @Test
    void cookieParsing() {
        String token = "abc_DEF-123";
        assertEquals(token, SessionStore.tokenFrom(List.of("other=1; wesort_session=" + token + "; x=y")));
        assertNull(SessionStore.tokenFrom(List.of("other=1")));
        assertNull(SessionStore.tokenFrom(null));
        String cookie = SessionStore.cookie(token);
        assertTrue(cookie.contains("HttpOnly")
                && cookie.contains("SameSite=Strict")
                && cookie.contains("Max-Age=2592000"));
    }
}
