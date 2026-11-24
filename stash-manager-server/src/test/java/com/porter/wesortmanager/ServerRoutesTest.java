package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRoutesTest {
    @Test
    void normalizePreservesPortAndStripsCaseAndTrailingDot() {
        assertEquals("6b6t.org", ServerRoutes.normalizeServer("6b6t.org"));
        assertEquals("6b6t.org", ServerRoutes.normalizeServer("  6B6T.ORG.  "));
        assertEquals("6b6t.org:25565", ServerRoutes.normalizeServer("6b6t.org.:25565"));
        assertEquals("alt.6b6t.org", ServerRoutes.normalizeServer("ALT.6b6t.org."));
        assertEquals("", ServerRoutes.normalizeServer(null));
        assertEquals("", ServerRoutes.normalizeServer("   "));
        assertEquals("[::1]:8765", ServerRoutes.normalizeServer("[::1]:8765"));
    }

    @Test
    void canonicalStripsAltAliasButPreservesPort() {
        assertEquals("6b6t.org", ServerRoutes.canonicalServer("6b6t.org"));
        assertEquals("6b6t.org:25565", ServerRoutes.canonicalServer("  ALT.6B6T.ORG:25565  "));
        assertEquals("127.0.0.1:54321", ServerRoutes.canonicalServer("127.0.0.1:54321"));
        assertEquals("", ServerRoutes.canonicalServer(null));
        assertEquals("", ServerRoutes.canonicalServer("   "));
    }

    @Test
    void sameServerUsesCanonicalForm() {
        assertTrue(ServerRoutes.sameServer("6b6t.org", "alt.6b6t.org"));
        assertTrue(ServerRoutes.sameServer("alt.6b6t.org", "6b6t.org"));
        assertTrue(ServerRoutes.sameServer("6B6T.ORG:25565", "alt.6b6t.org."));
        assertTrue(ServerRoutes.sameServer("6b6t.org", "6b6t.org"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", "evil.6b6t.org"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", "6b6t.com"));
        assertTrue(ServerRoutes.sameServer("127.0.0.1:54321", "127.0.0.1:54321"));
        assertFalse(ServerRoutes.sameServer("127.0.0.1:54321", "127.0.0.1:54322"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", "alt.6b6t.org:25566"));
        assertFalse(ServerRoutes.sameServer("6b6t.org", ""));
        assertFalse(ServerRoutes.sameServer("", "6b6t.org"));
    }

    @Test
    void configuredBaseDomainMatchesResolvedAgentAddress(@TempDir Path data) throws Exception {
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
        // Storage form: canonical host without the default port, so every database key
        // matches no matter how the address was spelled when it was configured.
        assertEquals("6b6t.org", config.minecraftServer());
        assertTrue(ServerRoutes.sameServer("6b6t.org", config.minecraftServer()));
        assertTrue(ServerRoutes.sameServer("alt.6b6t.org:25565", config.minecraftServer()));
        config.setMinecraftServer("127.0.0.1:54321");
        assertEquals("127.0.0.1:54321", Config.load(data).minecraftServer());
        assertEquals(-1, ServerRoutes.agentServerStatus(config, "127.0.0.1:54321"));
        assertEquals(409, ServerRoutes.agentServerStatus(config, "127.0.0.1:54322"));
    }

    @Test
    void storageKeyStripsOnlyTheDefaultPort() {
        assertEquals("6b6t.org", ServerRoutes.storageKey("6b6t.org:25565"));
        assertEquals("6b6t.org", ServerRoutes.storageKey("ALT.6B6T.ORG"));
        assertEquals("127.0.0.1:54321", ServerRoutes.storageKey("127.0.0.1:54321"));
        assertEquals("", ServerRoutes.storageKey(""));
        assertEquals("", ServerRoutes.storageKey(null));
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
