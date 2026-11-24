package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.inventory.PhysicalJournal;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalOperationsTest {
    @TempDir
    Path data;

    private final String agent = "00000000-0000-0000-0000-000000000001";

    private JsonObject frame(String item) {
        return JsonParser.parseString("{\"menu\":1,\"slots\":[\"" + item + "\",\"\"],\"cursor\":\"\"}")
                .getAsJsonObject();
    }

    private PhysicalJournal journal() throws Exception {
        var journal = new PhysicalJournal(data.resolve("client"));
        journal.start(JsonParser.parseString(
                        "{\"server\":\"s\",\"stash\":\"a\",\"dimension\":\"minecraft:overworld\",\"agent\":\"" + agent
                                + "\",\"kind\":\"DIRECT\",\"orderId\":\"o-test\"}")
                .getAsJsonObject());
        return journal;
    }

    private void intent(PhysicalJournal journal) throws Exception {
        var value = new JsonObject();
        value.add("origin", journal.current().getAsJsonObject("context"));
        value.add("before", frame("before"));
        value.add("expected", frame("after"));
        journal.intent(value);
    }

    private JsonObject playerInventory() {
        var snapshot = new com.porter.wesort.data.PhysicalSlots.Snapshot(46, java.util.List.of());
        var inventory = new JsonObject();
        inventory.addProperty("size", snapshot.size());
        inventory.addProperty("fingerprint", snapshot.fingerprint());
        inventory.add("slots", new JsonArray());
        inventory.add("slotVariants", new JsonArray());
        return inventory;
    }

    @Test
    void onlyAnExactCurrentInspectionCanReleaseTheOriginalSortingLease() throws Exception {
        var ledger = new StockLedger(data);
        var gate = new AutomationGate(ledger);
        var lease = gate.acquire("s", agent, null);
        var journal = new PhysicalJournal(data.resolve("fenced-client"));
        var context = journal().context();
        var fingerprint = com.porter.wesort.data.ContainerIdentity.hash(lease.token());
        context.addProperty("leaseFingerprint", fingerprint);
        journal.start(context);
        intent(journal);
        var store = new PhysicalOperations(ledger);
        store.accept(agent, journal.current());
        gate.finish("s", agent, lease.token(), false);
        journal.block("Pending native receipt");
        store.accept(agent, journal.current());
        var decision = store.inspect(journal.id(), journal.revision(), journal.checksum(), "owner");
        journal.verified(
                frame("after"),
                true,
                playerInventory(),
                java.util.UUID.randomUUID().toString());
        journal.complete();
        assertEquals(
                "NEEDS_RECONCILIATION",
                store.accept(agent, journal.current()).get("state").getAsString());
        assertNotNull(gate.current("s"));
        journal.verified(
                frame("after"), true, playerInventory(), decision.get("id").getAsString());
        journal.complete();
        var receipt = store.accept(agent, journal.current());
        assertEquals("COMPLETED", receipt.get("state").getAsString());
        assertEquals(fingerprint, receipt.get("releasedLease").getAsString());
        assertNull(gate.current("s"));
    }

    @Test
    void aliasedServerStorageKeyDoesNotRewriteJournalChecksum() throws Exception {
        var ledger = new StockLedger(data);
        var store = new PhysicalOperations(ledger);
        var journal = journal();
        var record = journal.current();
        var checksum = record.get("checksum");
        assertEquals(checksum, store.accept(agent, record, "canonical").get("checksum"));
        assertEquals(checksum, store.accept(agent, record, "canonical").get("checksum"));
        assertEquals("s", record.getAsJsonObject("context").get("server").getAsString());
        try (var connection = ledger.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT server,payload FROM physical_operations")) {
            assertTrue(rows.next());
            assertEquals("canonical", rows.getString(1));
            assertEquals(checksum, JsonParser.parseString(rows.getString(2)).getAsJsonObject().get("checksum"));
        }
    }

    @Test
    void durableMirroringIsIdempotentAppendOnlyAndCannotInventStock() throws Exception {
        var ledger = new StockLedger(data);
        var store = new PhysicalOperations(ledger);
        var journal = journal();
        var first = journal.current();
        assertEquals(first.get("checksum"), store.accept(agent, first).get("checksum"));
        store.accept(agent, first);
        intent(journal);
        store.accept(agent, journal.current());
        assertThrows(PhysicalOperations.Conflict.class, () -> store.accept(agent, first));
        assertEquals(0, ledger.revision());
        assertTrue(ledger.sources().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.accept("00000000-0000-0000-0000-000000000002", journal.current()));
        var corrupt = journal.current();
        corrupt.addProperty("state", "COMPLETED");
        assertThrows(IllegalArgumentException.class, () -> store.accept(agent, corrupt));
    }

    @Test
    void managerRestartFencesActiveWorkUntilAnExplicitCurrentInspection() throws Exception {
        var ledger = new StockLedger(data);
        var store = new PhysicalOperations(ledger);
        var journal = journal();
        intent(journal);
        store.accept(agent, journal.current());
        var restarted = new PhysicalOperations(new StockLedger(data));
        String id = journal.current().get("id").getAsString();
        assertEquals(
                "NEEDS_RECONCILIATION",
                restarted.accept(agent, journal.current()).get("state").getAsString());
        var local = new PhysicalJournal(data.resolve("client"));
        restarted.accept(agent, local.current());
        var review = local.current();
        var request = restarted.inspect(
                id, review.get("revision").getAsLong(), review.get("checksum").getAsString(), "owner");
        assertEquals(
                request.get("id"),
                restarted
                        .inspect(
                                id,
                                review.get("revision").getAsLong(),
                                review.get("checksum").getAsString(),
                                "owner")
                        .get("id"));
        assertNotNull(restarted.decision(agent, id));
        assertNull(restarted.decision("another-agent", id));
        local.verified(
                frame("after"), true, playerInventory(), request.get("id").getAsString());
        local.complete();
        assertEquals(
                "COMPLETED",
                restarted.accept(agent, local.current()).get("state").getAsString());
        restarted.decisionResult(agent, request.get("id").getAsString(), "COMPLETED", "Fresh exact source matched");
        assertNull(restarted.decision(agent, id));
    }

    @Test
    void staleReviewAndUninspectedOfflineResumeAreRejected() throws Exception {
        var ledger = new StockLedger(data);
        var clock = new AtomicLong(1000);
        var store = new PhysicalOperations(ledger, clock::get);
        var journal = journal();
        intent(journal);
        store.accept(agent, journal.current());
        var record = journal.current();
        String id = record.get("id").getAsString();
        assertThrows(
                PhysicalOperations.Conflict.class,
                () -> store.inspect(id, 1, record.get("checksum").getAsString(), "owner"));
        clock.addAndGet(15_000);
        assertEquals(
                "RECOVERING",
                store.list().get(0).getAsJsonObject().get("managerState").getAsString());
        journal.verified(frame("after"), false);
        journal.complete();
        assertEquals(
                "NEEDS_RECONCILIATION",
                store.accept(agent, journal.current()).get("state").getAsString());
    }

    @Test
    void fencedJournalRequestsReadOnlyInspectionWithoutOwner() throws Exception {
        var ledger = new StockLedger(data);
        var operations = new PhysicalOperations(ledger);
        var local = journal();
        intent(local);
        local.block("Agent restarted during a physical move");
        var receipt = operations.accept(agent, local.current());

        var decision = operations.inspectAutomatically(agent, receipt);
        assertNotNull(decision);
        assertEquals("INSPECT", decision.get("action").getAsString());
        assertEquals(receipt.get("revision"), decision.get("revision"));
        assertEquals(receipt.get("checksum"), decision.get("checksum"));
        var replay = operations.decision(agent, local.id());
        assertEquals(decision.get("id"), replay.get("id"));
        assertEquals(decision.get("revision"), replay.get("revision"));
        assertEquals(decision.get("checksum"), replay.get("checksum"));
        assertNull(operations.inspectAutomatically(agent, receipt)); // No duplicate pending request.

        local.verified(
                frame("after"), true, playerInventory(), decision.get("id").getAsString());
        local.complete();
        var finished = operations.accept(agent, local.current());
        assertEquals("COMPLETED", finished.get("state").getAsString());
        assertNull(operations.inspectAutomatically(agent, finished));
    }

    @Test
    void obsoleteRequestCannotBlockNewRevision() throws Exception {
        var operations = new PhysicalOperations(new StockLedger(data));
        var local = journal();
        intent(local);
        local.block("Inspect original slot");
        var receipt = operations.accept(agent, local.current());
        var old = operations.inspectAutomatically(agent, receipt);
        assertNotNull(old);

        // A local readback with no valid Manager decision is not sufficient to release the fence.
        local.verified(
                frame("after"),
                true,
                playerInventory(),
                java.util.UUID.randomUUID().toString());
        local.complete();
        var current = operations.accept(agent, local.current());
        assertEquals("NEEDS_RECONCILIATION", current.get("state").getAsString());
        assertNull(operations.decision(agent, local.id()));
        var next = operations.inspectAutomatically(agent, current);
        assertNotNull(next);
        assertNotEquals(old.get("id"), next.get("id"));
        assertEquals(current.get("revision"), next.get("revision"));
        assertEquals(current.get("checksum"), next.get("checksum"));

        // A late outcome for the old request must not replace the new decision.
        operations.decisionResult(agent, old.get("id").getAsString(), "COMPLETED", "Late outcome");
        assertEquals(next.get("id"), operations.decision(agent, local.id()).get("id"));
        local.verified(frame("after"), true, playerInventory(), next.get("id").getAsString());
        local.complete();
        assertEquals(
                "COMPLETED",
                operations.accept(agent, local.current()).get("state").getAsString());
        assertNull(operations.decision(agent, local.id()));
    }

    @Test
    void completionWithoutAcceptedProofDoesNotPermanentlyBlockRecovery() throws Exception {
        var clock = new AtomicLong(10_000);
        var operations = new PhysicalOperations(new StockLedger(data), clock::get);
        var local = journal();
        intent(local);
        local.block("Inspect original slot");
        var receipt = operations.accept(agent, local.current());
        var first = operations.inspectAutomatically(agent, receipt);
        assertNotNull(first);
        operations.decisionResult(agent, first.get("id").getAsString(), "COMPLETED", "No journal proof");
        assertNull(operations.inspectAutomatically(agent, receipt));

        clock.addAndGet(60_001);
        var next = operations.inspectAutomatically(agent, receipt);
        assertNotNull(next);
        assertNotEquals(first.get("id"), next.get("id"));
        assertEquals(
                "RECOVERING",
                operations.list().get(0).getAsJsonObject().get("managerState").getAsString());
    }

    @Test
    void failedAutomaticInspectionRetriesWithBackoff() throws Exception {
        var clock = new AtomicLong(10_000);
        var operations = new PhysicalOperations(new StockLedger(data), clock::get);
        var local = journal();
        intent(local);
        local.block("Inspect original slot");
        var receipt = operations.accept(agent, local.current());

        var first = operations.inspectAutomatically(agent, receipt);
        assertNotNull(first);
        operations.decisionResult(agent, first.get("id").getAsString(), "FAILED", "Bot is away from the stash");
        assertNull(operations.inspectAutomatically(agent, receipt));
        clock.addAndGet(60_001);
        var again = operations.inspectAutomatically(agent, receipt);
        assertNotNull(again);
        assertNotEquals(first.get("id").getAsString(), again.get("id").getAsString());
    }
}
