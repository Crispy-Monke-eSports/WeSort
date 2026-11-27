package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.wesort.data.Handoff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#140): handoff authority in SQLite, committed in the same transaction
 * as the order book and the audit trail.
 */
class HandoffsSqliteTest {
    @TempDir
    Path data;

    private static final String A1 = "11111111-1111-1111-1111-111111111111";

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
    void sameTransactionCommitPersistsBookHandoffAndAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var handoffs = new Handoffs(ledger, clock());
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-1");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        var scheduled = new Handoff[1];
        OrderRepository.SqliteWrite handoffWrite = c -> {
            scheduled[0] = handoffs.schedule(c, "o-1", "alice", "meet-1", A1);
        };
        repo.commitExtended("s", "a", 1, root, null, trail.entries(), List.of(handoffWrite));

        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertNotNull(handoffs.get(scheduled[0].id()));
        assertEquals(Handoff.State.SCHEDULED, handoffs.get(scheduled[0].id()).state());
        assertEquals(1, audit.size());
        // Restart keeps book, handoffs and audit together.
        var restarted = new Handoffs(new StockLedger(data), clock());
        assertEquals(Handoff.State.SCHEDULED, restarted.get(scheduled[0].id()).state());
    }

    @Test
    void failedOrderCommitPersistsNeitherBookNorHandoffNorAudit() throws Exception {
        var ledger = new StockLedger(data);
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);

        var handoffs = new Handoffs(ledger, clock());
        var seedHandoff = handoffs.schedule("o-1", "alice", "meet-1", A1);
        var seedTrail = audit.batch();
        seedTrail.actor("u", "U", "addr");
        seedTrail.say("order.place", "o-1");
        var seed = book("s", "a");
        seed.getAsJsonArray("orders").add(order("o-1"));
        repo.commitExtended("s", "a", 1, seed, null, seedTrail.entries(), List.of());
        assertEquals(2, revisionOf(ledger, "s", "a"));
        assertNotNull(handoffs.get(seedHandoff.id()));
        assertEquals(1, audit.size());

        // The next commit writes the handoff and audit, then fails: everything rolls back.
        var trail = audit.batch();
        trail.actor("u", "U", "addr");
        trail.say("order.place", "o-2");
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        root.getAsJsonArray("orders").add(order("o-2"));
        var scheduled = new Handoff[1];
        OrderRepository.SqliteWrite failing = c -> {
            scheduled[0] = handoffs.schedule(c, "o-2", "bob", "meet-2", A1);
            AuditLog.addAll(c, trail.entries());
            throw new IOException("simulated handoff failure");
        };
        assertThrows(
                IOException.class,
                () -> repo.commitExtended("s", "a", 2, root, null, trail.entries(), List.of(failing)));

        assertEquals(2, revisionOf(ledger, "s", "a"), "book revision must not advance");
        assertNull(handoffs.get(scheduled[0].id()), "handoff write must roll back");
        assertNotNull(handoffs.get(seedHandoff.id()), "earlier handoff must survive");
        assertEquals(1, audit.size(), "audit write must roll back");
    }

    @Test
    void staleJsonFileIsIgnoredAndNeverRead() throws Exception {
        Files.writeString(data.resolve("handoffs.json"), "{\"schema\":1,\"handoffs\":[]}", StandardCharsets.UTF_8);
        // The legacy Path constructor shares guild.sqlite and ignores the stale file.
        var handoffs = new Handoffs(data, time::get);
        assertEquals(0, handoffs.list(null).size());
        var h = handoffs.schedule("o-1", "alice", "meet-1", A1);
        assertEquals(Handoff.State.SCHEDULED, handoffs.get(h.id()).state());
    }
}
