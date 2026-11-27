package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StashFilesTest {
    @TempDir
    Path folder;

    @Test
    void reconnectUsesTheSameFileWithoutMergingIndependentLegacyStashes() throws Exception {
        var primary = Files.createFile(folder.resolve("6b6t.org_stock.nbt"));
        assertEquals(primary, StashFiles.resolve(folder, "Stock", "alt.6b6t.org:25565"));
        assertNotEquals(primary, StashFiles.resolve(folder, "Stock", "6b6t.org:25566"));
        assertNotEquals(primary, StashFiles.resolve(folder, "Stock", "other.6b6t.org"));
        var alternate = Files.createFile(folder.resolve("alt.6b6t.org_stock.nbt"));
        assertEquals(alternate, StashFiles.resolve(folder, "Stock", "alt.6b6t.org"));
        assertEquals(folder.resolve("6b6t.org_25565_stock.nbt"), StashFiles.resolve(folder, "Stock", "6b6t.org:25565"));
        Files.delete(primary);
        assertEquals(alternate, StashFiles.resolve(folder, "Stock", "6b6t.org"));
    }
}
