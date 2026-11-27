package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockLedgerTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(1000);

    private StockLedger ledger() throws Exception {
        return new StockLedger(data, time::get, 100);
    }

    private JsonObject index(String stash, long count) {
        return JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"" + stash
                        + "\",\"receivedAt\":1000,\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":"
                        + count + "}]}")
                .getAsJsonObject();
    }

    private JsonObject observation(StockLedger ledger, String stash, long count, long base, long sequence, int x) {
        return StockFixtures.physical(index(stash, count), ledger.epoch(), base, sequence, x);
    }

    private JsonObject source(JsonObject index) {
        return index.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }

    @Test
    void replayCannotRenewAgeOrChangePhysicalRevision() throws Exception {
        var l = ledger();
        var a = observation(l, "a", 64, 0, 1, 0);
        l.accept(a);
        long revision = l.sources().getFirst().revision();
        time.set(1099);
        l.accept(a);
        assertEquals(1000, l.sources().getFirst().verifiedAt());
        assertEquals(revision, l.sources().getFirst().revision());
        time.set(1100);
        assertEquals("STALE_AGE", l.sources().getFirst().status());
    }

    @Test
    void reusedProofCannotChangeQuantities() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        assertThrows(StockLedger.Conflict.class, () -> l.accept(observation(l, "a", 63, 0, 1, 0)));
        assertEquals(
                64,
                l.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
    }

    @Test
    void olderSourceRevisionCannotOverwriteNewerRead() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        long current = l.revision();
        l.accept(observation(l, "a", 32, current, 2, 0));
        assertThrows(StockLedger.Conflict.class, () -> l.accept(observation(l, "a", 99, current, 3, 0)));
        assertEquals(
                32,
                l.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
    }

    @Test
    void restartRequiresNewReadEvenWhenAgentReplaysCachedProof() throws Exception {
        var first = ledger();
        var a = observation(first, "a", 64, 0, 1, 0);
        first.accept(a);
        var restarted = ledger();
        assertEquals("STALE_STARTUP", restarted.sources().getFirst().status());
        assertThrows(StockLedger.Conflict.class, () -> restarted.accept(a));
        restarted.accept(observation(restarted, "a", 64, restarted.revision(), 2, 0));
        assertEquals("FRESH", restarted.sources().getFirst().status());
    }

    @Test
    void partialFailureRollsBackWholeBatchAndRevision() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        long before = l.revision();
        var batch = observation(l, "a", 1, before, 2, 4);
        batch.getAsJsonArray("physicalSources").add(source(observation(l, "a", 99, 0, 3, 0)));
        assertThrows(StockLedger.Conflict.class, () -> l.accept(batch));
        assertEquals(before, l.revision());
        assertEquals(1, l.sources().size());
    }

    @Test
    void separateStashesSeeingSameSourceDoNotCreateSeparatePhysicalIdentity() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        l.accept(observation(l, "b", 64, l.revision(), 2, 0));
        var sources = l.sources();
        assertEquals(2, sources.size());
        assertTrue(sources.stream().allMatch(StockLedger.Source::shared));
        assertEquals(
                1,
                sources.stream()
                        .map(s -> s.value().get("id").getAsString())
                        .distinct()
                        .count());
    }

    @Test
    void sharedPhysicalSourceCannotBeReservedEvenWhenFresh() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        l.accept(observation(l, "b", 64, l.revision(), 2, 0));
        assertTrue(l.sources().stream().allMatch(StockLedger.Source::shared));
        var reservations = PhysicalReservations.read(l, "s", null);
        assertFalse(reservations.hasSnapshots());
        assertTrue(reservations.available(Set.of()).isEmpty());
    }

    @Test
    void lossOfAccessPreservesLastObservedCountsAsUnknown() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        var unavailable = observation(l, "a", 0, l.revision(), 2, 0);
        source(unavailable).addProperty("state", "UNREACHABLE");
        l.accept(unavailable);
        var result = l.sources().getFirst();
        assertEquals("UNREACHABLE", result.status());
        assertEquals(
                64,
                result.value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
    }

    @Test
    void staleSourceCannotBecomeFreshWithoutNewProof() throws Exception {
        var l = ledger();
        var fresh = observation(l, "a", 64, 0, 1, 0);
        l.accept(fresh);
        var stale = fresh.deepCopy();
        source(stale).addProperty("state", "STALE");
        l.accept(stale);
        assertThrows(StockLedger.Conflict.class, () -> l.accept(fresh));
    }

    @Test
    void doubleChestSupersedesBothSingleHalves() throws Exception {
        var l = ledger();
        var halves = observation(l, "a", 32, 0, 1, 0);
        halves.getAsJsonArray("physicalSources").add(source(observation(l, "a", 32, 0, 2, 1)));
        l.accept(halves);
        var joined = observation(l, "a", 64, l.revision(), 3, 0);
        var s = source(joined);
        var p = new JsonArray();
        p.add(1);
        p.add(64);
        p.add(0);
        s.getAsJsonArray("parts").add(p);
        s.addProperty("size", 54);
        s.addProperty("kind", "minecraft:chest");
        s.addProperty(
                "id",
                new ContainerIdentity(
                                "s",
                                "minecraft:overworld",
                                List.of(
                                        new ContainerIdentity.Position(0, 64, 0),
                                        new ContainerIdentity.Position(1, 64, 0)))
                        .id());
        l.accept(joined);
        assertEquals(
                1, l.sources().stream().filter(v -> v.status().equals("FRESH")).count());
    }

    @Test
    void cachedSingleChestCannotReclaimPartOfANewerDoubleChestInAnotherStash() throws Exception {
        var l = ledger();
        var original = observation(l, "private", 32, 0, 1, 0);
        l.accept(original);
        var joined = observation(l, "shared", 64, l.revision(), 2, 0);
        var source = source(joined);
        var second = new JsonArray();
        second.add(1);
        second.add(64);
        second.add(0);
        source.getAsJsonArray("parts").add(second);
        source.addProperty("size", 54);
        source.addProperty("kind", "minecraft:chest");
        source.addProperty(
                "id",
                new ContainerIdentity(
                                "s",
                                "minecraft:overworld",
                                List.of(
                                        new ContainerIdentity.Position(0, 64, 0),
                                        new ContainerIdentity.Position(1, 64, 0)))
                        .id());
        l.accept(joined);
        long before = l.revision();
        assertThrows(StockLedger.Conflict.class, () -> l.accept(original));
        assertEquals(before, l.revision());
        assertEquals(
                1, l.sources().stream().filter(v -> v.status().equals("FRESH")).count());
    }

    @Test
    void malformedPhysicalQuantitiesCannotWrapOrRoundIntoStock() throws Exception {
        var l = ledger();
        for (String amount : List.of("-1", "1.5", "18446744073709551617")) {
            var bad = observation(l, "a", 1, 0, 1, 0);
            source(bad).getAsJsonArray("items").get(0).getAsJsonObject().add("count", JsonParser.parseString(amount));
            assertThrows(RuntimeException.class, () -> l.accept(bad));
            assertEquals(0, l.revision());
            assertTrue(l.sources().isEmpty());
        }
    }

    @Test
    void invalidTopologyAndOverlappingBatchAreRejectedBeforeWrite() throws Exception {
        var l = ledger();
        var bad = observation(l, "a", 1, 0, 1, 0);
        bad.getAsJsonArray("physicalSources").add(source(bad).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> l.accept(bad));
        assertEquals(0, l.revision());
    }

    @Test
    void concurrentSameBaseHasOnlyOneSuccessfulNewRevision() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        long base = l.revision();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                    () -> {
                        try {
                            l.accept(observation(l, "a", 32, base, 2, 0));
                            return true;
                        } catch (StockLedger.Conflict e) {
                            return false;
                        }
                    },
                    () -> {
                        try {
                            l.accept(observation(l, "a", 16, base, 3, 0));
                            return true;
                        } catch (StockLedger.Conflict e) {
                            return false;
                        }
                    }));
            assertEquals(
                    1,
                    results.stream()
                            .filter(f -> {
                                try {
                                    return Boolean.TRUE.equals(f.get());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .count());
        }
    }

    @Test
    void missingJournalTableInCurrentSchemaFailsClosedWithoutRecreatingIt() throws Exception {
        var first = ledger();
        first.accept(observation(first, "a", 64, 0, 1, 0));
        try (var c = first.open();
                var s = c.createStatement()) {
            s.execute("DROP TABLE physical_operations");
        }
        assertThrows(java.io.IOException.class, this::ledger);
        try (var c = first.open();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='physical_operations'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"order_books", "order_storage_meta", "order_event_log", "signed_request_nonces", "order_rows", "order_receipts"})
    void missingTableFromCompletedV8MigrationStopsRecovery(String table) throws Exception {
        var first = ledger();
        try (var c = first.open();
                var sql = c.createStatement()) {
            sql.execute("DROP TABLE " + table);
        }
        assertThrows(java.io.IOException.class, this::ledger);
        try (var c = first.open();
                var q = c.prepareStatement("SELECT 1 FROM sqlite_schema WHERE name=?")) {
            q.setString(1, table);
            try (var rows = q.executeQuery()) {
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void lostOrderBookWithSurvivingAuditCannotStartAsEmptyWarehouse() throws Exception {
        var first = ledger();
        try (var c = first.open();
                var sql = c.createStatement()) {
            sql.execute("INSERT INTO order_event_log VALUES('o-1',1,'s','stash','{}')");
        }
        assertThrows(java.io.IOException.class, this::ledger);
    }

    @Test
    void missingStockMetadataCannotSilentlyResetTheEpochAndRevision() throws Exception {
        var first = ledger();
        first.accept(observation(first, "a", 64, 0, 1, 0));
        try (var c = first.open();
                var s = c.createStatement()) {
            s.execute("DELETE FROM stock_meta");
        }
        assertThrows(java.io.IOException.class, this::ledger);
        try (var c = first.open();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT count(*) FROM stock_meta")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void foreignSqliteFileIsRejectedWithoutImport() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var s = c.createStatement()) {
            s.execute("CREATE TABLE foreign_data(value TEXT)");
        }
        assertThrows(java.io.IOException.class, this::ledger);
    }

    @Test
    void absentContainerKeepsUnknownHistoryAndCannotBeRevivedByCachedProof() throws Exception {
        var l = ledger();
        var original = observation(l, "a", 64, 0, 1, 0);
        l.accept(original);
        var absent = index("a", 0);
        absent.addProperty("physicalVersion", 1);
        absent.add("physicalSources", new JsonArray());
        l.accept(absent);
        assertEquals("WITHDRAWN", l.sources().getFirst().status());
        assertEquals(
                64,
                l.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
        assertThrows(StockLedger.Conflict.class, () -> l.accept(original));
        l.accept(observation(l, "a", 64, l.revision(), 2, 0));
        assertEquals("FRESH", l.sources().getFirst().status());
    }

    @Test
    void markingForRescanDoesNotInvalidateANewReadAlreadyInFlight() throws Exception {
        var l = ledger();
        var original = observation(l, "a", 64, 0, 1, 0);
        l.accept(original);
        long base = l.revision();
        var pendingRead = observation(l, "a", 32, base, 2, 0);
        var stale = original.deepCopy();
        source(stale).addProperty("state", "STALE");
        l.accept(stale);
        assertEquals(base, l.sources().getFirst().revision());
        l.accept(pendingRead);
        assertEquals("FRESH", l.sources().getFirst().status());
        assertEquals(
                32,
                l.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
    }

    @Test
    void firstReadCanCompleteWhileAnUnknownPlaceholderIsBeingAcknowledged() throws Exception {
        var l = ledger();
        var unknown = observation(l, "a", 0, 0, 1, 0);
        source(unknown).addProperty("state", "UNKNOWN");
        l.accept(unknown);
        assertEquals(0, l.sources().getFirst().revision());
        l.accept(observation(l, "a", 64, 0, 2, 0));
        assertEquals("FRESH", l.sources().getFirst().status());
    }

    @Test
    void withdrawnMembershipKeepsItsOwnHistoryRatherThanAnotherPrivateStash() throws Exception {
        var l = ledger();
        l.accept(observation(l, "a", 64, 0, 1, 0));
        var absent = index("a", 0);
        absent.addProperty("physicalVersion", 1);
        absent.add("physicalSources", new JsonArray());
        l.accept(absent);
        time.set(1020);
        l.accept(observation(l, "b", 32, l.revision(), 2, 0));
        assertEquals(1, l.sources().size());
        assertEquals("b", l.sources().getFirst().stash());
        var missing = index("b", 0);
        missing.addProperty("physicalVersion", 1);
        missing.add("physicalSources", new JsonArray());
        l.accept(missing);
        var a = l.sources().stream()
                .filter(s -> s.stash().equals("a"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                64,
                a.value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsLong());
        assertEquals(1000, a.verifiedAt());
        assertEquals("b", l.sources().getFirst().stash());
    }

    @Test
    void ageUsesManagerClockRatherThanClientTimestamp() throws Exception {
        var l = ledger();
        var value = observation(l, "a", 64, 0, 1, 0);
        source(value).addProperty("observedAt", Long.MAX_VALUE);
        l.accept(value);
        time.set(1100);
        assertEquals("STALE_AGE", l.sources().getFirst().status());
    }

    @Test
    void missingResumeReceiptTableInV9FailsClosed() throws Exception {
        var l = ledger();
        try (var c = l.open();
                var sql = c.createStatement()) {
            sql.execute("DROP TABLE order_resume_receipts");
        }
        assertThrows(java.io.IOException.class, this::ledger);
    }
}
