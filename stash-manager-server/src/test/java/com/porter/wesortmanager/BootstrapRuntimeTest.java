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

    @Test void controlCapabilityIsRequiredForHttpSetupAndCannotBeReplayed() throws Exception {
        Config config = Config.load(data);
        RuntimeHealth health = new RuntimeHealth(false);
        IndexStore index = new IndexStore(data, CreativeTabs.NONE);
        OrderStore orders = new OrderStore(data, index);
        Owners owners = new Owners(data);
        SessionStore sessions = new SessionStore(data);
        Users users = new Users(data);
        IconService icons = new IconService(data, id -> null, v -> false, new GameIcons(data.resolve("icons"), null, false), false);
        var routes = WeSortManager.routes(config, index, orders, owners, icons, new LinkState(0), List.of(),
            sessions, users, new AuditLog(data), new BotHub(), new LoginLimiter(), health);
        var pool = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(pool);
        server.createContext("/", routes.router());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
        AtomicFiles.ownerOnlyDir(data);
        try (LocalControl control = new LocalControl(data, routes.auth(), health, server.getAddress().getPort()); HttpClient http = HttpClient.newHttpClient()) {
            assertThrows(IOException.class, () -> LocalControl.request(data, "HEALTH"));
            health.ready(true);
            assertEquals("ready", LocalControl.request(data, "HEALTH"));
            var live = http.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, live.statusCode());
            assertEquals("{\"status\":\"ready\"}", live.body());
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
            assertEquals(409, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertThrows(IOException.class, () -> LocalControl.request(data, "BOOTSTRAP"));
            assertFalse(JsonParser.parseString(success.body()).getAsJsonObject().has("apiKey"));
            health.ready(false);
            assertEquals(503, http.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { server.stop(0); icons.close(); pool.shutdownNow(); }
        assertFalse(Files.exists(data.resolve(LocalControl.FILE)));
    }
}
