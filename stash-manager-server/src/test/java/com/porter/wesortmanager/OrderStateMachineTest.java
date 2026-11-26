package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderStateMachineTest {
    @Test
    void everyTransitionInTableResolves() {
        for (OrderStateMachine.Transition t : OrderStateMachine.TABLE) {
            var found = OrderStateMachine.lookup(t.from(), t.action());
            assertTrue(found.isPresent(), t.from() + "/" + t.action());
            assertEquals(t.to(), found.get().to());
        }
    }

    @Test
    void illegalTransitionsAreDenied() {
        assertFalse(OrderStateMachine.allows(OrderStateMachine.State.DRAFT, "pickup"));
        assertFalse(OrderStateMachine.allows(OrderStateMachine.State.DELIVERED, "cancel"));
        assertFalse(OrderStateMachine.allows(OrderStateMachine.State.RESERVED, "approve"));
        assertTrue(
                OrderStateMachine.lookup(OrderStateMachine.State.DRAFT, "nope").isEmpty());
    }

    @Test
    void terminalStatesArchive() {
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.DELIVERED, "archive"));
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.CANCELLED, "archive"));
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.REJECTED, "archive"));
        assertFalse(OrderStateMachine.allows(OrderStateMachine.State.ARCHIVED, "archive"));
    }

    @Test
    void fencingAndRecoveryAreBounded() {
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.PACKING, "fence"));
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.NEEDS_RECONCILIATION, "recover"));
        assertTrue(OrderStateMachine.allows(OrderStateMachine.State.RECOVERING, "reconcile-ok"));
        var recover = OrderStateMachine.lookup(OrderStateMachine.State.NEEDS_RECONCILIATION, "recover");
        assertTrue(recover.isPresent() && recover.get().needsOwner());
    }

    @Test
    void changesetRequiresNextRevision() {
        assertThrows(IllegalArgumentException.class, () -> new OrderChangeSet("o-1", 3L, 3L, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new OrderChangeSet("o-1", 3L, 4L, null, null, null, null));
        var ok = new OrderChangeSet(
                "o-1", 3L, 4L, java.util.List.of(java.util.Map.of("row", "order")), null, null, null);
        assertEquals(4L, ok.nextRevision());
    }
}
