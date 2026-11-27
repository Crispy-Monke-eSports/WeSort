package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * PR3 (#140): transactional audit trail in SQLite.
 *
 * <p>Business-critical entries are written on the caller's connection so they
 * commit or roll back with the order/stock/custody transaction they describe;
 * login/security entries use the own-transaction {@link #add} path. A corrupt
 * audit store fails closed instead of presenting an empty history, and a
 * failed write never publishes an in-memory-only event.
 */
final class AuditLog {
    static final int MAX = 2000;
    static final int MAX_DETAIL = 300;

    /** One audit entry; stamped with the writer's clock at persistence time. */
    record Entry(String userId, String userName, String action, String detail, String address, long at) {
        Entry(String userId, String userName, String action, String detail, String address) {
            this(userId, userName, action, detail, address, 0L);
        }
    }

    private final StockLedger ledger;
    private final Path legacyFile;
    private final LongSupplier clock;

    AuditLog(StockLedger ledger, Path dataDir) {
        this(ledger, dataDir, System::currentTimeMillis);
    }

    AuditLog(StockLedger ledger, LongSupplier clock) {
        this(ledger, null, clock);
    }

    AuditLog(StockLedger ledger, Path dataDir, LongSupplier clock) {
        if (ledger == null) throw new IllegalArgumentException("AuditLog requires the guild database");
        this.ledger = ledger;
        this.legacyFile = dataDir == null ? null : dataDir.resolve("audit.json");
        this.clock = clock;
    }

    /**
     * Fail-closed integrity check plus one-time import of the pre-SQLite
     * {@code audit.json} file. The file is renamed to {@code *.imported} after
     * a successful import so its evidence is preserved but never re-imported.
     */
    synchronized void load() {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            try (var q = c.createStatement();
                    var rows = q.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
                    throw new IllegalStateException("Guild database failed integrity check");
            }
            try (var q = c.createStatement();
                    var rows = q.executeQuery("SELECT 1 FROM audit_log LIMIT 0")) {
                // Table present: a missing audit trail is never an empty history.
            } catch (SQLException missing) {
                throw new IllegalStateException("Guild database is missing the authoritative audit log", missing);
            }
            importLegacyOnce(c);
            tx.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot verify audit history", e);
        }
    }

    /** Imports pre-SQLite audit.json once (same format, same version): no silent loss on upgrade. */
    private void importLegacyOnce(Connection c) throws SQLException {
        if (legacyFile == null || !Files.exists(legacyFile) || Files.isSymbolicLink(legacyFile)) return;
        try (var q = c.prepareStatement("SELECT COUNT(*) FROM audit_log");
                var rows = q.executeQuery()) {
            if (rows.next() && rows.getLong(1) > 0) return;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot verify audit history", e);
        }
        try {
            var list = com.google.gson.JsonParser.parseString(Files.readString(legacyFile, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonArray("entries");
            if (list == null) throw new IllegalArgumentException("Missing audit entries");
            try (var check = c.prepareStatement("SELECT COUNT(*) FROM audit_log")) {
                for (var e : list) {
                    if (!e.isJsonObject()) throw new IllegalArgumentException("Invalid audit entry");
                    var item = e.getAsJsonObject();
                    if (!item.has("at") || !item.get("at").isJsonPrimitive() || !item.getAsJsonPrimitive("at").isNumber()
                            || !item.has("action") || !item.get("action").isJsonPrimitive()
                            || !item.getAsJsonPrimitive("action").isString())
                        throw new IllegalArgumentException("Audit entry missing timestamp or action");
                    insert(
                            c,
                            item.get("at").getAsLong(),
                            item.has("userId") && !item.get("userId").isJsonNull()
                                    ? item.get("userId").getAsString()
                                    : null,
                            item.has("user") && !item.get("user").isJsonNull()
                                    ? item.get("user").getAsString()
                                    : null,
                            item.get("action").getAsString(),
                            item.has("detail") && !item.get("detail").isJsonNull()
                                    ? item.get("detail").getAsString()
                                    : null,
                            item.has("address") && !item.get("address").isJsonNull()
                                    ? item.get("address").getAsString()
                                    : null);
                }
            }
            Files.move(legacyFile, legacyFile.resolveSibling("audit.json.imported"));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot load audit.json; refusing to discard audit history", e);
        }
    }

    /** Own-transaction write for login/security events outside any business transaction. */
    void add(Users.Principal who, String action, String detail, String address) {
        add(who == null ? null : who.id(), who == null ? null : who.name(), action, detail, address);
    }

    synchronized void add(String userId, String userName, String action, String detail, String address) {
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            insert(c, clock.getAsLong(), userId, userName, action, detail, address);
            tx.commit();
        } catch (SQLException | RuntimeException e) {
            throw new UncheckedIOException("Cannot persist audit entry", new IOException(e));
        }
    }

    /** Same-transaction write: commits or rolls back with the surrounding business transaction. */
    static void add(Connection c, long at, String userId, String userName, String action, String detail, String address)
            throws SQLException {
        insert(c, at == 0L ? System.currentTimeMillis() : at, userId, userName, action, detail, address);
    }

    /** Writes staged batch entries; zero or negative stamps fall back to the wall clock. */
    static void addAll(Connection c, List<Entry> entries) throws SQLException {
        for (Entry e : entries) add(c, e.at(), e.userId(), e.userName(), e.action(), e.detail(), e.address());
    }

    private static void insert(
            Connection c, long at, String userId, String userName, String action, String detail, String address)
            throws SQLException {
        if (action == null || action.isEmpty()) throw new IllegalArgumentException("Audit action required");
        try (var p = c.prepareStatement(
                "INSERT INTO audit_log(at,user_id,user_name,action,detail,address) VALUES(?,?,?,?,?,?)")) {
            p.setLong(1, at);
            p.setString(2, userId);
            p.setString(3, userName);
            p.setString(4, action);
            p.setString(5, detail == null ? null : Http.truncate(clean(detail), MAX_DETAIL));
            p.setString(6, address);
            p.executeUpdate();
        }
        try (var trim = c.prepareStatement(
                "DELETE FROM audit_log WHERE seq NOT IN (SELECT seq FROM audit_log ORDER BY seq DESC LIMIT ?)")) {
            trim.setInt(1, MAX);
            trim.executeUpdate();
        }
    }

    /** Control characters become spaces, so an entry is always one line. */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> sb.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return sb.toString();
    }

    /** The newest entries first, at most limit. */
    synchronized JsonArray newest(int limit) {
        var out = new JsonArray();
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT at,user_id,user_name,action,detail,address FROM audit_log ORDER BY seq DESC LIMIT ?")) {
            p.setInt(1, Math.max(0, limit));
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var o = new JsonObject();
                    o.addProperty("at", rows.getLong(1));
                    o.addProperty("userId", rows.getString(2));
                    o.addProperty("user", rows.getString(3));
                    o.addProperty("action", rows.getString(4));
                    o.addProperty("detail", rows.getString(5));
                    o.addProperty("address", rows.getString(6));
                    out.add(o);
                }
            }
        } catch (SQLException e) {
            throw new UncheckedIOException("Cannot read audit history", new IOException(e));
        }
        return out;
    }

    synchronized int size() {
        try (var c = ledger.open();
                var q = c.createStatement();
                var rows = q.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (SQLException e) {
            throw new UncheckedIOException("Cannot read audit history", new IOException(e));
        }
    }

    /** Collects entries for one business operation; the store writes them in its transaction. */
    final class Batch {
        private final List<Entry> entries = new ArrayList<>();
        private String userId;
        private String userName;
        private String address;
        private final LongSupplier clock;

        Batch(LongSupplier clock) {
            this.clock = clock;
        }

        /** Who performs the operation; entries said afterwards carry this actor. */
        void actor(String userId, String userName, String address) {
            this.userId = userId;
            this.userName = userName;
            this.address = address;
        }

        /** Stages an entry, stamped now; persisted only if the surrounding commit succeeds. */
        void say(String action, String detail) {
            entries.add(new Entry(userId, userName, action, detail, address, clock.getAsLong()));
        }

        List<Entry> entries() {
            return List.copyOf(entries);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    Batch batch() {
        return new Batch(clock);
    }
}
