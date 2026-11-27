package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#140): custody authority in SQLite, committed in the same transaction
 * as the order book and the audit trail.
 */
class CustodySqliteTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(1_000_000L);

    private Clock clock() {
        return new Clock() {
            @Override
            public long millis() {
                return time.get();
            }

            @Override
            public long nano() {
                return time.get() * 1_000_000L;
            }
        };
    }

    private static JsonObject book(String server, String stash) {
        var root = new JsonObject();
        root.addProperty("server", server);
        root.addProperty("stash", stash);
        root.add("orders", new JsonArray());
        return root;
    }

    private static JsonObject order(String id) {
        var o = new JsonObject();
        o.addProperty("id", id);
        return o;
    }

    private static long revisionOf(StockLedger ledger, String server, String stash) throws Exception {
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT revision FROM order_books WHERE server=? AND stash=?")) {
            p.setString(1, server);
            p.setString(2, stash);
            try (var rows = p.executeQuery()) {
                assertTrue(rows.next(), "order book must exist");
                return rows.getLong(1);
            }
        }
    }

    @Test
    void sameTransactionCommitPersistsBookCustodyAndAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var custody = new PackageCustody(ledger, clock());
        String packageId = UUID.randomUUID().toString();
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-1");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        OrderRepository.SqliteWrite custodyWrite =
                c -> custody.stage(c, packageId, "o-1", null, null, null, null, new JsonObject());
        repo.commitExtended("s", "a", 1, root, null, trail.entries(), List.of(custodyWrite));

        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertNotNull(custody.get(packageId));
        assertEquals("o-1", custody.get(packageId).orderId());
        assertEquals(1, audit.size());
        assertEquals(
                "order.place",
                audit.newest(1).get(0).getAsJsonObject().get("action").getAsString());
        // Restart keeps book, custody and audit together.
        var restarted = new PackageCustody(new StockLedger(data), clock());
        assertNotNull(restarted.get(packageId));
    }

    @Test
    void failedOrderCommitPersistsNeitherBookNorCustodyNorAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var custody = new PackageCustody(ledger, clock());
        String goodId = UUID.randomUUID().toString();
        var seedTrail = audit.batch();
        seedTrail.actor("u", "U", "addr");
        seedTrail.say("order.place", "o-1");
        var seed = book("s", "a");
        seed.getAsJsonArray("orders").add(order("o-1"));
        repo.commitExtended(
                "s",
                "a",
                1,
                seed,
                null,
                seedTrail.entries(),
                List.of(c -> custody.stage(c, goodId, "o-1", null, null, null, null, new JsonObject())));
        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertNotNull(custody.get(goodId));
        assertEquals(1, audit.size());

        // The next commit writes custody and audit, then fails: everything rolls back.
        String badId = UUID.randomUUID().toString();
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-2");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        root.getAsJsonArray("orders").add(order("o-2"));
        OrderRepository.SqliteWrite failing = c -> {
            custody.stage(c, badId, "o-2", null, null, null, null, new JsonObject());
            AuditLog.addAll(c, trail.entries());
            throw new IOException("simulated custody failure");
        };
        assertThrows(
                IOException.class,
                () -> repo.commitExtended("s", "a", 2, root, null, trail.entries(), List.of(failing)));

        assertEquals(2, revisionOf(ledger, "s", "a"), "book revision must not advance");
        assertNull(custody.get(badId), "custody write must roll back");
        assertNotNull(custody.get(goodId), "earlier custody must survive");
        assertEquals(1, audit.size(), "audit write must roll back");
    }

    @Test
    void staleJsonFileIsIgnoredAndNeverRead() throws Exception {
        Files.writeString(data.resolve("custody.json"), "{\"schema\":1,\"packages\":[]}", StandardCharsets.UTF_8);
        // The legacy Path constructor shares guild.sqlite and ignores the stale file.
        var custody = new PackageCustody(data, time::get);
        assertEquals(0, custody.list(null).size());
        String packageId = UUID.randomUUID().toString();
        custody.stage(packageId, "o-1", new JsonObject());
        assertNotNull(custody.get(packageId));
    }
}
