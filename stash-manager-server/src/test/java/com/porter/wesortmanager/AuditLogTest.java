package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {
    @TempDir
    Path data;

    private StockLedger ledger() throws IOException {
        return new StockLedger(data);
    }

    @Test
    void persistsAcrossRestart() throws Exception {
        var ledger = ledger();
        AuditLog original = new AuditLog(ledger, data, new FakeClock(1234L));
        original.load();
        original.add("member-1", "Member", "order.created", "first", "127.0.0.1");

        AuditLog restarted = new AuditLog(new StockLedger(data), data);
        restarted.load();
        assertEquals(1, restarted.size());
        assertEquals(
                "order.created",
                restarted.newest(1).get(0).getAsJsonObject().get("action").getAsString());
        assertEquals(
                1234L, restarted.newest(1).get(0).getAsJsonObject().get("at").getAsLong());
    }

    @Test
    void missingTableFailsClosedInsteadOfEmptyHistory() throws Exception {
        var ledger = ledger();
        AuditLog audit = new AuditLog(ledger, data);
        audit.load();
        audit.add("member-1", "Member", "order.created", "first", "127.0.0.1");
        assertEquals(1, audit.size());

        try (var c = ledger.open();
                var sql = c.createStatement()) {
            sql.execute("DROP TABLE audit_log");
        }
        assertThrows(IllegalStateException.class, audit::load);
        assertThrows(UncheckedIOException.class, audit::size);
        assertThrows(UncheckedIOException.class, () -> audit.newest(10));
        assertThrows(UncheckedIOException.class, () -> audit.add("m", "M", "order.created", "x", "127.0.0.1"));
    }

    @Test
    void legacyFileImportsOnceAndIsNeverReimported() throws Exception {
        Files.writeString(
                data.resolve("audit.json"),
                "{\"entries\":[{\"at\":7,\"userId\":\"u\",\"user\":\"U\",\"action\":\"order.created\",\"detail\":\"d\",\"address\":\"a\"}]}",
                StandardCharsets.UTF_8);
        var ledger = ledger();
        AuditLog audit = new AuditLog(ledger, data);
        audit.load();
        assertEquals(1, audit.size());
        assertTrue(Files.exists(data.resolve("audit.json.imported")), "imported evidence is preserved");
        assertTrue(Files.notExists(data.resolve("audit.json")), "imported file must not be re-imported");

        audit.load();
        assertEquals(1, audit.size(), "second load must not duplicate the import");
    }

    @Test
    void corruptLegacyFileFailsClosed() throws Exception {
        Files.writeString(data.resolve("audit.json"), "{not-json", StandardCharsets.UTF_8);
        var ledger = ledger();
        assertThrows(IllegalStateException.class, () -> new AuditLog(ledger, data).load());

        Files.writeString(data.resolve("audit.json"), "{\"entries\":[null]}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new AuditLog(ledger, data).load());
    }

    @Test
    void integrityCheckFailureFailsClosed() throws Exception {
        var ledger = ledger();
        AuditLog audit = new AuditLog(ledger, data);
        audit.load();
        audit.add("member-1", "Member", "order.created", "first", "127.0.0.1");
        // Checkpoint WAL so the main file is authoritative, then corrupt a
        // non-root page structurally: PRAGMA quick_check must report it.
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        Path db = data.resolve("guild.sqlite");
        assertTrue(Files.size(db) > 8192, "database must span several pages to corrupt structurally");
        byte[] raw = Files.readAllBytes(db);
        java.util.Arrays.fill(raw, 8192, 8192 + 64, (byte) 0xFF);
        Files.write(db, raw);
        var failure = assertThrows(IllegalStateException.class, audit::load);
        assertTrue(
                failure.getMessage().contains("failed integrity check"),
                "must fail on quick_check, got: " + failure.getMessage());
    }

    @Test
    void sameTransactionWriteRollsBackWithItsTransaction() throws Exception {
        var ledger = ledger();
        AuditLog audit = new AuditLog(ledger, data);
        audit.load();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            AuditLog.add(c, 1L, "u", "U", "order.place", "o-1", "127.0.0.1");
            // No commit: closing the transaction rolls back.
        }
        assertEquals(0, audit.size(), "rolled-back audit entry must not be visible");
        assertEquals(0, audit.newest(10).size());

        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            AuditLog.add(c, 2L, "u", "U", "order.place", "o-1", "127.0.0.1");
            tx.commit();
        }
        assertEquals(1, audit.size());
    }

    @Test
    void newestReturnsNewestFirstWithLimitAndTrimsToMax() throws Exception {
        var ledger = ledger();
        AuditLog audit = new AuditLog(ledger, data, new FakeClock(5L));
        audit.load();
        audit.add("u", "U", "order.place", "first", "127.0.0.1");
        audit.add("u", "U", "order.pack", "second", "127.0.0.1");
        assertEquals(2, audit.newest(10).size());
        assertEquals(
                "order.pack",
                audit.newest(10).get(0).getAsJsonObject().get("action").getAsString());
        assertEquals(1, audit.newest(1).size());
        assertEquals(
                "order.pack",
                audit.newest(1).get(0).getAsJsonObject().get("action").getAsString());
    }
}
