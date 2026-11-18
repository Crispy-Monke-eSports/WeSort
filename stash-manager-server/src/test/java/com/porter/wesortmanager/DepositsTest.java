package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.data.Deposit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DepositsTest {
    @TempDir Path data;

    private static String id() { return UUID.randomUUID().toString(); }

    private static JsonObject snapshot() {
        var chests = new com.google.gson.JsonArray();
        var chest = new JsonObject();
        chest.addProperty("x", 10);
        chest.addProperty("y", 64);
        chest.addProperty("z", 20);
        chest.addProperty("slot", 0);
        chest.addProperty("item", "minecraft:dirt");
        chests.add(chest);
        var out = new JsonObject();
        out.add("chests", chests);
        return out;
    }

    @Test void beginIsIdempotentAndConfirmRequiresASnapshot() throws Exception {
        var deposits = new Deposits(data);
        String packageId = id();
        var first = deposits.begin(packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertEquals(first, deposits.begin(packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1"), "replay returns the same deposit");

        // A different order/kind/destination for the same package is refused.
        assertThrows(Deposits.Conflict.class, () -> deposits.begin(packageId, "o-2", Deposit.Kind.DELIVERY, "chest-a", "agent-1"));
        assertThrows(Deposits.Conflict.class, () -> deposits.begin(packageId, "o-1", Deposit.Kind.TRANSFER, "chest-a", "agent-1"));

        // No snapshot -> cannot reach DELIVERED.
        assertThrows(Deposits.Conflict.class, () -> deposits.confirm(packageId, "agent-1", null));
        assertThrows(Deposits.Conflict.class, () -> deposits.confirm(packageId, "agent-1", new JsonObject()));
        assertEquals(Deposit.State.DEPOSITING, deposits.get(packageId).state());
    }

    @Test void deliveredOnlyAfterConfirmedContentAndReplayCannotDoubleDeliver() throws Exception {
        var deposits = new Deposits(data);
        String packageId = id();
        deposits.begin(packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        var delivered = deposits.confirm(packageId, "agent-1", snapshot());
        assertEquals(Deposit.State.DELIVERED, delivered.state());
        assertEquals(2, delivered.revision());

        // A replayed confirmation is a no-op: same record, no second event, no double delivery.
        var replay = deposits.confirm(packageId, "agent-1", snapshot());
        assertEquals(delivered, replay);
        assertEquals(2, deposits.list("o-1").get(0).getAsJsonObject().getAsJsonArray("events").size());
    }

    @Test void aSecondAgentCannotConfirmAndUncertaintyReconciles() throws Exception {
        var deposits = new Deposits(data);
        String packageId = id();
        deposits.begin(packageId, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        assertThrows(Deposits.Conflict.class, () -> deposits.confirm(packageId, "agent-2", snapshot()));
        assertEquals(Deposit.State.NEEDS_RECONCILIATION, deposits.reconcile(packageId, "agent-2", "lost").state());
        assertEquals(Deposit.State.NEEDS_RECONCILIATION, deposits.reconcile(packageId, "agent-2", "lost").state(), "reconcile is idempotent");
    }

    @Test void deliveryAndTransferAreDistinguishedInTheLedger() throws Exception {
        var deposits = new Deposits(data);
        String a = id(), b = id();
        deposits.begin(a, "o-1", Deposit.Kind.DELIVERY, "chest-a", "agent-1");
        deposits.begin(b, "o-1", Deposit.Kind.TRANSFER, "server/stash", "agent-1");
        deposits.confirm(a, "agent-1", snapshot());
        deposits.confirm(b, "agent-1", snapshot());

        var listed = deposits.list("o-1");
        assertEquals(2, listed.size());
        boolean sawWithdrawal = false, sawRelocation = false;
        for (var e : listed) {
            var o = e.getAsJsonObject();
            if (o.get("kind").getAsString().equals("DELIVERY")) { sawWithdrawal = true; assertEquals("withdrawal", o.get("netEffect").getAsString()); }
            if (o.get("kind").getAsString().equals("TRANSFER")) { sawRelocation = true; assertEquals("relocation", o.get("netEffect").getAsString()); }
        }
        assertTrue(sawWithdrawal && sawRelocation, "both kinds must appear");
    }

    @Test void depositsPersistAcrossRestart() throws Exception {
        var deposits = new Deposits(data);
        String packageId = id();
        deposits.begin(packageId, "o-1", Deposit.Kind.TRANSFER, "server/stash", "agent-1");
        deposits.confirm(packageId, "agent-1", snapshot());

        var reloaded = new Deposits(data);
        assertEquals(Deposit.State.DELIVERED, reloaded.get(packageId).state());
        assertEquals(2, reloaded.list("o-1").get(0).getAsJsonObject().getAsJsonArray("events").size());
    }
}
