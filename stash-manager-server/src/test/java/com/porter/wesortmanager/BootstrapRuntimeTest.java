package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapRuntimeTest {
    @TempDir
    Path data;

    @Test
    void capabilityExpiresRotatesAndCannotBeReused() {
        AtomicLong clock = new AtomicLong(1000);
        BootstrapToken tokens = new BootstrapToken(clock::get);
        String first = tokens.issue();
        assertEquals(43, first.length());
        assertTrue(tokens.matches(first));
        assertFalse(tokens.matches(null));
        assertFalse(tokens.matches("XXXX-XXXX"));
        String next = tokens.issue();
        assertFalse(tokens.matches(first));
        assertTrue(tokens.matches(next));
        clock.addAndGet(BootstrapToken.LIFETIME_MS);
        assertFalse(tokens.matches(next));
        String last = tokens.issue();
        tokens.clear();
        assertFalse(tokens.matches(last));
    }

    @Test
    void installationCannotBeOpenedTwiceAndLockCanBeReacquired() throws Exception {
        try (ManagerLock first = ManagerLock.acquire(data)) {
            assertThrows(IOException.class, () -> ManagerLock.acquire(data));
        }
        try (ManagerLock next = ManagerLock.acquire(data)) {
            assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(data.resolve(".manager.lock")));
        }
    }

    @Test
    void currentKeySlotsReportPresenceAndRotateIndependently() throws Exception {
        var clock = new AtomicLong(1_000_000);
        var issued = new AgentCredentials(data, clock::get);
        String token = issued.issue(".Bedrock Gamer");
        String id = token.substring(0, token.indexOf('.'));
        issued.requireScoped();
        var credentials = new AgentCredentials(data, clock::get);
        assertEquals(id, credentials.find(id).id());
        assertEquals(".Bedrock Gamer", credentials.find(id).label());
        assertNull(credentials.find(id).binding());
        // Any valid signed request may update display-only presence.
        credentials.notePlayer(id, "Agent0");
        assertEquals("Agent0", credentials.find(id).binding().playerName());
        clock.addAndGet(500);
        credentials.notePlayer(id, "Renamed");
        assertEquals("Renamed", credentials.find(id).binding().playerName());
        assertEquals(1_000_500, credentials.find(id).binding().lastSeen());
        // No UUID claim, exclusivity or rebind timeout: another name is immediate.
        credentials.notePlayer(id, "Eve");
        assertEquals("Eve", credentials.find(id).binding().playerName());
        credentials.notePlayer(id, ".Bedrock Gamer");
        assertEquals(".Bedrock Gamer", credentials.find(id).binding().playerName());
        credentials.notePlayer(id, "bad\nname");
        assertEquals(".Bedrock Gamer", credentials.find(id).binding().playerName());
        // Exactly one active agent key per Manager. Revoke before rotating.
        assertThrows(IllegalArgumentException.class, () -> credentials.issue("spare"));
        assertTrue(credentials.revoke(id));
        assertNull(credentials.find(id));
        String anotherKey = credentials.issue("agent0");
        String anotherId = anotherKey.substring(0, anotherKey.indexOf('.'));
        assertTrue(anotherKey.startsWith(anotherId + "."));
        assertNotNull(credentials.find(anotherId));
        assertThrows(IllegalArgumentException.class, () -> credentials.issue("Agent5"));
        assertThrows(IllegalArgumentException.class, () -> credentials.issue(""));
        assertTrue(credentials.revoke(anotherId));
        assertNull(credentials.find(anotherId));
        String replacementKey = credentials.issue("Agent5");
        String replacementId = replacementKey.substring(0, replacementKey.indexOf('.'));
        assertNotNull(credentials.find(replacementId));
        // Only credentials persist across restarts, not transient player presence.
        var reopened = new AgentCredentials(data, clock::get);
        assertNull(reopened.find(replacementId).binding());
        assertTrue(reopened.scopedOnly());
    }

    @Test
    void controlCapabilityIsRequiredForHttpSetupAndCannotBeReplayed() throws Exception {
        Config config = Config.load(data);
        config.setMinecraftServer("s");
        RuntimeHealth health = new RuntimeHealth(false);
        IndexStore index = new IndexStore(data, CreativeTabs.NONE);
        OrderStore orders = new OrderStore(data, index);
        SessionStore sessions = new SessionStore(data);
        Users users = new Users(data);
        IconService icons = new IconService(
                data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        var audit = new AuditLog(data);
        var routes = WeSortManager.routes(
                config,
                index,
                orders,
                icons,
                new LinkState(0),
                List.of(),
                sessions,
                users,
                audit,
                new BotHub(),
                new LoginLimiter(),
                health);
        var pool = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(pool);
        server.createContext("/", routes.router());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
        AtomicFiles.ownerOnlyDir(data);
        try (LocalControl control = new LocalControl(
                        data,
                        routes.auth(),
                        health,
                        config,
                        server.getAddress().getPort(),
                        routes.agentCredentials(),
                        audit);
                HttpClient http = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> LocalControl.request(data, "HEALTH"));
            health.ready(true);
            assertEquals("ready", LocalControl.request(data, "HEALTH"));
            var live = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertEquals("{\"status\":\"ready\"}", live.body());
            assertThrows(IOException.class, () -> LocalControl.request(data, "AGENT_KEY"));
            String token = LocalControl.request(data, "BOOTSTRAP");
            String json = "{\"code\":\"" + token + "\",\"password\":\"runtime-test-only-83927\"}";
            var badOrigin = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                    .header("Content-Type", "application/json")
                    .header("X-Stash-Manager", "1")
                    .header("Origin", "https://evil.invalid")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            assertEquals(
                    403,
                    http.send(badOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNull(config.dashboardPassword());
            var spoofed = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                    .header("Content-Type", "application/json")
                    .header("X-Stash-Manager", "1")
                    .header("X-Forwarded-For", "127.0.0.1")
                    .POST(HttpRequest.BodyPublishers.ofString(json.replace(token, "FORGED")))
                    .build();
            assertEquals(
                    403,
                    http.send(spoofed, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNull(config.dashboardPassword());
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                    .header("Content-Type", "application/json")
                    .header("X-Stash-Manager", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            var success = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, success.statusCode());
            assertNotNull(config.dashboardPassword());
            assertEquals(config.apiKey(), LocalControl.request(data, "AGENT_KEY"));
            assertEquals(
                    409,
                    http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertThrows(IOException.class, () -> LocalControl.request(data, "BOOTSTRAP"));
            assertFalse(JsonParser.parseString(success.body()).getAsJsonObject().has("apiKey"));
            var credentials = routes.agentCredentials();
            String first = credentials.issue("bot");
            String id = first.substring(0, first.indexOf('.'));
            assertNotNull(credentials.find(id));
            assertThrows(IllegalArgumentException.class, () -> credentials.issue(""));
            assertFalse(Files.readString(data.resolve("agent-credentials.json")).contains(first.substring(37)));
            credentials.requireScoped();
            assertThrows(IOException.class, () -> LocalControl.request(data, "AGENT_KEY"));
            var reopened = new AgentCredentials(data);
            assertTrue(reopened.scopedOnly());
            assertArrayEquals(
                    Signer.deriveKey(first.substring(37)),
                    java.util.Base64.getDecoder().decode(reopened.find(id).signingKey()));
            assertTrue(credentials.revoke(id));
            assertNull(new AgentCredentials(data).find(id));
            health.ready(false);
            assertEquals(
                    503,
                    http.send(
                                    HttpRequest.newBuilder(URI.create(base + "/health/ready"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
        } finally {
            server.stop(0);
            icons.close();
            pool.shutdownNow();
        }
        assertFalse(Files.exists(data.resolve(LocalControl.FILE)));
    }
}
