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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A rejected write must not become an in-memory success, including on a retry. */
class DeliveryLedgerPersistenceTest {
    @TempDir
    Path data;

    private static String id() {
        return UUID.randomUUID().toString();
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
        var store = new Deposits(data);
        String pkg = id();
        store.begin(pkg, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        var before = store.list("o-1");
        var snapshot = new JsonObject();
        snapshot.addProperty("destination", "chest-a");
        var chests = new JsonArray();
        chests.add(new JsonObject());
        snapshot.add("chests", chests);

        Path saved = obstruct("deposits.json");
        assertThrows(IOException.class, () -> store.confirm(pkg, "agent-1", snapshot));
        assertEquals(Deposit.State.DEPOSITING, store.get(pkg).state());
        assertEquals(before, store.list("o-1"));
        assertThrows(IOException.class, () -> store.confirm(pkg, "agent-1", snapshot));
        restore("deposits.json", saved);

        assertEquals(Deposit.State.DELIVERED, store.confirm(pkg, "agent-1", snapshot).state());
        assertEquals(store.list("o-1"), new Deposits(data).list("o-1"));
    }

    @Test
    void failedHandoffConfirmationKeepsTheCargoHeld() throws Exception {
        var store = new Handoffs(data);
        String agent = id();
        var handoff = store.schedule("o-1", "alice", "meet-1", agent);
        store.arrive(handoff.id(), agent);
        var before = store.list("o-1");

        Path saved = obstruct("handoffs.json");
        assertThrows(IOException.class, () -> store.confirm(handoff.id(), "owner"));
        assertEquals(Handoff.State.AWAITING_RECIPIENT, store.get(handoff.id()).state());
        assertEquals(before, store.list("o-1"));
        restore("handoffs.json", saved);

        assertEquals(Handoff.State.DELIVERED, store.confirm(handoff.id(), "owner").state());
        assertEquals(store.list("o-1"), new Handoffs(data).list("o-1"));
    }

    @Test
    void failedCustodyPickupDoesNotAdvanceOrDuplicateRevision() throws Exception {
        var store = new PackageCustody(data);
        String pkg = id();
        String agent = id();
        store.stage(pkg, "o-1", new JsonObject());
        var before = store.list("o-1");

        Path saved = obstruct("custody.json");
        assertThrows(
                IOException.class,
                () -> store.transition(pkg, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, null, null));
        assertEquals(PackageCustody.State.STAGED, store.get(pkg).state());
        assertEquals(before, store.list("o-1"));
        restore("custody.json", saved);

        var picked = store.transition(pkg, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, null, null);
        assertEquals(2, picked.revision());
        assertEquals(store.list("o-1"), new PackageCustody(data).list("o-1"));
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

        assertEquals(DeliveryPoint.Status.PAUSED, store.pause(point.id(), point.revision(), "owner").status());
        assertEquals(store.list(), new DeliveryPoints(data).list());
    }
}
