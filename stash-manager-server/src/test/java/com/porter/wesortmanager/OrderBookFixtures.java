package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Directly inspect or corrupt a persisted book in recovery tests, never in production. */
final class OrderBookFixtures {
    private OrderBookFixtures() {}

    static JsonObject read(IndexStore index, String server, String stash) throws Exception {
        try (var c = index.stock().open();
                var p = c.prepareStatement("SELECT payload FROM order_books WHERE server=? AND stash=?")) {
            p.setString(1, server);
            p.setString(2, stash);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new AssertionError("Missing order book");
                return JsonParser.parseString(rows.getString(1)).getAsJsonObject();
            }
        }
    }

    static void replace(IndexStore index, JsonObject root) throws Exception {
        try (var c = index.stock().open();
                var p = c.prepareStatement("UPDATE order_books SET payload=? WHERE server=? AND stash=?")) {
            p.setString(1, root.toString());
            p.setString(2, root.get("server").getAsString());
            p.setString(3, root.get("stash").getAsString());
            if (p.executeUpdate() != 1) throw new AssertionError("Missing order book");
        }
    }
}
