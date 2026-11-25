package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Handoff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * W-039: player handoff at a registered meeting place. A handoff reaches {@code DELIVERED} only with an explicit
 * recipient receipt ({@code confirmedBy}) from the order owner/admin — never because a drop command ran. A drop
 * without a verified pickup goes to {@code NEEDS_RECONCILIATION}, and a no-show is {@code NO_SHOW}: the stock is not
 * returned until an explicit {@code RETURNED}, so there is no false credit. Only the recorded agent may advance a
 * handoff toward arrival, no-show or an unverified drop; a second agent cannot take over the cargo.
 */
final class Handoffs {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Entry> handoffs = new LinkedHashMap<>();

    private record Entry(Handoff handoff, JsonArray events) {}

    Handoffs(Path data) throws IOException {
        this(data, System::currentTimeMillis);
    }

    Handoffs(Path data, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.file = data.resolve("handoffs.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid handoff location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("handoffs")) {
                var o = entry.getAsJsonObject();
                var handoff = Handoff.read(o);
                handoffs.put(
                        handoff.id(),
                        new Entry(handoff, o.getAsJsonArray("events").deepCopy()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read handoffs", invalid);
        }
    }

    synchronized Handoff schedule(String orderId, String recipient, String meetingPoint, String agent)
            throws IOException {
        if (agent == null || agent.isBlank()) throw new IllegalArgumentException("A handoff needs an assigned agent");
        var handoff = Handoff.schedule(orderId, recipient, meetingPoint, agent);
        var events = new JsonArray();
        events.add(event("SCHEDULED", agent));
        commit(handoff.id(), new Entry(handoff, events));
        return handoff;
    }

    synchronized Handoff arrive(String id, String agent) throws IOException {
        var entry = byAgent(id, agent);
        return advanceTo(
                id,
                entry,
                entry.handoff().advance(Handoff.State.AWAITING_RECIPIENT, agent),
                "AWAITING_RECIPIENT",
                agent);
    }

    /** The explicit recipient receipt; only the order owner/admin may call this (enforced at the route). */
    synchronized Handoff confirm(String id, String actor) throws IOException {
        var entry = require(id);
        if (entry.handoff().state() == Handoff.State.DELIVERED) return entry.handoff(); // idempotent replay
        return advanceTo(id, entry, entry.handoff().advance(Handoff.State.DELIVERED, actor), "DELIVERED", actor);
    }

    synchronized Handoff noShow(String id, String agent) throws IOException {
        var entry = byAgent(id, agent);
        return advanceTo(id, entry, entry.handoff().advance(Handoff.State.NO_SHOW, agent), "NO_SHOW", agent);
    }

    /** A drop happened but no verified pickup: never DELIVERED, always uncertain. */
    synchronized Handoff dropUnverified(String id, String agent, String reason) throws IOException {
        var entry = byAgent(id, agent);
        return advanceTo(
                id,
                entry,
                entry.handoff().advance(Handoff.State.NEEDS_RECONCILIATION, agent),
                "DROP_UNVERIFIED",
                agent);
    }

    /** Resolves an uncertain handoff: DELIVERED (with a receipt) or RETURNED. */
    synchronized Handoff resolve(String id, Handoff.State outcome, String actor) throws IOException {
        var entry = require(id);
        // A legacy unassigned booking cannot be advanced by any agent. The owner
        // may explicitly confirm its return, but never mark it delivered here.
        boolean unassignedReturn = entry.handoff().state() == Handoff.State.SCHEDULED
                && (entry.handoff().agent() == null || entry.handoff().agent().isBlank())
                && outcome == Handoff.State.RETURNED;
        if (entry.handoff().state() != Handoff.State.NEEDS_RECONCILIATION && !unassignedReturn)
            throw new Conflict("Only an uncertain handoff may be resolved");
        if (outcome != Handoff.State.DELIVERED && outcome != Handoff.State.RETURNED)
            throw new Conflict("Resolution must be delivered or returned");
        return advanceTo(
                id,
                entry,
                entry.handoff().advance(outcome, actor),
                outcome == Handoff.State.DELIVERED ? "DELIVERED" : "RETURNED",
                actor);
    }

    /** A no-show customer may be retried (back to awaiting) or returned to stock. */
    synchronized Handoff retry(String id, String agent) throws IOException {
        var entry = byAgent(id, agent);
        return advanceTo(
                id,
                entry,
                entry.handoff().advance(Handoff.State.AWAITING_RECIPIENT, agent),
                "AWAITING_RECIPIENT",
                agent);
    }

    synchronized Handoff returnOrder(String id, String agent) throws IOException {
        var entry = byAgent(id, agent);
        return advanceTo(id, entry, entry.handoff().advance(Handoff.State.RETURNED, agent), "RETURNED", agent);
    }

    /**
     * Bounded wait in AWAITING_RECIPIENT before the manager records the no-show
     * itself (#210): no human can ever confirm for the recipient and a dead
     * agent can never report it, so without this the cargo stays fenced and
     * invisible forever.
     */
    static final long AWAITING_NOSHOW_AFTER_MS = 4 * 60 * 60_000;

    /**
     * Records a no-show for handoffs stuck awaiting a recipient past the bounded
     * wait, as {@code system} so the actor trail stays honest. NO_SHOW is
     * non-terminal (retryable back to AWAITING_RECIPIENT, returnable to
     * RETURNED), so nothing is fabricated and no stock is credited; an explicit
     * recipient receipt is still the only path to DELIVERED. Returns human-
     * readable descriptions for logging/audit.
     */
    synchronized java.util.List<String> sweepStaleAwaiting(long nowMs) throws IOException {
        var swept = new java.util.ArrayList<String>();
        for (String id : new java.util.ArrayList<>(handoffs.keySet())) {
            var entry = handoffs.get(id);
            if (entry == null || entry.handoff().state() != Handoff.State.AWAITING_RECIPIENT) continue;
            if (nowMs - lastEventAt(entry.events()) <= AWAITING_NOSHOW_AFTER_MS) continue;
            var next = entry.handoff().advance(Handoff.State.NO_SHOW, "system");
            advanceTo(id, entry, next, "NO_SHOW_AUTO", "system");
            swept.add("Handoff " + id + " (order " + entry.handoff().orderId() + ") had no recipient for "
                    + ((nowMs - lastEventAt(entry.events())) / 1000)
                    + "s; recorded NO_SHOW, retry or return remains available.");
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

    synchronized Handoff get(String id) {
        var entry = handoffs.get(id);
        return entry == null ? null : entry.handoff();
    }

    synchronized JsonArray list(String orderId) {
        var out = new JsonArray();
        for (var entry : handoffs.values()) {
            if (orderId != null && !entry.handoff().orderId().equals(orderId)) continue;
            var o = entry.handoff().json();
            o.add("events", entry.events().deepCopy());
            out.add(o);
        }
        return out;
    }

    private Entry byAgent(String id, String agent) throws Conflict {
        var entry = require(id);
        var handoff = entry.handoff();
        if (handoff.terminal()) throw new Conflict("A terminal handoff cannot change");
        if (agent == null || agent.isBlank() || !agent.equals(handoff.agent()))
            throw new Conflict("Another agent holds this handoff");
        return entry;
    }

    private Entry require(String id) throws Conflict {
        var entry = handoffs.get(id);
        if (entry == null) throw new Conflict("Unknown handoff");
        return entry;
    }

    private Handoff advanceTo(String id, Entry entry, Handoff next, String event, String actor) throws IOException {
        var events = entry.events().deepCopy();
        events.add(event(event, actor));
        commit(id, new Entry(next, events));
        return next;
    }

    private JsonObject event(String event, String by) {
        var o = new JsonObject();
        o.addProperty("at", clock.getAsLong());
        o.addProperty("event", event);
        o.addProperty("by", by);
        return o;
    }

    /** Publish only a fully persisted state; a failed write must not advance custody or delivery. */
    private void commit(String id, Entry entry) throws IOException {
        var updated = new LinkedHashMap<>(handoffs);
        updated.put(id, entry);
        save(updated);
        handoffs.clear();
        handoffs.putAll(updated);
    }

    private void save(Map<String, Entry> updated) throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        for (var entry : updated.values()) {
            var o = entry.handoff().json();
            o.add("events", entry.events().deepCopy());
            list.add(o);
        }
        root.add("handoffs", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
