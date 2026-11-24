package com.porter.wesortmanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;

/** Shared budget for regenerable icon files. The verified Minecraft client is a separate fixed-size input. */
final class IconDiskCache {
    static final long LIMIT = 16L * 1024 * 1024;
    private final Path root;
    private final long limit;
    private final LinkedHashMap<Path, Long> entries = new LinkedHashMap<>(128, 0.75f, true);
    private long used;
    private boolean initialized;

    IconDiskCache(Path root, long limit) {
        this.root = root;
        this.limit = limit;
    }

    synchronized void initialize() throws IOException {
        if (initialized) return;
        Files.createDirectories(root);
        record Entry(Path path, long size, long accessed) {}
        var existing = new ArrayList<Entry>();
        try (var paths = Files.walk(root, 3)) {
            for (var it = paths.iterator(); it.hasNext(); ) {
                Path path = it.next();
                String name = path.getFileName().toString();
                if (!(name.endsWith(".png") || name.endsWith(".miss") || name.endsWith(".retry"))) continue;
                var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isRegularFile())
                    existing.add(new Entry(
                            path, charge(attrs.size()), attrs.lastAccessTime().toMillis()));
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        existing.sort(Comparator.comparingLong(Entry::accessed).thenComparing(Entry::path));
        entries.clear();
        used = 0;
        for (Entry entry : existing) {
            entries.put(entry.path, entry.size);
            used += entry.size;
        }
        makeRoom(0);
        initialized = true;
    }

    synchronized byte[] read(Path path) throws IOException {
        initialize();
        Long size = entries.get(path);
        if (size == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        return Files.readAllBytes(path);
    }

    synchronized void write(Path path, byte[] bytes) throws IOException {
        initialize();
        long size = charge(bytes.length);
        if (size > limit) throw new IOException("Icon exceeds disk cache budget");
        long before = entries.getOrDefault(path, 0L);
        // The replaced entry is now most recently used. Its replacement fits even when it is the only entry.
        makeRoom(size - before);
        AtomicFiles.write(path, bytes);
        entries.put(path, size);
        used += size - before;
    }

    private static long charge(long bytes) {
        // Small PNGs and even empty negative-cache markers consume filesystem space.
        return Math.max(4096, bytes);
    }

    private void makeRoom(long additional) throws IOException {
        int removed = 0;
        while (used + additional > limit) {
            var oldest = entries.firstEntry();
            Files.deleteIfExists(oldest.getKey());
            entries.pollFirstEntry();
            used -= oldest.getValue();
            removed++;
        }
        if (removed > 0)
            System.out.println("Icon cache evicted " + removed + " file(s), remaining budget usage " + used + " bytes");
    }
}
