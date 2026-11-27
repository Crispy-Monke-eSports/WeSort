package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * W-029: custody tracking for a physical order package, from warehouse staging through pickup, transport and a
 * verified deposit or player handoff. The {@code packageId} is the Manager's own persistent identity — never a
 * Minecraft UUID, and never the visible {@code "WeSort (X/Y) - text"} label, so two packages with the same visible
 * name never merge. Each package binds its order, pack job, manifest fingerprint, destination and visible label, and
 * every custody event records the server-confirmed physical source (server/dimension/stash/slot), the agent and the
 * evidence. Custody is mutually exclusive: after an agent picks a package up, only that agent (or an explicit
 * reconciliation) may advance it, so two couriers can never claim the same package. Every change is an append-only
 * event and a reboot or replayed receipt does not change custody.
 *
 * <p>PR3 (#140): authoritative state lives in the shared guild SQLite database
 * ({@code custody_packages}/{@code custody_events}), so custody rows commit or
 * roll back in the same transaction as the order book and the audit trail.
 * Legacy {@code custody.json} files are never read; {@link StockLedger} warns
 * loudly when one is present.
 *
 * <p>Global lock order for nested same-transaction commits: ledger → index →
 * book → store. Public methods take the store monitor and a fresh ledger
 * transaction; {@code Connection}-scoped overloads assume the caller already
 * holds the locks in that order and never commit on their own.
 */
final class PackageCustody {
    enum State {
        STAGED,
        PICKUP_VERIFIED,
        IN_TRANSIT,
        DEPOSIT_VERIFIED,
        PLAYER_HANDOFF_PENDING,
        NEEDS_RECONCILIATION;

        boolean allows(State next) {
            return switch (this) {
                case STAGED -> next == PICKUP_VERIFIED || next == NEEDS_RECONCILIATION;
                case PICKUP_VERIFIED -> next == IN_TRANSIT || next == NEEDS_RECONCILIATION;
                case IN_TRANSIT ->
                    next == DEPOSIT_VERIFIED || next == PLAYER_HANDOFF_PENDING || next == NEEDS_RECONCILIATION;
                case DEPOSIT_VERIFIED, PLAYER_HANDOFF_PENDING -> next == NEEDS_RECONCILIATION;
                case NEEDS_RECONCILIATION -> false;
            };
        }
    }

    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    private final StockLedger ledger;
    private final Clock clock;

    PackageCustody(StockLedger ledger, Clock clock) {
        if (ledger == null) throw new IllegalArgumentException("PackageCustody requires the guild database");
        if (clock == null) throw new IllegalArgumentException("PackageCustody requires a clock");
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    PackageCustody(Path data) throws IOException {
        this(data, Clock.system());
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    PackageCustody(Path data, Clock clock) throws IOException {
        this(new StockLedger(data, clock), clock);
    }

    record Package(
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            State state,
            String agent,
            JsonObject location,
            long revision,
            JsonArray events) {
        JsonObject json() {
            var out = new JsonObject();
            out.addProperty("packageId", packageId);
            out.addProperty("orderId", orderId);
            out.addProperty("manifest", manifest);
            out.addProperty("jobId", jobId);
            out.addProperty("destination", destination);
            out.addProperty("label", label);
            out.addProperty("state", state.name());
            out.addProperty("agent", agent);
            out.add("location", location == null ? new JsonObject() : location.deepCopy());
            out.addProperty("revision", revision);
            out.add("events", events.deepCopy());
            return out;
        }
    }

    /** Stages a package at the warehouse; idempotent for the same package id and binding. */
    synchronized Package stage(String packageId, String orderId, JsonObject location) throws IOException {
        return stage(packageId, orderId, null, null, null, null, location, null);
    }

    synchronized Package stage(
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            JsonObject location)
            throws IOException {
        return stage(packageId, orderId, manifest, jobId, destination, label, location, null);
    }

    /** Stages a package, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Package stage(
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            JsonObject location,
            AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Package staged = stage(c, packageId, orderId, manifest, jobId, destination, label, location);
            if (trail != null) {
                trail.say("custody.stage", packageId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return staged;
        } catch (SQLException failure) {
            throw new IOException("Cannot stage package custody", failure);
        }
    }

    /**
     * Same-transaction stage: writes on the caller's connection without
     * committing, so an order commit can carry custody and audit atomically.
     */
    Package stage(
            Connection c,
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            JsonObject location)
            throws SQLException, IOException {
        UUID.fromString(packageId);
        Package existing = read(c, packageId);
        if (existing != null) {
            if (!existing.orderId().equals(orderId)
                    || !eq(existing.manifest(), manifest)
                    || !eq(existing.jobId(), jobId)
                    || !eq(existing.destination(), destination)) {
                throw new Conflict("Package already belongs to another order or binding");
            }
            return existing;
        }
        var staged = new Package(
                packageId,
                orderId,
                manifest,
                jobId,
                destination,
                label,
                State.STAGED,
                null,
                location == null ? new JsonObject() : location,
                0,
                new JsonArray());
        return insert(c, append(staged, "STAGED", null, location, new JsonObject()));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Advances a package to {@code next} by {@code agent}. A claim (the first agent to move a staged package) is
     * mutually exclusive; afterwards only the same agent, or a reconciliation, may move it.
     */
    synchronized Package transition(
            String packageId, String orderId, String agent, State next, JsonObject location, JsonObject evidence)
            throws IOException {
        return transition(packageId, orderId, agent, next, location, evidence, null);
    }

    /** Advances a package, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Package transition(
            String packageId,
            String orderId,
            String agent,
            State next,
            JsonObject location,
            JsonObject evidence,
            AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Package moved = transition(c, packageId, orderId, agent, next, location, evidence);
            if (trail != null) {
                trail.say("custody." + next.name(), packageId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return moved;
        } catch (SQLException failure) {
            throw new IOException("Cannot advance package custody", failure);
        }
    }

    /**
     * Same-transaction transition: writes on the caller's connection without
     * committing, so an order commit can carry custody and audit atomically.
     */
    Package transition(
            Connection c,
            String packageId,
            String orderId,
            String agent,
            State next,
            JsonObject location,
            JsonObject evidence)
            throws SQLException, IOException {
        Package p = read(c, packageId);
        if (p == null) throw new Conflict("Unknown package");
        if (!p.orderId().equals(orderId)) throw new Conflict("Package belongs to another order");
        if (next != State.NEEDS_RECONCILIATION) {
            if (!p.state().allows(next)) throw new Conflict("Package cannot move from " + p.state() + " to " + next);
            if (p.agent() != null && !p.agent().equals(agent)) {
                throw new Conflict("Another agent holds custody; physical reconciliation is required");
            }
        }
        return update(c, append(p, next.name(), agent, location, evidence == null ? new JsonObject() : evidence));
    }

    synchronized Package get(String packageId) throws IOException {
        try (var c = ledger.open()) {
            return read(c, packageId);
        } catch (SQLException failure) {
            throw new IOException("Cannot read package custody", failure);
        }
    }

    synchronized JsonArray list(String orderId) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open();
                var p = orderId == null
                        ? c.prepareStatement("SELECT package_id FROM custody_packages ORDER BY package_id")
                        : c.prepareStatement(
                                "SELECT package_id FROM custody_packages WHERE order_id=? ORDER BY package_id")) {
            if (orderId != null) p.setString(1, orderId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    Package pkg = read(c, rows.getString(1));
                    if (pkg != null) out.add(pkg.json());
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot list package custody", failure);
        }
        return out;
    }

    private Package append(Package p, String event, String agent, JsonObject location, JsonObject evidence) {
        long revision = p.revision() + 1;
        var row = new JsonObject();
        row.addProperty("seq", revision);
        row.addProperty("at", clock.millis());
        row.addProperty("event", event);
        row.addProperty("agent", agent);
        row.add("location", location == null ? new JsonObject() : location.deepCopy());
        row.add("evidence", evidence.deepCopy());
        var events = p.events().deepCopy();
        events.add(row);
        return new Package(
                p.packageId(),
                p.orderId(),
                p.manifest(),
                p.jobId(),
                p.destination(),
                p.label(),
                State.valueOf(event),
                agent == null ? p.agent() : agent,
                location == null ? p.location() : location,
                revision,
                events);
    }

    /** Inserts a new package row plus its first event; fails closed on a concurrent insert. */
    private static Package insert(Connection c, Package p) throws SQLException {
        try (var row = c.prepareStatement(
                "INSERT INTO custody_packages(package_id,order_id,state,agent,payload,revision) VALUES(?,?,?,?,?,?)")) {
            row.setString(1, p.packageId());
            row.setString(2, p.orderId());
            row.setString(3, p.state().name());
            row.setString(4, p.agent());
            row.setString(5, payloadOf(p));
            row.setLong(6, p.revision());
            row.executeUpdate();
        }
        insertEvents(c, p);
        return p;
    }

    /** Conditional update: a concurrent advance on another connection wins instead of silently overwriting. */
    private static Package update(Connection c, Package p) throws SQLException, Conflict {
        try (var row = c.prepareStatement(
                "UPDATE custody_packages SET state=?,agent=?,payload=?,revision=? WHERE package_id=? AND revision=?")) {
            row.setString(1, p.state().name());
            row.setString(2, p.agent());
            row.setString(3, payloadOf(p));
            row.setLong(4, p.revision());
            row.setString(5, p.packageId());
            row.setLong(6, p.revision() - 1);
            if (row.executeUpdate() != 1)
                throw new Conflict("Package custody changed concurrently; reread the package");
        }
        insertEvents(c, p);
        return p;
    }

    private static void insertEvents(Connection c, Package p) throws SQLException {
        try (var event = c.prepareStatement(
                "INSERT INTO custody_events(package_id,seq,payload) VALUES(?,?,?) ON CONFLICT(package_id,seq) DO NOTHING")) {
            for (var e : p.events()) {
                var row = e.getAsJsonObject();
                event.setString(1, p.packageId());
                event.setLong(2, row.get("seq").getAsLong());
                event.setString(3, row.toString());
                event.executeUpdate();
            }
        }
    }

    /** Package payload without the event log; events are authoritative in custody_events. */
    private static String payloadOf(Package p) {
        var out = p.json();
        out.remove("events");
        return out.toString();
    }

    private static Package read(Connection c, String packageId) throws SQLException, IOException {
        JsonObject stored;
        try (var p = c.prepareStatement("SELECT payload FROM custody_packages WHERE package_id=?")) {
            p.setString(1, packageId);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                stored = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read package custody", invalid);
        }
        var events = new JsonArray();
        try (var p = c.prepareStatement("SELECT payload FROM custody_events WHERE package_id=? ORDER BY seq")) {
            p.setString(1, packageId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) events.add(JsonParser.parseString(rows.getString(1)));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read package custody", invalid);
        }
        try {
            return new Package(
                    stored.get("packageId").getAsString(),
                    stored.get("orderId").getAsString(),
                    str(stored, "manifest"),
                    str(stored, "jobId"),
                    str(stored, "destination"),
                    str(stored, "label"),
                    State.valueOf(stored.get("state").getAsString()),
                    str(stored, "agent"),
                    stored.has("location") ? stored.getAsJsonObject("location") : new JsonObject(),
                    stored.get("revision").getAsLong(),
                    events);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read package custody", invalid);
        }
    }

    private static String str(JsonObject o, String field) {
        var value = o.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
