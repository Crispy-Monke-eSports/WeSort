package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JobTest {
    private static Job queued(Job.Category category) {
        return new Job(null, category, "o-1", "", null, Job.State.QUEUED, 0, 1);
    }

    @Test
    void prerequisitesFormTheChain() {
        assertEquals(java.util.Set.of(), Job.prerequisites(Job.Category.SCAN));
        assertEquals(java.util.Set.of(Job.Category.SCAN), Job.prerequisites(Job.Category.SORT));
        assertEquals(java.util.Set.of(Job.Category.SORT), Job.prerequisites(Job.Category.PACK));
        assertEquals(java.util.Set.of(Job.Category.PACK), Job.prerequisites(Job.Category.PICKUP));
        assertEquals(java.util.Set.of(Job.Category.PICKUP), Job.prerequisites(Job.Category.DELIVER));
        assertEquals(java.util.Set.of(), Job.prerequisites(Job.Category.RECONCILE));
    }

    @Test
    void claimSetsTheOwnerAndReassignClearsIt() {
        var claimed = queued(Job.Category.SCAN).advance(Job.State.CLAIMED, "agent-1");
        assertEquals("agent-1", claimed.agent());
        assertTrue(claimed.held());
        var reassigned = claimed.advance(Job.State.NEEDS_RECONCILIATION, null).advance(Job.State.QUEUED, null);
        assertNull(reassigned.agent());
        assertFalse(reassigned.held());
    }

    @Test
    void theStateGraphRejectsInvalidMoves() {
        assertThrows(
                IllegalArgumentException.class,
                () -> queued(Job.Category.SCAN).advance(Job.State.COMPLETED, null),
                "a queued job cannot complete without a claim");
        assertThrows(
                IllegalArgumentException.class, () -> queued(Job.Category.SCAN).advance(Job.State.PAUSED, null));
        var claimed = queued(Job.Category.SCAN).advance(Job.State.CLAIMED, "agent-1");
        assertThrows(
                IllegalArgumentException.class,
                () -> claimed.advance(Job.State.QUEUED, null),
                "a claimed job cannot skip reconciliation");
        var completed = claimed.advance(Job.State.COMPLETED, "agent-1");
        assertTrue(completed.terminal());
        assertThrows(IllegalArgumentException.class, () -> completed.advance(Job.State.CLAIMED, "agent-2"));
    }

    @Test
    void pauseResumeAndRevoke() {
        var claimed = queued(Job.Category.SORT).advance(Job.State.CLAIMED, "agent-1");
        var paused = claimed.advance(Job.State.PAUSED, "agent-1");
        assertEquals(Job.State.PAUSED, paused.state());
        assertTrue(paused.held());
        assertEquals(
                Job.State.CLAIMED, paused.advance(Job.State.CLAIMED, "agent-1").state());
        var revoked = claimed.advance(Job.State.REVOKED, "owner");
        assertTrue(revoked.terminal());
    }

    @Test
    void validationRejectsMalformedJobs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Job(null, Job.Category.SCAN, " ", "", null, Job.State.QUEUED, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Job(null, Job.Category.SCAN, "o-1", "", null, Job.State.QUEUED, -1, 1));
    }

    @Test
    void jsonRoundTrips() {
        var j = queued(Job.Category.PICKUP).advance(Job.State.CLAIMED, "agent-1");
        assertEquals(j, Job.read(j.json()));
    }
}
