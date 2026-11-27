package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Handoff;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * W-039: player handoff at a registered meeting place. A handoff reaches {@code DELIVERED} only with an explicit
 * recipient receipt ({@code confirmedBy}) from the order owner/admin — never because a drop command ran. A drop
 * without a verified pickup goes to {@code NEEDS_RECONCILIATION}, and a no-show is {@code NO_SHOW}: the stock is not
 * returned until an explicit {@code RETURNED}, so there is no false credit. Only the recorded agent may advance a
 * handoff toward arrival, no-show or an unverified drop; a second agent cannot take over the cargo.
 *
 * <p>PR3 (#140): authoritative state lives in the shared guild SQLite database
 * ({@code handoffs}/{@code handoff_events}), so handoff rows commit or roll
 * back in the same transaction as the order book and the audit trail. Legacy
 * {@code handoffs.json} files are never read; {@link StockLedger} warns loudly
 * when one is present.
 *
 * <p>Global lock order for nested same-transaction commits: ledger → index →
 * book → store. Public methods take the store monitor and a fresh ledger
 * transaction; {@code Connection}-scoped overloads assume the caller already
 * holds the locks in that order and never commit on their own.
 */
final class Handoffs {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    private final StockLedger ledger;
    private final Clock clock;

    Handoffs(StockLedger ledger, Clock clock) {
        if (ledger == null) throw new IllegalArgumentException("Handoffs require the guild database");
        if (clock == null) throw new IllegalArgumentException("Handoffs require a clock");
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    Handoffs(Path data) throws IOException {
        this(data, Clock.system());
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    Handoffs(Path data, Clock clock) throws IOException {
        this(new StockLedger(data, clock), clock);
    }

    private record Entry(Handoff handoff, JsonArray events) {}

    synchronized Handoff schedule(String orderId, String recipient, String meetingPoint, String agent)
            throws IOException {
        return schedule(orderId, recipient, meetingPoint, agent, null);
    }

    /** Schedules a handoff, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff schedule(
            String orderId, String recipient, String meetingPoint, String agent, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Handoff handoff = schedule(c, orderId, recipient, meetingPoint, agent);
            if (trail != null) {
                trail.say("handoff.schedule", handoff.id());
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return handoff;
        } catch (SQLException failure) {
            throw new IOException("Cannot schedule handoff", failure);
        }
    }

    /**
     * Same-transaction schedule: writes on the caller's connection without
     * committing, so an order commit can carry handoffs and audit atomically.
     */
    Handoff schedule(Connection c, String orderId, String recipient, String meetingPoint, String agent)
            throws SQLException, IOException {
        if (agent == null || agent.isBlank()) throw new IllegalArgumentException("A handoff needs an assigned agent");
        var handoff = Handoff.schedule(orderId, recipient, meetingPoint, agent);
        var events = new JsonArray();
        events.add(event("SCHEDULED", agent));
        insert(c, new Entry(handoff, events));
        return handoff;
    }

    synchronized Handoff arrive(String id, String agent) throws IOException {
        return arrive(id, agent, null);
    }

    /** Records arrival, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff arrive(String id, String agent, AuditLog.Batch trail) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = byAgent(c, id, agent);
            Handoff next = advanceTo(
                    c, id, entry, next(entry, Handoff.State.AWAITING_RECIPIENT, agent), "AWAITING_RECIPIENT", agent);
            if (trail != null) {
                trail.say("handoff.arrive", id);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot record handoff arrival", failure);
        }
    }

    /** The explicit recipient receipt; only the order owner/admin may call this (enforced at the route). */
    synchronized Handoff confirm(String id, String actor) throws IOException {
        return confirm(id, actor, null);
    }

    /** Confirms delivery, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff confirm(String id, String actor, AuditLog.Batch trail) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = require(c, id);
            if (entry.handoff().state() != Handoff.State.DELIVERED) {
                advanceTo(c, id, entry, next(entry, Handoff.State.DELIVERED, actor), "DELIVERED", actor);
            }
            Handoff done = read(c, id).handoff();
            if (trail != null) {
                trail.say("handoff.confirm", id);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return done; // idempotent replay returns the same record
        } catch (SQLException failure) {
            throw new IOException("Cannot confirm handoff", failure);
        }
    }

    synchronized Handoff noShow(String id, String agent) throws IOException {
        return noShow(id, agent, null);
    }

    /** Records a no-show, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff noShow(String id, String agent, AuditLog.Batch trail) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = byAgent(c, id, agent);
            Handoff next = advanceTo(c, id, entry, next(entry, Handoff.State.NO_SHOW, agent), "NO_SHOW", agent);
            if (trail != null) {
                trail.say("handoff.no-show", id);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot record handoff no-show", failure);
        }
    }

    /** A drop happened but no verified pickup: never DELIVERED, always uncertain. */
    synchronized Handoff dropUnverified(String id, String agent, String reason) throws IOException {
        return dropUnverified(id, agent, reason, null);
    }

    /** Records an unverified drop, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff dropUnverified(String id, String agent, String reason, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = byAgent(c, id, agent);
            Handoff next = advanceTo(
                    c, id, entry, next(entry, Handoff.State.NEEDS_RECONCILIATION, agent), "DROP_UNVERIFIED", agent);
            if (trail != null) {
                trail.say("handoff.drop", id);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot record handoff drop", failure);
        }
    }

    /** Resolves an uncertain handoff: DELIVERED (with a receipt) or RETURNED. */
    synchronized Handoff resolve(String id, Handoff.State outcome, String actor) throws IOException {
        return resolve(id, outcome, actor, null);
    }

    /** Resolves a handoff, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Handoff resolve(String id, Handoff.State outcome, String actor, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Handoff next = resolve(c, id, outcome, actor);
            if (trail != null) {
                trail.say("handoff.resolve", id);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot resolve handoff", failure);
        }
    }

    /**
     * Same-transaction resolve: writes on the caller's connection without
     * committing, so an order commit can carry handoffs and audit atomically.
     */
    Handoff resolve(Connection c, String id, Handoff.State outcome, String actor) throws SQLException, IOException {
        Entry entry = require(c, id);
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
                c,
                id,
                entry,
                next(entry, outcome, actor),
                outcome == Handoff.State.DELIVERED ? "DELIVERED" : "RETURNED",
                actor);
    }

    /** A no-show customer may be retried (back to awaiting) or returned to stock. */
    synchronized Handoff retry(String id, String agent) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = byAgent(c, id, agent);
            Handoff next = advanceTo(
                    c, id, entry, next(entry, Handoff.State.AWAITING_RECIPIENT, agent), "AWAITING_RECIPIENT", agent);
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot retry handoff", failure);
        }
    }

    synchronized Handoff returnOrder(String id, String agent) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Entry entry = byAgent(c, id, agent);
            Handoff next = advanceTo(c, id, entry, next(entry, Handoff.State.RETURNED, agent), "RETURNED", agent);
            tx.commit();
            return next;
        } catch (SQLException failure) {
            throw new IOException("Cannot return handoff", failure);
        }
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
        var swept = new ArrayList<String>();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            List<String> ids = new ArrayList<>();
            try (var p = c.prepareStatement("SELECT id FROM handoffs WHERE state='AWAITING_RECIPIENT' ORDER BY id")) {
                try (var rows = p.executeQuery()) {
                    while (rows.next()) ids.add(rows.getString(1));
                }
            }
            for (String id : ids) {
                Entry entry = read(c, id);
                if (entry == null || entry.handoff().state() != Handoff.State.AWAITING_RECIPIENT) continue;
                if (nowMs - lastEventAt(entry.events()) <= AWAITING_NOSHOW_AFTER_MS) continue;
                var next = next(entry, Handoff.State.NO_SHOW, "system");
                advanceTo(c, id, entry, next, "NO_SHOW_AUTO", "system");
                swept.add("Handoff " + id + " (order " + entry.handoff().orderId() + ") had no recipient for "
                        + ((nowMs - lastEventAt(entry.events())) / 1000)
                        + "s; recorded NO_SHOW, retry or return remains available.");
            }
            tx.commit();
        } catch (SQLException failure) {
            throw new IOException("Cannot sweep stale handoffs", failure);
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

    synchronized Handoff get(String id) throws IOException {
        try (var c = ledger.open()) {
            Entry entry = read(c, id);
            return entry == null ? null : entry.handoff();
        } catch (SQLException failure) {
            throw new IOException("Cannot read handoffs", failure);
        }
    }

    synchronized JsonArray list(String orderId) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open();
                var p = orderId == null
                        ? c.prepareStatement("SELECT id FROM handoffs ORDER BY id")
                        : c.prepareStatement("SELECT id FROM handoffs WHERE order_id=? ORDER BY id")) {
            if (orderId != null) p.setString(1, orderId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    Entry entry = read(c, rows.getString(1));
                    if (entry == null) continue;
                    var o = entry.handoff().json();
                    o.add("events", entry.events().deepCopy());
                    out.add(o);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot list handoffs", failure);
        }
        return out;
    }

    private Entry byAgent(Connection c, String id, String agent) throws SQLException, IOException, Conflict {
        Entry entry = require(c, id);
        var handoff = entry.handoff();
        if (handoff.terminal()) throw new Conflict("A terminal handoff cannot change");
        if (agent == null || agent.isBlank() || !agent.equals(handoff.agent()))
            throw new Conflict("Another agent holds this handoff");
        return entry;
    }

    private Entry require(Connection c, String id) throws SQLException, IOException, Conflict {
        Entry entry = read(c, id);
        if (entry == null) throw new Conflict("Unknown handoff");
        return entry;
    }

    private Handoff next(Entry entry, Handoff.State state, String actor) throws Conflict {
        try {
            return entry.handoff().advance(state, actor);
        } catch (IllegalArgumentException e) {
            throw new Conflict(e.getMessage());
        }
    }

    private Handoff advanceTo(Connection c, String id, Entry entry, Handoff next, String event, String actor)
            throws SQLException, IOException {
        var events = entry.events().deepCopy();
        events.add(event(event, actor));
        update(c, id, new Entry(next, events));
        return next;
    }

    private JsonObject event(String event, String by) {
        var o = new JsonObject();
        o.addProperty("at", clock.millis());
        o.addProperty("event", event);
        o.addProperty("by", by);
        return o;
    }

    /** Inserts a new handoff row plus its first event; fails closed on a duplicate id. */
    private static void insert(Connection c, Entry entry) throws SQLException {
        var h = entry.handoff();
        try (var row = c.prepareStatement(
                "INSERT INTO handoffs(id,order_id,state,agent,payload,revision) VALUES(?,?,?,?,?,?)")) {
            row.setString(1, h.id());
            row.setString(2, h.orderId());
            row.setString(3, h.state().name());
            row.setString(4, h.agent());
            row.setString(5, h.json().toString());
            row.setLong(6, h.revision());
            row.executeUpdate();
        }
        insertEvents(c, h.id(), entry.events());
    }

    /** Conditional update: a concurrent advance on another connection wins instead of silently overwriting. */
    private static void update(Connection c, String id, Entry entry) throws SQLException, Conflict {
        var h = entry.handoff();
        try (var row = c.prepareStatement(
                "UPDATE handoffs SET state=?,agent=?,payload=?,revision=? WHERE id=? AND revision=?")) {
            row.setString(1, h.state().name());
            row.setString(2, h.agent());
            row.setString(3, h.json().toString());
            row.setLong(4, h.revision());
            row.setString(5, id);
            row.setLong(6, h.revision() - 1);
            if (row.executeUpdate() != 1) throw new Conflict("Handoff changed concurrently; reread the handoff");
        }
        insertEvents(c, id, entry.events());
    }

    private static void insertEvents(Connection c, String id, JsonArray events) throws SQLException {
        try (var event = c.prepareStatement(
                "INSERT INTO handoff_events(id,seq,payload) VALUES(?,?,?) ON CONFLICT(id,seq) DO NOTHING")) {
            long seq = 0;
            for (var e : events) {
                event.setString(1, id);
                event.setLong(2, ++seq);
                event.setString(3, e.toString());
                event.executeUpdate();
            }
        }
    }

    private static Entry read(Connection c, String id) throws SQLException, IOException {
        JsonObject stored;
        try (var p = c.prepareStatement("SELECT payload FROM handoffs WHERE id=?")) {
            p.setString(1, id);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                stored = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read handoffs", invalid);
        }
        var events = new JsonArray();
        try (var p = c.prepareStatement("SELECT payload FROM handoff_events WHERE id=? ORDER BY seq")) {
            p.setString(1, id);
            try (var rows = p.executeQuery()) {
                while (rows.next()) events.add(JsonParser.parseString(rows.getString(1)));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read handoffs", invalid);
        }
        try {
            return new Entry(Handoff.read(stored), events);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read handoffs", invalid);
        }
    }
}
