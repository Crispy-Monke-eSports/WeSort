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
    void activeSessionsExpireAtTheAbsoluteDeadline(@TempDir Path dir) throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        SessionStore store = new SessionStore(dir, now);
        String token = store.create();
        assertTrue(store.valid(token));
        assertFalse(store.valid(token + "x"));
        long end = now.get() + SessionStore.LIFETIME_MS;
        while (now.get() + 1_800_000 < end) {
            now.advance(1_800_000);
            assertTrue(store.valid(token));
        }
        now.set(end - 1);
        assertTrue(store.valid(token));
        now.advance(1);
        assertFalse(store.valid(token));
        assertEquals(0, store.size());
    }

    @Test
    void idleExpirySurvivesRestartAndActivityRefreshesOnlyItsOwnSession(@TempDir Path dir) throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        long hour = SessionStore.idlePolicy("1");
        SessionStore store = new SessionStore(dir, now, hour);
        String idle = store.create();
        String active = store.create();
        now.advance(hour - 1);
        assertTrue(store.valid(active));
        now.advance(1);
        var restarted = new SessionStore(dir, now, hour);
        restarted.load();
        assertFalse(restarted.valid(idle));
        assertTrue(restarted.valid(active));
        now.advance(hour);
        assertFalse(restarted.valid(active));
        assertThrows(IllegalArgumentException.class, () -> SessionStore.idlePolicy("0"));
        assertThrows(IllegalArgumentException.class, () -> SessionStore.idlePolicy("169"));
        assertThrows(IllegalArgumentException.class, () -> SessionStore.idlePolicy("unlimited"));
    }

    @Test
    void previouslyIssuedLongSessionCannotOutliveNewPolicy(@TempDir Path dir) throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        SessionStore store = new SessionStore(dir, now);
        String token = store.create();
        var path = dir.resolve("sessions.json");
        var rows =
                com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonArray();
        var row = rows.get(0).getAsJsonObject();
        now.advance(SessionStore.LIFETIME_MS);
        row.addProperty("expires", now.get() + SessionStore.LIFETIME_MS);
        row.addProperty("lastSeen", now.get() - 1);
        Files.writeString(path, rows.toString());
        store.load();
        assertFalse(store.valid(token));
    }

    @Test
    void sessionsSurviveARestartAndOnlyHashesAreStored(@TempDir Path dir) throws Exception {
        FakeClock now = new FakeClock(5_000);
        SessionStore store = new SessionStore(dir, now);
        String token = store.create();
        String file = Files.readString(dir.resolve("sessions.json"));
        assertFalse(file.contains(token), "the token itself must not be written");
        assertTrue(file.contains(SessionStore.hash(token)));

        SessionStore reloaded = new SessionStore(dir, now);
        reloaded.load();
        assertTrue(reloaded.valid(token));

        now.advance(SessionStore.LIFETIME_MS);
        SessionStore later = new SessionStore(dir, now);
        later.load();
        assertEquals(0, later.size(), "expired sessions are pruned on load");
    }

    @Test
    void failedSessionWritePreservesTheOldSessionAndItsElevation(@TempDir Path dir) throws Exception {
        var clock = new FakeClock(1);
        var store = new SessionStore(dir, clock);
        String oldest = store.create();
        store.elevate(oldest);
        for (int i = 1; i < SessionStore.MAX_PER_USER; i++) {
            clock.advance(1);
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
        var restarted = new SessionStore(dir, clock);
        restarted.load();
        assertTrue(restarted.valid(oldest));
    }

    @Test
    void logoutAndLogoutEverywhere(@TempDir Path dir) throws Exception {
        SessionStore store = new SessionStore(dir, new FakeClock(1));
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
    void corruptSessionRegistryNeverAuthenticatesAPartiallyLoadedPrefix(@TempDir Path dir) throws Exception {
        var store = new SessionStore(dir, new FakeClock(1));
        String token = store.create();
        Path file = dir.resolve("sessions.json");
        var rows =
                com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonArray();
        rows.add(new com.google.gson.JsonObject());
        Files.writeString(file, rows.toString());

        var restarted = new SessionStore(dir, new FakeClock(1));
        restarted.load();
        assertEquals(0, restarted.size());
        assertFalse(restarted.valid(token));
    }

    @Test
    void failedRevocationIsNotReportedAsADurableLogout(@TempDir Path dir) throws Exception {
        var store = new SessionStore(dir, new FakeClock(1));
        String owner = store.create();
        String member = store.create("u-123456789abc");
        store.elevate(owner);
        Path file = dir.resolve("sessions.json");
        Path saved = Files.move(file, dir.resolve("sessions.saved"));
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "write failure");

        assertThrows(java.io.IOException.class, () -> store.remove(owner));
        assertThrows(java.io.IOException.class, () -> store.removeUser("u-123456789abc", null));
        assertThrows(java.io.IOException.class, () -> store.removeAllExcept(null));
        assertTrue(store.valid(owner));
        assertTrue(store.valid(member));
        assertTrue(store.elevated(owner));

        Files.delete(file.resolve("obstruction"));
        Files.delete(file);
        Files.move(saved, file);
        store.remove(owner);
        assertEquals(1, store.removeUser("u-123456789abc", null));
        var restarted = new SessionStore(dir, new FakeClock(1));
        restarted.load();
        assertFalse(restarted.valid(owner));
        assertFalse(restarted.valid(member));
    }

    @Test
    void cookieParsing() {
        String token = "abc_DEF-123";
        assertEquals(token, SessionStore.tokenFrom(List.of("other=1; wesort_session=" + token + "; x=y")));
        assertNull(SessionStore.tokenFrom(List.of("other=1")));
        assertNull(SessionStore.tokenFrom(null));
        String cookie = SessionStore.cookie(token);
        assertTrue(
                cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Max-Age=604800"));
    }
}
