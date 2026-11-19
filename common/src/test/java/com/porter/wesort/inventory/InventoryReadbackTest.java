package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryReadbackTest {
    private final InventoryReadback.Expected before =
            new InventoryReadback.Expected(1, List.of("diamond:64", "empty"), "empty");
    private final InventoryReadback.Expected after =
            new InventoryReadback.Expected(1, List.of("empty", "diamond:64"), "empty");

    @Test
    void predictionAndTimeAloneNeverAcknowledge() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.expire(7_000_000_000L);
        assertFalse(gate.ready());
        assertFalse(gate.failed());
    }

    @Test
    void oldSnapshotCannotAcknowledgeNewAction() {
        var gate = new InventoryReadback();
        gate.observe(after);
        gate.predict(after);
        gate.fence(0);
        gate.barrier();
        assertTrue(gate.failed());
    }

    @Test
    void freshFullSnapshotAndFollowingBarrierAcknowledge() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.observe(after);
        assertFalse(gate.ready());
        gate.barrier();
        assertTrue(gate.ready());
    }

    @Test
    void freshButRejectedMoveStopsInsteadOfRetrying() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.observe(before);
        gate.barrier();
        assertTrue(gate.failed());
        assertEquals(after, gate.expected());
        assertThrows(IllegalStateException.class, () -> gate.predict(after));
    }

    @Test
    void cursorMismatchIsNotACompleteMove() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.observe(new InventoryReadback.Expected(1, after.slots(), "diamond:64"));
        gate.barrier();
        assertTrue(gate.failed());
    }

    @Test
    void wrongMenuCannotReleaseFence() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.observe(new InventoryReadback.Expected(2, after.slots(), after.cursor()));
        gate.barrier();
        assertTrue(gate.failed());
    }

    @Test
    void timeoutRequiresReconciliationAndKeepsOriginalPostcondition() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.expire(8_000_000_000L);
        assertTrue(gate.failed());
        assertEquals(after, gate.expected());
    }

    @Test
    void restartedPendingIntentDoesNotReplayOrEnableAutomation() {
        var gate = new InventoryReadback();
        gate.restorePending(after);
        assertTrue(gate.failed());
        assertFalse(gate.ready());
        assertNull(gate.fence(0));
    }

    @Test
    void reconciliationStillRejectsTheUnchangedPreState() {
        var gate = new InventoryReadback();
        gate.restorePending(after);
        gate.reconcile(7, 0);
        gate.observe(new InventoryReadback.Expected(7, before.slots(), before.cursor()));
        gate.barrier();
        assertTrue(gate.failed());
        assertEquals(after, gate.expected());
    }

    @Test
    void explicitReconciliationVerifiesOriginalPostconditionInReopenedMenu() {
        var gate = new InventoryReadback();
        gate.restorePending(after);
        gate.reconcile(7, 0);
        gate.observe(new InventoryReadback.Expected(7, after.slots(), after.cursor()));
        gate.barrier();
        assertTrue(gate.ready());
    }

    @Test
    void everyQueuedBatchNeedsItsOwnOrderedSnapshot() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.predict(before);
        gate.fence(1);
        gate.observe(after);
        gate.barrier();
        assertFalse(gate.ready());
        gate.observe(before);
        gate.barrier();
        assertTrue(gate.ready());
    }

    @Test
    void unrelatedBarrierWhileIdleDoesNothing() {
        var gate = new InventoryReadback();
        gate.barrier();
        assertTrue(gate.ready());
    }

    @Test
    void identicalBatchesCannotReuseTheSameServerSnapshot() {
        var gate = new InventoryReadback();
        gate.predict(after);
        gate.fence(0);
        gate.predict(after);
        gate.fence(1);
        gate.observe(after);
        gate.barrier();
        gate.barrier();
        assertTrue(gate.failed());
    }
}
