package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.ShulkerUuid;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxJobsTest {
    @TempDir
    Path data;

    private static String namedComponents(String name) {
        return "{\"minecraft:custom_name\":\"" + name + "\"}";
    }

    private static JsonArray amounts(Map<String, Long> counts) {
        JsonArray out = new JsonArray();
        counts.forEach((key, count) -> {
            var row = new JsonObject();
            row.addProperty("id", "kit".equals(key) ? "minecraft:shulker_box" : key);
            row.addProperty("key", key);
            row.addProperty("count", count);
            out.add(row);
        });
        return out;
    }

    private JsonObject index(StockLedger ledger, String boxName) throws Exception {
        String components = boxName == null ? "{}" : namedComponents(boxName);
        var box = new PhysicalSlots.Item(
                "minecraft:shulker_box",
                "minecraft:shulker_box",
                1,
                components,
                PhysicalSlots.Kind.SHULKER,
                27,
                List.of(),
                "kit");
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box)));
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        root.add("items", new JsonArray());
        root.add("shulkers", new JsonArray());
        var identity =
                new ContainerIdentity("s", "minecraft:overworld", List.of(new ContainerIdentity.Position(0, 64, 0)));
        var source = new JsonObject();
        source.addProperty("id", identity.id());
        source.addProperty("dimension", "minecraft:overworld");
        var parts = new JsonArray();
        var pos = new JsonArray();
        pos.add(0);
        pos.add(64);
        pos.add(0);
        parts.add(pos);
        source.add("parts", parts);
        source.addProperty("kind", "minecraft:barrel");
        source.addProperty("size", 27);
        source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001");
        source.addProperty("sequence", ledger.revision() + 1);
        source.addProperty("epoch", ledger.epoch());
        source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", System.currentTimeMillis());
        source.addProperty("fingerprint", snapshot.fingerprint());
        source.addProperty("snbt", snapshot.snbt());
        source.add("items", amounts(snapshot.totals()));
        source.add("shulkers", amounts(snapshot.shulkers()));
        var sources = new JsonArray();
        sources.add(source);
        root.add("physicalSources", sources);
        root.addProperty("physicalVersion", 3);
        return root;
    }

    private static String sourceId(JsonObject index) {
        return index.getAsJsonArray("physicalSources")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    private JsonObject createRequest(String source, String kind) {
        var request = new JsonObject();
        request.addProperty("server", "s");
        request.addProperty("stash", "a");
        request.addProperty("source", source);
        request.addProperty("path", "0");
        request.addProperty("kind", kind);
        return request;
    }

    @Test
    void registerBoxJobMintsUuidAndCompletesOnlyAfterServerObservedRename() throws Exception {
        var ledger = new StockLedger(data);
        var unnamed = index(ledger, null);
        ledger.accept(unnamed);
        String source = sourceId(unnamed);

        var jobs = new BoxJobs(ledger);
        var created = jobs.create("owner", createRequest(source, "REGISTER_BOX"));
        assertEquals("PLANNED", created.get("state").getAsString());
        String uuid = created.get("uuid").getAsString();
        assertEquals("WeSort-" + uuid, created.get("name").getAsString());
        assertEquals(uuid, ShulkerUuid.parse(created.get("name").getAsString()).toString());
        assertNotNull(UUID.fromString(uuid));

        String agent = UUID.randomUUID().toString();
        var claimed = jobs.claim("s", "a", agent);
        assertEquals("IN_PROGRESS", claimed.get("state").getAsString());
        assertEquals(uuid, claimed.get("uuid").getAsString());
        String token = claimed.get("token").getAsString();

        var renamed = jobs.advance(claimed.get("id").getAsString(), agent, token, "renamed", "");
        assertEquals("VERIFYING", renamed.get("state").getAsString());

        // Completion before a fresh observation that carries the issued UUID is refused.
        var premature = jobs.advance(claimed.get("id").getAsString(), agent, token, "complete", "");
        assertEquals("VERIFYING", premature.get("state").getAsString());
        assertTrue(premature.has("message"));

        // The agent renames the box and the next fresh observation carries the UUID; the registry registers it.
        var named = index(ledger, "WeSort-" + uuid);
        ledger.accept(named);
        assertEquals(
                "REGISTERED",
                ledger.boxIdentities(source)
                        .get(0)
                        .getAsJsonObject()
                        .get("identityStatus")
                        .getAsString());

        var completed = jobs.advance(claimed.get("id").getAsString(), agent, token, "complete", "");
        assertEquals("COMPLETED", completed.get("state").getAsString());
        assertEquals(
                uuid, ledger.boxUuids().get(0).getAsJsonObject().get("uuid").getAsString());
    }

    @Test
    void rejectsAlreadyNamedBoxAndRestartFencesInFlightJobs() throws Exception {
        var ledger = new StockLedger(data);
        var named = index(ledger, "WeSort-550e8400-e29b-41d4-a716-446655440000");
        ledger.accept(named);
        var jobs = new BoxJobs(ledger);
        assertThrows(
                IllegalArgumentException.class,
                () -> jobs.create("owner", createRequest(sourceId(named), "REGISTER_BOX")));

        // A claimed job is fenced by a Manager restart, never silently reassigned.
        var fresh = new StockLedger(data);
        fresh.accept(index(fresh, null));
        var freshJobs = new BoxJobs(fresh);
        var created = freshJobs.create("owner", createRequest(sourceId(index(fresh, null)), "RETURN_INTAKE"));
        String agent = UUID.randomUUID().toString();
        var claimed = freshJobs.claim("s", "a", agent);
        assertEquals("IN_PROGRESS", claimed.get("state").getAsString());

        var restarted = new BoxJobs(fresh); // constructor fences interrupted jobs
        var listed = restarted.list("s");
        assertEquals(
                "NEEDS_RECONCILIATION",
                listed.get(0).getAsJsonObject().get("state").getAsString());
    }
}
