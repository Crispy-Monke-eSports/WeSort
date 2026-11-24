package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time repair for split-brain server identities. Admission ({@link ServerRoutes#sameServer})
 * has always treated {@code s} and {@code s:25565} as the same server, but older builds keyed
 * order books, stock, leases, jobs and physical operations on the exact configured spelling — so
 * changing {@code minecraftServer} orphaned every existing row in a second, invisible partition.
 * Storage now keys on {@link ServerRoutes#storageKey}; this moves every leftover spelling of the
 * configured server into that single partition at startup. Idempotent and additive: running it on
 * an already-merged database changes nothing.
 */
final class ServerKeyMigration {
    private ServerKeyMigration() {}

    /** @return human-readable summary of what was merged, empty when nothing was split. */
    static String reconcile(StockLedger ledger, String configured) throws IOException {
        String target = ServerRoutes.storageKey(configured == null ? "" : configured);
        if (target.isEmpty()) return "";
        var moved = new LinkedHashMap<String, Long>();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            for (String table : new String[] {
                "order_books",
                "stock_sources",
                "stock_parts",
                "stock_members",
                "stock_indexes",
                "automation_leases",
                "physical_operations",
                "box_jobs",
                "box_uuids",
                "box_uuid_history",
                "order_event_log",
                "kit_jobs",
                "kit_templates",
                "kit_template_versions"
            }) {
                for (String from : distinctServers(c, table)) {
                    if (!from.equals(target) && ServerRoutes.storageKey(from).equals(target)) {
                        mergePartition(c, table, from, target, moved);
                    }
                }
            }
            tx.commit();
        } catch (SQLException failure) {
            throw new IOException("Cannot merge split server partitions", failure);
        }
        if (moved.isEmpty()) return "";
        var summary = new StringBuilder("merged server partitions into '" + target + "': ");
        moved.forEach((table, count) -> summary.append(table).append('=').append(count).append(' '));
        return summary.toString().trim();
    }

    private static List<String> distinctServers(Connection c, String table) throws SQLException {
        var out = new ArrayList<String>();
        try (var q = c.createStatement();
                var rows = q.executeQuery("SELECT DISTINCT server FROM " + table)) {
            while (rows.next()) out.add(rows.getString(1));
        }
        return out;
    }

    private static void mergePartition(Connection c, String table, String from, String to, Map<String, Long> moved)
            throws SQLException {
        long count =
                switch (table) {
                    case "order_books" -> mergeOrderBooks(c, from, to);
                    case "stock_members", "stock_indexes", "kit_templates", "kit_template_versions" ->
                            mergeKeepNewest(c, table, from, to);
                    case "automation_leases" -> {
                        // A lease for a dead spelling can never be renewed or finished; drop it.
                        try (var p = c.prepareStatement("DELETE FROM automation_leases WHERE server=?")) {
                            p.setString(1, from);
                            yield (long) p.executeUpdate();
                        }
                    }
                    case "stock_parts" -> {
                        // Same coordinates re-read under both spellings: the live partition wins
                        // outright; anything still real is rebuilt by the next rescan.
                        long dropped;
                        try (var p = c.prepareStatement(
                                "DELETE FROM stock_parts o WHERE o.server=? AND EXISTS (SELECT 1 FROM stock_parts n WHERE n.server=? AND n.dimension=o.dimension AND n.x=o.x AND n.y=o.y AND n.z=o.z AND n.source=o.source)")) {
                            p.setString(1, from);
                            p.setString(2, to);
                            dropped = p.executeUpdate();
                        }
                        try (var p = c.prepareStatement("UPDATE stock_parts SET server=? WHERE server=?")) {
                            p.setString(1, to);
                            p.setString(2, from);
                            yield dropped + p.executeUpdate();
                        }
                    }
                    default -> {
                        try (var p = c.prepareStatement("UPDATE " + table + " SET server=? WHERE server=?")) {
                            p.setString(1, to);
                            p.setString(2, from);
                            yield (long) p.executeUpdate();
                        }
                    }
                };
        // Rewrite payload server fields that still name the old spelling, so runtime lookups
        // keyed on the storage form keep matching (order books, journals, box and kit jobs).
        rewritePayloadServers(c, table, from, to);
        if (count > 0) moved.merge(table, count, Long::sum);
    }

    /**
     * Merge two order-book partitions stash by stash: union orders and archived by id (newer
     * updatedAt wins), union receipts, keep the highest revision. Both sides stay readable
     * until the merged row commits.
     */
    private static long mergeOrderBooks(Connection c, String from, String to) throws SQLException {
        long touched = 0;
        var stashes = new ArrayList<String>();
        try (var p = c.prepareStatement("SELECT DISTINCT stash FROM order_books WHERE server=? OR server=?")) {
            p.setString(1, from);
            p.setString(2, to);
            try (var rows = p.executeQuery()) {
                while (rows.next()) stashes.add(rows.getString(1));
            }
        }
        try (var del = c.prepareStatement("DELETE FROM order_books WHERE server=? AND stash=?");
                var upsert = c.prepareStatement(
                        "INSERT INTO order_books(server,stash,revision,payload) VALUES(?,?,?,?) "
                                + "ON CONFLICT(server,stash) DO UPDATE SET revision=excluded.revision,payload=excluded.payload")) {
            for (var stash : stashes) {
                // Re-read both sides for this stash so unrelated stashes never interfere.
                JsonObject kept = null;
                long revision = 0;
                try (var p = c.prepareStatement("SELECT server,revision,payload FROM order_books WHERE stash=?")) {
                    p.setString(1, stash);
                    try (var rows = p.executeQuery()) {
                        while (rows.next()) {
                            String server = rows.getString(1);
                            if (!server.equals(to) && !server.equals(from)) continue;
                            var payload = JsonParser.parseString(rows.getString(3)).getAsJsonObject();
                            if (kept == null) {
                                kept = payload;
                                revision = rows.getLong(2);
                            } else {
                                kept = mergeBook(kept, payload);
                                revision = Math.max(revision, rows.getLong(2)) + 1;
                            }
                        }
                    }
                }
                if (kept == null) continue;
                kept.addProperty("server", to);
                rewriteBookServers(kept, to);
                del.setString(1, from);
                del.setString(2, stash);
                del.executeUpdate();
                upsert.setString(1, to);
                upsert.setString(2, stash);
                upsert.setLong(3, revision);
                upsert.setString(4, kept.toString());
                upsert.executeUpdate();
                touched++;
            }
        }
        return touched;
    }

    private static void rewriteBookServers(JsonObject book, String server) {
        for (String key : new String[] {"orders", "archived"}) {
            if (!book.has(key) || !book.get(key).isJsonArray()) continue;
            for (var e : book.getAsJsonArray(key)) {
                if (e.isJsonObject()) e.getAsJsonObject().addProperty("server", server);
            }
        }
        if (book.has("receipts") && book.get("receipts").isJsonObject()) {
            for (var e : book.getAsJsonObject("receipts").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    var receipt = e.getValue().getAsJsonObject();
                    if (receipt.has("order") && receipt.get("order").isJsonObject()) {
                        receipt.getAsJsonObject("order").addProperty("server", server);
                    }
                }
            }
        }
    }

    private static JsonObject mergeBook(JsonObject into, JsonObject other) {
        mergeOrderArray(into, other, "orders");
        mergeOrderArray(into, other, "archived");
        if (other.has("receipts") && other.get("receipts").isJsonObject()) {
            if (!into.has("receipts") || !into.get("receipts").isJsonObject()) {
                into.add("receipts", new JsonObject());
            }
            var kept = into.getAsJsonObject("receipts");
            for (var e : other.getAsJsonObject("receipts").entrySet()) {
                kept.add(e.getKey(), e.getValue());
            }
        }
        return into;
    }

    private static void mergeOrderArray(JsonObject into, JsonObject other, String key) {
        if (!other.has(key) || !other.get(key).isJsonArray()) return;
        if (!into.has(key) || !into.get(key).isJsonArray()) into.add(key, new JsonArray());
        var kept = into.getAsJsonArray(key);
        var byId = new LinkedHashMap<String, JsonObject>();
        for (var e : kept) {
            var o = e.getAsJsonObject();
            byId.put(o.get("id").getAsString(), o);
        }
        for (var e : other.getAsJsonArray(key)) {
            var o = e.getAsJsonObject();
            var prev = byId.get(o.get("id").getAsString());
            if (prev == null || updatedAt(o) > updatedAt(prev)) byId.put(o.get("id").getAsString(), o);
        }
        var merged = new JsonArray();
        byId.values().forEach(merged::add);
        into.add(key, merged);
    }

    private static long updatedAt(JsonObject order) {
        return order.has("updatedAt") && order.get("updatedAt").isJsonPrimitive()
                ? order.get("updatedAt").getAsLong()
                : 0L;
    }

    /**
     * Tables whose primary key includes the server: where both spellings hold the same logical
     * row, keep the newer revision (live spelling wins ties) and drop the other; where only the
     * old spelling has it, re-key it. Applies to stock_members, stock_indexes, kit_templates and
     * kit_template_versions. Stock parts carry no revision: the live spelling wins outright and
     * the next rescan rebuilds anything still real.
     */
    private static long mergeKeepNewest(Connection c, String table, String from, String to) throws SQLException {
        return switch (table) {
            case "stock_members" -> mergeKeepNewest(c, table, "stash,source", from, to);
            case "stock_indexes" -> mergeKeepNewest(c, table, "stash", from, to);
            case "kit_templates" -> mergeKeepNewest(c, table, "name_key", from, to);
            case "kit_template_versions" -> mergeKeepNewest(c, table, "name_key,revision", from, to);
            default -> throw new IllegalArgumentException("no merge key for " + table);
        };
    }

    private static long mergeKeepNewest(Connection c, String table, String keyColumns, String from, String to)
            throws SQLException {
        long moved = 0;
        // Twins under both spellings: keep the newer payload, drop the other.
        try (var twins = c.prepareStatement(
                "SELECT o.rowid, o.revision, n.rowid FROM " + table + " o JOIN " + table + " n ON "
                        + joinOn("o", "n", keyColumns.split(","))
                        + " WHERE o.server=? AND n.server=?")) {
            twins.setString(1, from);
            twins.setString(2, to);
            var dropOld = new ArrayList<Long>();
            var dropNew = new ArrayList<Long>();
            try (var rows = twins.executeQuery()) {
                while (rows.next()) {
                    // Live spelling wins ties; only a strictly newer leftover replaces it.
                    if (rows.getLong(2) > rows.getLong(3)) dropNew.add(rows.getLong(3));
                    else dropOld.add(rows.getLong(1));
                }
            }
            try (var del = c.prepareStatement("DELETE FROM " + table + " WHERE rowid=?")) {
                for (long id : dropOld) {
                    del.setLong(1, id);
                    moved += del.executeUpdate();
                }
                for (long id : dropNew) {
                    del.setLong(1, id);
                    moved += del.executeUpdate();
                }
            }
        }
        // Keys only the old spelling has: re-key them.
        try (var p = c.prepareStatement("UPDATE " + table + " SET server=? WHERE server=?")) {
            p.setString(1, to);
            p.setString(2, from);
            moved += p.executeUpdate();
        }
        return moved;
    }

    private static String joinOn(String left, String right, String[] columns) {
        var joins = new ArrayList<String>();
        for (String column : columns) {
            // Column names come from a fixed allow-list at the call site, never from user input.
            joins.add(left + "." + column.trim() + "=" + right + "." + column.trim());
        }
        return String.join(" AND ", joins);
    }

    private static void rewritePayloadServers(Connection c, String table, String from, String to) throws SQLException {
        // Only tables whose runtime lookups read payload server fields need rewrites; the rest
        // are keyed by columns alone. Probe the schema instead of assuming the column exists.
        boolean hasPayload = false;
        try (var cols = c.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (cols.next()) {
                if (cols.getString(2).equals("payload")) {
                    hasPayload = true;
                    break;
                }
            }
        }
        if (!hasPayload) return;
        String idColumn = table.equals("order_books") ? null : "rowid";
        if (idColumn == null) return; // order_books payloads are rebuilt by mergeOrderBooks.
        try (var q = c.createStatement();
                var rows = q.executeQuery("SELECT rowid,payload FROM " + table + " WHERE server='" + to + "'")) {
            var updates = new ArrayList<long[]>();
            while (rows.next()) updates.add(new long[] {rows.getLong(1)});
            try (var get = c.prepareStatement("SELECT payload FROM " + table + " WHERE rowid=?");
                    var set = c.prepareStatement("UPDATE " + table + " SET payload=? WHERE rowid=?")) {
                for (long[] key : updates) {
                    get.setLong(1, key[0]);
                    try (var r = get.executeQuery()) {
                        if (!r.next()) continue;
                        JsonElement root;
                        try {
                            root = JsonParser.parseString(r.getString(1));
                        } catch (RuntimeException invalid) {
                            continue;
                        }
                        if (!(root instanceof JsonObject rootObject)) continue;
                        boolean changed = rewriteServerField(rootObject, from, to);
                        JsonElement context = rootObject.get("context");
                        if (context instanceof JsonObject contextObject) {
                            changed |= rewriteServerField(contextObject, from, to);
                        }
                        if (changed) {
                            set.setString(1, rootObject.toString());
                            set.setLong(2, key[0]);
                            set.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    private static boolean rewriteServerField(JsonObject o, String from, String to) {
        if (o.has("server") && o.get("server").isJsonPrimitive()
                && o.get("server").getAsString().equals(from)) {
            o.addProperty("server", to);
            return true;
        }
        return false;
    }
}
