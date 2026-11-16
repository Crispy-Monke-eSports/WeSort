package com.porter.wesortmanager;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;

/** Source permissions are applied before either guild or stash aggregation. */
final class StockRoutes {
    private final IndexStore index;
    private final OrderStore orders;

    StockRoutes(IndexStore index, OrderStore orders) { this.index = index; this.orders = orders; }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/stock", Router.Guard.SESSION, Router.Perm.VIEW, this::get);
    }

    private void get(HttpExchange ex) throws IOException { Http.json(ex, 200, view(Router.user(ex))); }

    JsonObject view(Users.Principal who) throws IOException {
        synchronized (index) { return viewWithObservationLock(who); }
    }

    private JsonObject viewWithObservationLock(Users.Principal who) throws IOException {
        var query = index.stock();
        var sources = query.sources().stream().filter(s -> who.perms().seesStash(s.server(), s.stash())
            && !s.status().equals("SUPERSEDED")).toList();
        Map<String, JsonObject> items = new TreeMap<>(), details = new LinkedHashMap<>();
        Map<IndexStore.StashKey, Map<String, JsonObject>> stashItems = new LinkedHashMap<>();
        Map<String, Long> reserved = new HashMap<>();
        Map<IndexStore.StashKey, Map<String, Long>> stashReserved = new HashMap<>();
        for (var source : sources) {
            var book = new IndexStore.StashKey(source.server(), source.stash());
            if (!stashReserved.containsKey(book)) {
                var holds = orders.reservedFor(source.server(), source.stash());
                stashReserved.put(book, holds);
                holds.forEach((key, amount) -> reserved.merge(source.server() + "\u0000" + key, amount, Math::addExact));
            }
            addItems(stashItems.computeIfAbsent(book, key -> new TreeMap<>()), source, who);
            String id = source.value().get("id").getAsString();
            if (details.containsKey(id)) {
                if (!Redact.forced(who)) details.get(id).getAsJsonArray("stashes").add(source.stash());
                continue;
            }
            addItems(items, source, who);
            var detail = source.value().deepCopy();
            detail.addProperty("server", source.server()); detail.addProperty("serverLabel", serverLabel(source.server(), who));
            detail.addProperty("stash", source.stash());
            JsonArray memberships = new JsonArray(); memberships.add(source.stash()); detail.add("stashes", memberships);
            detail.addProperty("sourceRevision", source.revision()); detail.addProperty("verifiedAt", source.verifiedAt());
            detail.addProperty("state", source.shared() && source.status().equals("FRESH") ? "SHARED_SOURCE" : source.status());
            detail.remove("epoch"); detail.remove("session"); detail.remove("sequence"); detail.remove("baseRevision");
            // Existing holds have no physical allocation yet. Never invent an exact count per container.
            detail.add("reserved", JsonNull.INSTANCE); detail.add("available", JsonNull.INSTANCE);
            if (Redact.forced(who)) {
                for (String field : List.of("parts", "dimension", "id", "fingerprint", "items", "shulkers", "stashes")) detail.remove(field);
                detail.addProperty("stash", Redact.HIDDEN);
            }
            details.put(id, detail);
        }
        JsonArray stashes = new JsonArray();
        for (var entry : stashItems.entrySet()) {
            var book = entry.getKey(); var stash = new JsonObject();
            stash.addProperty("server", book.server()); stash.addProperty("serverLabel", serverLabel(book.server(), who));
            stash.addProperty("stash", Redact.forced(who) ? Redact.HIDDEN : book.stash());
            stash.add("items", balances(entry.getValue(), stashReserved.get(book), false, who)); stashes.add(stash);
        }
        JsonObject out = new JsonObject(); out.addProperty("stockEpoch", query.epoch()); out.addProperty("stockRevision", query.revision());
        out.addProperty("maxAgeMillis", query.maxAge()); out.addProperty("asOf", System.currentTimeMillis());
        out.addProperty("reservationPrecision", "aggregate");
        out.add("items", balances(items, reserved, true, who)); out.add("stashes", stashes);
        JsonArray physical = new JsonArray(); details.values().forEach(physical::add); out.add("sources", physical);
        return out;
    }

    private static String serverLabel(String server, Users.Principal who) { return Redact.forced(who) ? Redact.HIDDEN : server; }

    private static void addItems(Map<String, JsonObject> rows, StockLedger.Source source, Users.Principal who) {
        for (var value : source.value().getAsJsonArray("items")) {
            var item = value.getAsJsonObject(); String key = item.get("key").getAsString();
            var row = rows.computeIfAbsent(source.server() + "\u0000" + key, ignored -> {
                var result = new JsonObject(); result.addProperty("key", key); result.addProperty("server", source.server()); result.add("id", item.get("id"));
                result.addProperty("name", Redact.forced(who) ? Redact.prettyId(item.get("id").getAsString())
                    : item.has("name") ? item.get("name").getAsString() : item.get("id").getAsString());
                for (String field : List.of("observed", "fresh", "unknown")) result.addProperty(field, 0L);
                return result;
            });
            long amount = item.get("count").getAsLong();
            row.addProperty("observed", Math.addExact(row.get("observed").getAsLong(), amount));
            String bucket = source.status().equals("FRESH") && !source.shared() ? "fresh" : "unknown";
            row.addProperty(bucket, Math.addExact(row.get(bucket).getAsLong(), amount));
        }
    }

    private static JsonArray balances(Map<String, JsonObject> rows, Map<String, Long> holds, boolean guild, Users.Principal who) {
        JsonArray result = new JsonArray();
        for (var item : rows.values()) {
            String key = (guild ? item.get("server").getAsString() + "\u0000" : "") + "item:" + item.get("key").getAsString();
            long held = holds.getOrDefault(key, 0L);
            item.addProperty("reserved", held); item.addProperty("available", Math.max(0, item.get("fresh").getAsLong() - held));
            item.remove("fresh"); item.addProperty("serverLabel", serverLabel(item.get("server").getAsString(), who)); result.add(item);
        }
        return result;
    }
}
