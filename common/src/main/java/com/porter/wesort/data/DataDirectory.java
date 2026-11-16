package com.porter.wesort.data;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Opens only a new empty directory or an explicitly versioned WeSort installation. Never imports data. */
public final class DataDirectory {
    public static final String MARKER = ".wesort-installation";
    private DataDirectory() { }

    public static synchronized Path open(Path requested, String role) throws IOException {
        if (!role.equals("client") && !role.equals("manager")) throw new IllegalArgumentException("unknown WeSort role");
        Path root = requested.toAbsolutePath().normalize();
        for (Path part : root) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals("qwerte") || name.equals("qwerte-addon") || name.equals("stash-manager-data")) {
                throw new IOException("Refusing a Qwerte data path: " + root);
            }
        }
        // Inspect links without following them, before mkdir, sweeping temp files or reading any config.
        Path cursor = root.getRoot();
        for (Path part : root) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink in WeSort data path: " + cursor);
        }
        Files.createDirectories(root);
        Path marker = root.resolve(MARKER);
        String schema = "wesort-" + role + ":1\n";
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) throw new IOException("Nonempty directory is not a WeSort installation: " + root);
            }
            try {
                Files.writeString(marker, schema, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                // Another startup must have created the same marker; validate it below.
            }
        }
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).equals(schema)) {
            throw new IOException("Unknown WeSort data schema or installation role: " + root);
        }
        // Also refuse an icon/cache/kit/order file linked outside our own installation.
        try (var paths = Files.walk(root)) {
            if (paths.anyMatch(Files::isSymbolicLink)) throw new IOException("Symlink inside WeSort data directory: " + root);
        }
        return root;
    }

    public static Path clientFolder(Path meteorFolder, String child) {
        if (!child.matches("[a-z-]+")) throw new IllegalArgumentException("invalid data folder");
        try {
            return open(meteorFolder.resolve("wesort"), "client").resolve(child);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open isolated WeSort client data", e);
        }
    }
}
