package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.CanonicalJson;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Durable order snapshots and immutable order events, on the same guild database as physical observations. */
final class OrderBookRepository implements OrderRepository {
    record Snapshot(String server, String stash, long revision, JsonObject payload) {}

    private final StockLedger ledger;
    private final EventPublisher events;

    OrderBookRepository(StockLedger ledger) {
        this(ledger, EventPublisher.live());
    }

    OrderBookRepository(StockLedger ledger, EventPublisher events) {
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Restore order books from SQLite, in a single transaction. Validate every book.
     * There is no legacy file import (#11): {@code *.json} order files are never
     * read — authoritative orders live in {@code order_books}. Stray files on disk
     * are ignored (StockLedger warns loudly); only SQLite state is trusted.
     */
    public void load(Consumer<Snapshot> validate) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            boolean initialized;
            try (var q = c.createStatement();
                    var rows = q.executeQuery("SELECT id FROM order_storage_meta WHERE id=1")) {
                initialized = rows.next();
            }
            var snapshots = new ArrayList<Snapshot>();
            try (var q = c.createStatement();
                    var rows = q.executeQuery(
                            "SELECT server,stash,revision,payload FROM order_books ORDER BY server,stash")) {
                while (rows.next()) {
                    var snapshot = new Snapshot(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getLong(3),
                            JsonParser.parseString(rows.getString(4)).getAsJsonObject());
                    validateScope(snapshot);
                    snapshots.add(snapshot);
                }
            }
            if (!initialized) {
                if (!snapshots.isEmpty()) throw new IOException("Order books exist without their migration marker");
            }
            for (var snapshot : snapshots) {
                validate.accept(snapshot);
                saveResumeReceipts(c, snapshot);
            }
            if (!initialized) {
                for (var snapshot : snapshots) {
                    insert(c, snapshot);
                    appendEvents(c, snapshot);
                }
                try (var q = c.createStatement()) {
                    q.executeUpdate("INSERT INTO order_storage_meta(id) VALUES(1)");
                }
            }
            tx.commit();
        } catch (SQLException | RuntimeException failure) {
            throw new IOException("Cannot restore durable order books", failure);
        }
    }

    public long commit(String server, String stash, long expected, JsonObject root, StockLedger.BoxRelease release)
            throws IOException {
        var next = new Snapshot(server, stash, Math.addExact(expected, 1), root);
        long revision;
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            validateScope(next);
            JsonObject prevRoot = null;
            if (expected == 0) {
                insert(c, next);
            } else {
                try (var q = c.prepareStatement("SELECT payload FROM order_books WHERE server=? AND stash=?")) {
                    q.setString(1, server);
                    q.setString(2, stash);
                    try (var rows = q.executeQuery()) {
                        if (rows.next()) prevRoot = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                    }
                }
                try (var p = c.prepareStatement(
                        "UPDATE order_books SET revision=?,payload=? WHERE server=? AND stash=? AND revision=?")) {
                    p.setLong(1, next.revision());
                    p.setString(2, root.toString());
                    p.setString(3, server);
                    p.setString(4, stash);
                    p.setLong(5, expected);
                    if (p.executeUpdate() != 1) throw new IOException("Order book revision changed");
                }
            }
            if (release != null) ledger.releaseOrderBoxes(c, server, release);
            appendEvents(c, next);
            saveResumeReceipts(c, next);
            // PR3 (#265): every book commit proves a non-empty, revision-linked
            // changeset. Storage still writes the full snapshot until the diff
            // cutover lands; the contract is enforced now so the cutover is safe.
            var changesets = OrderChangeSet.forBook(prevRoot, root, expected);
            if (changesets.isEmpty() && expected > 0)
                throw new IOException("Order book commit carries no order, event or receipt change");
            tx.commit();
            revision = next.revision();
        } catch (SQLException | RuntimeException failure) {
            throw new IOException("Cannot commit order book and audit history", failure);
        }
        // Publish only after the single transaction commits (#140/#142):
        // rollback publishes nothing.
        events.afterCommit("orders", revision);
        return revision;
    }

    /** Terminal checkpoint dispositions have a different lifetime from the bounded UI archive. */
    private static void saveResumeReceipts(Connection c, Snapshot snapshot) throws SQLException {
        try (var save = c.prepareStatement(
                        "INSERT INTO order_resume_receipts(order_id,server,stash,agent,payload) VALUES(?,?,?,?,?) "
                                + "ON CONFLICT(order_id) DO UPDATE SET payload=excluded.payload "
                                + "WHERE server=excluded.server AND stash=excluded.stash AND agent=excluded.agent");
                var remove = c.prepareStatement("DELETE FROM order_resume_receipts WHERE order_id=?")) {
            for (String key : List.of("orders", "archived")) {
                if (!snapshot.payload().has(key)) continue;
                for (var element : snapshot.payload().getAsJsonArray(key)) {
                    var order = element.getAsJsonObject();
                    String id = order.get("id").getAsString();
                    if (!order.has("agent")
                            || order.get("agent").isJsonNull()
                            || !OrderStore.FINISHED.contains(order.get("status").getAsString())) {
                        remove.setString(1, id);
                        remove.executeUpdate();
                        continue;
                    }
                    save.setString(1, id);
                    save.setString(2, snapshot.server());
                    save.setString(3, snapshot.stash());
                    save.setString(4, order.get("agent").getAsString());
                    save.setString(5, order.toString());
                    if (save.executeUpdate() != 1) throw new SQLException("Order resume receipt scope changed");
                }
            }
        }
    }

    public JsonObject resumeReceipt(String server, String stash, String agent, String id) throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT payload FROM order_resume_receipts WHERE order_id=? AND server=? AND stash=? AND agent=?")) {
            p.setString(1, id);
            p.setString(2, server);
            p.setString(3, stash);
            p.setString(4, agent);
            try (var rows = p.executeQuery()) {
                return rows.next() ? JsonParser.parseString(rows.getString(1)).getAsJsonObject() : null;
            }
        } catch (SQLException | RuntimeException failure) {
            throw new IOException("Cannot restore order resume receipt", failure);
        }
    }

    private static void validateScope(Snapshot snapshot) {
        if (snapshot.revision() < 1
                || !snapshot.server().equals(snapshot.payload().get("server").getAsString())
                || !snapshot.stash().equals(snapshot.payload().get("stash").getAsString()))
            throw new IllegalArgumentException("Invalid order book scope");
    }

    private static void insert(Connection c, Snapshot row) throws SQLException {
        try (var p = c.prepareStatement("INSERT INTO order_books(server,stash,revision,payload) VALUES(?,?,?,?)")) {
            p.setString(1, row.server());
            p.setString(2, row.stash());
            p.setLong(3, row.revision());
            p.setString(4, row.payload().toString());
            p.executeUpdate();
        }
    }

    /** No history is erased when old orders leave the bounded operational archive. */
    private static void appendEvents(Connection c, Snapshot snapshot) throws SQLException {
        try (var insert = c.prepareStatement(
                        "INSERT INTO order_event_log(order_id,seq,server,stash,payload) VALUES(?,?,?,?,?) ON CONFLICT(order_id,seq) DO NOTHING");
                var existing = c.prepareStatement(
                        "SELECT server,stash,payload FROM order_event_log WHERE order_id=? AND seq=?")) {
            for (String key : List.of("orders", "archived")) {
                if (!snapshot.payload().has(key)) continue;
                for (var element : snapshot.payload().getAsJsonArray(key)) {
                    var order = element.getAsJsonObject();
                    if (!order.has("history")) continue;
                    String id = order.get("id").getAsString();
                    int seq = 0;
                    for (var event : order.getAsJsonArray("history")) {
                        String payload = CanonicalJson.stringify(event);
                        insert.setString(1, id);
                        insert.setInt(2, ++seq);
                        insert.setString(3, snapshot.server());
                        insert.setString(4, snapshot.stash());
                        insert.setString(5, payload);
                        if (insert.executeUpdate() == 1) continue;
                        existing.setString(1, id);
                        existing.setInt(2, seq);
                        try (var rows = existing.executeQuery()) {
                            if (!rows.next()
                                    || !snapshot.server().equals(rows.getString(1))
                                    || !snapshot.stash().equals(rows.getString(2))
                                    || !payload.equals(rows.getString(3)))
                                throw new SQLException("Order audit history cannot be rewritten");
                        }
                    }
                }
            }
        }
    }
}
