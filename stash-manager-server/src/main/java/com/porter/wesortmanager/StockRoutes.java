package com.porter.wesortmanager;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;

/** All stock viewers share the guild balances. Scope controls access to physical locations. */
final class StockRoutes {
    private final IndexStore index;
    private final OrderStore orders;

    StockRoutes(IndexStore index, OrderStore orders) { this.index = index; this.orders = orders; }

    void register(Router router, AuditLog audit) {
        router.exact(Router.GET, "/api/v1/stock", Router.Guard.SESSION, Router.Perm.VIEW, this::get);
        router.exact(Router.GET, "/api/v1/stock/source", Router.Guard.SESSION, Router.Perm.VIEW, this::getSource);
        router.exact(Router.GET, "/api/v1/stock/boxes", Router.Guard.SESSION, Router.Perm.OWNER, this::boxes);
        router.exact(Router.POST, "/api/v1/stock/return-intake", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER,
            ex -> returnIntake(ex, audit));
    }

    private void boxes(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) { Http.error(ex, 404, "not found"); return; }
        synchronized (index) {
            var response = new JsonObject();
            response.add("active", index.stock().boxUuids());
            response.add("history", index.stock().boxUuidHistory());
            Http.json(ex, 200, response);
        }
    }

    private void get(HttpExchange ex) throws IOException { Http.json(ex, 200, view(Router.user(ex))); }

    private void getSource(HttpExchange ex) throws IOException {
        JsonObject value = source(Router.user(ex), Http.query(ex).getOrDefault("id", ""));
        if (value == null) Http.json(ex, 404, new JsonObject()); else Http.json(ex, 200, value);
    }

    JsonObject source(Users.Principal who, String id) throws IOException {
        if (Redact.forced(who) || !who.perms().positions()) return null;
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
                if (who.owner() && returnContainer(source)) out.add("returnIntake", ledger.returnDecisions(id));
                if (who.owner()) {
                    var identities = ledger.boxIdentities(id);
                    if (!identities.isEmpty()) out.add("boxIdentities", identities);
                }
            }
            var physical = PhysicalReservations.read(ledger, source.server(), source.stash());
            if (physical.hasSnapshots()) {
                out.add("observed", Json.GSON.toJsonTree(physical.observed(id)));
                var held = orders.physicalHolds(source.server()).stream().filter(r -> id.equals(r.allocation().source())).toList();
                var reserved = new TreeMap<String,Long>();
                held.forEach(r -> reserved.merge(r.allocation().ref(), r.allocation().count(), Math::addExact));
                out.add("reserved", Json.GSON.toJsonTree(reserved));
                var roots = held.stream().map(r -> r.allocation().root()).collect(java.util.stream.Collectors.toSet());
                var available = index.automation().current(source.server()) == null ? physical.available(roots, id) : Map.of();
                out.add("available", Json.GSON.toJsonTree(available));
                if (who.owner()) out.add("allocations", Json.GSON.toJsonTree(held));
            }
            return out;
        }
    }

    private static boolean returnContainer(StockLedger.Source source) {
        return switch (source.value().get("kind").getAsString()) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel" -> true;
            default -> false;
        };
    }

    private void returnIntake(HttpExchange ex, AuditLog audit) throws IOException {
        var who = Router.user(ex);
        if (Redact.on(ex)) { Http.error(ex, 404, "No return source in this scope"); return; }
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) { Http.error(ex, 400, "Expected a return decision"); return; }
        var body = raw.getAsJsonObject();
        String id = AddonRoutes.string(body, "source");
        String path = AddonRoutes.string(body, "path");
        var revision = body.get("slotRevision");
        var decisionRevision = body.get("expectedDecisionRevision");
        var choice = body.get("markUuid");
        if (id == null || !id.matches("c:[a-f0-9]{64}") || path == null || !path.matches("0|[1-9][0-9]?")
            || !(revision instanceof JsonPrimitive seen) || !seen.isNumber()
            || !(decisionRevision instanceof JsonPrimitive previous) || !previous.isNumber()
            || !(choice instanceof JsonPrimitive mark) || !mark.isBoolean()) {
            Http.error(ex, 400, "Expected a physical source, root slot revision and explicit UUID choice"); return;
        }
        long expected;
        long expectedDecision;
        try {
            expected = seen.getAsBigDecimal().longValueExact();
            expectedDecision = previous.getAsBigDecimal().longValueExact();
            if (expected < 1 || expectedDecision < 0) throw new ArithmeticException();
        }
        catch (ArithmeticException invalid) { Http.error(ex, 400, "Invalid slot revision"); return; }
        StockLedger.ReturnDecision decision;
        synchronized (index) {
            var ledger = index.stock();
            var source = ledger.sources().stream().filter(s -> id.equals(s.value().get("id").getAsString())
                && who.perms().seesStash(s.server(), s.stash()) && !s.status().equals("SUPERSEDED")).findFirst().orElse(null);
            if (source == null || !returnContainer(source)) { Http.error(ex, 404, "No return source in this scope"); return; }
            if (!source.status().equals("FRESH") || !source.value().has("slotFingerprint")) {
                Http.error(ex, 409, "Read the container again before recording a return"); return;
            }
            var slot = ledger.physicalSlots(id).stream().filter(s -> path.equals(s.get("path").getAsString())
                && s.get("present").getAsBoolean()).findFirst().orElse(null);
            if (slot == null || slot.get("revision").getAsLong() != expected || !"SHULKER".equals(slot.get("kind").getAsString())
                || slot.get("count").getAsInt() != 1 || slot.get("size").getAsInt() != 27) {
                Http.error(ex, 409, "The selected slot no longer holds the verified shulker"); return;
            }
            decision = ledger.recordReturn(id, slot, source.value().get("slotFingerprint").getAsString(), who.id(),
                mark.getAsBoolean(), expectedDecision);
        }
        if (decision == null) { Http.error(ex, 409, "The return choice was updated. Reopen the source to see the current choice."); return; }
        if (decision.changed()) audit.add(who, mark.getAsBoolean() ? "return.uuid.requested" : "return.keep_name",
            id + "/" + path, AuthRoutes.address(ex));
        Http.json(ex, 200, decision.record());
    }

    JsonObject view(Users.Principal who) throws IOException {
        synchronized (index) { return viewWithObservationLock(who); }
    }

    private JsonObject viewWithObservationLock(Users.Principal who) throws IOException {
        var query = index.stock();
        Set<String> blockedServers = new HashSet<>(); JsonArray automation = new JsonArray();
        var sources = query.sources().stream().filter(s -> !s.status().equals("SUPERSEDED")).toList();
        var visibleServers = new HashSet<String>();
        for (var source : sources) if (who.perms().seesStash(source.server(), source.stash())) visibleServers.add(source.server());
        for (String server : sources.stream().map(StockLedger.Source::server).distinct().toList()) {
            var lease = index.automation().current(server);
            if (lease != null) {
                blockedServers.add(server);
                if (visibleServers.contains(server)) {
                    var entry = new JsonObject(); entry.addProperty("server", server);
                    entry.addProperty("state", lease.state()); automation.add(entry);
                }
            }
        }
        Map<String, JsonObject> items = new TreeMap<>(), details = new LinkedHashMap<>();
        Map<IndexStore.StashKey, Map<String, JsonObject>> stashItems = new LinkedHashMap<>();
        Map<String, Long> reserved = new HashMap<>();
        Map<IndexStore.StashKey, Map<String, Long>> stashReserved = new HashMap<>();
        Map<IndexStore.StashKey, PhysicalReservations> physicalStashes = new HashMap<>();
        Map<String, Map<String, Long>> exactAvailable = new HashMap<>();
        Map<IndexStore.StashKey, Map<String, Long>> stashAvailable = new HashMap<>();
        Map<String, Set<PhysicalReservations.Root>> rootsByServer = new HashMap<>();
        Set<String> countedSources = new HashSet<>();
        for (var source : sources) {
            var book = new IndexStore.StashKey(source.server(), source.stash());
            if (!stashReserved.containsKey(book)) {
                var holds = orders.reservedFor(source.server(), source.stash());
                stashReserved.put(book, holds);
                holds.forEach((key, amount) -> reserved.merge(source.server() + "\u0000" + key, amount, Math::addExact));
            }
            String id = source.value().get("id").getAsString();
            var roots = rootsByServer.computeIfAbsent(book.server(), orders::heldRoots);
            if (!physicalStashes.containsKey(book)) {
                var observations = PhysicalReservations.read(query, book.server(), book.stash());
                physicalStashes.put(book, observations);
                if (observations.hasSnapshots()) stashAvailable.put(book, observations.available(roots));
            }
            var typed = physicalStashes.get(book);
            if (countedSources.add(id)) {
                addItems(items, source, who);
                if (typed.hasSnapshots()) {
                    var totals = exactAvailable.computeIfAbsent(book.server(), ignored -> new HashMap<>());
                    typed.available(roots, id).forEach((ref,count) -> totals.merge(ref,count,Math::addExact));
                }
            }
            if (!who.perms().seesStash(source.server(), source.stash())) continue;
            addItems(stashItems.computeIfAbsent(book, key -> new TreeMap<>()), source, who);
            if (!who.perms().positions() && !Redact.forced(who)) continue;
            if (details.containsKey(id)) {
                if (!Redact.forced(who)) details.get(id).getAsJsonArray("stashes").add(source.stash());
                continue;
            }
            var detail = source.value().deepCopy();
            detail.addProperty("server", source.server()); detail.addProperty("serverLabel", serverLabel(source.server(), who));
            detail.addProperty("stash", source.stash());
            JsonArray memberships = new JsonArray(); memberships.add(source.stash()); detail.add("stashes", memberships);
            detail.addProperty("sourceRevision", source.revision()); detail.addProperty("verifiedAt", source.verifiedAt());
            detail.addProperty("state", source.shared() && source.status().equals("FRESH") ? "SHARED_SOURCE" : source.status());
            detail.remove("epoch"); detail.remove("session"); detail.remove("sequence"); detail.remove("baseRevision");
            if (source.value().has("slotFingerprint") && !Redact.forced(who)) {
                var held = new TreeMap<String,Long>();
                for (var reservation : orders.physicalHolds(source.server())) {
                    var allocation = reservation.allocation();
                    if (id.equals(allocation.source())) held.merge(allocation.ref(), allocation.count(), Math::addExact);
                }
                detail.add("reserved", Json.GSON.toJsonTree(held));
                detail.add("available", Json.GSON.toJsonTree(blockedServers.contains(book.server()) ? Map.of() : typed.available(roots,id)));
            } else {
                detail.add("reserved", JsonNull.INSTANCE);
                detail.add("available", JsonNull.INSTANCE);
            }
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
            stash.add("items", balances(entry.getValue(), stashReserved.get(book), false, who, blockedServers, stashAvailable.get(book) == null ? Map.of() : Map.of(book.server(), stashAvailable.get(book)))); stashes.add(stash);
        }
        JsonObject out = new JsonObject(); out.addProperty("stockEpoch", query.epoch()); out.addProperty("stockRevision", query.revision());
        out.addProperty("maxAgeMillis", query.maxAge()); out.addProperty("asOf", System.currentTimeMillis());
        out.addProperty("reservationPrecision", exactAvailable.isEmpty() ? "aggregate" : "physical-root");
        var guildBalances = balances(items, reserved, true, who, blockedServers, exactAvailable);
        for (var value : guildBalances) {
            var row = value.getAsJsonObject();
            if (!visibleServers.contains(row.get("server").getAsString())) {
                row.remove("server");
                row.addProperty("serverLabel", "Guild server");
            }
        }
        out.add("items", guildBalances);
        out.add("stashes", stashes);
        JsonArray physical = new JsonArray(); details.values().forEach(physical::add); out.add("sources", physical); out.add("automation", automation);
        return out;
    }

    private static String serverLabel(String server, Users.Principal who) { return Redact.forced(who) ? Redact.HIDDEN : server; }

    private static void addItems(Map<String, JsonObject> rows, StockLedger.Source source, Users.Principal who) {
        for (var value : source.value().getAsJsonArray("items")) {
            var item = value.getAsJsonObject(); String key = item.get("key").getAsString();
            var row = rows.computeIfAbsent(source.server() + "\u0000" + key, ignored -> {
                var result = new JsonObject(); result.addProperty("key", key); result.addProperty("server", source.server()); result.add("id", item.get("id"));
                result.addProperty("name", Redact.forced(who) || !who.perms().positions() || !who.perms().seesStash(source.server(), source.stash()) ? Redact.prettyId(item.get("id").getAsString())
                    : item.has("name") ? item.get("name").getAsString() : item.get("id").getAsString());
                for (String field : List.of("observed", "fresh", "unknown")) result.addProperty(field, 0L);
                return result;
            });
            long amount = item.get("count").getAsLong();
            row.addProperty("observed", Math.addExact(row.get("observed").getAsLong(), amount));
            String bucket = source.status().equals("FRESH") && (!source.shared() || source.value().has("slotFingerprint")) ? "fresh" : "unknown";
            row.addProperty(bucket, Math.addExact(row.get(bucket).getAsLong(), amount));
        }
    }

    private static JsonArray balances(Map<String, JsonObject> rows, Map<String, Long> holds, boolean guild,
                                      Users.Principal who, Set<String> blockedServers, Map<String,Map<String,Long>> exactAvailable) {
        JsonArray result = new JsonArray();
        for (var item : rows.values()) {
            String key = (guild ? item.get("server").getAsString() + "\u0000" : "") + "item:" + item.get("key").getAsString();
            long held = holds.getOrDefault(key, 0L);
            String server = item.get("server").getAsString();
            var exact = exactAvailable.get(server);
            long available = exact == null ? Math.max(0, item.get("fresh").getAsLong() - held)
                : exact.getOrDefault("item:" + item.get("key").getAsString(), 0L);
            item.addProperty("reserved", held);
            item.addProperty("available", blockedServers.contains(server) ? 0 : available);
            item.remove("fresh"); item.addProperty("serverLabel", serverLabel(item.get("server").getAsString(), who)); result.add(item);
        }
        return result;
    }
}
