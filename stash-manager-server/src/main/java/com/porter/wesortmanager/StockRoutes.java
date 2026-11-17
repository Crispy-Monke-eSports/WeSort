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
        router.exact(Router.GET, "/api/v1/stock/source", Router.Guard.SESSION, Router.Perm.VIEW, this::getSource);
    }

    private void get(HttpExchange ex) throws IOException { Http.json(ex, 200, view(Router.user(ex))); }

    private void getSource(HttpExchange ex) throws IOException {
        JsonObject value = source(Router.user(ex), Http.query(ex).getOrDefault("id", ""));
        if (value == null) Http.json(ex, 404, new JsonObject()); else Http.json(ex, 200, value);
    }

    JsonObject source(Users.Principal who, String id) throws IOException {
        if (Redact.forced(who)) return null;
        synchronized (index) {
            var ledger = index.stock();
            // Authorize the membership before loading potentially sensitive component data.
            var visible = ledger.sources().stream().filter(s -> who.perms().seesStash(s.server(), s.stash())
                && !s.status().equals("SUPERSEDED") && s.value().get("id").getAsString().equals(id)).findFirst();
            if (visible.isEmpty()) return null;
            var source = visible.get(); var out = new JsonObject();
            out.addProperty("id", id); out.addProperty("server", source.server()); out.addProperty("stash", source.stash());
            out.add("parts", source.value().get("parts")); out.add("dimension", source.value().get("dimension"));
            out.addProperty("state", source.status()); out.addProperty("sourceRevision", source.revision()); out.addProperty("verifiedAt", source.verifiedAt());
            out.addProperty("locationModel", "source/slot-path");
            JsonObject snapshot = source.value().has("slotFingerprint") ? ledger.snapshot(id, source.value().get("slotFingerprint").getAsString()) : null;
            out.add("snapshot", snapshot == null ? JsonNull.INSTANCE : snapshot);
            // The history frame carries its original content; current slot revisions must never label an older frame.
            if (snapshot != null && source.status().equals("FRESH")) {
                JsonArray slots = new JsonArray(); ledger.physicalSlots(id).forEach(slots::add); out.add("slots", slots);
            }
            return out;
        }
    }

    JsonObject view(Users.Principal who) throws IOException {
        synchronized (index) { return viewWithObservationLock(who); }
    }

    private JsonObject viewWithObservationLock(Users.Principal who) throws IOException {
        var query = index.stock();
        Set<String> blockedServers = new HashSet<>(); JsonArray automation = new JsonArray();
        for (String server : query.sources().stream().filter(source -> who.perms().seesStash(source.server(), source.stash())).map(StockLedger.Source::server).distinct().toList()) {
            var lease = index.automation().current(server);
            if (lease != null) {
                blockedServers.add(server); var entry = new JsonObject(); entry.addProperty("server", server);
                entry.addProperty("state", lease.state()); automation.add(entry);
            }
        }
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
                for (String field : List.of("parts", "dimension", "id", "fingerprint", "items", "shulkers", "stashes", "slotSchema", "slotFingerprint", "slotCount")) detail.remove(field);
                detail.addProperty("stash", Redact.HIDDEN);
            }
            details.put(id, detail);
        }
        JsonArray stashes = new JsonArray();
        for (var entry : stashItems.entrySet()) {
            var book = entry.getKey(); var stash = new JsonObject();
            stash.addProperty("server", book.server()); stash.addProperty("serverLabel", serverLabel(book.server(), who));
            stash.addProperty("stash", Redact.forced(who) ? Redact.HIDDEN : book.stash());
            stash.add("items", balances(entry.getValue(), stashReserved.get(book), false, who, blockedServers)); stashes.add(stash);
        }
        JsonObject out = new JsonObject(); out.addProperty("stockEpoch", query.epoch()); out.addProperty("stockRevision", query.revision());
        out.addProperty("maxAgeMillis", query.maxAge()); out.addProperty("asOf", System.currentTimeMillis());
        out.addProperty("reservationPrecision", "aggregate");
        out.add("items", balances(items, reserved, true, who, blockedServers)); out.add("stashes", stashes);
        JsonArray physical = new JsonArray(); details.values().forEach(physical::add); out.add("sources", physical); out.add("automation", automation);
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

    private static JsonArray balances(Map<String, JsonObject> rows, Map<String, Long> holds, boolean guild, Users.Principal who, Set<String> blockedServers) {
        JsonArray result = new JsonArray();
        for (var item : rows.values()) {
            String key = (guild ? item.get("server").getAsString() + "\u0000" : "") + "item:" + item.get("key").getAsString();
            long held = holds.getOrDefault(key, 0L);
            item.addProperty("reserved", held); item.addProperty("available", blockedServers.contains(item.get("server").getAsString()) ? 0 : Math.max(0, item.get("fresh").getAsLong() - held));
            item.remove("fresh"); item.addProperty("serverLabel", serverLabel(item.get("server").getAsString(), who)); result.add(item);
        }
        return result;
    }
}
