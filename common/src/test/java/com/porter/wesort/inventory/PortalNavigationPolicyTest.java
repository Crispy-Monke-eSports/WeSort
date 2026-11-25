package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalNavigationPolicyTest {
    @Test
    void onlyAnExplicitlyValidatedLobbyPortalCanNavigateBeforeInventoryReadback() {
        assertTrue(PortalNavigationPolicy.mayBootstrap(true, true, true, true, true));
        for (int missing = 0; missing < 5; missing++) {
            boolean[] inputs = {true, true, true, true, true};
            inputs[missing] = false;
            assertFalse(PortalNavigationPolicy.mayBootstrap(inputs[0], inputs[1], inputs[2], inputs[3], inputs[4]));
        }
    }

    @Test
    void lobbyTransitNeverDependsOnAgentLocalJournal() {
        // Manager is truth: even with a fenced local journal the navigation-only
        // portal bootstrap must stay allowed. Physical mutation paths keep their
        // own fence; this policy no longer takes a journal input at all.
        assertTrue(PortalNavigationPolicy.mayBootstrap(true, true, true, true, true));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedJournalOverloadIgnoresJournalFlag() {
        // Backwards compat: the old 6-arg overload must behave identically
        // regardless of the journal flag — transit never depends on it.
        assertTrue(PortalNavigationPolicy.mayBootstrap(true, true, true, true, true, true));
        assertTrue(PortalNavigationPolicy.mayBootstrap(true, true, true, true, true, false));
        assertFalse(PortalNavigationPolicy.mayBootstrap(false, true, true, true, true, true));
    }

    @Test
    void onlyPortalScopedFailuresCanResetOnLogout() {
        assertTrue(PortalNavigationPolicy.clearPauseOnDisconnect(true, true));
        assertFalse(PortalNavigationPolicy.clearPauseOnDisconnect(true, false));
        assertFalse(PortalNavigationPolicy.clearPauseOnDisconnect(false, true));
    }
}
