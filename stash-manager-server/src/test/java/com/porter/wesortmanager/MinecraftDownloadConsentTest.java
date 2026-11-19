package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftDownloadConsentTest {
    @Test
    void newInstallRequiresEulaBeforeStartup(@TempDir Path data) throws Exception {
        Config config = Config.load(data);
        assertFalse(config.eulaAccepted);
        assertTrue(assertThrows(IOException.class, () -> WeSortManager.requireEula(config))
                .getMessage()
                .contains("eula=true"));
        String text = Files.readString(data.resolve("config.properties"));
        assertTrue(text.contains("eula=false"));
        assertTrue(text.contains("https://www.minecraft.net/en-us/eula"));
        assertTrue(text.contains("Minecraft Java Edition license"));
        assertFalse(text.contains("\nminecraftJar="));
        assertFalse(text.contains("\nacceptMinecraftDownload="));
    }

    @Test
    void oldDownloadOptInOrLocalClientCannotBypassEula(@TempDir Path data) throws Exception {
        Config.load(data);
        Files.writeString(
                data.resolve("config.properties"),
                "port=8765\nacceptMinecraftDownload=true\nminecraftJar=/old/client.jar\niconSource=wiki\n");
        Config config = Config.load(data);
        assertFalse(config.eulaAccepted);
        assertThrows(IOException.class, () -> WeSortManager.requireEula(config));
        assertTrue(Files.readString(data.resolve("config.properties")).contains("eula=false"));
    }

    @Test
    void explicitEulaTruePermitsStartupAndOtherValuesFailClosed(@TempDir Path data) throws Exception {
        Config.load(data);
        Path file = data.resolve("config.properties");
        Files.writeString(file, "port=8765\neula=true\n");
        assertTrue(Config.load(data).eulaAccepted);
        assertDoesNotThrow(() -> WeSortManager.requireEula(Config.load(data)));
        Files.writeString(file, "port=8765\neula=yes\n");
        assertFalse(Config.load(data).eulaAccepted);
        Files.writeString(file, "port=8765\neula=false\n");
        assertFalse(Config.load(data).eulaAccepted);
    }
}
