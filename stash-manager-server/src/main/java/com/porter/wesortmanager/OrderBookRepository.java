package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.CanonicalJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Durable order snapshots and immutable order events, on the same guild database as physical observations. */
final class OrderBookRepository {
    record Snapshot(String server, String stash, long revision, JsonObject payload) {}

    private final StockLedger ledger;

    OrderBookRepository(StockLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * Import old WeSort books once, in a single transaction. Validate every book before the marker
     * is committed. Afterward the legacy files are never read or written, even if left on disk.
     */
    void load(Path legacy, Consumer<Snapshot> validate) throws IOException {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            boolean initialized;
            try (var q = c.createStatement();
                    var rows = q.executeQuery("SELECT id FROM order_storage_meta WHERE id=1")) {
                initialized = rows.next();
            }
            var snapshots = new ArrayList<Snapshot>();
            try (var q = c.createStatement();
                    var rows = q.executeQuery("SELECT server,stash,revision,payload FROM order_books ORDER BY server,stash")) {
                while (rows.next()) {
                    var snapshot = new Snapshot(
                            rows.getString(1), rows.getString(2), rows.getLong(3),
                            JsonParser.parseString(rows.getString(4)).getAsJsonObject());
                    validateScope(snapshot);
                    snapshots.add(snapshot);
                }
            }
            if (!initialized) {
                if (!snapshots.isEmpty()) throw new IOException("Order books exist without their migration marker");
                if (Files.isSymbolicLink(legacy)) throw new IOException("Invalid legacy order directory");
                if (Files.exists(legacy)) {
                    try (var files = Files.newDirectoryStream(legacy, "*.json")) {
                        for (Path file : files) {
                            if (Files.isSymbolicLink(file)) throw new IOException("Invalid legacy order file");
                            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                            var snapshot = new Snapshot(
                                    root.get("server").getAsString(), root.get("stash").getAsString(), 1, root);
                            validateScope(snapshot);
                            snapshots.add(snapshot);
                        }
                    }
                }
            }
            for (var snapshot : snapshots) validate.accept(snapshot);
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

    long commit(String server, String stash, long expected, JsonObject root) throws IOException {
        var next = new Snapshot(server, stash, Math.addExact(expected, 1), root);
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            validateScope(next);
            if (expected == 0) {
                insert(c, next);
            } else {
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
            appendEvents(c, next);
            tx.commit();
            return next.revision();
        } catch (SQLException | RuntimeException failure) {
            throw new IOException("Cannot commit order book and audit history", failure);
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
