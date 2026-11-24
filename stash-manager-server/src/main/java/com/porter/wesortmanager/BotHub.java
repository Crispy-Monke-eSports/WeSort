package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

final class BotHub {
    static final int MAX_BOTS = 64;
    static final int MAX_WAITERS = 8;
    static final long POLL_WAIT_MS = 25_000;
    static final long ONLINE_MS = 35_000;
    static final long EXPIRE_QUEUED_MS = 10_000;
    static final long UNKNOWN_SENT_MS = 60_000;
    static final long WATCH_MS = 10_000;
    static final int EVENTS_KEEP = 500;
    static final int JOBS_KEEP = 500;
    private static final Set<String> CAPABILITIES =
            Set.of("settings", "activity", "warehouse-scan", "warehouse-sort", "pack", "stage", "heartbeat");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> RECEIPT_FIELDS = Set.of("id", "lease", "sequence", "ok", "value", "error");

    /** A settings scope as the bot last pushed it. */
    static final class Scope {
        String schemaHash;
        String valuesHash;
        JsonArray schema; // null until the bot sends one
        Map<String, JsonObject> nodes; // leaf and group nodes by path
        JsonObject values = new JsonObject();
        long receivedAt;
    }

    static final class Job {
        final String id;
        final String type; // set | command
        final JsonObject payload;
        final long createdAt;
        String status = "queued"; // queued | sent | done | error | expired | unknown
        long sentAt;
        long sequence;
        String lease;
        JsonObject receipt;
        JsonElement value;
        String error;

        Job(String id, String type, JsonObject payload, long createdAt) {
            this.id = id;
            this.type = type;
            this.payload = payload;
            this.createdAt = createdAt;
        }
    }

    static final class Bot {
        final String botId;
        String player;
        JsonObject plugin = new JsonObject();
        JsonObject runtime = new JsonObject();
        Set<String> caps = new LinkedHashSet<>();
        /** The scopes the bot says it can show (bot/hello "scopes"); only "plugin" is pushed unasked. */
        Set<String> declaredScopes = new LinkedHashSet<>();

        long helloAt;
        /** Bumped by every bot/hello: a long-poll from before it (a restarted bot's dangling request) gives up. */
        long helloGen;

        String controlSession;
        String controlEpoch;
        ManifestScope manifestScope;
        long jobSequence;
        long lastPoll;
        long lastSeen;
        Long lastHeartbeat;
        int waiting;
        long watchUntil;
        Boolean watchingSent;
        long needSentAt;
        long watchActivityUntil;
        Boolean watchingActivitySent;
        JsonObject activity; // the latest cleaned snapshot, or null
        long activityAt; // when it arrived
        final ArrayDeque<JsonObject> events = new ArrayDeque<>(); // newest last
        final java.util.HashSet<String> eventKeys = new java.util.HashSet<>(); // (at, source, text) of those kept
        final Map<String, Scope> scopes = new HashMap<>();
        final Set<String> need = new LinkedHashSet<>(); // "scope\u0000what"
        final ArrayDeque<Job> queue = new ArrayDeque<>();
        final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();

        Bot(String botId) {
            this.botId = botId;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private int waiters;

    BotHub() {
        this(System::currentTimeMillis);
    }

    BotHub(LongSupplier clock) {
        this.clock = clock;
    }

    long now() {
        return clock.getAsLong();
    }

    // ---------- bot side ----------

    /** bot/hello: registers or updates a bot. Returns false when the registry is full of online bots. */
    boolean hello(String botId, String player, JsonObject plugin, JsonObject runtime, List<String> caps) {
        return hello(botId, player, plugin, runtime, caps, List.of());
    }

    boolean hello(
            String botId,
            String player,
            JsonObject plugin,
            JsonObject runtime,
            List<String> caps,
            List<String> scopes) {
        return register(botId, player, plugin, runtime, caps, scopes, null) == 200;
    }

    int register(
            String botId,
            String player,
            JsonObject plugin,
            JsonObject runtime,
            List<String> caps,
            List<String> scopes,
            String controlSession) {
        return register(botId, player, plugin, runtime, caps, scopes, controlSession, null);
    }

    record ManifestScope(String botId, String server, String stash, String dimension, String agent, String player) {}

    int register(
            String botId,
            String player,
            JsonObject plugin,
            JsonObject runtime,
            List<String> caps,
            List<String> scopes,
            String controlSession,
            ManifestScope scope) {
        long now = now();
        Bot b;
        synchronized (this) {
            b = bots.get(botId);
            if (b == null) {
                if (bots.size() >= MAX_BOTS && !evictOne(now)) return 503;
                b = new Bot(botId);
                bots.put(botId, b);
            }
        }
        synchronized (b) {
            boolean sessionChanged = !java.util.Objects.equals(b.controlSession, controlSession);
            if (sessionChanged && b.controlSession != null && (controlSession == null || sessionAlive(b, now)))
                return 409;
            if (sessionChanged || !java.util.Objects.equals(b.manifestScope, scope)) {
                for (Job job : b.jobs.values()) {
                    if (job.status.equals("queued") || job.status.equals("sent")) job.status = "unknown";
                }
                b.queue.clear();
                b.scopes.clear();
                b.controlSession = controlSession;
                b.controlEpoch = java.util.UUID.randomUUID().toString();
                b.lastHeartbeat = null;
                b.manifestScope = scope;
                b.activity = null;
                b.activityAt = 0;
            }
            if (player != null && !player.isEmpty()) b.player = player;
            b.plugin = plugin;
            b.runtime = runtime;
            boolean heartbeatWasSupported = b.caps.contains("heartbeat");
            b.caps = new LinkedHashSet<>(
                    caps.stream().filter(CAPABILITIES::contains).toList());
            if (!heartbeatWasSupported || !b.caps.contains("heartbeat")) b.lastHeartbeat = null;
            b.declaredScopes =
                    new LinkedHashSet<>(scopes.stream().filter("plugin"::equals).toList());
            b.helloAt = now;
            b.lastSeen = now;
            b.lastPoll = now;
            b.need.clear(); // a new session pushes everything by itself
            b.watchingSent = null;
            b.watchingActivitySent = null;
            b.helloGen++;
            b.notifyAll(); // ends a long-poll from the bot's previous run, so it can't take what this run must be told
        }
        return 200;
    }

    boolean currentControlSession(Bot bot, String session, String epoch) {
        synchronized (bot) {
            return session != null
                    && epoch != null
                    && session.equals(bot.controlSession)
                    && epoch.equals(bot.controlEpoch)
                    && sessionAlive(bot, now());
        }
    }

    ManifestScope manifestScope(Bot bot, String session, String epoch) {
        synchronized (bot) {
            return currentControlSession(bot, session, epoch) ? bot.manifestScope : null;
        }
    }

    private boolean evictOne(long now) {
        Bot oldest = null;
        for (Bot b : bots.values()) {
            synchronized (b) {
                if (sessionAlive(b, now)) continue;
                if (oldest == null || b.lastSeen < oldest.lastSeen) oldest = b;
            }
        }
        if (oldest == null) return false;
        bots.remove(oldest.botId);
        return true;
    }

    /**
     * Revoke the cached control session when its signing key is revoked.
     * Retiring the bot also wakes in-flight long-polls and prevents queued
     * physical work from being handed to a now-unauthorized session.
     */
    void retire(String botId) {
        Bot bot;
        synchronized (this) {
            bot = bots.remove(botId);
        }
        if (bot == null) return;
        synchronized (bot) {
            for (Job job : bot.jobs.values()) {
                if (job.status.equals("queued") || job.status.equals("sent")) job.status = "unknown";
            }
            bot.queue.clear();
            bot.helloGen++;
            bot.notifyAll();
        }
    }

    synchronized Bot bot(String botId) {
        return botId == null ? null : bots.get(botId);
    }

    synchronized List<Bot> all() {
        return new ArrayList<>(bots.values());
    }

    /** Modern agents prove presence through ping/pong; legacy agents retain poll-based presence. */
    static boolean online(Bot b, long now) {
        if (b.controlSession != null && b.caps.contains("heartbeat"))
            return b.lastHeartbeat != null && now - b.lastHeartbeat < ONLINE_MS;
        return sessionAlive(b, now);
    }

    private static boolean sessionAlive(Bot b, long now) {
        return b.waiting > 0
                || now - b.lastPoll < ONLINE_MS
                || b.lastHeartbeat != null && now - b.lastHeartbeat < ONLINE_MS;
    }

    /** Only the currently registered epoch can refresh presence. No order or stock access. */
    synchronized boolean heartbeat(String botId, String session, String epoch) {
        Bot bot = bots.get(botId);
        if (bot == null) return false;
        synchronized (bot) {
            if (!bot.caps.contains("heartbeat") || !currentControlSession(bot, session, epoch)) return false;
            bot.lastSeen = now();
            bot.lastHeartbeat = bot.lastSeen;
            return true;
        }
    }

    /**
     * bot/state for one scope. A schema is stored whenever it comes (asked for or not); when the schema hash changed
     * and no schema came, it is asked for. Returns the need list for the reply (possibly empty).
     */
    JsonArray state(
            Bot b,
            String scope,
            String schemaHash,
            String valuesHash,
            JsonArray schema,
            Map<String, JsonObject> nodes,
            JsonObject values) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            if (b.controlSession != null) b.lastPoll = now;
            Scope s = b.scopes.computeIfAbsent(scope, k -> new Scope());
            if (schema != null) {
                s.schema = schema;
                s.nodes = nodes;
                s.schemaHash = schemaHash;
                b.need.remove(scope + "\u0000schema");
            } else if (s.schema == null || !schemaHash.equals(s.schemaHash)) {
                b.need.add(scope + "\u0000schema");
            }
            s.values = values;
            s.valuesHash = valuesHash;
            s.receivedAt = now;
            b.need.remove(scope + "\u0000values");
            JsonArray need = new JsonArray();
            for (String n : b.need) {
                String[] p = n.split("\u0000", 2);
                JsonObject o = new JsonObject();
                o.addProperty("scope", p[0]);
                o.addProperty("what", p[1]);
                need.add(o);
            }
            b.notifyAll();
            return need;
        }
    }

    /** The outcome of a long-poll: 200 with a body, 204, or 429 (too many waiting). */
    record Poll(int status, JsonObject body) {}

    /** bot/next: hands out queued jobs and needs, waiting up to 25 s for something to happen. */
    Poll next(Bot b) throws InterruptedException {
        return next(b, POLL_WAIT_MS, null);
    }

    Poll next(Bot b, long waitMillis, String controlSession) throws InterruptedException {
        synchronized (this) {
            if (waiters >= MAX_WAITERS) return new Poll(429, null);
            waiters++;
        }
        try {
            synchronized (b) {
                if (!java.util.Objects.equals(b.controlSession, controlSession)) return new Poll(409, null);
                b.waiting++;
                long gen = b.helloGen;
                try {
                    long end = now() + waitMillis;
                    while (true) {
                        // A hello since this poll began: it belongs to the bot's previous run (its socket is
                        // probably dead). Answer 204 without handing out jobs or watch changes.
                        if (b.helloGen != gen) return new Poll(204, null);
                        long now = now();
                        b.lastPoll = now;
                        b.lastSeen = now;
                        expire(b, now);
                        boolean watching = b.watchUntil > now;
                        boolean watchingActivity = b.watchActivityUntil > now;
                        boolean watchChanged = b.watchingSent == null
                                || b.watchingSent != watching
                                || b.watchingActivitySent == null
                                || b.watchingActivitySent != watchingActivity;
                        // A need is repeated at most every 10 s, so a bot that can't answer it doesn't spin.
                        boolean needDue = !b.need.isEmpty() && now - b.needSentAt >= 10_000;
                        Job leased = controlSession == null
                                ? null
                                : b.jobs.values().stream()
                                        .filter(job -> job.status.equals("sent"))
                                        .findFirst()
                                        .orElse(null);
                        if (!b.queue.isEmpty() || leased != null || needDue || watchChanged) {
                            JsonObject out = new JsonObject();
                            JsonArray jobs = new JsonArray();
                            if (controlSession != null) {
                                if (leased == null) {
                                    leased = b.queue.pollFirst();
                                    if (leased != null) {
                                        leased.status = "sent";
                                        leased.sentAt = now;
                                        leased.lease =
                                                java.util.UUID.randomUUID().toString();
                                    }
                                }
                                if (leased != null) {
                                    JsonObject job = payload(leased);
                                    job.addProperty("sequence", leased.sequence);
                                    job.addProperty("lease", leased.lease);
                                    job.addProperty("expiresAt", leased.sentAt + UNKNOWN_SENT_MS);
                                    jobs.add(job);
                                }
                            } else
                                while (!b.queue.isEmpty()) {
                                    Job j = b.queue.pollFirst();
                                    j.status = "sent";
                                    j.sentAt = now;
                                    jobs.add(payload(j));
                                }
                            out.add("jobs", jobs);
                            JsonArray need = new JsonArray();
                            for (String n : b.need) {
                                String[] p = n.split("\u0000", 2);
                                JsonObject o = new JsonObject();
                                o.addProperty("scope", p[0]);
                                o.addProperty("what", p[1]);
                                need.add(o);
                            }
                            out.add("need", need);
                            if (!need.isEmpty()) b.needSentAt = now;
                            out.addProperty("watching", watching);
                            out.addProperty("watchingActivity", watchingActivity);

                            b.watchingSent = watching;
                            b.watchingActivitySent = watchingActivity;
                            return new Poll(200, out);
                        }
                        long left = end - now;
                        if (left <= 0) return new Poll(204, null);
                        // Wake up in time to tell the bot when the browser stops watching.
                        long wait = Math.min(left, watching ? Math.max(1, b.watchUntil - now + 1) : left);
                        if (watchingActivity) wait = Math.min(wait, Math.max(1, b.watchActivityUntil - now + 1));
                        if (!b.need.isEmpty()) wait = Math.min(wait, Math.max(1, b.needSentAt + 10_000 - now));
                        b.wait(wait);
                    }
                } finally {
                    b.waiting--;
                    b.lastPoll = now();
                }
            }
        } finally {
            synchronized (this) {
                waiters--;
            }
        }
    }

    private static JsonObject payload(Job job) {
        JsonObject out = job.payload.deepCopy();
        out.addProperty("id", job.id);
        out.addProperty("type", job.type);
        return out;
    }

    /** bot/results: one result (a set reply, a command's first reply, or a later output part with more). */
    void result(Bot b, String id, boolean ok, boolean more, JsonElement value, String error) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            expire(b, now);
            Job j = b.jobs.get(id);
            if (b.controlSession != null || j == null || !j.status.equals("sent") || more) return;
            j.status = ok ? "done" : "error";
            j.value = value;
            j.error = error;
            b.notifyAll();
        }
    }

    enum Acknowledgement {
        APPLIED,
        REPLAY,
        REFUSED
    }

    Acknowledgement acknowledge(Bot b, String controlSession, JsonObject receipt) {
        synchronized (b) {
            if (controlSession == null || !controlSession.equals(b.controlSession)) return Acknowledgement.REFUSED;
            if (!RECEIPT_FIELDS.containsAll(receipt.keySet())) return Acknowledgement.REFUSED;
            String id = AddonRoutes.string(receipt, "id");
            String lease = AddonRoutes.string(receipt, "lease");
            Job job = b.jobs.get(id);
            var sequence = receipt.get("sequence");
            var ok = receipt.get("ok");
            if (job == null
                    || lease == null
                    || !lease.equals(job.lease)
                    || sequence == null
                    || !sequence.equals(new com.google.gson.JsonPrimitive(job.sequence))
                    || !(ok instanceof com.google.gson.JsonPrimitive outcome)
                    || !outcome.isBoolean()) return Acknowledgement.REFUSED;
            if (outcome.getAsBoolean() && !java.util.Objects.equals(job.payload.get("value"), receipt.get("value"))) {
                return Acknowledgement.REFUSED;
            }
            if (!outcome.getAsBoolean() && receipt.has("value")) return Acknowledgement.REFUSED;
            String error = AddonRoutes.string(receipt, "error");
            if (receipt.has("error") && (error == null || error.length() > 1000 || outcome.getAsBoolean())) {
                return Acknowledgement.REFUSED;
            }
            if (job.receipt != null) {
                if (!job.receipt.equals(receipt)) return Acknowledgement.REFUSED;
                b.lastSeen = b.lastPoll = now();
                return Acknowledgement.REPLAY;
            }
            expire(b, now());
            if (!job.status.equals("sent")) return Acknowledgement.REFUSED;
            job.receipt = receipt.deepCopy();
            job.status = outcome.getAsBoolean() ? "done" : "error";
            job.value = receipt.get("value");
            job.error = error;
            b.lastSeen = b.lastPoll = now();
            b.notifyAll();
            return Acknowledgement.APPLIED;
        }
    }

    /** Queued jobs nobody took within 10 s expire; sent ones without an answer within 60 s become unknown. */
    private static void expire(Bot b, long now) {
        for (Iterator<Job> it = b.queue.iterator(); it.hasNext(); ) {
            Job j = it.next();
            long queueLifetime = b.controlSession == null ? EXPIRE_QUEUED_MS : UNKNOWN_SENT_MS;
            if (now - j.createdAt >= queueLifetime) {
                it.remove();
                j.status = "expired";
            }
        }
        for (Job j : b.jobs.values()) {
            if (j.status.equals("sent") && now - j.sentAt >= UNKNOWN_SENT_MS) {
                j.status = "unknown";
            }
        }
    }

    // ---------- browser side ----------

    /** A browser has this bot open: the bot re-checks its values every 3 s and the long-poll is told at once. */
    void watch(Bot b) {
        synchronized (b) {
            boolean was = b.watchUntil > now();
            b.watchUntil = now() + WATCH_MS;
            if (!was) b.notifyAll();
        }
    }

    /** A browser has this bot's Activity tab open: the bot pushes every 2 s, and the long-poll is told at once. */
    void watchActivity(Bot b) {
        synchronized (b) {
            boolean was = b.watchActivityUntil > now();
            b.watchActivityUntil = now() + WATCH_MS;
            if (!was) b.notifyAll();
        }
    }

    /** bot/activity: stores the snapshot and adds its new events (de-duplicated by at, source and text). */
    void activity(Bot b, JsonObject snapshot, JsonArray events) {
        long now = now();
        synchronized (b) {
            b.lastSeen = now;
            b.activity = snapshot;
            b.activityAt = now;
            for (JsonElement e : events) {
                JsonObject v = e.getAsJsonObject();
                String key =
                        v.get("at").getAsLong() + "\u0000" + v.get("source").getAsString() + "\u0000"
                                + v.get("text").getAsString();
                if (!b.eventKeys.add(key)) continue;
                b.events.addLast(v);
                while (b.events.size() > EVENTS_KEEP) {
                    JsonObject old = b.events.pollFirst();
                    b.eventKeys.remove(old.get("at").getAsLong() + "\u0000"
                            + old.get("source").getAsString() + "\u0000"
                            + old.get("text").getAsString());
                }
            }
        }
    }

    /** {activity, receivedAt, events (newest first)} for the page. */
    JsonObject activityJson(Bot b) {
        JsonObject out = new JsonObject();
        synchronized (b) {
            out.add("activity", b.activity == null ? JsonNull.INSTANCE : b.activity.deepCopy());
            out.addProperty("receivedAt", b.activityAt > 0 ? b.activityAt : null);
            JsonArray ev = new JsonArray();
            java.util.Iterator<JsonObject> it = b.events.descendingIterator();
            while (it.hasNext()) ev.add(it.next());
            out.add("events", ev);
            out.addProperty("online", online(b, now()));
        }
        return out;
    }

    boolean online(Bot b) {
        synchronized (b) {
            return online(b, now());
        }
    }

    Scope scope(Bot b, String scope) {
        synchronized (b) {
            return b.scopes.get(scope);
        }
    }

    /**
     * A browser opened a scope the server has no schema for: ask the bot for it (schema and values), at once.
     * Only for scopes the bot declared (or already pushed). Returns whether it was asked.
     */
    boolean requestScope(Bot b, String scope) {
        synchronized (b) {
            if (!b.declaredScopes.contains(scope) && !b.scopes.containsKey(scope)) return false;
            boolean added = b.need.add(scope + "\u0000schema");
            if (added) {
                b.needSentAt = 0;
                b.notifyAll();
            }
            return true;
        }
    }

    /** Queues settings changes; returns the job ids in order. */
    List<String> queueSets(Bot b, String scope, List<JsonObject> changes) {
        if (!"plugin".equals(scope)) throw new IllegalArgumentException("scope must be plugin");
        long now = now();
        List<String> ids = new ArrayList<>();
        synchronized (b) {
            for (JsonObject c : changes) {
                JsonObject p = new JsonObject();
                p.addProperty("scope", scope);
                p.add("path", c.get("path"));
                p.add("value", c.has("value") ? c.get("value") : JsonNull.INSTANCE);
                if (c.has("expect")) p.add("expect", c.get("expect"));
                Job j = new Job(newId(), "set", p, now);
                j.sequence = ++b.jobSequence;
                add(b, j);
                ids.add(j.id);
            }
            b.notifyAll();
        }
        return ids;
    }

    /** The same, remembering which website user sent it (a "list"-mode user only sees their own). */
    private static void add(Bot b, Job j) {
        b.queue.addLast(j);
        b.jobs.put(j.id, j);
        if (b.jobs.size() > JOBS_KEEP) {
            Iterator<Job> it = b.jobs.values().iterator();
            while (b.jobs.size() > JOBS_KEEP && it.hasNext()) {
                Job old = it.next();
                if (old.status.equals("queued")) continue;
                it.remove();
            }
        }
    }

    /** Status of jobs by id ("unknown" for ids the server doesn't hold). */
    JsonArray jobs(Bot b, List<String> ids) {
        JsonArray out = new JsonArray();
        synchronized (b) {
            expire(b, now());
            for (String id : ids) {
                Job j = b.jobs.get(id);
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                o.addProperty("status", j == null ? "unknown" : j.status);
                if (j != null) {
                    if (j.value != null) o.add("value", j.value);
                    if (j.error != null) o.addProperty("error", j.error);
                }
                out.add(o);
            }
        }
        return out;
    }

    /** Console entries changed after revision {@code after}, oldest first, plus the newest revision. */

    /** The same, only the entries sent by onlyBy (null: everyone's). */
    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "j-" + HexFormat.of().formatHex(bytes);
    }
}
