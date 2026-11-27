package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.wesort.data.Deposit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#140): deposit authority in SQLite, committed in the same transaction
 * as the order book and the audit trail.
 */
class DepositsSqliteTest {
    @TempDir
    Path data;

    private final FakeClock time = new FakeClock(1_000_000L);

    private FakeClock clock() {
        return time;
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

    private static JsonObject snapshot(String destination) {
        var chests = new JsonArray();
        var chest = new JsonObject();
        chest.addProperty("x", 1);
        chest.addProperty("y", 64);
        chest.addProperty("z", 2);
        chest.addProperty("slot", 0);
        chest.addProperty("item", "minecraft:dirt");
        chests.add(chest);
        var out = new JsonObject();
        out.addProperty("destination", destination);
        out.add("chests", chests);
        return out;
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
    void sameTransactionCommitPersistsBookDepositAndAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var deposits = new Deposits(ledger, clock());
        String packageId = UUID.randomUUID().toString();
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-1");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        JsonObject snap = snapshot("chest-a");
        OrderRepository.SqliteWrite depositWrite = c -> {
            deposits.begin(c, packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
            deposits.confirm(c, packageId, "agent-1", snap);
        };
        repo.commitExtended("s", "a", 1, root, null, trail.entries(), List.of(depositWrite));

        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertEquals(Deposit.State.DELIVERED, deposits.get(packageId).state());
        assertEquals(
                2,
                deposits.list("o-1")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("events")
                        .size());
        assertEquals(1, audit.size());
        // Restart keeps book, deposits and audit together.
        var restarted = new Deposits(new StockLedger(data), clock());
        assertEquals(Deposit.State.DELIVERED, restarted.get(packageId).state());
    }

    @Test
    void failedOrderCommitPersistsNeitherBookNorDepositNorAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var deposits = new Deposits(ledger, clock());
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
                List.of(c -> deposits.begin(c, goodId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1")));
        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertEquals(Deposit.State.DEPOSITING, deposits.get(goodId).state());
        assertEquals(1, audit.size());

        // The next commit writes the deposit and audit, then fails: everything rolls back.
        String badId = UUID.randomUUID().toString();
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-2");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        root.getAsJsonArray("orders").add(order("o-2"));
        JsonObject snap = snapshot("chest-b");
        OrderRepository.SqliteWrite failing = c -> {
            deposits.begin(c, badId, "o-2", Deposit.Kind.DELIVERY, "chest-b", "agent-1");
            deposits.confirm(c, badId, "agent-1", snap);
            AuditLog.addAll(c, trail.entries());
            throw new IOException("simulated deposit failure");
        };
        assertThrows(
                IOException.class,
                () -> repo.commitExtended("s", "a", 2, root, null, trail.entries(), List.of(failing)));

        assertEquals(2, revisionOf(ledger, "s", "a"), "book revision must not advance");
        assertNull(deposits.get(badId), "deposit write must roll back");
        assertEquals(Deposit.State.DEPOSITING, deposits.get(goodId).state(), "earlier deposit must survive");
        assertEquals(1, audit.size(), "audit write must roll back");
    }

    @Test
    void staleJsonFileIsIgnoredAndNeverRead() throws Exception {
        Files.writeString(data.resolve("deposits.json"), "{\"schema\":1,\"deposits\":[]}", StandardCharsets.UTF_8);
        // The legacy Path constructor shares guild.sqlite and ignores the stale file.
        var deposits = new Deposits(data, time);
        assertEquals(0, deposits.list(null).size());
        String packageId = UUID.randomUUID().toString();
        deposits.begin(packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertEquals(Deposit.State.DEPOSITING, deposits.get(packageId).state());
    }
}
