package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalJournalTest {
    @TempDir
    Path directory;

    private JsonObject context() {
        return JsonParser.parseString(
                        "{\"server\":\"private-test-server\",\"dimension\":\"minecraft:overworld\",\"agent\":\"00000000-0000-"
                                + "0000-0000-000000000001\",\"kind\":\"KIT\",\"jobId\":\"test-job\",\"orderId\":\"test-order\"}")
                .getAsJsonObject();
    }

    private JsonObject frame(int menu, String stack) {
        var frame = new JsonObject();
        frame.addProperty("menu", menu);
        var slots = new JsonArray();
        slots.add(stack);
        slots.add("");
        frame.add("slots", slots);
        frame.addProperty("cursor", "");
        return frame;
    }

    private JsonObject intent() {
        var out = new JsonObject();
        out.add("origin", context());
        out.add("before", frame(5, "before"));
        out.add("expected", frame(5, "after"));
        return out;
    }

    @Test
    void durableIntentExistsBeforeMutationAndOnlyExactReceiptCanCommit() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        journal.phase("TRANSFER", new JsonObject());
        journal.intent(intent());
        var stored = PhysicalJournal.validate(JsonParser.parseString(Files.readString(directory.resolve("active.json")))
                .getAsJsonObject());
        assertEquals(
                "INTENT",
                stored.getAsJsonArray("events")
                        .get(stored.getAsJsonArray("events").size() - 1)
                        .getAsJsonObject()
                        .get("type")
                        .getAsString());
        assertTrue(journal.pending());
        assertThrows(java.io.IOException.class, journal::complete);
        assertThrows(java.io.IOException.class, () -> journal.verified(frame(5, "wrong variant"), false));
        journal.verified(frame(5, "after"), false);
        assertFalse(journal.pending());
        journal.complete();
        assertTrue(journal.terminal());
        assertEquals(
                "COMPLETED",
                new PhysicalJournal(directory).current().get("state").getAsString());
    }

    @Test
    void everyPhysicalPhaseIsFencedOnRestartAndCannotBlindlyRetry() throws Exception {
        for (String phase : List.of("SELECT", "PLACE", "OPEN", "TRANSFER", "VERIFY", "BREAK", "PICKUP", "STORE")) {
            Path folder = directory.resolve(phase);
            var journal = new PhysicalJournal(folder);
            journal.start(context());
            journal.phase(phase, new JsonObject());
            journal.intent(intent());
            var restarted = new PhysicalJournal(folder);
            assertTrue(restarted.blocked(), phase);
            assertTrue(restarted.pending());
            assertEquals(phase, restarted.current().get("interruptedPhase").getAsString());
            assertThrows(java.io.IOException.class, () -> restarted.intent(intent()));
            assertThrows(java.io.IOException.class, restarted::complete);
        }
    }

    @Test
    void explicitFreshSourcePostconditionResumesVerificationWithoutDuplicatingMutation() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        journal.intent(intent());
        var restarted = new PhysicalJournal(directory);
        long before = restarted.current().get("revision").getAsLong();
        assertThrows(java.io.IOException.class, () -> restarted.verified(frame(9, "after"), false));
        assertThrows(java.io.IOException.class, () -> restarted.verified(frame(9, "before"), true));
        restarted.verified(frame(9, "after"), true);
        restarted.complete();
        assertEquals("COMPLETED", restarted.current().get("state").getAsString());
        assertEquals(
                1,
                restarted.current().getAsJsonArray("events").asList().stream()
                        .filter(v ->
                                v.getAsJsonObject().get("type").getAsString().equals("INTENT"))
                        .count());
        assertEquals(before + 2, restarted.current().get("revision").getAsLong());
    }

    @Test
    void cannotReplaceUnresolvedWorkAndCompletedHistoryRetainsOrderCorrelation() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        assertThrows(java.io.IOException.class, () -> journal.start(context()));
        journal.intent(intent());
        journal.verified(frame(5, "after"), false);
        journal.complete();
        String first = journal.current().get("id").getAsString();
        journal.start(context());
        assertNotEquals(first, journal.current().get("id").getAsString());
        assertEquals(2, journal.records().size());
        assertTrue(journal.records().stream()
                .allMatch(r -> r.getAsJsonObject("context")
                        .get("orderId")
                        .getAsString()
                        .equals("test-order")));
    }

    @Test
    void alteredContextStateOrEventIsRejectedAndFileRemainsUnchanged() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        journal.intent(intent());
        for (String field : List.of("state", "context", "events")) {
            var corrupt = journal.current();
            if (field.equals("state")) corrupt.addProperty("state", "COMPLETED");
            else if (field.equals("context"))
                corrupt.getAsJsonObject("context").addProperty("server", "another-server");
            else corrupt.getAsJsonArray("events").get(0).getAsJsonObject().addProperty("type", "FORGED");
            String bytes = corrupt.toString();
            Files.writeString(directory.resolve("active.json"), bytes);
            assertThrows(java.io.IOException.class, () -> new PhysicalJournal(directory));
            assertEquals(bytes, Files.readString(directory.resolve("active.json")));
        }
    }

    private static JsonObject rehash(JsonObject value) {
        String previous = "";
        for (var entry : value.getAsJsonArray("events")) {
            var row = entry.getAsJsonObject();
            row.addProperty("previous", previous);
            row.remove("hash");
            previous =
                    com.porter.wesort.data.ContainerIdentity.hash(com.porter.wesort.data.CanonicalJson.stringify(row));
            row.addProperty("hash", previous);
        }
        value.remove("checksum");
        value.addProperty(
                "checksum",
                com.porter.wesort.data.ContainerIdentity.hash(com.porter.wesort.data.CanonicalJson.stringify(value)));
        return value;
    }

    @Test
    void validChecksumsCannotSubstituteForStateTransitions() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        var forged = journal.current();
        forged.addProperty("state", "COMPLETED");
        forged.addProperty("phase", "COMPLETED");
        assertThrows(IllegalArgumentException.class, () -> PhysicalJournal.validate(rehash(forged)));

        var unknown = journal.current();
        unknown.getAsJsonArray("events").get(0).getAsJsonObject().addProperty("type", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> PhysicalJournal.validate(rehash(unknown)));

        var fractional = journal.current();
        fractional.getAsJsonArray("events").get(0).getAsJsonObject().addProperty("seq", 1.5);
        assertThrows(IllegalArgumentException.class, () -> PhysicalJournal.validate(rehash(fractional)));
    }

    @Test
    void batchedIntentsAndRepeatInspectionRemainValid() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        journal.intent(intent());
        journal.intent(intent());
        journal.verified(frame(5, "after"), false);
        journal.complete();
        assertEquals(journal.current(), PhysicalJournal.validate(journal.current()));
        journal.verified(frame(9, "after"), true);
        journal.complete();
        assertEquals(journal.current(), PhysicalJournal.validate(journal.current()));
    }

    @Test
    void rejectedReceiptNamesWhichCheckFailed() throws Exception {
        var menuJournal = new PhysicalJournal(directory.resolve("menu"));
        menuJournal.start(context());
        menuJournal.intent(intent());
        var menuFailure =
                assertThrows(java.io.IOException.class, () -> menuJournal.verified(frame(9, "after"), false));
        org.junit.jupiter.api.Assertions.assertTrue(
                menuFailure.getMessage().contains("menu expected=5 actual=9"), menuFailure.getMessage());

        var cursorJournal = new PhysicalJournal(directory.resolve("cursor"));
        cursorJournal.start(context());
        var cursorIntent = intent();
        cursorIntent.getAsJsonObject("expected").addProperty("cursor", "held");
        cursorIntent.getAsJsonObject("before").addProperty("cursor", "");
        cursorJournal.intent(cursorIntent);
        var cursorFailure =
                assertThrows(java.io.IOException.class, () -> cursorJournal.verified(frame(5, "after"), false));
        org.junit.jupiter.api.Assertions.assertTrue(
                cursorFailure.getMessage().contains("cursor differs"), cursorFailure.getMessage());

        var slotsJournal = new PhysicalJournal(directory.resolve("slots"));
        slotsJournal.start(context());
        slotsJournal.intent(intent());
        var slotsFailure =
                assertThrows(java.io.IOException.class, () -> slotsJournal.verified(frame(5, "wrong"), false));
        org.junit.jupiter.api.Assertions.assertTrue(
                slotsFailure.getMessage().contains("slots differ at [0]"), slotsFailure.getMessage());
    }

    @Test
    void preconditionFailureIsDistinctFromUnknownPhysicalOutcome() throws Exception {
        var journal = new PhysicalJournal(directory);
        journal.start(context());
        journal.failBeforeMutation("No tool");
        assertEquals("FAILED", journal.current().get("state").getAsString());
        journal.start(context());
        journal.intent(intent());
        assertThrows(java.io.IOException.class, () -> journal.failBeforeMutation("Already transmitted"));
        journal.block("Missing server ACK");
        assertEquals(
                "NEEDS_RECONCILIATION",
                new PhysicalJournal(directory).current().get("state").getAsString());
    }
}
