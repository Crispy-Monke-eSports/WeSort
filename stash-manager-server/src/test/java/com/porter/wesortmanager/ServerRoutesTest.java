package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRoutesTest {
    @Test
    void normalizeStripsCasePortAndTrailingDot() {
        assertEquals("6b6t.org", ServerRoutes.normalizeServer("6b6t.org"));
        assertEquals("6b6t.org", ServerRoutes.normalizeServer("  6B6T.ORG.  "));
        assertEquals("6b6t.org", ServerRoutes.normalizeServer("6b6t.org:25565"));
        assertEquals("alt.6b6t.org", ServerRoutes.normalizeServer("ALT.6b6t.org."));
        assertEquals("", ServerRoutes.normalizeServer(null));
        assertEquals("", ServerRoutes.normalizeServer("   "));
        assertEquals("[::1]", ServerRoutes.normalizeServer("[::1]:8765"));
    }

    @Test
    void sameServerAcceptsSrvTargetAlias() {
        assertTrue(ServerRoutes.sameServer("6b6t.org", "alt.6b6t.org"));
        assertTrue(ServerRoutes.sameServer("alt.6b6t.org", "6b6t.org"));
        assertTrue(ServerRoutes.sameServer("6B6T.ORG:25565", "alt.6b6t.org."));
        assertTrue(ServerRoutes.sameServer("6b6t.org", "6b6t.org"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", "evil.6b6t.org"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", "6b6t.com"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", ""));
        assertFalse(ServerRoutes.sameServer("", "6b6t.org"));
    }

    @Test
    void agentServerStatusUsesNormalizedIdentity(@TempDir Path data) throws Exception {
        Config.load(data);
        Files.writeString(data.resolve("config.properties"), "port=8765\neula=true\nminecraftServer=6b6t.org\n");
        Config config = Config.load(data);
        assertEquals(-1, ServerRoutes.agentServerStatus(config, "alt.6b6t.org:25565"));
        assertEquals(-1, ServerRoutes.agentServerStatus(config, "6B6T.ORG."));
        assertEquals(409, ServerRoutes.agentServerStatus(config, "other.org"));
        assertEquals(400, ServerRoutes.agentServerStatus(config, ""));
    }

    @Test
    void settingServerStoresNormalizedHost(@TempDir Path data) throws Exception {
        Config.load(data);
        Files.writeString(data.resolve("config.properties"), "port=8765\neula=true\n");
        Config config = Config.load(data);
        config.setMinecraftServer("  ALT.6b6t.org:25565  ");
        assertEquals("alt.6b6t.org", config.minecraftServer());
        assertTrue(ServerRoutes.sameServer("6b6t.org", config.minecraftServer()));
    }
}
