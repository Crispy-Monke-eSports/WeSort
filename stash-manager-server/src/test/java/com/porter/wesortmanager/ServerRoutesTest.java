package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.porter.wesort.data.MinecraftServerAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRoutesTest {
    @Test
    void normalizePreservesPortAndStripsCaseAndTrailingDot() {
        assertEquals("6b6t.org", MinecraftServerAddress.normalizeServer("6b6t.org"));
        assertEquals("6b6t.org", MinecraftServerAddress.normalizeServer("  6B6T.ORG.  "));
        assertEquals("6b6t.org:25565", MinecraftServerAddress.normalizeServer("6b6t.org.:25565"));
        assertEquals("alt.6b6t.org", MinecraftServerAddress.normalizeServer("ALT.6b6t.org."));
        assertEquals("", MinecraftServerAddress.normalizeServer(null));
        assertEquals("", MinecraftServerAddress.normalizeServer("   "));
        assertEquals("[::1]:8765", MinecraftServerAddress.normalizeServer("[::1]:8765"));
    }

    @Test
    void canonicalStripsAltAliasButPreservesPort() {
        assertEquals("6b6t.org", MinecraftServerAddress.canonicalServer("6b6t.org"));
        assertEquals("6b6t.org:25565", MinecraftServerAddress.canonicalServer("  ALT.6B6T.ORG:25565  "));
        assertEquals("127.0.0.1:54321", MinecraftServerAddress.canonicalServer("127.0.0.1:54321"));
        assertEquals("", MinecraftServerAddress.canonicalServer(null));
        assertEquals("", MinecraftServerAddress.canonicalServer("   "));
    }

    @Test
    void sameServerUsesCanonicalForm() {
        assertTrue(MinecraftServerAddress.sameServer("6b6t.org", "alt.6b6t.org"));
        assertTrue(MinecraftServerAddress.sameServer("alt.6b6t.org", "6b6t.org"));
        assertTrue(MinecraftServerAddress.sameServer("6B6T.ORG:25565", "alt.6b6t.org."));
        assertTrue(MinecraftServerAddress.sameServer("6b6t.org", "6b6t.org"));
        assertFalse(MinecraftServerAddress.sameServer("6b6t.org", "evil.6b6t.org"));
        assertFalse(MinecraftServerAddress.sameServer("example.org", "alt.example.org"));
        assertFalse(MinecraftServerAddress.sameServer("6b6t.org", "alt.6b6t.org.example.org"));
        assertFalse(MinecraftServerAddress.sameServer("6b6t.org", "6b6t.com"));
        assertTrue(MinecraftServerAddress.sameServer("127.0.0.1:54321", "127.0.0.1:54321"));
        assertFalse(MinecraftServerAddress.sameServer("127.0.0.1:54321", "127.0.0.1:54322"));
        assertFalse(MinecraftServerAddress.sameServer("6b6t.org", "alt.6b6t.org:25566"));
        assertFalse(MinecraftServerAddress.sameServer("6b6t.org", ""));
        assertFalse(MinecraftServerAddress.sameServer("", "6b6t.org"));
    }

    @Test
    void settingServerStoresNormalizedHost(@TempDir Path data) throws Exception {
        Config.load(data);
        Files.writeString(data.resolve("config.properties"), "port=8765\neula=true\n");
        Config config = Config.load(data);
        config.setMinecraftServer("  ALT.6b6t.org:25565  ");
        assertEquals("6b6t.org:25565", config.minecraftServer());
        assertTrue(MinecraftServerAddress.sameServer("6b6t.org", config.minecraftServer()));
        config.setMinecraftServer("127.0.0.1:54321");
        assertEquals("127.0.0.1:54321", Config.load(data).minecraftServer());
    }

    @Test
    void physicalObservationKeepsWireIdentityUnderConfiguredStorageKey(@TempDir Path data) throws Exception {
        var ledger = new StockLedger(data);
        var index = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"alt.6b6t.org:25565\",\"stash\":\"a\",\"receivedAt\":1000,"
                                + "\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1}]}")
                .getAsJsonObject();
        var observation = StockFixtures.physical(index, ledger.epoch(), 0, 1, 0);
        var id = observation
                .getAsJsonArray("physicalSources")
                .get(0)
                .getAsJsonObject()
                .get("id");
        ledger.accept(observation, "6b6t.org");
        assertEquals("alt.6b6t.org:25565", observation.get("server").getAsString());
        assertEquals("6b6t.org", ledger.sources().getFirst().server());
        assertEquals(id, ledger.sources().getFirst().value().get("id"));
        assertEquals("6b6t.org", ledger.indexes().getFirst().get("server").getAsString());
    }
}
