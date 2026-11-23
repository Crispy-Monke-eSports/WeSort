package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void loadsPreviouslyPersistedEntries() {
        AuditLog original = new AuditLog(data, () -> 1234L);
        original.add("member-1", "Member", "order.created", "first", "127.0.0.1");

        AuditLog restarted = new AuditLog(data);
        restarted.load();
        assertEquals(1, restarted.size());
        assertEquals(
                "order.created",
                restarted.newest(1).get(0).getAsJsonObject().get("action").getAsString());
        assertEquals(
                1234L, restarted.newest(1).get(0).getAsJsonObject().get("at").getAsLong());
    }

    @Test
    void corruptExistingAuditFailsClosedWithoutErasingLoadedHistory() throws IOException {
        AuditLog audit = new AuditLog(data);
        audit.add("member-1", "Member", "order.created", "first", "127.0.0.1");
        assertEquals(1, audit.size());

        Files.writeString(data.resolve("audit.json"), "{\"entries\":[null]}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, audit::load);
        assertEquals(1, audit.size(), "a failed reload must not erase in-memory history");
        assertThrows(IllegalStateException.class, () -> new AuditLog(data).load());
    }

    @Test
    void invalidAuditDocumentCannotBecomeAnEmptyHistory() throws IOException {
        Files.writeString(data.resolve("audit.json"), "{not-json", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new AuditLog(data).load());

        Files.writeString(data.resolve("audit.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new AuditLog(data).load());
    }

    @Test
    void failedDiskWriteDoesNotPublishAnUnpersistedAuditEvent() throws IOException {
        Path auditFile = data.resolve("audit.json");
        Files.createDirectory(auditFile);
        AuditLog audit = new AuditLog(data);

        assertThrows(
                UncheckedIOException.class,
                () -> audit.add("member-1", "Member", "order.created", "first", "127.0.0.1"));
        assertEquals(0, audit.size(), "failed persistence must not leave an in-memory-only event");

        Files.delete(auditFile);
        audit.add("member-1", "Member", "order.created", "after recovery", "127.0.0.1");
        assertEquals(1, audit.size());

        AuditLog restarted = new AuditLog(data);
        restarted.load();
        assertEquals(1, restarted.size());
        assertEquals(
                "after recovery",
                restarted.newest(1).get(0).getAsJsonObject().get("detail").getAsString());
    }
}
