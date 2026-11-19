package com.porter.wesortmanager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Single raw textures from the checksum-verified official client (eula=true), for page chrome that is
 * literally vanilla game content — currently only the Classic theme's dirt background. Item icons stay
 * with GameIcons/IconService; this is only for textures the game itself uses as UI background.
 */
final class VanillaTexture {
    /** The vanilla menu background, from the official client. */
    static final String DIRT = "assets/minecraft/textures/block/dirt.png";

    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private VanillaTexture() {}

    /**
     * The dirt texture from this installation's verified client, or null when it cannot be read
     * (no download is ever triggered here: the Manager resolves the client at startup).
     */
    static byte[] dirt(Path iconsDir) {
        Path jar = iconsDir.resolve("sources/26.3/client-" + VanillaAssets.CLIENT_SHA1 + ".jar");
        try {
            if (!VanillaAssets.valid(jar)) return null;
            return read(jar, DIRT);
        } catch (IOException e) {
            System.err.println("Vanilla dirt texture unavailable: " + e.getMessage());
            return null;
        }
    }

    /** Raw bytes of one jar entry, or null when missing, empty or not a PNG. */
    static byte[] read(Path jar, String entry) throws IOException {
        if (Files.isSymbolicLink(jar) || !Files.isRegularFile(jar)) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            if (found == null || found.isDirectory() || found.getSize() < PNG_MAGIC.length + 1) return null;
            try (InputStream in = zip.getInputStream(found)) {
                byte[] data = in.readAllBytes();
                if (data.length < PNG_MAGIC.length + 1) return null;
                for (int i = 0; i < PNG_MAGIC.length; i++) if (data[i] != PNG_MAGIC[i]) return null;
                return data;
            }
        }
    }
}
