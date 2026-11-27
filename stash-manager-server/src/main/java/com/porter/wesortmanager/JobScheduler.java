package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Job;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * W-034: the single-agent job scheduler. Jobs are submitted with a category (SCAN → SORT → PACK → PICKUP → DELIVER,
 * or the RECONCILE override) and a work-unit {@code chain}; a step is only claimable once its predecessor for the
 * same chain is COMPLETED, so a lone agent can chain every step without a handover. A claim is atomic — exactly one
 * agent owns a job, and an agent holds at most one physical transaction at a time. Each claim carries a lease with a
 * token; a lapsed lease moves the job to NEEDS_RECONCILIATION and it is only reassignable after an operator
 * reconciles it (a blind restart of an uncertain physical step is impossible). Priority orders the queue.
 *
 * <p>PR3 (#140): authoritative state lives in the shared guild SQLite database
 * ({@code jobs}/{@code job_events}), so scheduler rows commit or roll back in
 * the same transaction as the order book and the audit trail. Legacy
 * {@code jobs.json} files are never read; {@link StockLedger} warns loudly
 * when one is present.
 *
 * <p>Global lock order for nested same-transaction commits: ledger → index →
 * book → store. Public methods take the store monitor and a fresh ledger
 * transaction; {@code Connection}-scoped overloads assume the caller already
 * holds the locks in that order and never commit on their own.
 *
 * <p>Lease time is measured on a monotonic clock while deadlines stay
 * comparable to epoch milliseconds (see {@link #monotonic}), so moving the wall
 * clock back can never extend a physical lease.
 */
final class JobScheduler {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    static final long TTL = 60_000; // lease time-to-live, millis
    private final StockLedger ledger;
    private final Clock clock;
    private final Clock leaseClock;

    JobScheduler(StockLedger ledger) throws IOException {
        this(ledger, Clock.system(), monotonic());
    }

    JobScheduler(StockLedger ledger, Clock clock) throws IOException {
        this(ledger, clock, clock);
    }

    JobScheduler(StockLedger ledger, Clock clock, Clock leaseClock) throws IOException {
        if (ledger == null) throw new IllegalArgumentException("JobScheduler requires the guild database");
        if (clock == null || leaseClock == null) throw new IllegalArgumentException("JobScheduler requires clocks");
        this.ledger = ledger;
        this.clock = clock;
        this.leaseClock = leaseClock;
        // The old process can no longer vouch for any outstanding physical lease: fence
        // every held job like BoxJobs fences IN_PROGRESS/VERIFYING at startup, and
        // record the fence as an event so the automatic requeue timer restarts here.
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            fenceHeld(c, "manager_restart");
            tx.commit();
        } catch (SQLException failure) {
            throw new IOException("Cannot fence interrupted job leases", failure);
        }
    }

    /** Lease deadlines stay comparable to epoch millis while elapsed time is measured monotonically. */
    private static Clock monotonic() {
        long wall = System.currentTimeMillis();
        long start = System.nanoTime();
        return new Clock() {
            @Override
            public long millis() {
                return wall + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }

            @Override
            public long nano() {
                return System.nanoTime();
            }
        };
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    JobScheduler(Path data) throws IOException {
        this(data, System::currentTimeMillis, monotonicMillis());
    }

    /** Keep lease deadlines comparable to epoch milliseconds while measuring elapsed time monotonically. */
    private static LongSupplier monotonicMillis() {
        long wall = System.currentTimeMillis();
        long start = System.nanoTime();
        return () -> wall + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    JobScheduler(Path data, LongSupplier clock) throws IOException {
        this(data, clock, clock);
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    JobScheduler(Path data, LongSupplier clock, LongSupplier leaseClock) throws IOException {
        this(new StockLedger(data), adapt(clock), adapt(leaseClock));
    }

    private static Clock adapt(LongSupplier clock) {
        return new Clock() {
            @Override
            public long millis() {
                return clock.getAsLong();
            }

            @Override
            public long nano() {
                return System.nanoTime();
            }
        };
    }

    private record Row(Job job, long deadline, String token, JsonArray events) {}

    synchronized Job submit(Job.Category category, String chain, String detail, int priority, String by)
            throws IOException {
        return submit(category, chain, detail, priority, by, null);
    }

    /** Submits a job, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Job submit(
            Job.Category category, String chain, String detail, int priority, String by, AuditLog.Batch trail)
            throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            var job = new Job(null, category, chain, detail == null ? "" : detail, null, Job.State.QUEUED, priority, 1);
            var events = new JsonArray();
            events.add(event("submitted", by));
            insert(c, new Row(job, 0, null, events));
            if (trail != null) {
                trail.say("job.submit", job.id());
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return job;
        } catch (SQLException failure) {
            throw new IOException("Cannot submit job", failure);
        }
    }

    /**
     * Atomically allocates the highest-priority eligible job to {@code agent}. A claim fails when the agent already
     * holds an active or uncertain transaction, so no agent ever runs two physical steps at once and no job has two
     * owners. Returns a lease token and deadline with the job, or a null job when the queue is empty.
     */
    synchronized JsonObject claim(String agent) throws IOException {
        return claim(agent, null);
    }

    /** Claims a job, writing staged audit entries in the same transaction when a trail is given. */
    synchronized JsonObject claim(String agent, AuditLog.Batch trail) throws IOException {
        UUID.fromString(agent);
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            var out = claim(c, agent);
            if (trail != null) {
                // Empty-queue claims are audited too, with an empty subject.
                trail.say(
                        "job.claim",
                        out.get("job").isJsonObject()
                                ? out.getAsJsonObject("job").get("id").getAsString()
                                : "");
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return out;
        } catch (SQLException failure) {
            throw new IOException("Cannot claim job", failure);
        }
    }

    private JsonObject claim(Connection c, String agent) throws SQLException, IOException {
        if (busy(c)) throw new Conflict("An active or uncertain job already holds the single physical work slot");
        Job candidate = null;
        try (var p = c.prepareStatement("SELECT payload,priority,revision FROM jobs WHERE state='QUEUED'")) {
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var job = Job.read(JsonParser.parseString(rows.getString(1)).getAsJsonObject());
                    if (!prerequisitesMet(c, job)) continue;
                    if (candidate == null
                            || job.priority() > candidate.priority()
                            || (job.priority() == candidate.priority() && job.revision() < candidate.revision()))
                        candidate = job;
                }
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read job scheduler", invalid);
        }
        var out = new JsonObject();
        if (candidate == null) {
            out.add("job", com.google.gson.JsonNull.INSTANCE);
            return out;
        }
        var claimed = advance(candidate, Job.State.CLAIMED, agent);
        String token = UUID.randomUUID().toString();
        long deadline = Math.addExact(leaseClock.millis(), TTL);
        var events = eventsOf(c, candidate.id());
        events.add(event("claimed", agent));
        update(c, candidate.id(), new Row(claimed, deadline, token, events), candidate.revision());
        out.add("job", claimed.json());
        out.addProperty("token", token);
        out.addProperty("deadline", deadline);
        return out;
    }

    synchronized Job renew(String jobId, String agent, String token) throws IOException {
        return renew(jobId, agent, token, null);
    }

    synchronized Job renew(String jobId, String agent, String token, AuditLog.Batch trail) throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = held(c, jobId, agent);
            if (!entry.token().equals(token)) throw new Conflict("Lease token mismatch");
            long deadline = Math.addExact(leaseClock.millis(), TTL);
            var events = entry.events().deepCopy();
            events.add(event("renewed", agent));
            update(
                    c,
                    jobId,
                    new Row(entry.job(), deadline, token, events),
                    entry.job().revision());
            if (trail != null) {
                trail.say("job.renew", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return entry.job();
        } catch (SQLException failure) {
            throw new IOException("Cannot renew job", failure);
        }
    }

    synchronized Job complete(String jobId, String agent, String token) throws IOException {
        return complete(jobId, agent, token, null);
    }

    synchronized Job complete(String jobId, String agent, String token, AuditLog.Batch trail) throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = require(c, jobId);
            if (entry.token() == null
                    || !entry.token().equals(token)
                    || !agent.equals(entry.job().agent())) throw new Conflict("Lease token or agent mismatch");
            if (entry.job().state() == Job.State.COMPLETED) {
                tx.commit();
                return entry.job();
            }
            entry = held(c, jobId, agent);
            Job next = advanceTo(c, jobId, entry, advance(entry.job(), Job.State.COMPLETED, agent), "completed", agent);
            if (trail != null) {
                trail.say("job.complete", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot complete job", failure);
        }
    }

    synchronized Job pause(String jobId, String agent) throws IOException {
        return pause(jobId, agent, null);
    }

    synchronized Job pause(String jobId, String agent, AuditLog.Batch trail) throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = held(c, jobId, agent);
            Job next = advanceTo(c, jobId, entry, advance(entry.job(), Job.State.PAUSED, agent), "paused", agent);
            if (trail != null) {
                trail.say("job.pause", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot pause job", failure);
        }
    }

    synchronized Job resume(String jobId, String agent, String token) throws IOException {
        return resume(jobId, agent, token, null);
    }

    synchronized Job resume(String jobId, String agent, String token, AuditLog.Batch trail) throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = require(c, jobId);
            var j = entry.job();
            if (j.state() != Job.State.PAUSED || !j.agent().equals(agent))
                throw new Conflict("Job is not paused by this agent");
            if (!entry.token().equals(token)) throw new Conflict("Lease token mismatch");
            Job next = advanceTo(c, jobId, entry, advance(j, Job.State.CLAIMED, agent), "resumed", agent);
            if (trail != null) {
                trail.say("job.resume", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot resume job", failure);
        }
    }

    synchronized Job revoke(String jobId, String by) throws IOException {
        return revoke(jobId, by, null);
    }

    synchronized Job revoke(String jobId, String by, AuditLog.Batch trail) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = require(c, jobId);
            Job next;
            if (entry.job().state() == Job.State.NEEDS_RECONCILIATION) {
                next = entry.job();
            } else if (entry.job().terminal()) {
                throw new Conflict("A terminal job cannot be revoked");
            } else if (entry.job().held()) {
                Row uncertain = fenced(entry, "revoked_while_held", by);
                update(c, jobId, uncertain, entry.job().revision());
                next = uncertain.job();
            } else {
                next = advanceTo(c, jobId, entry, advance(entry.job(), Job.State.REVOKED, by), "revoked", by);
            }
            if (trail != null) {
                trail.say("job.revoke", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot revoke job", failure);
        }
    }

    /** Resolves an uncertain job: back to QUEUED for reassignment, or COMPLETED. */
    synchronized Job reconcile(String jobId, Job.State outcome, String by) throws IOException {
        return reconcile(jobId, outcome, by, null);
    }

    /** Reconciles a job, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Job reconcile(String jobId, Job.State outcome, String by, AuditLog.Batch trail) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Row entry = require(c, jobId);
            if (entry.job().state() != Job.State.NEEDS_RECONCILIATION)
                throw new Conflict("Only an uncertain job may be reconciled");
            if (outcome != Job.State.QUEUED && outcome != Job.State.COMPLETED)
                throw new Conflict("Reconciliation must reassign or complete");
            Job next = advanceTo(c, jobId, entry, advance(entry.job(), outcome, by), "reconciled", by);
            if (trail != null) {
                trail.say("job.reconcile", jobId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot reconcile job", failure);
        }
    }

    /**
     * Bounded wait in NEEDS_RECONCILIATION before the manager requeues the job
     * itself (#213): no human can ever open the owner browser to reconcile, and
     * one uncertain job blocks the single physical work slot ({@code busy()})
     * forever.
     */
    static final long UNCERTAIN_AUTO_REQUEUE_AFTER_MS = 30 * 60_000;

    /**
     * Requeues jobs stuck uncertain past the bounded wait, as {@code system}.
     * The lease is already fenced and chain prerequisites still gate claiming,
     * so no blind restart of in-flight physical work is possible; the automatic
     * path only ever yields QUEUED, never COMPLETED (completion needs proof).
     * Returns human-readable descriptions for logging/audit.
     */
    synchronized java.util.List<String> sweepStaleUncertain(long nowMs) throws IOException {
        var swept = new ArrayList<String>();
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            List<String> ids = new ArrayList<>();
            try (var p = c.prepareStatement("SELECT id FROM jobs WHERE state='NEEDS_RECONCILIATION' ORDER BY id")) {
                try (var rows = p.executeQuery()) {
                    while (rows.next()) ids.add(rows.getString(1));
                }
            }
            for (String id : ids) {
                Row entry = read(c, id);
                if (entry == null || entry.job().state() != Job.State.NEEDS_RECONCILIATION) continue;
                if (nowMs - lastEventAt(entry.events()) <= UNCERTAIN_AUTO_REQUEUE_AFTER_MS) continue;
                Job next = advance(entry.job(), Job.State.QUEUED, "system");
                advanceTo(c, id, entry, next, "auto_reconciled", "system");
                swept.add("Job " + id + " (" + entry.job().category() + " "
                        + entry.job().chain()
                        + ") was uncertain for " + ((nowMs - lastEventAt(entry.events())) / 1000)
                        + "s; requeued for claiming.");
            }
            tx.commit();
        } catch (SQLException failure) {
            throw new IOException("Cannot sweep stale jobs", failure);
        }
        return swept;
    }

    private static long lastEventAt(JsonArray events) {
        long last = 0;
        for (var e : events) {
            var o = e.getAsJsonObject();
            if (o.has("at") && !o.get("at").isJsonNull())
                last = Math.max(last, o.get("at").getAsLong());
        }
        return last;
    }

    synchronized Job get(String jobId) throws IOException {
        try (var c = ledger.open()) {
            Row entry = read(c, jobId);
            return entry == null ? null : entry.job();
        } catch (SQLException failure) {
            throw new IOException("Cannot read job scheduler", failure);
        }
    }

    /** Owner queue visibility, ordered by state (queued first) then priority then age. */
    synchronized JsonArray queue() throws IOException {
        expireCommitted();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            var list = new ArrayList<Row>();
            try (var p = c.prepareStatement(
                    "SELECT id FROM jobs ORDER BY CASE WHEN state='QUEUED' THEN 0 ELSE 1 END,priority DESC,revision ASC")) {
                try (var rows = p.executeQuery()) {
                    while (rows.next()) {
                        Row entry = read(c, rows.getString(1));
                        if (entry != null) list.add(entry);
                    }
                }
            }
            tx.commit();
            var out = new JsonArray();
            for (var entry : list) {
                var o = entry.job().json();
                o.addProperty("deadline", entry.deadline());
                o.add("events", entry.events().deepCopy());
                out.add(o);
            }
            return out;
        } catch (SQLException failure) {
            throw new IOException("Cannot read job scheduler", failure);
        }
    }

    private Row held(Connection c, String jobId, String agent) throws SQLException, IOException, Conflict {
        Row entry = require(c, jobId);
        var j = entry.job();
        if (!j.held() || !j.agent().equals(agent)) throw new Conflict("Job is not held by this agent");
        return entry;
    }

    private Row require(Connection c, String jobId) throws SQLException, IOException, Conflict {
        Row entry = read(c, jobId);
        if (entry == null) throw new Conflict("Unknown job");
        return entry;
    }

    private boolean busy(Connection c) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT 1 FROM jobs WHERE state IN ('CLAIMED','PAUSED','NEEDS_RECONCILIATION') LIMIT 1")) {
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean prerequisitesMet(Connection c, Job job) throws SQLException {
        var done = new java.util.HashSet<Job.Category>();
        try (var p = c.prepareStatement("SELECT category FROM jobs WHERE chain=? AND state='COMPLETED'")) {
            p.setString(1, job.chain());
            try (var rows = p.executeQuery()) {
                while (rows.next()) done.add(Job.Category.valueOf(rows.getString(1)));
            }
        }
        return done.containsAll(Job.prerequisites(job.category()));
    }

    /**
     * Expires lapsed leases in their own committed transaction before the
     * operation runs: a fence must survive even when the operation itself then
     * fails (a lapsed lease can never be completed). Folding the fence into
     * the operation transaction would roll it back together with the
     * rejection, reviving a dead lease.
     */
    private void expireCommitted() throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            expire(c);
            tx.commit();
        } catch (SQLException failure) {
            throw new IOException("Cannot expire job leases", failure);
        }
    }

    /** Lapses any held job whose lease has run out into NEEDS_RECONCILIATION (never a blind reassignment). */
    private void expire(Connection c) throws SQLException, IOException {
        long now = leaseClock.millis();
        List<String> ids = new ArrayList<>();
        try (var p = c.prepareStatement(
                "SELECT id FROM jobs WHERE state IN ('CLAIMED','PAUSED') AND deadline<=? ORDER BY id")) {
            p.setLong(1, now);
            try (var rows = p.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        for (String id : ids) {
            Row entry = read(c, id);
            if (entry == null || !entry.job().held()) continue;
            update(
                    c,
                    id,
                    fenced(entry, "expired", entry.job().agent()),
                    entry.job().revision());
        }
    }

    private Row fenced(Row e, String reason, String by) {
        var events = e.events().deepCopy();
        events.add(event(reason, by));
        return new Row(advanceUnchecked(e.job(), Job.State.NEEDS_RECONCILIATION, null), 0, null, events);
    }

    /**
     * Fences every held job, like BoxJobs fences IN_PROGRESS/VERIFYING at
     * startup: a restarted Manager never silently resumes in-flight physical
     * work. The fence is recorded as an event so the automatic requeue timer
     * restarts from the fence, not from the stale claim.
     */
    private void fenceHeld(Connection c, String reason) throws SQLException, IOException {
        List<String> ids = new ArrayList<>();
        try (var p = c.prepareStatement("SELECT id FROM jobs WHERE state IN ('CLAIMED','PAUSED') ORDER BY id")) {
            try (var rows = p.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
        }
        for (String id : ids) {
            Row entry = read(c, id);
            if (entry == null || !entry.job().held()) continue;
            update(
                    c,
                    id,
                    fenced(entry, reason, entry.job().agent()),
                    entry.job().revision());
        }
    }

    private Job advance(Job job, Job.State next, String agent) throws Conflict {
        try {
            return job.advance(next, agent);
        } catch (IllegalArgumentException e) {
            throw new Conflict(e.getMessage());
        }
    }

    private static Job advanceUnchecked(Job job, Job.State next, String agent) {
        return job.advance(next, agent);
    }

    private Job advanceTo(Connection c, String jobId, Row entry, Job next, String event, String by)
            throws SQLException, IOException {
        var events = entry.events().deepCopy();
        events.add(event(event, by));
        update(
                c,
                jobId,
                new Row(next, entry.deadline(), entry.token(), events),
                entry.job().revision());
        return next;
    }

    private JsonObject event(String event, String by) {
        var o = new JsonObject();
        o.addProperty("at", clock.millis());
        o.addProperty("event", event);
        o.addProperty("by", by);
        return o;
    }

    /** Inserts a new job row plus its first event; fails closed on a duplicate id. */
    private static void insert(Connection c, Row entry) throws SQLException {
        var j = entry.job();
        try (var row = c.prepareStatement(
                "INSERT INTO jobs(id,chain,category,state,agent,priority,deadline,token,payload,revision) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            row.setString(1, j.id());
            row.setString(2, j.chain());
            row.setString(3, j.category().name());
            row.setString(4, j.state().name());
            row.setString(5, j.agent());
            row.setInt(6, j.priority());
            row.setLong(7, entry.deadline());
            row.setString(8, entry.token());
            row.setString(9, j.json().toString());
            row.setLong(10, j.revision());
            row.executeUpdate();
        }
        insertEvents(c, j.id(), entry.events());
    }

    /** Conditional update: a concurrent transition on another connection wins instead of silently overwriting. */
    private static void update(Connection c, String jobId, Row entry, long expectedRevision)
            throws SQLException, Conflict {
        var j = entry.job();
        try (var row = c.prepareStatement(
                "UPDATE jobs SET chain=?,category=?,state=?,agent=?,priority=?,deadline=?,token=?,payload=?,revision=? WHERE id=? AND revision=?")) {
            row.setString(1, j.chain());
            row.setString(2, j.category().name());
            row.setString(3, j.state().name());
            row.setString(4, j.agent());
            row.setInt(5, j.priority());
            row.setLong(6, entry.deadline());
            row.setString(7, entry.token());
            row.setString(8, j.json().toString());
            row.setLong(9, j.revision());
            row.setString(10, jobId);
            row.setLong(11, expectedRevision);
            if (row.executeUpdate() != 1) throw new Conflict("Job changed concurrently; reread the queue");
        }
        insertEvents(c, jobId, entry.events());
    }

    private static void insertEvents(Connection c, String jobId, JsonArray events) throws SQLException {
        try (var event = c.prepareStatement(
                "INSERT INTO job_events(id,seq,payload) VALUES(?,?,?) ON CONFLICT(id,seq) DO NOTHING")) {
            long seq = 0;
            for (var e : events) {
                event.setString(1, jobId);
                event.setLong(2, ++seq);
                event.setString(3, e.toString());
                event.executeUpdate();
            }
        }
    }

    private static JsonArray eventsOf(Connection c, String jobId) throws SQLException {
        var events = new JsonArray();
        try (var p = c.prepareStatement("SELECT payload FROM job_events WHERE id=? ORDER BY seq")) {
            p.setString(1, jobId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) events.add(JsonParser.parseString(rows.getString(1)));
            }
        }
        return events;
    }

    private static Row read(Connection c, String jobId) throws SQLException, IOException {
        JsonObject stored;
        long deadline;
        String token;
        try (var p = c.prepareStatement("SELECT payload,deadline,token FROM jobs WHERE id=?")) {
            p.setString(1, jobId);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                stored = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                deadline = rows.getLong(2);
                token = rows.getString(3);
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read job scheduler", invalid);
        }
        try {
            return new Row(Job.read(stored), deadline, token, eventsOf(c, jobId));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read job scheduler", invalid);
        }
    }
}
