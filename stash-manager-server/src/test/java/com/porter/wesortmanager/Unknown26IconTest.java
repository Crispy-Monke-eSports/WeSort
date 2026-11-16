package com.porter.wesortmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class Unknown26IconTest {
    @TempDir Path data;
    @Test void unindexed26ItemNeverReturnsAnUnversionedCachedWikiImage() throws Exception {
        Path icons = data.resolve("icons"); Files.createDirectories(icons.resolve("minecraft"));
        byte[] old = new byte[] {1, 2, 3}; Files.write(icons.resolve("minecraft/unknown.png"), old);
        try (var service = new IconService(data, id -> null, version -> false, new GameIcons(icons, null, false), true)) {
            var icon = service.get("minecraft", "unknown", "26.3");
            assertEquals(200, icon.status()); assertEquals("image/png", icon.contentType());
            assertFalse(java.util.Arrays.equals(old, icon.data()));
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(icon.data()));
            assertEquals(16, image.getWidth()); assertEquals(16, image.getHeight());
            assertArrayEquals(old, Files.readAllBytes(icons.resolve("minecraft/unknown.png")));
        }
    }
    @Test void unsupportedVersionNeverUsesLegacyCachedWikiIcon() throws Exception {
        Path icons = data.resolve("icons"); Files.createDirectories(icons.resolve("minecraft"));
        byte[] old = new byte[] {9, 8, 7}; Files.write(icons.resolve("minecraft/unknown.png"), old);
        try (var service = new IconService(data, id -> null, version -> false, new GameIcons(icons, null, false), true)) {
            var icon = service.get("minecraft", "unknown", "1.21.11");
            assertEquals(200, icon.status());
            assertFalse(java.util.Arrays.equals(old, icon.data()));
        }
    }
}
