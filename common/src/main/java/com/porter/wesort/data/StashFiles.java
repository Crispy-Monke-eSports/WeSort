package com.porter.wesort.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Resolves the existing stash for the default 6b6t endpoint across its known login spellings. */
public final class StashFiles {
    private StashFiles() {}

    public static Path resolve(Path folder, String name, String server) {
        Path exact = file(folder, name, server);
        if (!MinecraftServerAddress.sameServer(server, "6b6t.org") || Files.exists(exact)) return exact;
        Path existing = null;
        for (String alias : List.of("6b6t.org", "alt.6b6t.org", "6b6t.org:25565", "alt.6b6t.org:25565")) {
            Path candidate = file(folder, name, alias);
            if (!Files.exists(candidate)) continue;
            // Multiple independent legacy stashes must stay separate.
            if (existing != null) return exact;
            existing = candidate;
        }
        return existing == null ? file(folder, name, "6b6t.org") : existing;
    }

    private static Path file(Path folder, String name, String server) {
        String safe = (server + "_" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return folder.resolve(safe + ".nbt");
    }
}
