package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.porter.wesort.data.Handoff;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoffsTest {
    @TempDir
    Path data;

    private static final String A1 = "11111111-1111-1111-1111-111111111111";
    private static final String A2 = "22222222-2222-2222-2222-222222222222";

    @Test
    void confirmationIsTheOnlyPathToDeliveredAndReplayIsIdempotent() throws Exception {
        var handoffs = new Handoffs(data);
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);

        // A drop without verified pickup can never reach DELIVERED.
        handoffs.dropUnverified(h.id(), A1, "no pickup");
        assertEquals(Handoff.State.NEEDS_RECONCILIATION, handoffs.get(h.id()).state());

        // Resolve with an explicit receipt -> DELIVERED, and replay returns the same record.
        var resolved = handoffs.resolve(h.id(), Handoff.State.DELIVERED, "owner");
        assertEquals(Handoff.State.DELIVERED, resolved.state());
        assertEquals("owner", resolved.confirmedBy());
        assertEquals(resolved, handoffs.confirm(h.id(), "owner"));
    }

    @Test
    void aSecondAgentCannotTakeOverTheCargo() throws Exception {
        var handoffs = new Handoffs(data);
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        assertThrows(
                Handoffs.Conflict.class, () -> handoffs.noShow(h.id(), A2), "a second agent cannot drive the handoff");
        assertThrows(Handoffs.Conflict.class, () -> handoffs.dropUnverified(h.id(), A2, "x"));
    }

    @Test
    void noShowDoesNotReturnStockUntilExplicit() throws Exception {
        var handoffs = new Handoffs(data);
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        assertEquals(Handoff.State.NO_SHOW, handoffs.noShow(h.id(), A1).state());
        assertFalse(handoffs.get(h.id()).terminal(), "no-show keeps the cargo held");
        assertEquals(Handoff.State.RETURNED, handoffs.returnOrder(h.id(), A1).state());
    }

    @Test
    void handoffsPersistAcrossRestart() throws Exception {
        var handoffs = new Handoffs(data);
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        handoffs.confirm(h.id(), "owner");

        var reloaded = new Handoffs(data);
        assertEquals(Handoff.State.DELIVERED, reloaded.get(h.id()).state());
        assertEquals("owner", reloaded.get(h.id()).confirmedBy());
    }
}
