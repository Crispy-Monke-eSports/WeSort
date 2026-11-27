package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.wesort.data.Job;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#140): scheduler authority in SQLite, committed in the same transaction
 * as the order book and the audit trail.
 */
class JobSchedulerSqliteTest {
    @TempDir
    Path data;

    private static final String A1 = "11111111-1111-1111-1111-111111111111";

    private final AtomicLong time = new AtomicLong(0);

    private Clock clock() {
        return new Clock() {
            @Override
            public long millis() {
                return time.get();
            }

            @Override
            public long nano() {
                return System.nanoTime();
            }
        };
    }

    private JobScheduler scheduler(StockLedger ledger) throws Exception {
        return new JobScheduler(ledger, clock(), clock());
    }

    private static JsonObject book(String server, String stash) {
        var root = new JsonObject();
        root.addProperty("server", server);
        root.addProperty("stash", stash);
        root.add("orders", new JsonArray());
        return root;
    }

    private static JsonObject order(String id) {
        var o = new JsonObject();
        o.addProperty("id", id);
        return o;
    }

    private static long revisionOf(StockLedger ledger, String server, String stash) throws Exception {
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT revision FROM order_books WHERE server=? AND stash=?")) {
            p.setString(1, server);
            p.setString(2, stash);
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next(), "order book must exist");
                return rows.getLong(1);
            }
        }
    }

    @Test
    void sameTransactionCommitPersistsBookJobAndAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var scheduler = scheduler(ledger);
        var submitted = scheduler.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-1");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        repo.commitExtended("s", "a", 1, root, null, trail.entries(), List.of());

        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertEquals(Job.State.QUEUED, scheduler.get(submitted.id()).state());
        assertEquals(1, audit.size());
        // Restart keeps book, jobs and audit together and fences nothing new.
        var restarted = scheduler(new StockLedger(data));
        assertEquals(Job.State.QUEUED, restarted.get(submitted.id()).state());
        assertEquals(1, restarted.queue().size());
    }

    @Test
    void failedOrderCommitPersistsNeitherBookNorJobNorAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var scheduler = scheduler(ledger);
        var seedJob = scheduler.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        var seedTrail = audit.batch();
        seedTrail.actor("u", "U", "addr");
        seedTrail.say("order.place", "o-1");
        var seed = book("s", "a");
        seed.getAsJsonArray("orders").add(order("o-1"));
        repo.commitExtended("s", "a", 1, seed, null, seedTrail.entries(), List.of());
        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertNotNull(scheduler.get(seedJob.id()));
        assertEquals(1, audit.size());

        // The next commit stages a job claim and audit, then fails: everything rolls back.
        var claimed = scheduler.claim(A1);
        String jobId = claimed.getAsJsonObject("job").get("id").getAsString();
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.pack", "o-2");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        root.getAsJsonArray("orders").add(order("o-2"));
        OrderRepository.SqliteWrite failing = c -> {
            AuditLog.addAll(c, trail.entries());
            throw new IOException("simulated job failure");
        };
        assertThrows(
                IOException.class,
                () -> repo.commitExtended("s", "a", 2, root, null, trail.entries(), List.of(failing)));

        assertEquals(2, revisionOf(ledger, "s", "a"), "book revision must not advance");
        assertEquals(
                Job.State.CLAIMED,
                scheduler.get(jobId).state(),
                "the claim from before the failed commit must survive untouched");
        assertEquals(1, audit.size(), "audit write must roll back");
    }

    @Test
    void bootFenceAndChainGateAndMonotonicLease() throws Exception {
        var ledger = new StockLedger(data);
        var scheduler = scheduler(ledger);
        var scan = scheduler.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        scheduler.submit(Job.Category.SORT, "o-1", "", 0, "owner");
        var claimed = scheduler.claim(A1);
        assertEquals(scan.id(), claimed.getAsJsonObject("job").get("id").getAsString());

        // A restart fences the held SORT-blocked claim; the queue still gates on the chain.
        var restarted = scheduler(new StockLedger(data));
        assertEquals(Job.State.NEEDS_RECONCILIATION, restarted.get(scan.id()).state());
        assertThrows(JobScheduler.Conflict.class, () -> restarted.claim(A1));

        // Reconcile back to queued, complete the chain head, then the next step unlocks.
        restarted.reconcile(scan.id(), Job.State.QUEUED, "owner");
        var retry = restarted.claim(A1);
        restarted.complete(scan.id(), A1, retry.get("token").getAsString());
        var next = restarted.claim(A1);
        assertEquals(Job.Category.SORT, Job.read(next.getAsJsonObject("job")).category());
    }

    @Test
    void staleJsonFileIsIgnoredAndNeverRead() throws Exception {
        Files.writeString(data.resolve("jobs.json"), "{\"schema\":1,\"jobs\":[]}", StandardCharsets.UTF_8);
        // The legacy Path constructor shares guild.sqlite and ignores the stale file.
        var scheduler = new JobScheduler(data, time::get);
        assertEquals(0, scheduler.queue().size());
        var job = scheduler.submit(Job.Category.SCAN, "o-1", "", 0, "owner");
        assertEquals(Job.State.QUEUED, scheduler.get(job.id()).state());
    }
}
