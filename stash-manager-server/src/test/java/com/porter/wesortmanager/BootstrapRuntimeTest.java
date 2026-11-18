package com.porter.wesortmanager;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.*;

class BootstrapRuntimeTest {
    @TempDir Path data;

    @Test void capabilityExpiresRotatesAndCannotBeReused() {
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

    @Test void installationCannotBeOpenedTwiceAndLockCanBeReacquired() throws Exception {
        try (ManagerLock first = ManagerLock.acquire(data)) {
            assertThrows(IOException.class, () -> ManagerLock.acquire(data));
        }
        try (ManagerLock next = ManagerLock.acquire(data)) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(data.resolve(".manager.lock")));
        }
    }

    @Test void fleetScopesMigrateAndRotateIndependently() throws Exception {
        var id = java.util.UUID.randomUUID().toString();
        var file = data.resolve("agent-credentials.json");
        var key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        Files.writeString(file, """
            {"version":1,"scopedOnly":true,"agents":[{"agent":"%s","player":"Agent0","server":"srv","stash":"old","key":"%s"}]}
            """.formatted(id, key));
        var credentials = new AgentCredentials(data);
        assertTrue(credentials.find(id).scope().matchesLocation("srv", "old"));
        assertFalse(credentials.find(id).scope().matchesLocation("srv", "new"));
        var scope = new AgentCredentials.Scope(id, "Agent0", "srv", java.util.Set.of("old", "new"));
        assertThrows(IllegalArgumentException.class, () -> credentials.issue(
            new AgentCredentials.Scope(java.util.UUID.randomUUID().toString(), "agent0", "srv", "new")));
        for (int i = 1; i < AgentCredentials.MAX_ACTIVE_AGENTS; i++) {
            credentials.issue(new AgentCredentials.Scope(java.util.UUID.randomUUID().toString(), "Agent" + i, "srv", "stash" + i));
        }
        var sixth = new AgentCredentials.Scope(java.util.UUID.randomUUID().toString(), "Agent5", "srv", "stash5");
        assertThrows(IllegalArgumentException.class, () -> credentials.issue(sixth));
        credentials.issue(scope);
        var reopened = new AgentCredentials(data);
        assertEquals(scope, reopened.find(id).scope());
        assertFalse(reopened.find(id).scope().matchesLocation("other", "new"));
        assertTrue(reopened.revoke(id));
        assertNotNull(reopened.issue(sixth));
    }

    @Test void controlCapabilityIsRequiredForHttpSetupAndCannotBeReplayed() throws Exception {
        Config config = Config.load(data);
        RuntimeHealth health = new RuntimeHealth(false);
        IndexStore index = new IndexStore(data, CreativeTabs.NONE);
        OrderStore orders = new OrderStore(data, index);
        Owners owners = new Owners(data);
        SessionStore sessions = new SessionStore(data);
        Users users = new Users(data);
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        var audit = new AuditLog(data);
        var routes = WeSortManager.routes(config, index, orders, owners, icons, new LinkState(0), List.of(),
            sessions, users, audit, new BotHub(), new LoginLimiter(), health);
        var pool = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(pool);
        server.createContext("/", routes.router());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
        AtomicFiles.ownerOnlyDir(data);
        try (LocalControl control = new LocalControl(data, routes.auth(), health, config, server.getAddress().getPort(),
                 routes.agentCredentials(), audit); HttpClient http = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> LocalControl.request(data, "HEALTH"));
            health.ready(true);
            assertEquals("ready", LocalControl.request(data, "HEALTH"));
            var live = http.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertEquals("{\"status\":\"ready\"}", live.body());
            assertThrows(IOException.class, () -> LocalControl.request(data, "AGENT_KEY"));
            String token = LocalControl.request(data, "BOOTSTRAP");
            String json = "{\"code\":\"" + token + "\",\"password\":\"runtime-test-only-83927\"}";
            var badOrigin = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                .header("Content-Type", "application/json").header("X-Stash-Manager", "1")
                .header("Origin", "https://evil.invalid").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            assertEquals(403, http.send(badOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNull(config.dashboardPassword());
            var spoofed = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                .header("Content-Type", "application/json").header("X-Stash-Manager", "1")
                .header("X-Forwarded-For", "127.0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(json.replace(token, "FORGED"))).build();
            assertEquals(403, http.send(spoofed, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertNull(config.dashboardPassword());
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/setup"))
                .header("Content-Type", "application/json").header("X-Stash-Manager", "1")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var success = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, success.statusCode());
            assertNotNull(config.dashboardPassword());
            assertEquals(config.apiKey(), LocalControl.request(data, "AGENT_KEY"));
            assertEquals(409, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertThrows(IOException.class, () -> LocalControl.request(data, "BOOTSTRAP"));
            assertFalse(JsonParser.parseString(success.body()).getAsJsonObject().has("apiKey"));
            String agent = java.util.UUID.randomUUID().toString();
            var scope = new AgentCredentials.Scope(agent, "TestAgent", "server", "stash");
            var credentials = routes.agentCredentials();
            String first = credentials.issue(scope);
            String replacement = credentials.issue(scope);
            assertNotEquals(first, replacement);
            assertFalse(Files.readString(data.resolve("agent-credentials.json")).contains(replacement.substring(37)));
            assertThrows(IllegalArgumentException.class,
                () -> credentials.issue(new AgentCredentials.Scope(agent, "TestAgent", "other-server", "stash")));
            credentials.requireScoped();
            assertThrows(IOException.class, () -> LocalControl.request(data, "AGENT_KEY"));
            var reopened = new AgentCredentials(data);
            assertTrue(reopened.scopedOnly());
            assertArrayEquals(Signer.deriveKey(replacement.substring(37)), reopened.find(agent).key());
            assertTrue(credentials.revoke(agent));
            assertNull(new AgentCredentials(data).find(agent));
            health.ready(false);
            assertEquals(503, http.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { server.stop(0); icons.close(); pool.shutdownNow(); }
        assertFalse(Files.exists(data.resolve(LocalControl.FILE)));
    }
}
