package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class BackupsTest {
    @TempDir Path data;
    @TempDir Path fresh;

    private Backups backups() {
        return new Backups(data);
    }

    private void write(Path rel, String content) throws Exception {
        Path p = data.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private String read(Path rel) throws Exception {
        return Files.readString(data.resolve(rel));
    }

    /** A realistic data directory: every guild-owned store plus credentials and the installation marker. */
    private void fixture() throws Exception {
        write(Path.of(".wesort-installation"), "wesort-manager:1\n");
        write(Path.of("config.properties"), "port=8765\napiKey=secret-key-123\ndashboardPassword=$hash$abc\n");
        write(Path.of("users.json"), "{\"schema\":3,\"users\":[{\"id\":\"u-1\",\"role\":\"owner\"}]}");
        write(Path.of("quotas.json"), "{\"schema\":2,\"quotas\":[{\"member\":\"u-1\",\"limit\":64}]}");
        write(Path.of("audit.json"), "{\"schema\":1,\"events\":[{\"event\":\"login\"}]}");
        write(Path.of("custody.json"), "{\"schema\":1,\"packages\":[]}");
        write(Path.of("streamer-ids.key"), "streamer-secret");
        write(Path.of("orders/o-1.json"), "{\"schema\":1,\"order\":{\"id\":\"o-1\",\"kind\":\"SELF\",\"comment\":\"x\"}}");
        write(Path.of("indexes/s.json"), "{\"server\":\"s\",\"items\":[]}");
    }

    @Test void backupRestoreRoundTripsEveryGuildFileAndCredential() throws Exception {
        fixture();
        String users = read(Path.of("users.json"));
        String quotas = read(Path.of("quotas.json"));
        String config = read(Path.of("config.properties"));
        String order = read(Path.of("orders/o-1.json"));

        String id = backups().create();
        assertEquals(1, backups().list().size());

        // Drift: a member is deleted and the quota store is lost entirely.
        write(Path.of("users.json"), "{\"schema\":3,\"users\":[]}");
        Files.delete(data.resolve("quotas.json"));

        backups().restore(id);

        assertEquals(users, read(Path.of("users.json")), "roles must survive a restore");
        assertEquals(quotas, read(Path.of("quotas.json")), "quota policy must survive a restore");
        assertEquals(config, read(Path.of("config.properties")), "credentials must survive a restore");
        assertEquals(order, read(Path.of("orders/o-1.json")), "order type and comment must survive a restore");
    }

    @Test void restoreOntoAFreshHostDirectory() throws Exception {
        fixture();
        String users = read(Path.of("users.json"));
        String id = backups().create();

        backups().restore(id, fresh);

        assertEquals(users, Files.readString(fresh.resolve("users.json")));
        assertEquals(read(Path.of("orders/o-1.json")), Files.readString(fresh.resolve("orders/o-1.json")));
        assertEquals(read(Path.of("config.properties")), Files.readString(fresh.resolve("config.properties")));
    }

    @Test void retentionPrunesTheOldestBackups() throws Exception {
        fixture();
        AtomicLong clock = new AtomicLong(1000);
        Backups b = new Backups(data, clock::get, 2);
        assertEquals("1000", b.create());
        clock.set(2000);
        assertEquals("2000", b.create());
        clock.set(3000);
        assertEquals("3000", b.create());

        var ids = b.list();
        assertEquals(2, ids.size());
        assertEquals("2000", ids.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("3000", ids.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test void aCorruptedBackupIsRefusedOnVerifyAndRestore() throws Exception {
        fixture();
        String id = backups().create();
        Files.writeString(data.resolve("backups").resolve(id).resolve("users.json"), "tampered");

        assertThrows(Backups.Conflict.class, () -> backups().verify(id));
        assertThrows(Backups.Conflict.class, () -> backups().restore(id));
    }

    @Test void transientAndRegenerableEntriesAreNeverBackedUp() throws Exception {
        fixture();
        write(Path.of("icons/x.png"), "icon");
        write(Path.of("sources/26.3/foo"), "client");
        write(Path.of(".manager.lock"), "lock");

        String id = backups().create();
        var entries = backups().verify(id).entries();

        assertTrue(entries.containsKey("users.json"));
        assertTrue(entries.containsKey("config.properties"));
        assertFalse(entries.containsKey("icons/x.png"));
        assertFalse(entries.containsKey("sources/26.3/foo"));
        assertFalse(entries.containsKey(".manager.lock"));
    }

    @Test void theManifestRecordsPerStoreSchemaVersions() throws Exception {
        fixture();
        String id = backups().create();
        var entries = backups().verify(id).entries();

        assertEquals(3, entries.get("users.json").schema());
        assertEquals(2, entries.get("quotas.json").schema());
        assertNull(entries.get("config.properties").schema(), "config is not a schema-versioned store");
        assertNull(entries.get("orders/o-1.json").schema(), "nested order files are checksummed, not schema-read");
    }

    @Test void restoreRejectsAnEntryThatEscapesTheDataDirectory() throws Exception {
        fixture();
        String id = backups().create();
        Path manifestFile = data.resolve("backups").resolve(id).resolve("manifest.json");
        var manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
        JsonObject row = new JsonObject();
        row.addProperty("sha256", "00".repeat(32));
        manifest.getAsJsonObject("entries").add("../evil", row);
        Files.writeString(manifestFile, manifest.toString());

        assertThrows(Backups.Conflict.class, () -> backups().restore(id));
    }

    @Test void anInterruptedBackupLeavesOnlyStagingThatStartupSweeps() throws Exception {
        fixture();
        Path staging = data.resolve("backups").resolve(".staging-123");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("users.json"), "partial");

        assertEquals(1, Backups.sweepStaging(data));
        assertFalse(Files.exists(staging));
    }
}
