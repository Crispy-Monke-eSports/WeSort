package com.porter.wesortmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MinecraftDownloadConsentTest {
    @Test void newInstallDefaultsToNoDownload(@TempDir Path data) throws Exception {
        Config config = Config.load(data);
        assertFalse(config.acceptMinecraftDownload, "Download is opt-in only");
        String text = Files.readString(data.resolve("config.properties"));
        assertTrue(text.contains("acceptMinecraftDownload=false"));
        assertTrue(text.contains("https://www.minecraft.net/en-us/eula"));
        assertTrue(text.contains("Minecraft Java Edition license"));
    }

    @Test void existingInstallGetsOptInDocumentationWithoutChangingConsent(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve("config.properties"), "port=8765\niconSource=auto\n");
        Config config = Config.load(data);
        assertFalse(config.acceptMinecraftDownload);
        assertTrue(Files.readString(data.resolve("config.properties")).contains("acceptMinecraftDownload=false"));
        assertFalse(Config.load(data).acceptMinecraftDownload);
    }

    @Test void explicitTrueIsRecognizedAndInvalidValuesFailClosed(@TempDir Path data) throws Exception {
        Path path = data.resolve("config.properties");
        Files.writeString(path, "port=8765\nacceptMinecraftDownload=true\n");
        assertTrue(Config.load(data).acceptMinecraftDownload);
        Files.writeString(path, "port=8765\nacceptMinecraftDownload=yes\n");
        assertFalse(Config.load(data).acceptMinecraftDownload);
        Files.writeString(path, "port=8765\nacceptMinecraftDownload=false\n");
        assertFalse(Config.load(data).acceptMinecraftDownload);
    }
}
