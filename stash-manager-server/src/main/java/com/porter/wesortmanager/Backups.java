package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * W-044: atomic backup and restore of the Manager's persistent data directory. A backup is a complete, self-contained
 * snapshot of every guild-owned file — users, roles, quota policy, order comments, audit, stock, package journals and
 * credentials — never any Qwerte data, and never the regenerable Minecraft client or icon caches. Each file is
 * checksummed (SHA-256) and the per-store schema versions are recorded in a manifest, so a restore verifies integrity
 * and refuses a backup of an incompatible format. Snapshots are taken atomically (staged, then renamed), are
 * owner-only, and are pruned to a bounded retention window. A restore only ever rewrites data files; it never proves a
 * physical Minecraft box exists or is named — the agent must re-observe before any new stock promise.
 */
final class Backups {
    static final class Conflict extends IOException {
        Conflict(String reason) { super(reason); }
    }

    static final int FORMAT = 1;
    static final int DEFAULT_RETENTION = 10;
    private static final String DIR = "backups";
    private static final String MANIFEST = "manifest.json";
    /** Transient sockets/locks and regenerable caches that are never part of a backup. */
    private static final Set<String> EXCLUDED = Set.of(
            DIR, ".manager.lock", ".control.sock", "icons", "sources", "game-26.3-v2");

    private final Path dataDir;
    private final Path dir;
    private final LongSupplier clock;
    private final int retention;

    Backups(Path dataDir) {
        this(dataDir, System::currentTimeMillis, DEFAULT_RETENTION);
    }

    Backups(Path dataDir, LongSupplier clock) {
        this(dataDir, clock, DEFAULT_RETENTION);
    }

    Backups(Path dataDir, LongSupplier clock, int retention) {
        this.dataDir = dataDir;
        this.dir = dataDir.resolve(DIR);
        this.clock = clock;
        this.retention = retention;
    }

    record Snapshot(String id, long at, Map<String, Entry> entries) {
        record Entry(String sha256, Integer schema) {}
    }

    /** Snapshots the data directory atomically and returns the new backup id. */
    String create() throws IOException {
        Files.createDirectories(dir);
        AtomicFiles.ownerOnlyDir(dir);
        // Retry while a concurrent writer keeps changing files, so a live snapshot is always internally consistent.
        for (int attempt = 0; attempt < 3; attempt++) {
            String id = Long.toString(clock.getAsLong());
            Path staging = dir.resolve(".staging-" + id);
            deleteRecursively(staging);
            Files.createDirectories(staging);

            Map<String, String> live = hashes();
            for (String rel : live.keySet()) {
                Path dst = staging.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(dataDir.resolve(rel), dst, StandardCopyOption.REPLACE_EXISTING);
            }
            if (live.equals(hashes())) {
                writeManifest(staging, id, live);
                AtomicFiles.ownerOnlyDir(staging);
                Path finalDir = dir.resolve(id);
                move(staging, finalDir);
                prune();
                return id;
            }
            deleteRecursively(staging);
        }
        throw new Conflict("Cannot take a stable snapshot while the data directory keeps changing");
    }

    /** Every backup, oldest first, with its recorded timestamp and file count. */
    JsonArray list() throws IOException {
        JsonArray out = new JsonArray();
        if (!Files.isDirectory(dir)) return out;
        List<Path> backups = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            for (var p : entries.toList()) {
                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".staging-")) backups.add(p);
            }
        }
        backups.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (var p : backups) {
            JsonObject row = new JsonObject();
            row.addProperty("id", p.getFileName().toString());
            try {
                var manifest = JsonParser.parseString(Files.readString(p.resolve(MANIFEST))).getAsJsonObject();
                row.addProperty("at", manifest.get("at").getAsLong());
                row.addProperty("files", manifest.getAsJsonObject("entries").size());
            } catch (RuntimeException | IOException corrupt) {
                row.addProperty("corrupt", true);
            }
            out.add(row);
        }
        return out;
    }

    /** Reads the manifest and verifies every file checksum; never writes anything. */
    Snapshot verify(String id) throws IOException {
        Path backup = dir.resolve(id);
        if (!Files.isDirectory(backup)) throw new Conflict("Unknown backup: " + id);
        Path manifestFile = backup.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestFile)) throw new Conflict("Backup manifest is missing");
        JsonObject manifest;
        try {
            manifest = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new Conflict("Backup manifest is corrupt");
        }
        if (manifest.get("format").getAsInt() != FORMAT) throw new Conflict("Unsupported backup format");
        Map<String, Snapshot.Entry> entries = new LinkedHashMap<>();
        for (var entry : manifest.getAsJsonObject("entries").entrySet()) {
            var row = entry.getValue().getAsJsonObject();
            entries.put(
                    entry.getKey(),
                    new Snapshot.Entry(row.get("sha256").getAsString(),
                            row.has("schema") ? row.get("schema").getAsInt() : null));
        }
        for (var entry : entries.entrySet()) {
            Path file = safeResolve(backup, entry.getKey());
            if (!Files.isRegularFile(file)) throw new Conflict("Backup is missing " + entry.getKey());
            if (!sha256(file).equals(entry.getValue().sha256()))
                throw new Conflict("Backup checksum mismatch: " + entry.getKey());
        }
        return new Snapshot(id, manifest.get("at").getAsLong(), entries);
    }

    /** Restores a verified backup over this data directory. */
    void restore(String id) throws IOException {
        restore(id, dataDir);
    }

    /** Restores a verified backup over a target directory (e.g. a fresh Linux Docker host's volume). */
    void restore(String id, Path target) throws IOException {
        Snapshot snapshot = verify(id);
        Path backup = dir.resolve(id);
        for (var entry : snapshot.entries().entrySet()) {
            Path src = safeResolve(backup, entry.getKey());
            Path dst = safeResolve(target, entry.getKey());
            Files.createDirectories(dst.getParent());
            AtomicFiles.write(dst, Files.readAllBytes(src), true);
        }
        AtomicFiles.ownerOnlyDir(target);
    }

    /** Removes .staging-* directories left by a crash in the middle of a backup. */
    static int sweepStaging(Path dataDir) {
        Path dir = dataDir.resolve(DIR);
        if (!Files.isDirectory(dir)) return 0;
        int removed = 0;
        try (var entries = Files.list(dir)) {
            for (var p : entries.toList()) {
                if (p.getFileName().toString().startsWith(".staging-")) {
                    try {
                        deleteRecursively(p);
                        removed++;
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }

    private Map<String, String> hashes() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dataDir)) return out;
        try (var walk = Files.walk(dataDir)) {
            for (var p : walk.toList()) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = dataDir.relativize(p);
                String top = rel.getName(0).toString();
                if (EXCLUDED.contains(top)) continue;
                String name = rel.toString().replace('\\', '/');
                if (name.endsWith(".tmp")) continue;
                out.put(name, sha256(p));
            }
        }
        return out;
    }

    private void writeManifest(Path staging, String id, Map<String, String> hashes) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT);
        manifest.addProperty("id", id);
        manifest.addProperty("at", clock.getAsLong());
        JsonObject entries = new JsonObject();
        for (var e : hashes.entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("sha256", e.getValue());
            if (!e.getKey().contains("/") && e.getKey().endsWith(".json")) {
                Integer schema = readSchema(staging.resolve(e.getKey()));
                if (schema != null) row.addProperty("schema", schema);
            }
            entries.add(e.getKey(), row);
        }
        manifest.add("entries", entries);
        AtomicFiles.write(staging.resolve(MANIFEST), manifest.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private static Integer readSchema(Path file) {
        try {
            var root = JsonParser.parseString(Files.readString(file));
            if (root.isJsonObject() && root.getAsJsonObject().has("schema")
                    && root.getAsJsonObject().get("schema").isJsonPrimitive())
                return root.getAsJsonObject().get("schema").getAsInt();
        } catch (RuntimeException | IOException ignored) {
        }
        return null;
    }

    private void prune() throws IOException {
        List<Path> backups = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            for (var p : entries.toList()) {
                if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".staging-")) backups.add(p);
            }
        }
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        while (backups.size() > retention) {
            deleteRecursively(backups.remove(backups.size() - 1));
        }
    }

    /** Resolves a backup-relative path and refuses anything that escapes the base directory. */
    private static Path safeResolve(Path base, String rel) throws Conflict {
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base.normalize())) throw new Conflict("Unsafe path in backup: " + rel);
        return resolved;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
