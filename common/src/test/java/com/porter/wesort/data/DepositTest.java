package com.porter.wesort.data;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DepositTest {
    private static String id() { return UUID.randomUUID().toString(); }

    @Test void beginStartsDepositingAndAdvancesThroughTheGraph() {
        var d = Deposit.begin(id(), "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertEquals(Deposit.State.DEPOSITING, d.state());
        assertEquals(1, d.revision());
        var delivered = d.advance(Deposit.State.DELIVERED, "agent-1");
        assertEquals(Deposit.State.DELIVERED, delivered.state());
        assertEquals(2, delivered.revision());
        assertEquals(Deposit.State.NEEDS_RECONCILIATION, delivered.advance(Deposit.State.NEEDS_RECONCILIATION, null).state());
    }

    @Test void aSecondAgentCannotCompleteAnotherAgentsDeposit() {
        var d = Deposit.begin(id(), "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertThrows(IllegalArgumentException.class, () -> d.advance(Deposit.State.DELIVERED, "agent-2"));
        // Reconciliation is always open, even to a different (or no) agent.
        assertEquals(Deposit.State.NEEDS_RECONCILIATION, d.advance(Deposit.State.NEEDS_RECONCILIATION, "agent-2").state());
    }

    @Test void deliveryIsAWithdrawalAndTransferIsARelocation() {
        var delivery = Deposit.begin(id(), "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertTrue(delivery.reducesStock());
        assertEquals("withdrawal", delivery.netEffect());
        var transfer = Deposit.begin(id(), "o-1", Deposit.Kind.TRANSFER, "server/stash", "agent-1");
        assertFalse(transfer.reducesStock());
        assertEquals("relocation", transfer.netEffect());
    }

    @Test void validationRejectsMalformedDeposits() {
        assertThrows(IllegalArgumentException.class, () -> Deposit.begin("not-a-uuid", "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1"));
        assertThrows(IllegalArgumentException.class, () -> Deposit.begin(id(), "  ", Deposit.Kind.DELIVERY, "chest-a", "agent-1"));
        assertThrows(IllegalArgumentException.class, () -> Deposit.begin(id(), "o-1", Deposit.Kind.DELIVERY, " ", "agent-1"));
    }

    @Test void jsonRoundTrips() {
        var d = Deposit.begin(id(), "o-1", Deposit.Kind.TRANSFER, "server/stash", "agent-1").advance(Deposit.State.DELIVERED, "agent-1");
        assertEquals(d, Deposit.read(d.json()));
    }
}
