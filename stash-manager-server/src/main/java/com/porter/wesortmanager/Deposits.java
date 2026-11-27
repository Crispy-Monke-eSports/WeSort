package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Deposit;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.LongSupplier;

/**
 * W-038: the physical deposition ledger. A package is deposited either as a {@code DELIVERY} to a recipient outside
 * the stock (a withdrawal) or a {@code TRANSFER} to an internal guild stash (a relocation, no change to the guild
 * total) — the kind is explicit and never inferred. {@code DELIVERED} is reached only after a server-confirmed,
 * non-empty chest content snapshot, so a double report or reconnect can never create a double delivery: replaying a
 * confirmation of an already delivered package returns the same record. An uncertain outcome goes to
 * {@code NEEDS_RECONCILIATION}. Every change is an append-only event.
 *
 * <p>PR3 (#140): authoritative state lives in the shared guild SQLite database
 * ({@code deposit_packages}/{@code deposit_events}), so deposit rows commit or
 * roll back in the same transaction as the order book and the audit trail.
 * Legacy {@code deposits.json} files are never read; {@link StockLedger} warns
 * loudly when one is present.
 *
 * <p>Global lock order for nested same-transaction commits: ledger → index →
 * book → store. Public methods take the store monitor and a fresh ledger
 * transaction; {@code Connection}-scoped overloads assume the caller already
 * holds the locks in that order and never commit on their own.
 */
final class Deposits {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    private final StockLedger ledger;
    private final Clock clock;

    Deposits(StockLedger ledger, Clock clock) {
        if (ledger == null) throw new IllegalArgumentException("Deposits require the guild database");
        if (clock == null) throw new IllegalArgumentException("Deposits require a clock");
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    Deposits(Path data) throws IOException {
        this(data, System::currentTimeMillis);
    }

    /** Legacy data-dir constructor: shares the same guild.sqlite; the JSON file itself is never read. */
    Deposits(Path data, LongSupplier clock) throws IOException {
        this(new StockLedger(data), adapt(clock));
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

    private record Entry(Deposit deposit, JsonArray events) {}

    /** Begins a deposit; idempotent for the same package + order + kind + destination. */
    synchronized Deposit begin(String packageId, String orderId, Deposit.Kind kind, String destination, String agent)
            throws IOException {
        return begin(packageId, orderId, kind, destination, agent, null);
    }

    /** Begins a deposit, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Deposit begin(
            String packageId, String orderId, Deposit.Kind kind, String destination, String agent, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Deposit begun = begin(c, packageId, orderId, kind, destination, agent);
            if (trail != null) {
                trail.say("deposit.begin", packageId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return begun;
        } catch (SQLException failure) {
            throw new IOException("Cannot begin deposit", failure);
        }
    }

    /**
     * Same-transaction begin: writes on the caller's connection without
     * committing, so an order commit can carry deposits and audit atomically.
     */
    Deposit begin(Connection c, String packageId, String orderId, Deposit.Kind kind, String destination, String agent)
            throws SQLException, IOException {
        Entry existing = read(c, packageId);
        if (existing != null) {
            var d = existing.deposit();
            if (!d.orderId().equals(orderId)
                    || d.kind() != kind
                    || !d.destination().equals(destination))
                throw new Conflict("Package already has a different deposit");
            return d;
        }
        var d = Deposit.begin(packageId, orderId, kind, destination, agent);
        var events = new JsonArray();
        events.add(event("DEPOSITING", agent, null));
        insert(c, new Entry(d, events));
        return d;
    }

    /** Confirms a deposit: only a server-confirmed, non-empty content snapshot of the deposit's own destination reaches DELIVERED. Replay is a no-op. */
    synchronized Deposit confirm(String packageId, String agent, JsonObject snapshot) throws IOException {
        return confirm(packageId, agent, snapshot, null);
    }

    /** Confirms a deposit, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Deposit confirm(String packageId, String agent, JsonObject snapshot, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Deposit confirmed = confirm(c, packageId, agent, snapshot);
            if (trail != null) {
                trail.say("deposit.confirm", packageId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return confirmed;
        } catch (SQLException failure) {
            throw new IOException("Cannot confirm deposit", failure);
        }
    }

    /**
     * Same-transaction confirm: writes on the caller's connection without
     * committing, so an order commit can carry deposits and audit atomically.
     */
    Deposit confirm(Connection c, String packageId, String agent, JsonObject snapshot)
            throws SQLException, IOException {
        Entry entry = require(c, packageId);
        var d = entry.deposit();
        if (d.state() == Deposit.State.DELIVERED) return d; // idempotent: no double delivery
        if (snapshot == null
                || !snapshot.has("chests")
                || !snapshot.get("chests").isJsonArray()
                || snapshot.getAsJsonArray("chests").isEmpty())
            throw new Conflict("A confirmed, non-empty chest content snapshot is required before DELIVERED");
        String snapshotDestination =
                snapshot.has("destination") && snapshot.get("destination").isJsonPrimitive()
                        ? snapshot.get("destination").getAsString()
                        : null;
        if (snapshotDestination == null || !snapshotDestination.equals(d.destination()))
            throw new Conflict("The snapshot must be from the deposit's own destination");
        var confirmed = advance(d, Deposit.State.DELIVERED, agent);
        var events = entry.events().deepCopy();
        events.add(event("DELIVERED", agent, snapshot));
        update(c, new Entry(confirmed, events));
        return confirmed;
    }

    /** Marks an uncertain outcome for operator reconciliation. */
    synchronized Deposit reconcile(String packageId, String agent, String reason) throws IOException {
        return reconcile(packageId, agent, reason, null);
    }

    /** Reconciles a deposit, writing staged audit entries in the same transaction when a trail is given. */
    synchronized Deposit reconcile(String packageId, String agent, String reason, AuditLog.Batch trail)
            throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            Deposit reconciled = reconcile(c, packageId, agent, reason);
            if (trail != null) {
                trail.say("deposit.reconcile", packageId);
                AuditLog.addAll(c, trail.entries());
            }
            tx.commit();
            return reconciled;
        } catch (SQLException failure) {
            throw new IOException("Cannot reconcile deposit", failure);
        }
    }

    /**
     * Same-transaction reconcile: writes on the caller's connection without
     * committing, so an order commit can carry deposits and audit atomically.
     */
    Deposit reconcile(Connection c, String packageId, String agent, String reason) throws SQLException, IOException {
        Entry entry = require(c, packageId);
        var d = entry.deposit();
        if (d.state() == Deposit.State.NEEDS_RECONCILIATION) return d;
        var reconciled = advance(d, Deposit.State.NEEDS_RECONCILIATION, agent);
        var events = entry.events().deepCopy();
        var ev = event("NEEDS_RECONCILIATION", agent, null);
        ev.addProperty("reason", reason == null ? "" : reason);
        events.add(ev);
        update(c, new Entry(reconciled, events));
        return reconciled;
    }

    synchronized Deposit get(String packageId) throws IOException {
        try (var c = ledger.open()) {
            Entry entry = read(c, packageId);
            return entry == null ? null : entry.deposit();
        } catch (SQLException failure) {
            throw new IOException("Cannot read deposits", failure);
        }
    }

    synchronized JsonArray list(String orderId) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open();
                var p = orderId == null
                        ? c.prepareStatement("SELECT package_id FROM deposit_packages ORDER BY package_id")
                        : c.prepareStatement(
                                "SELECT package_id FROM deposit_packages WHERE order_id=? ORDER BY package_id")) {
            if (orderId != null) p.setString(1, orderId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    Entry entry = read(c, rows.getString(1));
                    if (entry == null) continue;
                    var o = entry.deposit().json();
                    o.add("events", entry.events().deepCopy());
                    out.add(o);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot list deposits", failure);
        }
        return out;
    }

    private static Deposit advance(Deposit d, Deposit.State next, String agent) throws Conflict {
        try {
            return d.advance(next, agent);
        } catch (IllegalArgumentException e) {
            throw new Conflict(e.getMessage());
        }
    }

    private Entry require(Connection c, String packageId) throws SQLException, IOException, Conflict {
        Entry entry = read(c, packageId);
        if (entry == null) throw new Conflict("Unknown package");
        return entry;
    }

    private JsonObject event(String name, String agent, JsonObject snapshot) {
        var o = new JsonObject();
        o.addProperty("at", clock.millis());
        o.addProperty("event", name);
        o.addProperty("agent", agent);
        if (snapshot != null) o.add("snapshot", snapshot.deepCopy());
        return o;
    }

    /** Inserts a new deposit row plus its first event; fails closed on a concurrent insert. */
    private static void insert(Connection c, Entry entry) throws SQLException {
        var d = entry.deposit();
        try (var row = c.prepareStatement(
                "INSERT INTO deposit_packages(package_id,order_id,kind,state,agent,destination,payload,revision) VALUES(?,?,?,?,?,?,?,?)")) {
            row.setString(1, d.packageId());
            row.setString(2, d.orderId());
            row.setString(3, d.kind().name());
            row.setString(4, d.state().name());
            row.setString(5, d.agent());
            row.setString(6, d.destination());
            row.setString(7, d.json().toString());
            row.setLong(8, d.revision());
            row.executeUpdate();
        }
        insertEvents(c, d.packageId(), entry.events());
    }

    /** Conditional update: a concurrent advance on another connection wins instead of silently overwriting. */
    private static void update(Connection c, Entry entry) throws SQLException, Conflict {
        var d = entry.deposit();
        try (var row = c.prepareStatement(
                "UPDATE deposit_packages SET state=?,agent=?,payload=?,revision=? WHERE package_id=? AND revision=?")) {
            row.setString(1, d.state().name());
            row.setString(2, d.agent());
            row.setString(3, d.json().toString());
            row.setLong(4, d.revision());
            row.setString(5, d.packageId());
            row.setLong(6, d.revision() - 1);
            if (row.executeUpdate() != 1) throw new Conflict("Deposit changed concurrently; reread the package");
        }
        insertEvents(c, d.packageId(), entry.events());
    }

    private static void insertEvents(Connection c, String packageId, JsonArray events) throws SQLException {
        try (var event = c.prepareStatement(
                "INSERT INTO deposit_events(package_id,seq,payload) VALUES(?,?,?) ON CONFLICT(package_id,seq) DO NOTHING")) {
            long seq = 0;
            for (var e : events) {
                event.setString(1, packageId);
                event.setLong(2, ++seq);
                event.setString(3, e.toString());
                event.executeUpdate();
            }
        }
    }

    private static Entry read(Connection c, String packageId) throws SQLException, IOException {
        JsonObject stored;
        try (var p = c.prepareStatement("SELECT payload FROM deposit_packages WHERE package_id=?")) {
            p.setString(1, packageId);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                stored = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read deposits", invalid);
        }
        var events = new JsonArray();
        try (var p = c.prepareStatement("SELECT payload FROM deposit_events WHERE package_id=? ORDER BY seq")) {
            p.setString(1, packageId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) events.add(JsonParser.parseString(rows.getString(1)));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read deposits", invalid);
        }
        try {
            return new Entry(Deposit.read(stored), events);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read deposits", invalid);
        }
    }
}
