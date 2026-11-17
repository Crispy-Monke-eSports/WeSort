package com.porter.wesort.inventory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PhysicalWorkTest {
    @Test void boundedAttemptsCannotBeResetByRepeatingSamePhase() {
        var work = new PhysicalWork("repack", 0); work.enter(PhysicalWork.Phase.OPEN, 0);
        for (int i=0;i<3;i++) { work.enter(PhysicalWork.Phase.OPEN, 0); assertTrue(work.attempt(0)); }
        assertFalse(work.attempt(0)); assertEquals(PhysicalWork.Phase.NEEDS_RECONCILIATION, work.phase());
        work.enter(PhysicalWork.Phase.COMPLETED, 0); assertEquals(PhysicalWork.Phase.NEEDS_RECONCILIATION, work.phase());
    }
    @Test void absoluteDeadlineSurvivesPhaseChanges() {
        var work = new PhysicalWork("bulk", 0);
        work.enter(PhysicalWork.Phase.PLACE, 59_000_000_000L);
        work.enter(PhysicalWork.Phase.TRANSFER, 118_000_000_000L);
        work.enter(PhysicalWork.Phase.PICKUP, 177_000_000_000L);
        work.enter(PhysicalWork.Phase.STORE, 295_000_000_000L);
        assertTrue(work.expired(301_000_000_000L));
    }
    @Test void pickupDeadlineAndPreconditionFailureRemainDiagnostic() {
        var work = new PhysicalWork("mixed", 0); work.enter(PhysicalWork.Phase.PICKUP, 0);
        assertFalse(work.expired(119_000_000_000L)); assertTrue(work.expired(121_000_000_000L));
        assertEquals("NEEDS_RECONCILIATION", work.json().get("phase").getAsString());
        var blocked = new PhysicalWork("bulk", 0); blocked.failBeforeMutation("No empty shulker");
        assertEquals(PhysicalWork.Phase.FAILED, blocked.phase()); assertFalse(blocked.attempt(0));
    }
}
