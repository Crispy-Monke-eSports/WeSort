package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaAssetsTest {
    @TempDir
    Path dir;

    @Test
    void missingTruncatedAndSymlinkedSourcesAreNotAccepted() throws Exception {
        Path file = dir.resolve("client.jar");
        assertFalse(VanillaAssets.valid(file));
        Files.writeString(file, "wrong version or interrupted download");
        assertFalse(VanillaAssets.valid(file));
        Path link = dir.resolve("link.jar");
        Files.createSymbolicLink(link, file);
        assertFalse(VanillaAssets.valid(link));
    }

    @Test
    void explicitConsentRequiredBeforeAnyFilesOrNetwork(@TempDir Path isolated) throws Exception {
        Path icons = isolated.resolve("icons");
        assertThrows(java.io.IOException.class, () -> VanillaAssets.client26(icons, false));
        assertFalse(Files.exists(icons), "No directory or cache may be created before opt-in");
    }

    @Test
    void symlinkedAssetDirectoryIsRejectedBeforeDownloadOrWrite() throws Exception {
        Path elsewhere = dir.resolve("outside");
        Files.createDirectory(elsewhere);
        Path icons = dir.resolve("icons");
        Files.createDirectory(icons);
        Files.createSymbolicLink(icons.resolve("sources"), elsewhere);
        assertThrows(java.io.IOException.class, () -> VanillaAssets.client26(icons, true));
        try (var files = Files.list(elsewhere)) {
            assertEquals(0, files.count());
        }
    }
}
