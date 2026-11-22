package com.porter.wesort.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalWorldSessionTest {
    @Test
    void directSurvivalLoginDoesNotArmPortalMerelyBecauseServerIs6b6t() {
        var session = new PortalWorldSession();
        Object survival = new Object();
        assertFalse(session.observe(survival, "minecraft:overworld", 80_000, -5_000));
        assertFalse(session.inLobby());
        assertFalse(session.arrived());
        assertFalse(session.observe(survival, "minecraft:overworld", 80_100, -5_100));
        assertFalse(session.inLobby());
    }

    @Test
    void aWorldChangeWithoutPortalEvidenceIsNotSurvivalProof() {
        var session = new PortalWorldSession();
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        assertFalse(session.arrived());
        assertFalse(session.inLobby());
    }

    @Test
    void crossingThenWorldTransferCompletesTransit() {
        var session = new PortalWorldSession();
        Object lobby = new Object();
        assertFalse(session.observe(lobby, "minecraft:overworld", 0, 0));
        session.lobbySeen(0, 0);
        assertTrue(session.inLobby());
        session.crossingAttempted();
        assertFalse(session.observe(lobby, "minecraft:overworld", 0, 0));
        assertTrue(session.observe(new Object(), "minecraft:overworld", 0, 0));
        assertTrue(session.arrived());
        assertFalse(session.inLobby());
    }

    @Test
    void manualPortalPassageCanBeRecognizedWithoutAContactTick() {
        var session = new PortalWorldSession();
        Object lobby = new Object();
        session.observe(lobby, "minecraft:overworld", 0, 0);
        session.lobbySeen(0, 0);
        assertTrue(session.inLobby());

        // 6b6t's proxy may retain the same ClientLevel and dimension while
        // moving the player tens of thousands of blocks onto a survival worker.
        assertTrue(session.observe(lobby, "minecraft:overworld", 80_000, -4_000));
        assertTrue(session.arrived());
        assertFalse(session.inLobby());
    }

    @Test
    void manuallyEnteredPortalOnNewWorkerLevelAlsoConfirmsSurvival() {
        var session = new PortalWorldSession();
        Object lobby = new Object();
        session.observe(lobby, "minecraft:overworld", 0, 0);
        session.lobbySeen(0, 0);
        assertTrue(session.observe(new Object(), "minecraft:overworld", 80_000, 0));
        assertFalse(session.inLobby());
    }

    @Test
    void ordinaryRespawnInLobbyDoesNotPretendToBeSurvival() {
        var session = new PortalWorldSession();
        session.observe(new Object(), "minecraft:overworld", 0, 0);
        session.lobbySeen(0, 0);
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        assertFalse(session.inLobby());
        assertFalse(session.arrived());
        session.lobbySeen(0, 0);
        assertTrue(session.inLobby());
    }

    @Test
    void crossingThenDimensionTransferCompletesTransit() {
        var session = new PortalWorldSession();
        Object level = new Object();
        session.observe(level, "minecraft:overworld");
        session.lobbySeen(0, 0);
        session.crossingAttempted();
        assertTrue(session.observe(level, "minecraft:the_nether"));
    }

    @Test
    void failedCrossingMustNotMistakeRespawnForSurvival() {
        var session = new PortalWorldSession();
        Object lobby = new Object();
        session.observe(lobby, "minecraft:overworld");
        session.lobbySeen(0, 0);
        session.crossingAttempted();
        session.cancelAttempt();
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        session.lobbySeen(0, 0);
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        session.lobbySeen(0, 0);
        session.crossingAttempted();
        assertTrue(session.observe(new Object(), "minecraft:overworld", 0, 0));
    }

    @Test
    void disconnectAndRejoinDoesNotReactivatePortalOnSurvival() {
        var session = new PortalWorldSession();
        Object oldLobby = new Object();
        session.observe(oldLobby, "minecraft:overworld", 0, 0);
        session.lobbySeen(0, 0);
        session.crossingAttempted();
        assertTrue(session.observe(new Object(), "minecraft:overworld", 80_000, 0));

        session.reset();
        assertFalse(session.arrived());
        assertFalse(session.inLobby());
        assertFalse(session.started());
        assertFalse(session.observe(new Object(), "minecraft:overworld", 80_000, 0));
        assertFalse(session.inLobby());

        // A later login that truly starts in the lobby may arm again.
        session.reset();
        assertFalse(session.observe(new Object(), "minecraft:overworld", 0, 0));
        session.lobbySeen(0, 0);
        assertTrue(session.inLobby());
    }
}
