package com.porter.wesortmanager.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class GameVersionTest {
    @TempDir Path dir;
    private Path jar(String json) throws Exception {
        Path jar = dir.resolve("client.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("version.json")); output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        return jar;
    }
    @Test void anotherReleaseCannotPopulate26Cache() throws Exception {
        assertFalse(GameAssets.matchesVersion(jar("{\"id\":\"1.21.11\"}"), "26.3"));
        assertTrue(GameAssets.matchesVersion(jar("{\"id\":\"26.3\"}"), "26.3"));
    }
    @Test void malformedOrOversizedMetadataFailsClosed() throws Exception {
        assertFalse(GameAssets.matchesVersion(jar("{}"), "26.3"));
        assertFalse(GameAssets.matchesVersion(jar("x".repeat(65537)), "26.3"));
    }
}
