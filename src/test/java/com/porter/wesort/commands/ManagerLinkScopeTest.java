package com.porter.wesort.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagerLinkScopeTest {
    @Test
    void aReplyIsBoundToTheManagerUrlMinecraftServerAndSigningCredential() {
        String issuedFor = ManagerLinkScope.of("https://manager.example:8765", "6b6t.org", "first-key");

        assertTrue(ManagerLinkScope.matches(
                issuedFor, ManagerLinkScope.of("https://manager.example:8765", "6b6t.org", "first-key")));
        assertFalse(ManagerLinkScope.matches(
                issuedFor, ManagerLinkScope.of("https://manager.example:8765", "6b6t.org", "rotated-key")));
        assertFalse(ManagerLinkScope.matches(
                issuedFor, ManagerLinkScope.of("https://different.example:8765", "6b6t.org", "first-key")));
        assertFalse(ManagerLinkScope.matches(
                issuedFor, ManagerLinkScope.of("https://manager.example:8765", "other-server", "first-key")));
    }

    @Test
    void scopeNeverContainsTheSigningSecret() {
        String secret = "do-not-echo-this-credential";
        String scope = ManagerLinkScope.of("http://127.0.0.1:8765", "6b6t.org", secret);
        assertFalse(scope.contains(secret));
        assertNotEquals(scope, ManagerLinkScope.of("http://127.0.0.1:8765", "6b6t.org", "new-key"));
    }

    @Test
    void managerUrlTrailingSlashDoesNotCauseAFalseStaleReply() {
        String requested = ManagerLinkScope.of("http://127.0.0.1:8765", "6b6t.org", "key");
        String configured = ManagerLinkScope.of("http://127.0.0.1:8765/", "6b6t.org", "key");
        assertTrue(ManagerLinkScope.matches(requested, configured));
    }

    @Test
    void missingConnectionCannotCertifyAnyReply() {
        assertFalse(ManagerLinkScope.matches("", ""));
        assertFalse(ManagerLinkScope.matches(null, ""));
        assertFalse(ManagerLinkScope.matches(ManagerLinkScope.of("http://127.0.0.1:8765", "6b6t.org", "key"), ""));
        assertFalse(ManagerLinkScope.matches(
                ManagerLinkScope.of("", "6b6t.org", "key"), ManagerLinkScope.of("", "6b6t.org", "key")));
    }
}
