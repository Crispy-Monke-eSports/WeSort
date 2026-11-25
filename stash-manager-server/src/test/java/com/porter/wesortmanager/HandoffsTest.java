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

    @Test
    void rejectsUnassignedBookingsAndAllowsExplicitReturnOfLegacyBooking() throws Exception {
        var handoffs = new Handoffs(data);
        assertThrows(IllegalArgumentException.class, () -> handoffs.schedule("o-1", "alice", "meet-1", null));
        assertThrows(IllegalArgumentException.class, () -> handoffs.schedule("o-1", "alice", "meet-1", " "));
        assertTrue(handoffs.list(null).isEmpty());
        // Simulate a booking persisted by a previous Manager that allowed null agents.
        var legacy = Handoff.schedule("o-1", "alice", "meet-1", null);
        var root = new com.google.gson.JsonObject();
        root.addProperty("schema", 1);
        var entries = new com.google.gson.JsonArray();
        var entry = legacy.json();
        entry.add("events", new com.google.gson.JsonArray());
        entries.add(entry);
        root.add("handoffs", entries);
        java.nio.file.Files.writeString(data.resolve("handoffs.json"), root.toString());
        var restarted = new Handoffs(data);
        assertThrows(Handoffs.Conflict.class, () -> restarted.arrive(legacy.id(), A1));
        assertThrows(Handoffs.Conflict.class, () -> restarted.resolve(legacy.id(), Handoff.State.DELIVERED, "owner"));
        assertEquals(
                Handoff.State.RETURNED,
                restarted.resolve(legacy.id(), Handoff.State.RETURNED, "owner").state());
        assertEquals(Handoff.State.RETURNED, new Handoffs(data).get(legacy.id()).state());
    }

    @Test
    void staleAwaitingRecipientIsRecordedNoShowWithoutFabricatingDelivery() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        var handoffs = new Handoffs(data, clock::get);
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        handoffs.arrive(h.id(), A1);

        // A fresh arrival is left alone.
        assertTrue(handoffs.sweepStaleAwaiting(clock.get()).isEmpty());
        assertEquals(Handoff.State.AWAITING_RECIPIENT, handoffs.get(h.id()).state());

        // Past the bounded wait with no recipient and no agent report: system
        // records NO_SHOW (retryable, returnable) — never DELIVERED.
        clock.addAndGet(Handoffs.AWAITING_NOSHOW_AFTER_MS + 1);
        var swept = handoffs.sweepStaleAwaiting(clock.get());
        assertEquals(1, swept.size());
        assertEquals(Handoff.State.NO_SHOW, handoffs.get(h.id()).state());
        assertFalse(handoffs.get(h.id()).terminal(), "no-show keeps the cargo held");
        assertEquals(Handoff.State.AWAITING_RECIPIENT, handoffs.retry(h.id(), A1).state());
    }
}
