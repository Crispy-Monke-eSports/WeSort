package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.porter.wesort.data.Job;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PR3 Step 7 (#142): the Clock seam gives tests deterministic control over both time axes. */
class ClockTest {
    @TempDir
    Path data;

    private static final String A1 = "11111111-1111-1111-1111-111111111111";

    @Test
    void fakeClockAdvanceMovesBothAxesAndSetWallMovesOnlyTheWall() {
        var clock = new FakeClock(1_000_000L);
        long nanoBefore = clock.nano();
        clock.advance(60_000L);
        assertEquals(1_060_000L, clock.millis());
        assertEquals(nanoBefore + 60_000L * 1_000_000L, clock.nano());
        clock.setWall(1_000_000L);
        assertEquals(1_000_000L, clock.millis(), "the wall jumps back");
        assertEquals(nanoBefore + 60_000L * 1_000_000L, clock.nano(), "monotonic time is untouched");
    }

    @Test
    void eventStampsFollowTheWallAxis() throws Exception {
        var clock = new FakeClock(5_000_000L);
        var scheduler = new JobScheduler(new StockLedger(data), clock);
        scheduler.submit(Job.Category.SCAN, "chain-1", "", 0, "owner");
        clock.advance(1_000L);
        scheduler.claim(A1);
        long lastAt = 0;
        var queued = scheduler.queue();
        for (var e : queued.get(0).getAsJsonObject().getAsJsonArray("events"))
            lastAt = e.getAsJsonObject().get("at").getAsLong();
        assertEquals(5_001_000L, lastAt, "job events are stamped with wall millis");
    }

    @Test
    void monotonicLeaseExpiryIsImmuneToWallJumps(@TempDir Path data2) throws Exception {
        var clock = new FakeClock(10_000_000L);
        var scheduler = new JobScheduler(new StockLedger(data), clock);
        var job = scheduler.submit(Job.Category.SCAN, "chain-1", "", 0, "owner");
        var claimed = scheduler.claim(A1);
        String token = claimed.get("token").getAsString();

        // An hour of wall time vanishes, but the monotonic lease keeps running past its TTL.
        clock.setWall(clock.millis() - 3_600_000L);
        clock.advance(JobScheduler.TTL);
        assertThrows(
                JobScheduler.Conflict.class,
                () -> scheduler.renew(job.id(), A1, token),
                "a lapsed lease cannot be renewed even though the wall moved back");
        assertEquals(Job.State.NEEDS_RECONCILIATION, scheduler.get(job.id()).state());

        // And a wall jump forward can never shorten a lease either: a fresh claim
        // with the wall pushed ahead still needs the full monotonic TTL to lapse.
        var clock2 = new FakeClock(20_000_000L);
        var scheduler2 = new JobScheduler(new StockLedger(data2), clock2);
        var job2 = scheduler2.submit(Job.Category.SCAN, "chain-2", "", 0, "owner");
        var claimed2 = scheduler2.claim(A1);
        clock2.setWall(clock2.millis() + JobScheduler.TTL);
        assertEquals(
                Job.State.COMPLETED,
                scheduler2
                        .complete(job2.id(), A1, claimed2.get("token").getAsString())
                        .state(),
                "jumping the wall forward past the deadline must not fence a live lease");
    }
}
