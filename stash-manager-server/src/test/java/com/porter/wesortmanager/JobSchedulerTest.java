package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.porter.wesort.data.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobSchedulerTest {
    @TempDir
    Path data;

    private final AtomicLong clock = new AtomicLong(0);

    private static final String A1 = "11111111-1111-1111-1111-111111111111";
    private static final String A2 = "22222222-2222-2222-2222-222222222222";

    private JobScheduler scheduler() throws Exception {
        return new JobScheduler(data, clock::get);
    }

    private static String id(JsonObject claim) {
        return claim.getAsJsonObject("job").get("id").getAsString();
    }

    private static String token(JsonObject claim) {
        return claim.get("token").getAsString();
    }

    @Test
    void claimIsAtomicAndAnAgentHoldsAtMostOneJob() throws Exception {
        var s = scheduler();
        s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var first = s.claim(A1);
        assertTrue(first.get("job").isJsonObject(), "the first agent gets the only job");
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A2), "only one physical work slot exists");
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A1), "one agent cannot hold two jobs");

        s.submit(Job.Category.SCAN, "o-2", "", 0, "owner");
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A2));
        s.complete(id(first), A1, token(first));
        var second = s.claim(A1);
        assertTrue(second.get("job").isJsonObject());
        assertNotEquals(id(first), id(second), "the same agent can claim the next job");
    }

    @Test
    void priorityOrdersTheQueue() throws Exception {
        var s = scheduler();
        var low = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var high = s.submit(Job.Category.SCAN, "o-2", "", 5, "owner");
        var first = s.claim(A1);
        assertEquals(high.id(), id(first), "the higher priority job is allocated first");
        s.complete(high.id(), A1, token(first));
        assertEquals(low.id(), id(s.claim(A1)), "the lower priority job follows");
    }

    @Test
    void aStepIsOnlyClaimableAfterItsPredecessorCompletes() throws Exception {
        var s = scheduler();
        var scan = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var sort = s.submit(Job.Category.SORT, "o-1", "", 0, "owner");
        var pack = s.submit(Job.Category.PACK, "o-1", "", 0, "owner");
        var pickup = s.submit(Job.Category.PICKUP, "o-1", "", 0, "owner");

        var c1 = s.claim(A1);
        assertEquals(scan.id(), id(c1));
        s.complete(scan.id(), A1, token(c1));
        var c2 = s.claim(A1);
        assertEquals(sort.id(), id(c2));
        s.complete(sort.id(), A1, token(c2));

        // PACK is now eligible, but PICKUP still waits on PACK.
        var c3 = s.claim(A1);
        assertEquals(pack.id(), id(c3), "PICKUP cannot be claimed before PACK completes");
        s.complete(pack.id(), A1, token(c3));
        assertEquals(pickup.id(), id(s.claim(A1)), "PICKUP is eligible once PACK is complete");
    }

    @Test
    void aLapsedLeaseNeedsReconciliationBeforeReassignment() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var claimed = s.claim(A1);
        String token = token(claimed);
        clock.set(JobScheduler.TTL); // the lease lapses
        assertThrows(
                JobScheduler.Conflict.class,
                () -> s.complete(job.id(), A1, token),
                "a lapsed lease cannot be completed");
        assertEquals(Job.State.NEEDS_RECONCILIATION, s.get(job.id()).state());
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A2), "an uncertain job blocks every claimant");

        s.reconcile(job.id(), Job.State.QUEUED, "owner");
        var re = s.claim(A2);
        assertTrue(re.get("job").isJsonObject(), "after reconciliation another agent may claim it");
        assertEquals(A2, s.get(job.id()).agent());
    }

    @Test
    void renewExtendsTheLease() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var claimed = s.claim(A1);
        String token = token(claimed);
        clock.set(JobScheduler.TTL - 1);
        s.renew(job.id(), A1, token);
        clock.set(JobScheduler.TTL + 1); // past the original deadline but within the renewed one
        assertEquals(Job.State.COMPLETED, s.complete(job.id(), A1, token).state());
    }

    @Test
    void pauseResumeAndRevoke() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var claimed = s.claim(A1);
        assertEquals(Job.State.PAUSED, s.pause(job.id(), A1).state());
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A1), "a paused job still occupies the agent");
        assertEquals(Job.State.CLAIMED, s.resume(job.id(), A1, token(claimed)).state());
        assertEquals(Job.State.REVOKED, s.revoke(job.id(), "owner").state());
    }

    @Test
    void aLoneAgentChainsEveryStepWithoutAHandover() throws Exception {
        var s = scheduler();
        Job.Category[] chain = {
            Job.Category.SCAN, Job.Category.SORT, Job.Category.PACK, Job.Category.PICKUP, Job.Category.DELIVER
        };
        for (Job.Category c : chain) s.submit(c, "o-1", "", 0, "owner");
        for (Job.Category c : chain) {
            var claimed = s.claim(A1);
            assertTrue(claimed.get("job").isJsonObject(), c + " should be claimable");
            var job = s.get(id(claimed));
            assertEquals(c, job.category());
            assertEquals(A1, job.agent());
            s.complete(job.id(), A1, token(claimed));
        }
        assertTrue(s.claim(A1).get("job").isJsonNull(), "everything is done");
    }

    @Test
    void pausedLeaseExpiryFencesEveryClaimantAndSurvivesRestart() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var claim = s.claim(A1);
        s.pause(job.id(), A1);
        s.submit(Job.Category.SCAN, "o-2", "", 0, "owner");
        clock.set(JobScheduler.TTL);
        var restarted = scheduler();
        assertEquals(Job.State.NEEDS_RECONCILIATION, restarted.get(job.id()).state());
        assertThrows(JobScheduler.Conflict.class, () -> restarted.claim(A2));
        assertThrows(JobScheduler.Conflict.class, () -> restarted.complete(job.id(), A1, token(claim)));
    }

    @Test
    void writeFailureCannotPublishClaimOrCompletionAndExactReplayIsIdempotent() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        Path file = data.resolve("jobs.json");
        Path saved = data.resolve("jobs.saved");
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "write failure");
        assertThrows(IOException.class, () -> s.claim(A1));
        assertEquals(Job.State.QUEUED, s.get(job.id()).state());
        Files.delete(file.resolve("obstruction"));
        Files.delete(file);
        Files.move(saved, file);

        var claim = s.claim(A1);
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "write failure");
        assertThrows(IOException.class, () -> s.complete(job.id(), A1, token(claim)));
        assertEquals(Job.State.CLAIMED, s.get(job.id()).state());
        assertThrows(JobScheduler.Conflict.class, () -> s.claim(A2));
        Files.delete(file.resolve("obstruction"));
        Files.delete(file);
        Files.move(saved, file);

        var completed = s.complete(job.id(), A1, token(claim));
        var restarted = scheduler();
        assertEquals(completed, restarted.complete(job.id(), A1, token(claim)));
        assertThrows(JobScheduler.Conflict.class, () -> restarted.complete(job.id(), A2, token(claim)));
        assertThrows(JobScheduler.Conflict.class, () -> restarted.complete(job.id(), A1, "old"));
        assertEquals(s.queue(), restarted.queue());
    }

    @Test
    void theQueuePersistsAcrossRestart() throws Exception {
        var s = scheduler();
        var job = s.submit(Job.Category.SCAN, "o-1", "", 3, "owner");
        var claimed = s.claim(A1);
        s.pause(job.id(), A1);

        var reloaded = new JobScheduler(data, clock::get);
        assertEquals(Job.State.PAUSED, reloaded.get(job.id()).state());
        assertEquals(A1, reloaded.get(job.id()).agent());
    }
}
