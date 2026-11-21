package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Classic dirt background reads one entry from a client jar; never downloads. */
class VanillaTextureTest {
    @TempDir
    Path data;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3, 4, 5, 6, 7, 8, 9};

    private Path jarWith(String entry, byte[] payload) throws Exception {
        Path jar = data.resolve("client-test.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            var e = new ZipEntry(entry);
            out.putNextEntry(e);
            out.write(payload);
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void readsDirtEntryFromAJar() throws Exception {
        Path jar = jarWith(VanillaTexture.DIRT, PNG);
        assertArrayEquals(PNG, VanillaTexture.read(jar, VanillaTexture.DIRT));
    }

    @Test
    void missingEntryOrJunkIsNull() throws Exception {
        Path jar = jarWith("other.png", PNG);
        assertNull(VanillaTexture.read(jar, VanillaTexture.DIRT));
        Path junk = jarWith(VanillaTexture.DIRT, "not a png at all!!".getBytes());
        assertNull(VanillaTexture.read(jar, VanillaTexture.DIRT));
        assertNull(VanillaTexture.read(data.resolve("absent.jar"), VanillaTexture.DIRT));
    }

    @Test
    void noClientMeansNoDirtAndNoDownload() {
        assertNull(VanillaTexture.dirt(data.resolve("icons")));
    }

    @Test
    void zipsSlipEntriesCannotEscape() throws Exception {
        // A malicious entry name is simply not the dirt entry.
        try (var out = new ZipOutputStream(Files.newOutputStream(data.resolve("evil.jar")))) {
            out.putNextEntry(new ZipEntry("../../evil.png"));
            out.write(PNG);
            out.closeEntry();
        }
        Path evil = data.resolve("evil.jar");
        assertNull(VanillaTexture.read(evil, VanillaTexture.DIRT));
    }
}
