package com.porter.wesortmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VanillaAssetsTest {
    @TempDir Path dir;
    @Test void missingTruncatedAndSymlinkedSourcesAreNotAccepted() throws Exception {
        Path file = dir.resolve("client.jar"); assertFalse(VanillaAssets.valid(file));
        Files.writeString(file, "wrong version or interrupted download"); assertFalse(VanillaAssets.valid(file));
        Path link = dir.resolve("link.jar"); Files.createSymbolicLink(link, file); assertFalse(VanillaAssets.valid(link));
    }
    @Test void symlinkedAssetDirectoryIsRejectedBeforeDownloadOrWrite() throws Exception {
        Path elsewhere = dir.resolve("outside"); Files.createDirectory(elsewhere);
        Path icons = dir.resolve("icons"); Files.createDirectory(icons); Files.createSymbolicLink(icons.resolve("sources"), elsewhere);
        assertThrows(java.io.IOException.class, () -> VanillaAssets.client26(icons));
        try (var files = Files.list(elsewhere)) { assertEquals(0, files.count()); }
    }
}
