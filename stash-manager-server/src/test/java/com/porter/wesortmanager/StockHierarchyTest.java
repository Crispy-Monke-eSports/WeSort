package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockHierarchyTest {
    @TempDir
    Path data;

    private PhysicalSlots.Snapshot frame(int count) {
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var box = new PhysicalSlots.Item(
                "minecraft:shulker_box",
                "minecraft:shulker_box",
                1,
                "{}",
                PhysicalSlots.Kind.SHULKER,
                27,
                List.of(new PhysicalSlots.Slot(26, count, diamond)),
                "kit");
        return new PhysicalSlots.Snapshot(
                27, List.of(new PhysicalSlots.Slot(0, 1, box), new PhysicalSlots.Slot(2, 1, box)));
    }

    private JsonArray amounts(Map<String, Long> counts) {
        JsonArray out = new JsonArray();
        counts.forEach((key, count) -> {
            var row = new JsonObject();
            row.addProperty("id", key.equals("kit") ? "minecraft:shulker_box" : key);
            row.addProperty("key", key);
            row.addProperty("count", count);
            out.add(row);
        });
        return out;
    }

    private JsonObject index(StockLedger ledger, int count) throws Exception {
        var frame = frame(count);
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("receivedAt", System.currentTimeMillis());
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        root.add("items", amounts(frame.totals()));
        root.add("shulkers", amounts(frame.shulkers()));
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var writer = new PhysicalSlots.Writer();
        source(root).add("slots", writer.slots(frame.slots()));
        source(root).addProperty("fingerprint", frame.fingerprint());
        root.addProperty("physicalVersion", 2);
        root.add("slotVariants", writer.variants());
        return root;
    }

    private JsonObject source(JsonObject index) {
        return index.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }

    @Test
    void returnChoiceSurvivesRestartAndStaleChangesCannotReplaceIt() throws Exception {
        var ledger = new StockLedger(data);
        var frame = index(ledger, 32);
        ledger.accept(frame);
        String id = source(frame).get("id").getAsString();
        var slot = ledger.physicalSlots(id).stream()
                .filter(row -> "0".equals(row.get("path").getAsString()))
                .findFirst()
                .orElseThrow();
        String snapshot =
                ledger.sources().getFirst().value().get("slotFingerprint").getAsString();
        var keep = ledger.recordReturn(id, slot, snapshot, "owner", false, 0);
        assertTrue(keep.changed());
        var restarted = new StockLedger(data);
        assertEquals(keep.record(), restarted.returnDecisions(id).get(0).getAsJsonObject());
        assertNull(restarted.recordReturn(id, slot, snapshot, "owner", true, 0));
        var mark = restarted.recordReturn(id, slot, snapshot, "owner", true, 1);
        assertEquals("UUID_REQUESTED", mark.record().get("state").getAsString());
        assertNull(restarted.recordReturn(id, slot, snapshot, "owner", false, 0));
    }

    @Test
    void storesHierarchyInSameTransactionAndKeepsStableSlotRevisions() throws Exception {
        var ledger = new StockLedger(data);
        var first = index(ledger, 32);
        var id = source(first).get("id").getAsString();
        ledger.accept(first);
        var before = ledger.physicalSlots(id);
        assertEquals(4, before.size());
        assertTrue(before.stream().allMatch(r -> r.get("revision").getAsLong() == 1));
        assertFalse(ledger.sources().getFirst().value().has("slotSnapshot"));
        var same = index(ledger, 32);
        ledger.accept(same);
        assertTrue(ledger.physicalSlots(id).stream()
                .allMatch(r -> r.get("revision").getAsLong() == 1));
        var changed = index(ledger, 31);
        ledger.accept(changed);
        assertTrue(ledger.physicalSlots(id).stream()
                .allMatch(r -> r.get("revision").getAsLong() == 3));
        assertEquals(frame(32).json(), ledger.snapshot(id, frame(32).fingerprint()));
        assertEquals(frame(31).json(), ledger.snapshot(id, frame(31).fingerprint()));
        assertThrows(StockLedger.Conflict.class, () -> ledger.accept(same));
    }

    @Test
    void rejectsAggregateMismatchWithoutCommittingAnyRows() throws Exception {
        var ledger = new StockLedger(data);
        var bad = index(ledger, 32);
        source(bad).getAsJsonArray("items").get(0).getAsJsonObject().addProperty("count", 999);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(bad));
        assertEquals(0, ledger.revision());
        assertTrue(ledger.sources().isEmpty());
    }

    @Test
    void uncertaintyPreservesSnapshotAndRestartRequiresNewRead() throws Exception {
        var ledger = new StockLedger(data);
        var original = index(ledger, 32);
        ledger.accept(original);
        var stale = index(ledger, 1);
        source(stale).addProperty("state", "UNREACHABLE");
        ledger.accept(stale);
        var source = ledger.sources().getFirst();
        assertEquals("UNREACHABLE", source.status());
        assertEquals(
                frame(32).fingerprint(), source.value().get("slotFingerprint").getAsString());
        assertEquals(
                4, ledger.physicalSlots(source.value().get("id").getAsString()).size());
        var restarted = new StockLedger(data);
        assertNotEquals("FRESH", restarted.sources().getFirst().status());
        assertThrows(StockLedger.Conflict.class, () -> restarted.accept(original));
    }

    @Test
    void detailRequiresVisibleMembershipAndForcedModeCannotFetchComponents() throws Exception {
        var ledger = new StockLedger(data);
        var original = index(ledger, 32);
        ledger.accept(original);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        var orders = new OrderStore(data, store);
        orders.load();
        var routes = new StockRoutes(store, orders);
        var id = source(original).get("id").getAsString();
        var owner = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);
        assertEquals(frame(32).json(), routes.source(owner, id).getAsJsonObject("snapshot"));
        var member = new Users.Principal("member", "Member", false, Users.PRESETS.get("member"), false);
        assertNull(routes.source(member, id));
        assertTrue(routes.view(member).getAsJsonArray("sources").isEmpty());
        assertFalse(routes.view(member).getAsJsonArray("stashes").isEmpty());
        var hidden = new Users.Principal(
                "hidden",
                "Hidden",
                false,
                Users.Perms.of(
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        List.of(new Users.StashRef("other")),
                        List.of()),
                false);
        assertNull(routes.source(hidden, id));
        var hiddenStock = routes.view(hidden);
        assertTrue(hiddenStock.getAsJsonArray("sources").isEmpty());
        assertTrue(hiddenStock.getAsJsonArray("stashes").isEmpty());
        var visibleBalance = routes.view(owner).getAsJsonArray("items").get(0).getAsJsonObject();
        var sharedBalance = hiddenStock.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(visibleBalance.get("observed"), sharedBalance.get("observed"));
        assertFalse(sharedBalance.has("server"));
        var nopos = new Users.Principal(
                "nopos",
                "NoPos",
                false,
                Users.Perms.of(true, false, false, false, false, false, false, null, null),
                false);
        assertNull(routes.source(nopos, id));
        assertFalse(routes.view(nopos).toString().contains("slotFingerprint"));
        assertFalse(Redact.index(original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .has("slotVariants"));
    }

    @Test
    void changedContentsBlockExistingOrderEvenWhenEnoughAggregateStockRemains() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNotNull(order.sourceGuards);
        assertNotNull(orders.claim("s", "a"));
        store.putIndex(index(ledger, 31));
        assertNull(orders.claim("s", "a"));
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertNull(restarted.claim("s", "a"));
        assertEquals(order.sourceGuards, OrderStore.Order.fromJson(order.toJson()).sourceGuards);
    }

    @Test
    void racingWholeBoxesAndContentsAcrossAliasesCannotReserveTheSameRoots() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var alias = index(ledger, 32);
        alias.addProperty("stash", "b");
        store.putIndex(alias);
        var orders = new OrderStore(data, store);
        orders.load();
        var whole = new OrderStore.Line("shulker", "kit", 2);
        var child = new OrderStore.Line("item", "minecraft:diamond", 64);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<OrderStore.Order> first = () -> tryOrder(orders, start, "a", whole);
        java.util.concurrent.Callable<OrderStore.Order> second = () -> tryOrder(orders, start, "b", child);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(first);
            var b = pool.submit(second);
            start.countDown();
            // Both aliases refer to one physical source. Neither may reserve it
            // until ownership is unambiguous, even if their requests race.
            assertNull(a.get());
            assertNull(b.get());
            assertTrue(orders.heldRoots("s").isEmpty());
            var restarted = new OrderStore(data, store);
            restarted.load();
            assertTrue(restarted.heldRoots("s").isEmpty());
        }
    }

    private static OrderStore.Order tryOrder(
            OrderStore orders, java.util.concurrent.CountDownLatch start, String stash, OrderStore.Line line)
            throws Exception {
        start.await();
        try {
            return orders.place("s", stash, List.of(line), null);
        } catch (OrderStore.OrderException conflict) {
            // A shared source can surface as an empty verified aggregate (400)
            // or an explicit reservation conflict (409), but never be allocated.
            assertTrue(conflict.status == 400 || conflict.status == 409);
            return null;
        }
    }

    @Test
    void failedClaimRetainsRootsUntilFreshReadFromThePhysicalSource() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var ledger = new StockLedger(data, now::get, 600_000);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        assertNull(orders.claimPayload("s", "a", UUID.randomUUID().toString()));
        assertThrows(
                OrderStore.OrderException.class,
                () -> orders.status(order.id, "failed", null, null, null, null, "other"));
        var failed = orders.status(order.id, "failed", null, null, null, null, agent);
        orders.releaseFromFreshObservation("s");
        assertEquals(2, orders.heldRoots("s").size());
        assertEquals(0, orders.clearFinished("s", "a"));

        var refreshed = index(ledger, 32);
        now.set(failed.updatedAt + 1_000);
        source(refreshed).addProperty("observedAt", now.get());
        store.putIndex(refreshed);
        orders.releaseFromFreshObservation("s");
        assertTrue(orders.heldRoots("s").isEmpty());
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertTrue(restarted.heldRoots("s").isEmpty());
    }

    @Test
    void stagingNeedsNativeReceiptAndCannotBecomeFinalDelivery() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 1)), null);
        assertEquals(OrderStage.RESERVED, order.stage());
        String agent = UUID.randomUUID().toString();
        var claim = orders.claimPayload("s", "a", agent);
        assertEquals("PACKING", claim.get("state").getAsString());
        var result = new JsonObject();
        result.addProperty("shulkers", 1);
        var uncertain = orders.status(order.id, "delivered", null, null, null, result, agent, "receipt-a");
        assertEquals(OrderStage.RECOVERING, uncertain.stage());
        assertEquals("in_progress", uncertain.status);
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertEquals(
                uncertain.toJson(),
                restarted
                        .status(order.id, "delivered", null, null, null, result, agent, "receipt-a")
                        .toJson());
        assertEquals(
                409,
                assertThrows(
                                OrderStore.OrderException.class,
                                () -> restarted.status(order.id, "failed", null, null, null, null, agent, "receipt-a"))
                        .status);
        assertEquals(0, restarted.clearFinished("s", "a"));
        assertEquals(1, restarted.heldRoots("s").size());
        assertFalse(OrderStage.PACKING.allows(OrderStage.DELIVERED));
        assertFalse(OrderStage.PICKUP_READY.allows(OrderStage.DELIVERED));
        assertFalse(OrderStage.NEEDS_RECONCILIATION.allows(OrderStage.DELIVERED));
        assertTrue(OrderStage.AWAITING_CUSTOMER.allows(OrderStage.DELIVERED));
    }

    private com.porter.wesort.inventory.PhysicalJournal stagedReceipt(StockLedger ledger, String orderId, String agent)
            throws Exception {
        var journal = new com.porter.wesort.inventory.PhysicalJournal(data.resolve("fixture-agent"));
        var context = JsonParser.parseString("""
            {"server":"s","dimension":"minecraft:overworld","kind":"DIRECT","execution":"STAGE_PACKAGE"}
            """).getAsJsonObject();
        context.addProperty("agent", agent);
        context.addProperty("orderId", orderId);
        context.addProperty("stash", "a");
        journal.start(context);
        var evidence = JsonParser.parseString("""
            {"origin":{"server":"s","dimension":"minecraft:overworld","source":[1,64,2]},
             "before":{"menu":1,"slots":[],"cursor":"empty"},
             "expected":{"menu":1,"slots":[],"cursor":"empty"}}
            """).getAsJsonObject();
        evidence.getAsJsonObject("origin").addProperty("player", agent);
        String stack = "{\"count\":1,\"id\":\"minecraft:shulker_box\"}";
        for (int i = 0; i < 63; i++) {
            evidence.getAsJsonObject("before").getAsJsonArray("slots").add(i == 54 ? stack : "empty");
            evidence.getAsJsonObject("expected").getAsJsonArray("slots").add(i == 0 ? stack : "empty");
        }
        journal.intent(evidence);
        journal.verified(evidence.getAsJsonObject("expected"), false);
        journal.complete();
        new PhysicalOperations(ledger).accept(agent, journal.current());
        return journal;
    }

    @Test
    void verifiedWarehouseHandoffStopsAtPickupReady() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 1)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        var journal = stagedReceipt(ledger, order.id, agent);
        var result = new JsonObject();
        result.addProperty("shulkers", 1);
        result.add("lines", JsonParser.parseString("[{\"key\":\"kit\",\"delivered\":1}]"));
        var ready = orders.status(order.id, "delivered", null, null, null, result, agent, "staging-ok");
        assertEquals(OrderStage.PICKUP_READY, ready.stage());
        assertEquals("packed", ready.status);
        assertEquals(1, ready.manifest.getAsJsonArray("packages").size());
        assertEquals(
                journal.current().get("id"),
                ready.manifest
                        .getAsJsonArray("packages")
                        .get(0)
                        .getAsJsonObject()
                        .get("operationId"));
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertEquals(
                ready.manifest,
                restarted.status(order.id, "delivered", null, null, null, result, agent, "staging-ok").manifest);
        var scope = new BotHub.ManifestScope("test-bot", "s", "a", "minecraft:overworld", agent, "TestAgent");
        var manifest = restarted.manifest(order.id, scope);
        assertEquals(order.id, restarted.pendingManifest(scope).get("orderId").getAsString());
        assertEquals(ready.manifest.get("hash"), manifest.get("hash"));
        var publicEntry = manifest.getAsJsonArray("packages").get(0).getAsJsonObject();
        assertNotNull(UUID.fromString(publicEntry.get("packageId").getAsString()));
        assertFalse(publicEntry.has("staging"));
        assertNull(restarted.manifest(
                order.id, new BotHub.ManifestScope("test-bot", "s", "a", "minecraft:the_nether", agent, "TestAgent")));
        assertNull(restarted.manifest(
                order.id,
                new BotHub.ManifestScope("test-bot", "different", "a", "minecraft:overworld", agent, "TestAgent")));
        assertNull(restarted.manifest(
                order.id,
                new BotHub.ManifestScope(
                        "test-bot",
                        "s",
                        "a",
                        "minecraft:overworld",
                        UUID.randomUUID().toString(),
                        "TestAgent")));
        String hash = manifest.get("hash").getAsString();
        assertEquals(
                409,
                assertThrows(
                                OrderStore.OrderException.class,
                                () -> restarted.acknowledgeManifest(order.id, scope, "0".repeat(64)))
                        .status);
        var receipt = restarted.acknowledgeManifest(order.id, scope, hash);
        assertTrue(receipt.changed());
        var reloaded = new OrderStore(data, store);
        reloaded.load();
        assertNull(reloaded.pendingManifest(scope));
        assertEquals(
                new OrderStore.ManifestReceipt(receipt.acknowledgedAt(), false),
                reloaded.acknowledgeManifest(order.id, scope, hash));
        assertEquals(0, orders.clearFinished("s", "a"));
        assertThrows(OrderStore.OrderException.class, () -> orders.cancel(order.id));
    }

    @Test
    void malformedCompletionEntersRecoveryAndCannotBeReopenedByLateProof() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 1)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        stagedReceipt(ledger, order.id, agent);
        var result = JsonParser.parseString("""
            {"shulkers":1.5,"lines":[{"key":"kit","delivered":1}]}
            """).getAsJsonObject();
        var recovery = orders.status(order.id, "delivered", null, null, null, result, agent, "bad-count");
        assertEquals(OrderStage.RECOVERING, recovery.stage());
        assertNull(recovery.manifest);
        result.addProperty("shulkers", 1);
        var late = orders.status(order.id, "delivered", null, null, null, result, agent, "late-completion");
        assertEquals(OrderStage.RECOVERING, late.stage());
        assertNull(late.manifest);
        assertFalse(orders.heldRoots("s").isEmpty());
    }

    @Test
    void cancelledPackingCannotBeMarkedPackedByLateCompletion() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 1)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        stagedReceipt(ledger, order.id, agent);
        orders.cancel(order.id);
        var result = JsonParser.parseString("""
            {"shulkers":1,"lines":[{"key":"kit","delivered":1}]}
            """).getAsJsonObject();
        var recovery = orders.status(order.id, "delivered", null, null, null, result, agent, "late-completion");
        assertEquals(OrderStage.RECOVERING, recovery.stage());
        assertNull(recovery.manifest);
        assertFalse(orders.heldRoots("s").isEmpty());
    }

    @Test
    void malformedLineCountsNeverBecomeSuccessfulPackingReceipts() {
        var lines = List.of(new OrderStore.Line("shulker", "kit", 2));
        for (String invalid : List.of("2.5", "18446744073709551618", "\"2\"", "null", "true", "{}", "[]")) {
            var result = JsonParser.parseString("{\"lines\":[{\"key\":\"kit\",\"delivered\":" + invalid + "}]}")
                    .getAsJsonObject();
            assertFalse(OrderStore.allLinesStaged(lines, result), invalid);
        }
        for (String invalid : List.of("null", "2", "{}", "{\"key\":\"kit\"}")) {
            var result = JsonParser.parseString("{\"lines\":[" + invalid + "]}").getAsJsonObject();
            assertFalse(OrderStore.allLinesStaged(lines, result), invalid);
        }
        var valid = JsonParser.parseString("{\"lines\":[{\"key\":\"kit\",\"delivered\":2}]}")
                .getAsJsonObject();
        assertTrue(OrderStore.allLinesStaged(lines, valid));
    }

    @Test
    void frozenPackageCountSurvivesPartialDelivery() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        // Two kits need two frozen boxes (packageTotal Y=2).
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 2)), null);
        assertEquals(2, order.packageTotal);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        stagedReceipt(ledger, order.id, agent);
        // One staged box with all lines covered: must NOT become PICKUP_READY with Y=1.
        // Fail closed to RECOVERING against the same frozen total instead (#68).
        var partial = JsonParser.parseString("""
            {"shulkers":1,"lines":[{"key":"kit","delivered":2}]}
            """).getAsJsonObject();
        var recovery = orders.status(order.id, "delivered", null, null, null, partial, agent, "partial-3-of-4");
        assertEquals(OrderStage.RECOVERING, recovery.stage());
        assertNull(recovery.manifest);
        assertTrue(recovery.message.contains("1 of frozen 2"));
    }

    @Test
    void partialStagingReportsDoNotCoverAnEntireOrder() {
        var lines = List.of(new OrderStore.Line("shulker", "kit", 2));
        var partial = JsonParser.parseString("{\"lines\":[{\"key\":\"kit\",\"delivered\":1}]}")
                .getAsJsonObject();
        assertFalse(OrderStore.allLinesStaged(lines, partial));
        var duplicate = JsonParser.parseString("""
            {"lines":[{"key":"kit","delivered":2},{"key":"kit","delivered":2}]}
            """).getAsJsonObject();
        assertFalse(OrderStore.allLinesStaged(lines, duplicate));
    }

    @Test
    void identicalShulkerFingerprintsStillGetDistinctPackageIds() {
        var first = JsonParser.parseString("""
            {"operationId":"first","stackFingerprint":"same-stack",
             "staging":{"dimension":"minecraft:overworld","position":[1,64,2],"slot":0}}
            """).getAsJsonObject();
        var second = first.deepCopy();
        second.addProperty("operationId", "second");
        assertNull(PackageManifest.staged("o-fixture", List.of(first, second), 2));
        second.getAsJsonObject("staging").addProperty("slot", 1);
        var packages =
                PackageManifest.staged("o-fixture", List.of(first, second), 2).getAsJsonArray("packages");
        assertNotEquals(
                packages.get(0).getAsJsonObject().get("packageId"),
                packages.get(1).getAsJsonObject().get("packageId"));
        second.addProperty("operationId", "first");
        assertNull(PackageManifest.staged("o-fixture", List.of(first, second), 2));
    }

    @Test
    void schemaTwoMigratesWithoutLosingHistoricalStock() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(index(ledger, 32));
        try (var c = ledger.open();
                var sql = c.createStatement()) {
            sql.execute("DROP TABLE physical_snapshots");
            sql.execute("DROP TABLE physical_slots");
            sql.execute("PRAGMA user_version=2");
        }
        var upgraded = new StockLedger(data);
        assertEquals("STALE_STARTUP", upgraded.sources().getFirst().status());
        assertEquals(
                2,
                upgraded.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("shulkers")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsInt());
        // No physical slot payload is invented when upgrading a version that never stored it.
        assertNull(upgraded.snapshot(
                upgraded.sources().getFirst().value().get("id").getAsString(),
                frame(32).fingerprint()));
    }

    @Test
    void legacyReadInvalidatesOldPhysicalSlotsWithoutDiscardingHistory() throws Exception {
        var ledger = new StockLedger(data);
        var original = index(ledger, 32);
        ledger.accept(original);
        var id = source(original).get("id").getAsString();
        var legacy = index(ledger, 31);
        legacy.addProperty("physicalVersion", 1);
        ledger.accept(legacy);
        assertTrue(ledger.physicalSlots(id).stream()
                .noneMatch(r -> r.get("present").getAsBoolean()));
        assertNotNull(ledger.snapshot(id, frame(32).fingerprint()));
    }
}
