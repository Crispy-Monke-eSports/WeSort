package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HandoffTest {
    private static Handoff scheduled() {
        return Handoff.schedule("o-1", "alice", "meet-1", "11111111-1111-1111-1111-111111111111");
    }

    @Test
    void deliveredRequiresARecipientReceipt() {
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        assertThrows(
                IllegalArgumentException.class,
                () -> awaiting.advance(Handoff.State.DELIVERED, null),
                "no receipt, no DELIVERED");
        assertThrows(IllegalArgumentException.class, () -> awaiting.advance(Handoff.State.DELIVERED, " "));
        var delivered = awaiting.advance(Handoff.State.DELIVERED, "owner");
        assertEquals("owner", delivered.confirmedBy());
        assertTrue(delivered.terminal());
    }

    @Test
    void aDropWithoutVerifiedPickupIsNeverDelivered() {
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        var uncertain = awaiting.advance(Handoff.State.NEEDS_RECONCILIATION, "11111111-1111-1111-1111-111111111111");
        assertEquals(Handoff.State.NEEDS_RECONCILIATION, uncertain.state());
        assertNull(uncertain.confirmedBy());
    }

    @Test
    void noShowIsTraceableAndOnlyReturnedExplicitly() {
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        var noShow = awaiting.advance(Handoff.State.NO_SHOW, "11111111-1111-1111-1111-111111111111");
        assertEquals(Handoff.State.NO_SHOW, noShow.state());
        assertFalse(noShow.terminal(), "no-show is not a false stock return");
        assertEquals(
                Handoff.State.AWAITING_RECIPIENT,
                noShow.advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111")
                        .state());
        assertEquals(
                Handoff.State.RETURNED,
                noShow.advance(Handoff.State.RETURNED, "11111111-1111-1111-1111-111111111111")
                        .state());
    }

    @Test
    void uncertainHandoffIsResolvedToDeliveredOrReturned() {
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        var uncertain = awaiting.advance(Handoff.State.NEEDS_RECONCILIATION, "11111111-1111-1111-1111-111111111111");
        assertEquals(
                Handoff.State.DELIVERED,
                uncertain.advance(Handoff.State.DELIVERED, "owner").state());
        assertEquals(
                Handoff.State.RETURNED,
                awaiting.advance(Handoff.State.NEEDS_RECONCILIATION, "11111111-1111-1111-1111-111111111111")
                        .advance(Handoff.State.RETURNED, "owner")
                        .state());
    }

    @Test
    void theStateGraphRejectsInvalidMoves() {
        assertThrows(
                IllegalArgumentException.class,
                () -> scheduled().advance(Handoff.State.DELIVERED, "owner"),
                "a scheduled handoff cannot skip the meeting");
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        assertThrows(IllegalArgumentException.class, () -> awaiting.advance(Handoff.State.SCHEDULED, null));
        var delivered = awaiting.advance(Handoff.State.DELIVERED, "owner");
        assertThrows(
                IllegalArgumentException.class,
                () -> delivered.advance(Handoff.State.RETURNED, "owner"),
                "DELIVERED is terminal");
    }

    @Test
    void validationAndRoundTrip() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Handoff.schedule(" ", "alice", "meet-1", "11111111-1111-1111-1111-111111111111"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Handoff.schedule("o-1", " ", "meet-1", "11111111-1111-1111-1111-111111111111"));
        var awaiting = scheduled().advance(Handoff.State.AWAITING_RECIPIENT, "11111111-1111-1111-1111-111111111111");
        assertEquals(awaiting, Handoff.read(awaiting.json()));
    }
}
