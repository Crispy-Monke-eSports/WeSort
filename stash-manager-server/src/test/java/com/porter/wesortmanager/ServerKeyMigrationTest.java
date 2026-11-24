package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Changing "s" to "s:25565" must not orphan existing books, leases or journals. */
class ServerKeyMigrationTest {
    @TempDir
    Path data;

    StockLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        ledger = new StockLedger(data);
    }

    private static JsonObject order(String id, String server, long updatedAt, String state) {
        var o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("server", server);
        o.addProperty("stash", "a");
        o.addProperty("state", state);
        o.addProperty("status", "pending");
        o.addProperty("updatedAt", updatedAt);
        return o;
    }

    private static String book(String server, long revision, JsonObject... orders) {
        var root = new JsonObject();
        root.addProperty("server", server);
        root.addProperty("stash", "a");
        var list = new JsonArray();
        for (var o : orders) list.add(o);
        root.add("orders", list);
        root.add("archived", new JsonArray());
        root.add("receipts", new JsonObject());
        return root.toString();
    }

    private void insertBook(String server, long revision, String payload) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("INSERT INTO order_books(server,stash,revision,payload) VALUES(?,?,?,?)")) {
            p.setString(1, server);
            p.setString(2, "a");
            p.setLong(3, revision);
            p.setString(4, payload);
            p.executeUpdate();
        }
    }

    private Set<String> bookServers() throws Exception {
        var out = new HashSet<String>();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var rows = c.createStatement().executeQuery("SELECT DISTINCT server FROM order_books")) {
            while (rows.next()) out.add(rows.getString(1));
        }
        return out;
    }

    private JsonObject bookPayload(String server, String stash) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("SELECT payload FROM order_books WHERE server=? AND stash=?")) {
            p.setString(1, server);
            p.setString(2, stash);
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next());
                return JsonParser.parseString(rows.getString(1)).getAsJsonObject();
            }
        }
    }

    @Test
    void changingSpellingKeepsEveryOrderVisible() throws Exception {
        insertBook("s", 1, book("s", 1, order("o-1", "s", 100, "RESERVED")));
        insertBook("s:25565", 1, book("s:25565", 1, order("o-2", "s:25565", 200, "RESERVED")));
        String summary = ServerKeyMigration.reconcile(ledger, "s:25565");
        assertFalse(summary.isEmpty(), "expected a merge report, got empty");
        assertEquals(Set.of("s"), bookServers());
        var merged = bookPayload("s", "a");
        assertEquals("s", merged.get("server").getAsString());
        var ids = new HashSet<String>();
        for (var e : merged.getAsJsonArray("orders")) {
            var o = e.getAsJsonObject();
            assertEquals("s", o.get("server").getAsString());
            ids.add(o.get("id").getAsString());
        }
        assertEquals(Set.of("o-1", "o-2"), ids);
    }

    @Test
    void newerDuplicateWinsAndRevisionAdvances() throws Exception {
        insertBook("s", 5, book("s", 5, order("o-1", "s", 100, "RESERVED")));
        insertBook("s:25565", 3, book("s:25565", 3, order("o-1", "s:25565", 300, "PACKING")));
        ServerKeyMigration.reconcile(ledger, "s");
        var merged = bookPayload("s", "a");
        assertEquals(1, merged.getAsJsonArray("orders").size());
        var kept = merged.getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("PACKING", kept.get("state").getAsString());
        assertEquals("s", kept.get("server").getAsString());
    }

    @Test
    void alreadyMergedDatabaseIsUntouched() throws Exception {
        insertBook("s", 1, book("s", 1, order("o-1", "s", 100, "RESERVED")));
        assertEquals("", ServerKeyMigration.reconcile(ledger, "s"));
        assertEquals("", ServerKeyMigration.reconcile(ledger, "other.org"));
        assertEquals("", ServerKeyMigration.reconcile(ledger, ""));
    }

    @Test
    void deadSpellingLeasesAreDropped() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "INSERT INTO automation_leases(server,agent,token,deadline,state) VALUES(?,?,?,?,?)")) {
            p.setString(1, "s:25565");
            p.setString(2, "agent");
            p.setString(3, "tok");
            p.setLong(4, 1);
            p.setString(5, "NEEDS_RECONCILIATION");
            p.executeUpdate();
        }
        ServerKeyMigration.reconcile(ledger, "s");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var rows = c.createStatement().executeQuery("SELECT COUNT(*) FROM automation_leases")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void journalServerFieldsFollowThePartition() throws Exception {
        var payload = new JsonObject();
        payload.addProperty("id", "j1");
        var context = new JsonObject();
        context.addProperty("server", "s:25565");
        payload.add("context", context);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "INSERT INTO physical_operations(id,server,agent,stash,revision,checksum,payload,state,last_seen,fence_revision) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, "j1");
            p.setString(2, "s:25565");
            p.setString(3, "agent");
            p.setString(4, "a");
            p.setLong(5, 1);
            p.setString(6, "c");
            p.setString(7, payload.toString());
            p.setString(8, "IN_PROGRESS");
            p.setLong(9, System.currentTimeMillis());
            p.setLong(10, 0);
            p.executeUpdate();
        }
        ServerKeyMigration.reconcile(ledger, "s");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("SELECT server,payload FROM physical_operations WHERE id='j1'")) {
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("s", rows.getString(1));
                assertEquals(
                        "s",
                        JsonParser.parseString(rows.getString(2))
                                .getAsJsonObject()
                                .getAsJsonObject("context")
                                .get("server")
                                .getAsString());
            }
        }
    }
}
