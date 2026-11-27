package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;

/** Directly inspect or corrupt a persisted book in recovery tests, never in production. */
final class OrderBookFixtures {
    private OrderBookFixtures() {}

    static JsonObject read(IndexStore index, String server, String stash) throws Exception {
        try (var c = index.stock().open()) {
            var root = new JsonObject();
            root.addProperty("server", server);
            root.addProperty("stash", stash);
            for (String location : List.of("orders", "archived")) {
                var array = new com.google.gson.JsonArray();
                try (var q = c.prepareStatement(
                        "SELECT payload FROM order_rows WHERE server=? AND stash=? AND location=? ORDER BY seq")) {
                    q.setString(1, server);
                    q.setString(2, stash);
                    q.setString(3, location);
                    try (var rows = q.executeQuery()) {
                        while (rows.next())
                            array.add(JsonParser.parseString(rows.getString(1)).getAsJsonObject());
                    }
                }
                if (!array.isEmpty() || location.equals("orders")) root.add(location, array);
            }
            try (var q = c.prepareStatement(
                    "SELECT receipt_key,payload FROM order_receipts WHERE server=? AND stash=?")) {
                q.setString(1, server);
                q.setString(2, stash);
                try (var rows = q.executeQuery()) {
                    JsonObject receipts = null;
                    while (rows.next()) {
                        if (receipts == null) receipts = new JsonObject();
                        receipts.add(rows.getString(1), JsonParser.parseString(rows.getString(2)));
                    }
                    if (receipts != null) root.add("receipts", receipts);
                }
            }
            try (var q = c.prepareStatement("SELECT revision FROM order_books WHERE server=? AND stash=?")) {
                q.setString(1, server);
                q.setString(2, stash);
                try (var rows = q.executeQuery()) {
                    if (!rows.next()) throw new AssertionError("Missing order book");
                }
            }
            return root;
        }
    }

    static void replace(IndexStore index, JsonObject root) throws Exception {
        try (var c = index.stock().open()) {
            String server = root.get("server").getAsString();
            String stash = root.get("stash").getAsString();
            try (var del = c.prepareStatement("DELETE FROM order_rows WHERE server=? AND stash=?")) {
                del.setString(1, server);
                del.setString(2, stash);
                del.executeUpdate();
            }
            int seq = 0;
            for (String location : List.of("orders", "archived")) {
                if (!root.has(location) || !root.get(location).isJsonArray()) continue;
                for (var element : root.getAsJsonArray(location)) {
                    var order = element.getAsJsonObject();
                    try (var p = c.prepareStatement(
                            "INSERT INTO order_rows(order_id,server,stash,location,payload,seq,revision) VALUES(?,?,?,?,?,?,?)")) {
                        p.setString(1, order.get("id").getAsString());
                        p.setString(2, server);
                        p.setString(3, stash);
                        p.setString(4, location);
                        p.setString(5, order.toString());
                        p.setInt(6, seq++);
                        p.setLong(7, currentRevision(c, server, stash));
                        p.executeUpdate();
                    }
                }
                seq = 0;
            }
            try (var del = c.prepareStatement("DELETE FROM order_receipts WHERE server=? AND stash=?")) {
                del.setString(1, server);
                del.setString(2, stash);
                del.executeUpdate();
            }
            if (root.has("receipts") && root.get("receipts").isJsonObject()) {
                for (var entry : root.getAsJsonObject("receipts").entrySet()) {
                    try (var p = c.prepareStatement(
                            "INSERT INTO order_receipts(receipt_key,server,stash,payload) VALUES(?,?,?,?)")) {
                        p.setString(1, entry.getKey());
                        p.setString(2, server);
                        p.setString(3, stash);
                        p.setString(4, entry.getValue().toString());
                        p.executeUpdate();
                    }
                }
            }
        }
    }

    private static long currentRevision(java.sql.Connection c, String server, String stash) throws Exception {
        try (var q = c.prepareStatement("SELECT revision FROM order_books WHERE server=? AND stash=?")) {
            q.setString(1, server);
            q.setString(2, stash);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Missing order book");
                return rows.getLong(1);
            }
        }
    }
}
