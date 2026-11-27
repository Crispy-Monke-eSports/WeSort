package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.porter.wesort.data.Handoff;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandoffsTest {
    @TempDir
    Path data;

    private final FakeClock time = new FakeClock(1_000_000L);

    private FakeClock clock() {
        return time;
    }

    private Handoffs handoffs() throws Exception {
        return new Handoffs(new StockLedger(data), clock());
    }

    private static final String A1 = "11111111-1111-1111-1111-111111111111";
    private static final String A2 = "22222222-2222-2222-2222-222222222222";

    @Test
    void confirmationIsTheOnlyPathToDeliveredAndReplayIsIdempotent() throws Exception {
        var handoffs = handoffs();
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
        var handoffs = handoffs();
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        assertThrows(
                Handoffs.Conflict.class, () -> handoffs.noShow(h.id(), A2), "a second agent cannot drive the handoff");
        assertThrows(Handoffs.Conflict.class, () -> handoffs.dropUnverified(h.id(), A2, "x"));
    }

    @Test
    void noShowDoesNotReturnStockUntilExplicit() throws Exception {
        var handoffs = handoffs();
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        assertEquals(Handoff.State.NO_SHOW, handoffs.noShow(h.id(), A1).state());
        assertFalse(handoffs.get(h.id()).terminal(), "no-show keeps the cargo held");
        assertEquals(Handoff.State.RETURNED, handoffs.returnOrder(h.id(), A1).state());
    }

    @Test
    void handoffsPersistAcrossRestart() throws Exception {
        var handoffs = handoffs();
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);
        handoffs.confirm(h.id(), "owner");

        var reloaded = handoffs();
        assertEquals(Handoff.State.DELIVERED, reloaded.get(h.id()).state());
        assertEquals("owner", reloaded.get(h.id()).confirmedBy());
    }

    @Test
    void rejectsUnassignedBookingsAndAllowsExplicitReturnOfLegacyBooking() throws Exception {
        var handoffs = handoffs();
        assertThrows(IllegalArgumentException.class, () -> handoffs.schedule("o-1", "alice", "meet-1", null));
        assertThrows(IllegalArgumentException.class, () -> handoffs.schedule("o-1", "alice", "meet-1", " "));
        assertTrue(handoffs.list(null).isEmpty());
        // Simulate a booking persisted by a previous Manager that allowed null agents.
        var legacy = Handoff.schedule("o-1", "alice", "meet-1", null);
        try (var c = new StockLedger(data).open()) {
            try (var p = c.prepareStatement(
                    "INSERT INTO handoffs(id,order_id,state,agent,payload,revision) VALUES(?,?,?,?,?,?)")) {
                p.setString(1, legacy.id());
                p.setString(2, legacy.orderId());
                p.setString(3, legacy.state().name());
                p.setString(4, null);
                p.setString(5, legacy.json().toString());
                p.setLong(6, legacy.revision());
                p.executeUpdate();
            }
        }
        var restarted = handoffs();
        assertThrows(Handoffs.Conflict.class, () -> restarted.arrive(legacy.id(), A1));
        assertThrows(Handoffs.Conflict.class, () -> restarted.resolve(legacy.id(), Handoff.State.DELIVERED, "owner"));
        assertEquals(
                Handoff.State.RETURNED,
                restarted.resolve(legacy.id(), Handoff.State.RETURNED, "owner").state());
        assertEquals(Handoff.State.RETURNED, handoffs().get(legacy.id()).state());
    }

    @Test
    void staleAwaitingRecipientIsRecordedNoShowWithoutFabricatingDelivery() throws Exception {
        var handoffs = handoffs();
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);

        // A fresh arrival is left alone.
        assertTrue(handoffs.sweepStaleAwaiting(time.get()).isEmpty());
        assertEquals(Handoff.State.AWAITING_RECIPIENT, handoffs.get(h.id()).state());

        // Past the bounded wait with no recipient and no agent report: system
        // records NO_SHOW (retryable, returnable) — never DELIVERED.
        time.advance(Handoffs.AWAITING_NOSHOW_AFTER_MS + 1);
        var swept = handoffs.sweepStaleAwaiting(time.get());
        assertEquals(1, swept.size());
        assertEquals(Handoff.State.NO_SHOW, handoffs.get(h.id()).state());
        assertFalse(handoffs.get(h.id()).terminal(), "no-show keeps the cargo held");
        assertEquals(
                Handoff.State.AWAITING_RECIPIENT, handoffs.retry(h.id(), A1).state());
    }
}
