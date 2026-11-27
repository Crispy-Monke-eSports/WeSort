package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.wesort.data.DeliveryPoint;
import com.porter.wesort.data.Deposit;
import com.porter.wesort.data.Handoff;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rejected write must not become an in-memory success, including on a retry.
 * Custody, deposits and handoffs now fail closed through SQLite transactions:
 * a commit that throws rolls back the book, the domain rows and the audit
 * trail together.
 */
class DeliveryLedgerPersistenceTest {
    @TempDir
    Path data;

    private final FakeClock time = new FakeClock(1_000_000L);

    private FakeClock clock() {
        return time;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static JsonObject snapshot() {
        var snapshot = new JsonObject();
        snapshot.addProperty("destination", "chest-a");
        var chests = new JsonArray();
        chests.add(new JsonObject());
        snapshot.add("chests", chests);
        return snapshot;
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

    private Path obstruct(String name) throws IOException {
        Path file = data.resolve(name);
        Path saved = data.resolve(name + ".saved");
        Files.move(file, saved);
        Files.createDirectory(file);
        Files.writeString(file.resolve("obstruction"), "disk failure");
        return saved;
    }

    private void restore(String name, Path saved) throws IOException {
        Path file = data.resolve(name);
        Files.delete(file.resolve("obstruction"));
        Files.delete(file);
        Files.move(saved, file);
    }

    @Test
    void failedDepositConfirmationDoesNotBecomeAnIdempotentFalseSuccess() throws Exception {
        var ledger = new StockLedger(data);
        var store = new Deposits(ledger, clock());
        String pkg = id();
        store.begin(pkg, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        var before = store.list("o-1");

        // A commit that writes the confirmation and then fails rolls everything back.
        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        OrderRepository.SqliteWrite failing = c -> {
            store.confirm(c, pkg, "agent-1", snapshot());
            throw new IOException("simulated confirmation failure");
        };
        assertThrows(
                IOException.class, () -> repo.commitExtended("s", "a", 1, root, null, List.of(), List.of(failing)));
        assertEquals(Deposit.State.DEPOSITING, store.get(pkg).state());
        assertEquals(before, store.list("o-1"));
        assertThrows(
                IOException.class, () -> repo.commitExtended("s", "a", 1, root, null, List.of(), List.of(failing)));

        assertEquals(
                Deposit.State.DELIVERED,
                store.confirm(pkg, "agent-1", snapshot()).state());
        assertEquals(store.list("o-1"), new Deposits(new StockLedger(data), clock()).list("o-1"));
    }

    @Test
    void failedHandoffConfirmationKeepsTheCargoHeld() throws Exception {
        var ledger = new StockLedger(data);
        var store = new Handoffs(ledger, clock());
        String agent = id();
        var handoff = store.schedule("o-1", "alice", "meet-1", agent);
        store.arrive(handoff.id(), agent);
        var before = store.list("o-1");

        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        OrderRepository.SqliteWrite failing = c -> {
            if (!store.get(handoff.id()).state().equals(Handoff.State.AWAITING_RECIPIENT))
                throw new IllegalStateException("unexpected handoff state");
            throw new IOException("simulated confirmation failure");
        };
        assertThrows(
                IOException.class, () -> repo.commitExtended("s", "a", 1, root, null, List.of(), List.of(failing)));
        assertEquals(Handoff.State.AWAITING_RECIPIENT, store.get(handoff.id()).state());
        assertEquals(before, store.list("o-1"));

        assertEquals(
                Handoff.State.DELIVERED, store.confirm(handoff.id(), "owner").state());
        assertEquals(store.list("o-1"), new Handoffs(new StockLedger(data), clock()).list("o-1"));
    }

    @Test
    void failedCustodyPickupDoesNotAdvanceOrDuplicateRevision() throws Exception {
        var ledger = new StockLedger(data);
        var store = new PackageCustody(ledger, clock());
        String pkg = id();
        String agent = id();
        store.stage(pkg, "o-1", new JsonObject());
        var before = store.list("o-1");

        var audit = new AuditLog(ledger, data);
        audit.load();
        var repo = new OrderBookRepository(ledger);
        repo.commit("s", "a", 0, book("s", "a"), null);
        var root = book("s", "a");
        root.getAsJsonArray("orders").add(order("o-1"));
        OrderRepository.SqliteWrite failing = c -> {
            store.transition(c, pkg, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, null, null);
            throw new IOException("simulated pickup failure");
        };
        assertThrows(
                IOException.class, () -> repo.commitExtended("s", "a", 1, root, null, List.of(), List.of(failing)));
        assertEquals(PackageCustody.State.STAGED, store.get(pkg).state());
        assertEquals(before, store.list("o-1"));

        var picked = store.transition(pkg, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, null, null);
        assertEquals(2, picked.revision());
        assertEquals(store.list("o-1"), new PackageCustody(new StockLedger(data), clock()).list("o-1"));
    }

    @Test
    void failedDestinationUpdateDoesNotPublishNewRevision() throws Exception {
        var store = new DeliveryPoints(data);
        var request = new JsonObject();
        request.addProperty("server", "6b6t.org");
        request.addProperty("dimension", "minecraft:overworld");
        request.addProperty("x", 10);
        request.addProperty("y", 64);
        request.addProperty("z", 20);
        request.addProperty("type", "CHEST");
        request.addProperty("access", "PROJECT");
        request.addProperty("status", "VERIFIED");
        var point = store.create(request, "owner");
        var before = store.list();

        Path saved = obstruct("delivery-points.json");
        assertThrows(IOException.class, () -> store.pause(point.id(), point.revision(), "owner"));
        assertEquals(point.revision(), store.get(point.id()).revision());
        assertEquals(before, store.list());
        restore("delivery-points.json", saved);

        assertEquals(
                DeliveryPoint.Status.PAUSED,
                store.pause(point.id(), point.revision(), "owner").status());
        assertEquals(store.list(), new DeliveryPoints(data).list());
    }
}
