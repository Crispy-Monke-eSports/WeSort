package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutomationTokenTest {
    @Test
    void machineReadableDeadTokensDropTheLease() {
        assertTrue(StashManager.isDeadTokenReply("{\"error\":\"unknown sorting token\"}"));
        assertTrue(StashManager.isDeadTokenReply("{\"error\":\"sorting token expired\"}"));
        assertTrue(StashManager.isDeadTokenReply("{\"error\":\"invalid lease token\"}"));
        assertTrue(StashManager.isDeadTokenReply("{\"error\":\"token revoked by operator\"}"));
        assertTrue(StashManager.isDeadTokenReply("{\"error\":\"lease fingerprint mismatch\"}"));
    }

    @Test
    void transientConflictsKeepTheToken() {
        // Plain 409s and unrelated errors are transient: the token is kept with backoff.
        assertFalse(StashManager.isDeadTokenReply("{\"error\":\"physical conflict\"}"));
        assertFalse(StashManager.isDeadTokenReply("{\"error\":\"order holds reservations\"}"));
        assertFalse(StashManager.isDeadTokenReply("conflict"));
        assertFalse(StashManager.isDeadTokenReply(""));
        assertFalse(StashManager.isDeadTokenReply(null));
        assertFalse(StashManager.isDeadTokenReply("not json"));
        assertFalse(StashManager.isDeadTokenReply("[\"unknown token\"]"));
        assertFalse(StashManager.isDeadTokenReply("{\"error\":42}"));
        // A token mention without a dead marker is not enough.
        assertFalse(StashManager.isDeadTokenReply("{\"error\":\"token required\"}"));
    }
}
