package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#228/#267): normalized SNBT authority is written on the same
 * transaction as the legacy rows, deduplicates exact variants and rolls back
 * with the observation when validation fails.
 */
class SnbtStoreTest {
    @TempDir
    Path data;

    private JsonObject observation(StockLedger ledger, int slot, int x) throws Exception {
        var frame = new com.porter.wesort.data.PhysicalSlots.Snapshot(
                27,
                List.of(new com.porter.wesort.data.PhysicalSlots.Slot(
                        slot,
                        7,
                        new com.porter.wesort.data.PhysicalSlots.Item(
                                "minecraft:diamond",
                                "minecraft:diamond",
                                64,
                                "{\"minecraft:custom_data\":{exact:9007199254740993L}}",
                                com.porter.wesort.data.PhysicalSlots.Kind.ITEM,
                                0,
                                List.of(),
                                ""))));
        var root = JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":7}],"shulkers":[]}
                """).getAsJsonObject();
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision() + 1, x);
        root.addProperty("physicalVersion", 3);
        var source = root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        return root;
    }

    private long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var q = c.createStatement();
                var r = q.executeQuery(sql)) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    @Test
    void authorityRowsPersistAndVariantsDeduplicate() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(observation(ledger, 26, 0));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("SELECT content_snbt,sha256 FROM snbt_snapshots")) {
            try (var r = p.executeQuery()) {
                assertTrue(r.next(), "authoritative SNBT must be persisted at rest");
                assertEquals(64, r.getString(2).length());
                assertTrue(r.getString(1).contains("9007199254740993L"));
                assertFalse(r.next(), "one snapshot per distinct fingerprint");
            }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM variant_register"));
        assertEquals(1, count("SELECT COUNT(*) FROM slot_projection WHERE present=1"));
        // Re-observe the same variant at a second source: descriptor stays single, positions grow.
        var second = observation(ledger, 25, 1);
        var src = second.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        ledger.accept(second);
        assertEquals(1, count("SELECT COUNT(*) FROM variant_register"), "exact variants deduplicate");
        assertEquals(2, count("SELECT COUNT(*) FROM slot_projection WHERE present=1"));
        // Variant lookups resolve through the index, not a scan.
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "EXPLAIN QUERY PLAN SELECT source_id FROM slot_projection WHERE variant_id=1")) {
            try (var r = p.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (r.next()) plan.append(r.getString(4)).append(';');
                assertTrue(plan.toString().contains("USING"), "variant lookup must use an index: " + plan);
            }
        }
        assertEquals(src.get("fingerprint").getAsString().length(), 64);
    }

    @Test
    void corruptObservationRollsBackNormalizedRows() throws Exception {
        var ledger = new StockLedger(data);
        var bad = observation(ledger, 26, 0);
        bad.getAsJsonArray("physicalSources").get(0).getAsJsonObject().addProperty("snbt", "{broken:");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(bad));
        assertEquals(0, count("SELECT COUNT(*) FROM snbt_snapshots"));
        assertEquals(0, count("SELECT COUNT(*) FROM variant_register"));
        assertEquals(0, count("SELECT COUNT(*) FROM slot_projection"));
        assertEquals(0, ledger.revision(), "failed validation commits nothing");
    }

    @Test
    void variantDigestCollisionWithDifferentBytesFailsClosed() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(observation(ledger, 26, 0));
        assertEquals(1, count("SELECT COUNT(*) FROM variant_register"));
        // Plant hostile bytes under the registered digest: reuse must verify, not trust.
        String hostile =
                "{\"components\":{},\"group\":\"\",\"id\":\"minecraft:stone\",\"key\":\"minecraft:stone\",\"kind\":\"ITEM\",\"maxStack\":64,\"size\":0}";
        try (var c = ledger.open();
                var u = c.prepareStatement("UPDATE variant_register SET canonical_variant_snbt=?")) {
            u.setString(1, hostile);
            assertEquals(1, u.executeUpdate());
            var item = new PhysicalSlots.Item(
                    "minecraft:diamond",
                    "minecraft:diamond",
                    64,
                    "{\"minecraft:custom_data\":{exact:9007199254740993L}}",
                    PhysicalSlots.Kind.ITEM,
                    0,
                    List.of(),
                    "");
            assertThrows(IllegalArgumentException.class, () -> SnbtStore.variantId(c, item));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM variant_register"), "failed reuse registers nothing");
    }
}
