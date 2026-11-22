package com.porter.wesort.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortalWorldSessionTest {
    @Test
    void aWorldChangeWithoutPortalEntryIsNotSurvivalProof() {
        var session = new PortalWorldSession();
        assertFalse(session.observe(new Object(), "minecraft:overworld"));
        assertFalse(session.observe(new Object(), "minecraft:overworld"));
        assertFalse(session.arrived());
    }

    @Test
    void crossingThenWorldTransferCompletesTransit() {
        var session = new PortalWorldSession();
        Object lobby = new Object();
        assertFalse(session.observe(lobby, "minecraft:overworld"));
        session.crossingAttempted();
        assertFalse(session.observe(lobby, "minecraft:overworld"));
        assertTrue(session.observe(new Object(), "minecraft:overworld")); // proxy replacement
        assertTrue(session.arrived());
    }

    @Test
    void crossingThenDimensionTransferCompletesTransit() {
        var session = new PortalWorldSession();
        Object level = new Object();
        session.observe(level, "minecraft:overworld");
        session.crossingAttempted();
        assertTrue(session.observe(level, "minecraft:the_nether"));
    }

    @Test
    void disconnectAndRejoinRequiresAnotherEntry() {
        var session = new PortalWorldSession();
        Object oldWorld = new Object();
        session.observe(oldWorld, "minecraft:overworld");
        session.crossingAttempted();
        assertTrue(session.observe(new Object(), "minecraft:overworld"));

        session.reset();
        assertFalse(session.arrived());
        assertFalse(session.started());
        Object rejoinedLobby = new Object();
        assertFalse(session.observe(rejoinedLobby, "minecraft:overworld"));
        assertFalse(session.observe(new Object(), "minecraft:overworld"));
        session.crossingAttempted();
        assertTrue(session.observe(new Object(), "minecraft:overworld"));
    }
}
