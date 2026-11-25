package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Job;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * W-034: the single-agent job scheduler. Jobs are submitted with a category (SCAN → SORT → PACK → PICKUP → DELIVER,
 * or the RECONCILE override) and a work-unit {@code chain}; a step is only claimable once its predecessor for the
 * same chain is COMPLETED, so a lone agent can chain every step without a handover. A claim is atomic — exactly one
 * agent owns a job, and an agent holds at most one physical transaction at a time. Each claim carries a lease with a
 * token; a lapsed lease moves the job to NEEDS_RECONCILIATION and it is only reassignable after an operator
 * reconciles it (a blind restart of an uncertain physical step is impossible). Priority orders the queue.
 */
final class JobScheduler {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    static final long TTL = 60_000; // lease time-to-live, millis
    private static final int SCHEMA = 1;
    private final Path file;
    private final LongSupplier clock;
    private final LongSupplier leaseClock;
    private final Map<String, Entry> jobs = new LinkedHashMap<>();

    private record Entry(Job job, long deadline, String token, JsonArray events) {}

    JobScheduler(Path data) throws IOException {
        this(data, System::currentTimeMillis, monotonicMillis());
    }

    /** Keep lease deadlines comparable to epoch milliseconds while measuring elapsed time monotonically. */
    private static LongSupplier monotonicMillis() {
        long wall = System.currentTimeMillis();
        long start = System.nanoTime();
        return () -> wall + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    JobScheduler(Path data, LongSupplier clock) throws IOException {
        this(data, clock, clock);
    }

    /** PR5 (#142/#35): Clock-based wiring (wall + monotonic in one). */
    JobScheduler(Path data, Clock clock) throws IOException {
        this(data, clock::millis, clock::nano);
    }

    JobScheduler(Path data, LongSupplier clock, LongSupplier leaseClock) throws IOException {
        this.clock = clock;
        this.leaseClock = leaseClock;
        this.file = data.resolve("jobs.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid job scheduler location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("jobs")) {
                var o = entry.getAsJsonObject();
                var job = Job.read(o);
                jobs.put(
                        job.id(),
                        new Entry(
                                job,
                                o.get("deadline").getAsLong(),
                                o.has("token") && !o.get("token").isJsonNull()
                                        ? o.get("token").getAsString()
                                        : null,
                                o.getAsJsonArray("events").deepCopy()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read job scheduler", invalid);
        }
        // The old process can no longer vouch for any outstanding physical lease.
        fenceLeases(true);
    }

    synchronized Job submit(Job.Category category, String chain, String detail, int priority, String by)
            throws IOException {
        expire();
        var job = new Job(null, category, chain, detail == null ? "" : detail, null, Job.State.QUEUED, priority, 1);
        var events = new JsonArray();
        events.add(event("submitted", by));
        commit(job.id(), new Entry(job, 0, null, events));
        return job;
    }

    /**
     * Atomically allocates the highest-priority eligible job to {@code agent}. A claim fails when the agent already
     * holds an active or uncertain transaction, so no agent ever runs two physical steps at once and no job has two
     * owners. Returns a lease token and deadline with the job, or a null job when the queue is empty.
     */
    synchronized JsonObject claim(String agent) throws IOException {
        UUID.fromString(agent);
        expire();
        if (busy()) throw new Conflict("An active or uncertain job already holds the single physical work slot");
        Job candidate = null;
        for (var entry : jobs.values()) {
            var j = entry.job();
            if (j.state() != Job.State.QUEUED || !prerequisitesMet(j)) continue;
            if (candidate == null
                    || j.priority() > candidate.priority()
                    || (j.priority() == candidate.priority() && j.revision() < candidate.revision())) candidate = j;
        }
        var out = new JsonObject();
        if (candidate == null) {
            out.add("job", com.google.gson.JsonNull.INSTANCE);
            return out;
        }
        var claimed = candidate.advance(Job.State.CLAIMED, agent);
        String token = UUID.randomUUID().toString();
        long deadline = leaseClock.getAsLong() + TTL;
        var events = jobs.get(candidate.id()).events().deepCopy();
        events.add(event("claimed", agent));
        commit(candidate.id(), new Entry(claimed, deadline, token, events));
        out.add("job", claimed.json());
        out.addProperty("token", token);
        out.addProperty("deadline", deadline);
        return out;
    }

    synchronized Job renew(String jobId, String agent, String token) throws IOException {
        expire();
        var entry = held(jobId, agent);
        if (!entry.token().equals(token)) throw new Conflict("Lease token mismatch");
        long deadline = leaseClock.getAsLong() + TTL;
        var events = entry.events().deepCopy();
        events.add(event("renewed", agent));
        commit(jobId, new Entry(entry.job(), deadline, token, events));
        return entry.job();
    }

    synchronized Job complete(String jobId, String agent, String token) throws IOException {
        expire();
        var entry = require(jobId);
        if (entry.token() == null
                || !entry.token().equals(token)
                || !agent.equals(entry.job().agent())) throw new Conflict("Lease token or agent mismatch");
        if (entry.job().state() == Job.State.COMPLETED) return entry.job();
        entry = held(jobId, agent);
        return advanceTo(jobId, entry, entry.job().advance(Job.State.COMPLETED, agent), "completed", agent);
    }

    synchronized Job pause(String jobId, String agent) throws IOException {
        expire();
        var entry = held(jobId, agent);
        return advanceTo(jobId, entry, entry.job().advance(Job.State.PAUSED, agent), "paused", agent);
    }

    synchronized Job resume(String jobId, String agent, String token) throws IOException {
        expire();
        var entry = require(jobId);
        var j = entry.job();
        if (j.state() != Job.State.PAUSED || !j.agent().equals(agent))
            throw new Conflict("Job is not paused by this agent");
        if (!entry.token().equals(token)) throw new Conflict("Lease token mismatch");
        return advanceTo(jobId, entry, j.advance(Job.State.CLAIMED, agent), "resumed", agent);
    }

    synchronized Job revoke(String jobId, String by) throws IOException {
        var entry = require(jobId);
        if (entry.job().state() == Job.State.NEEDS_RECONCILIATION) return entry.job();
        if (entry.job().terminal()) throw new Conflict("A terminal job cannot be revoked");
        if (entry.job().held()) {
            var uncertain = fenced(entry, "revoked_while_held", by);
            commit(jobId, uncertain);
            return uncertain.job();
        }
        return advanceTo(jobId, entry, entry.job().advance(Job.State.REVOKED, by), "revoked", by);
    }

    /** Resolves an uncertain job: back to QUEUED for reassignment, or COMPLETED. */
    synchronized Job reconcile(String jobId, Job.State outcome, String by) throws IOException {
        var entry = require(jobId);
        if (entry.job().state() != Job.State.NEEDS_RECONCILIATION)
            throw new Conflict("Only an uncertain job may be reconciled");
        if (outcome != Job.State.QUEUED && outcome != Job.State.COMPLETED)
            throw new Conflict("Reconciliation must reassign or complete");
        return advanceTo(jobId, entry, entry.job().advance(outcome, by), "reconciled", by);
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
        expire();
        var swept = new java.util.ArrayList<String>();
        for (var id : new java.util.ArrayList<>(jobs.keySet())) {
            var entry = jobs.get(id);
            if (entry == null || entry.job().state() != Job.State.NEEDS_RECONCILIATION) continue;
            if (nowMs - lastEventAt(entry.events()) <= UNCERTAIN_AUTO_REQUEUE_AFTER_MS) continue;
            var next = entry.job().advance(Job.State.QUEUED, "system");
            advanceTo(id, entry, next, "auto_reconciled", "system");
            swept.add("Job " + id + " (" + entry.job().category() + " "
                    + entry.job().chain()
                    + ") was uncertain for " + ((nowMs - lastEventAt(entry.events())) / 1000)
                    + "s; requeued for claiming.");
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

    synchronized Job get(String jobId) {
        var entry = jobs.get(jobId);
        return entry == null ? null : entry.job();
    }

    /** Owner queue visibility, ordered by state (queued first) then priority then age. */
    synchronized JsonArray queue() throws IOException {
        expire();
        var list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparingInt((Entry e) -> e.job().state() == Job.State.QUEUED ? 0 : 1)
                .thenComparingInt((Entry e) -> -e.job().priority())
                .thenComparingLong(e -> e.job().revision()));
        var out = new JsonArray();
        for (var entry : list) {
            var o = entry.job().json();
            o.addProperty("deadline", entry.deadline());
            o.add("events", entry.events().deepCopy());
            // PR8 (#213): machine-readable fence reason + retry deadline for the
            // dashboard; never an interactive instruction to the agent.
            if (entry.job().state() == Job.State.NEEDS_RECONCILIATION) {
                o.addProperty("reason", "lease-lapsed-needs-reconcile");
                o.addProperty("retryEligibleAfter", UNCERTAIN_AUTO_REQUEUE_AFTER_MS);
            }
            out.add(o);
        }
        return out;
    }

    private Entry held(String jobId, String agent) throws Conflict {
        var entry = require(jobId);
        var j = entry.job();
        if (!j.held() || !j.agent().equals(agent)) throw new Conflict("Job is not held by this agent");
        return entry;
    }

    private Entry require(String jobId) throws Conflict {
        var entry = jobs.get(jobId);
        if (entry == null) throw new Conflict("Unknown job");
        return entry;
    }

    private boolean busy() {
        return jobs.values().stream()
                .anyMatch(e -> e.job().held() || e.job().state() == Job.State.NEEDS_RECONCILIATION);
    }

    private boolean prerequisitesMet(Job job) {
        for (var prereq : Job.prerequisites(job.category())) {
            boolean done = jobs.values().stream()
                    .anyMatch(e -> e.job().chain().equals(job.chain())
                            && e.job().category() == prereq
                            && e.job().state() == Job.State.COMPLETED);
            if (!done) return false;
        }
        return true;
    }

    /** Lapses any held job whose lease has run out into NEEDS_RECONCILIATION (never a blind reassignment). */
    private void expire() throws IOException {
        fenceLeases(false);
    }

    private Entry fenced(Entry e, String reason, String by) {
        var events = e.events().deepCopy();
        events.add(event(reason, by));
        return new Entry(e.job().advance(Job.State.NEEDS_RECONCILIATION, null), 0, null, events);
    }

    private void fenceLeases(boolean restart) throws IOException {
        long now = leaseClock.getAsLong();
        boolean changed = false;
        var updated = new LinkedHashMap<>(jobs);
        for (var entry : jobs.entrySet()) {
            var e = entry.getValue();
            var j = e.job();
            if (j.held() && (restart || now >= e.deadline())) {
                updated.put(entry.getKey(), fenced(e, restart ? "manager_restart" : "expired", j.agent()));
                changed = true;
            }
        }
        if (changed) commit(updated);
    }

    private Job advanceTo(String jobId, Entry entry, Job next, String event, String by) throws IOException {
        var events = entry.events().deepCopy();
        events.add(event(event, by));
        commit(jobId, new Entry(next, entry.deadline(), entry.token(), events));
        return next;
    }

    private JsonArray history(String event, String by) {
        var out = new JsonArray();
        out.add(event(event, by));
        return out;
    }

    private JsonObject event(String event, String by) {
        var o = new JsonObject();
        o.addProperty("at", clock.getAsLong());
        o.addProperty("event", event);
        o.addProperty("by", by);
        return o;
    }

    private void commit(String id, Entry entry) throws IOException {
        var updated = new LinkedHashMap<>(jobs);
        updated.put(id, entry);
        commit(updated);
    }

    private void commit(Map<String, Entry> updated) throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        for (var entry : updated.values()) {
            var o = entry.job().json();
            o.addProperty("deadline", entry.deadline());
            o.addProperty("token", entry.token());
            o.add("events", entry.events().deepCopy());
            list.add(o);
        }
        root.add("jobs", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
        jobs.clear();
        jobs.putAll(updated);
    }
}
