package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Server-side proof that an unrenamed staged box can never reach PICKUP_READY. */
class RequiredPackageLabelReceiptTest {
    @TempDir
    Path data;

    private OrderStore.Order staged(String physicallyObservedName) throws Exception {
        var ledger = new StockLedger(data);
        var index = new IndexStore(data, CreativeTabs.NONE, ledger);
        index.load();
        StockFixtures.put(index, JsonParser.parseString("""
            {"version":1,"server":"s","stash":"a",
             "items":[{"key":"minecraft:diamond","id":"minecraft:diamond","count":64,"maxStack":64}]}
            """).getAsJsonObject());
        var orders = new OrderStore(data, index);
        orders.load();
        var requested = OrderStore.normalizeNewOrderNames(JsonParser.parseString("""
            {"mode":"packageLabel","version":1,"text":"Projekt A"}
            """));
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 64)), requested);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));

        var journal = new com.porter.wesort.inventory.PhysicalJournal(data.resolve("label-agent"));
        var context = JsonParser.parseString("""
            {"server":"s","dimension":"minecraft:overworld","kind":"DIRECT","execution":"STAGE_PACKAGE"}
            """).getAsJsonObject();
        context.addProperty("agent", agent);
        context.addProperty("orderId", order.id);
        context.addProperty("stash", "a");
        journal.start(context);

        // 26.3 typed NBT for one actually staged shulker. The observed item,
        // not the order's requested name, is the only staging truth.
        var item = new JsonObject();
        var count = new JsonObject();
        count.addProperty("type", 3);
        count.addProperty("value", "1");
        item.add("count", count);
        var id = new JsonObject();
        id.addProperty("type", 8);
        id.addProperty("value", new JsonPrimitive("minecraft:shulker_box").toString());
        item.add("id", id);
        if (physicallyObservedName != null) {
            var customName = new JsonObject();
            customName.addProperty("type", 8);
            customName.addProperty("value", new JsonPrimitive(physicallyObservedName).toString());
            var fields = new JsonObject();
            fields.add("minecraft:custom_name", customName);
            var components = new JsonObject();
            components.addProperty("type", 10);
            components.add("value", fields);
            item.add("components", components);
        }
        var stackTag = new JsonObject();
        stackTag.addProperty("type", 10);
        stackTag.add("value", item);
        String stack = stackTag.toString();

        var evidence = JsonParser.parseString("""
            {"origin":{"server":"s","dimension":"minecraft:overworld","source":[1,64,2]},
             "before":{"menu":1,"slots":[],"cursor":"empty"},
             "expected":{"menu":1,"slots":[],"cursor":"empty"}}
            """).getAsJsonObject();
        evidence.getAsJsonObject("origin").addProperty("player", agent);
        for (int i = 0; i < 63; i++) {
            evidence.getAsJsonObject("before").getAsJsonArray("slots").add(i == 54 ? stack : "empty");
            evidence.getAsJsonObject("expected").getAsJsonArray("slots").add(i == 0 ? stack : "empty");
        }
        journal.intent(evidence);
        journal.verified(evidence.getAsJsonObject("expected"), false);
        journal.complete();
        new PhysicalOperations(ledger).accept(agent, journal.current());
        var result = new JsonObject();
        result.addProperty("shulkers", 1);
        result.add("lines", JsonParser.parseString("[{\"key\":\"minecraft:diamond\",\"delivered\":64}]"));
        return orders.status(order.id, "delivered", null, null, null, result, agent, "physical-staging");
    }

    @Test
    void missingAnvilReadbackKeepsOrderFenced() throws Exception {
        var result = staged(null);
        assertEquals(OrderStage.NEEDS_RECONCILIATION, result.stage());
        assertEquals("needs_reconciliation", result.status);
        assertNotNull(result.manifest, "Preserve real staging evidence for operator reconciliation");
        assertFalse(PackageManifest.requiredLabelsVerified(result.manifest, result.names));
    }

    @Test
    void wrongPhysicalNameCannotBeReportedAsPickupReady() throws Exception {
        var result = staged("WeSort (1/1) - Fel projekt");
        assertEquals(OrderStage.NEEDS_RECONCILIATION, result.stage());
        assertFalse(PackageManifest.requiredLabelsVerified(result.manifest, result.names));
    }

    @Test
    void exactlyVerifiedStagedLabelMayReachPickupReady() throws Exception {
        var result = staged("WeSort (1/1) - Projekt A");
        assertEquals(OrderStage.PICKUP_READY, result.stage());
        assertEquals("packed", result.status);
        assertTrue(PackageManifest.requiredLabelsVerified(result.manifest, result.names));
        assertEquals(
                "WeSort (1/1) - Projekt A",
                result.manifest
                        .getAsJsonArray("packages")
                        .get(0)
                        .getAsJsonObject()
                        .get("verifiedName")
                        .getAsString());
    }
}
