package com.porter.wesort.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The connection screen and chat command must never claim "Connected" before a verified reply. */
class ConnectionStateTest {
    @Test
    void moduleOffIsDisconnected() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.of(false, "http://h:1", "k", "online"));
    }

    @Test
    void missingAddressOrKeyIsNotConfigured() {
        assertEquals(ConnectionState.NOT_CONFIGURED, ConnectionState.of(true, "", "k", "idle"));
        assertEquals(ConnectionState.NOT_CONFIGURED, ConnectionState.of(true, "http://h:1", "", "idle"));
        assertEquals(ConnectionState.NOT_CONFIGURED, ConnectionState.of(true, "http://h:1", "k", "no key"));
    }

    @Test
    void onlyVerifiedOnlineIsOnline() {
        assertEquals(ConnectionState.ONLINE, ConnectionState.of(true, "http://h:1", "k", "online"));
        // Operational states mean local work, not a verified reply: never ONLINE.
        assertEquals(ConnectionState.CONTACTING, ConnectionState.of(true, "http://h:1", "k", "idle"));
        assertEquals(
                ConnectionState.CONTACTING, ConnectionState.of(true, "http://h:1", "k", "awaiting server inventory"));
        assertEquals(
                ConnectionState.CONTACTING,
                ConnectionState.of(true, "http://h:1", "k", "physical operation needs reconciliation"));
    }

    @Test
    void failuresMapToTheirOwnStates() {
        assertEquals(ConnectionState.BAD_ADDRESS, ConnectionState.of(true, "http://h:1", "k", "bad url"));
        assertEquals(ConnectionState.WRONG_KEY, ConnectionState.of(true, "http://h:1", "k", "error 401"));
        assertEquals(ConnectionState.UNREACHABLE, ConnectionState.of(true, "http://h:1", "k", "offline"));
        assertEquals(ConnectionState.WRONG_SERVER, ConnectionState.of(true, "http://h:1", "k", "wrong server"));
        assertEquals(
                ConnectionState.SIGNATURE_INVALID,
                ConnectionState.of(true, "http://h:1", "k", "server signature invalid"));
    }

    @Test
    void signedControlPresenceIsOnlineEvenWithoutStashOrInventory() {
        assertEquals(ConnectionState.ONLINE, ConnectionState.ofVerified(true, "http://h:1", "k", "no stash", true));
        assertEquals(
                ConnectionState.ONLINE,
                ConnectionState.ofVerified(true, "http://h:1", "k", "awaiting server inventory", true));
        assertEquals(
                ConnectionState.ONLINE,
                ConnectionState.ofVerified(true, "http://h:1", "k", "physical operation needs reconciliation", true));
        assertEquals(
                ConnectionState.CONTACTING,
                ConnectionState.ofVerified(true, "http://h:1", "k", "awaiting server inventory", false));
    }

    @Test
    void oldOnlineFlagDoesNotSurviveConnectionLoss() {
        assertEquals(
                ConnectionState.CONTACTING, ConnectionState.ofVerified(true, "http://h:1", "k", "online", false));
        assertEquals(ConnectionState.ONLINE, ConnectionState.ofVerified(true, "http://h:1", "k", "online", true));
    }

    @Test
    void staleAuthNeverMasksDisconnectOrRealFailures() {
        assertEquals(ConnectionState.DISCONNECTED, ConnectionState.ofVerified(false, "http://h:1", "k", "idle", true));
        assertEquals(ConnectionState.NOT_CONFIGURED, ConnectionState.ofVerified(true, "", "k", "idle", true));
        assertEquals(ConnectionState.WRONG_KEY, ConnectionState.ofVerified(true, "http://h:1", "k", "error 401", true));
        assertEquals(
                ConnectionState.WRONG_SERVER,
                ConnectionState.ofVerified(true, "http://h:1", "k", "wrong server", true));
        assertEquals(
                ConnectionState.SIGNATURE_INVALID,
                ConnectionState.ofVerified(true, "http://h:1", "k", "server signature invalid", true));
        assertEquals(ConnectionState.UNREACHABLE, ConnectionState.ofVerified(true, "http://h:1", "k", "offline", true));
    }

    @Test
    void everyStateHasALabelAndAHint() {
        for (ConnectionState state : ConnectionState.values()) {
            assertEquals(false, state.label().isBlank());
            assertEquals(false, state.hint().isBlank());
        }
    }
}
