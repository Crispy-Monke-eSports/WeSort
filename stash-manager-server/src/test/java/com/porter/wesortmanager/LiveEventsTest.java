package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live bus: subscribers get every bump, and mutating calls map to the right channels. */
class LiveEventsTest {
    @Test
    void bumpReachesSubscriber() throws InterruptedException {
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        try {
            long rev = LiveEvents.bump("orders", "operations");
            LiveEvents.Event event = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(rev, event.rev());
            assertEquals(Set.of("orders", "operations"), event.channels());
        } finally {
            LiveEvents.unsubscribe(queue);
        }
    }

    @Test
    void slowSubscriberGetsBoundedResyncInsteadOfUnboundedQueue() throws InterruptedException {
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        try {
            for (int i = 0; i < LiveEvents.MAX_PENDING_EVENTS + 2; i++) LiveEvents.bump("orders");
            assertTrue(queue.size() <= LiveEvents.MAX_PENDING_EVENTS);
            assertTrue(queue.stream().anyMatch(e -> e.channels().contains("__unknown__")));
        } finally {
            LiveEvents.unsubscribe(queue);
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws InterruptedException {
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        LiveEvents.unsubscribe(queue);
        LiveEvents.bump("orders");
        assertTrue(queue.poll(200, TimeUnit.MILLISECONDS) == null);
    }

    @Test
    void routeChangedMapsMutations() throws InterruptedException {
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        try {
            LiveEvents.routeChanged("/api/v1/orders", "POST");
            assertEquals(Set.of("orders", "operations", "stashes"), take(queue).channels());

            LiveEvents.routeChanged("/api/v1/index", "POST");
            assertEquals(
                    Set.of("index", "stock", "stashes", "operations"),
                    take(queue).channels());

            LiveEvents.routeChanged("/api/v1/bot/activity", "POST");
            assertEquals(Set.of("bots", "operations"), take(queue).channels());

            LiveEvents.routeChanged("/api/v1/bots/abc/settings", "POST");
            assertEquals(Set.of("bots"), take(queue).channels());

            // Reads never push: the page already holds the data it asked for.
            LiveEvents.routeChanged("/api/v1/orders", "GET");
            LiveEvents.routeChanged("/api/v1/events", "GET");
            LiveEvents.routeChanged("/health", "POST");
            assertTrue(queue.poll(200, TimeUnit.MILLISECONDS) == null);
        } finally {
            LiveEvents.unsubscribe(queue);
        }
    }

    @Test
    void heartbeatPresenceIsThrottled() throws InterruptedException {
        LiveEvents.resetThrottlesForTests();
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        try {
            // Drain anything other tests published to the shared bus.
            while (queue.poll(50, TimeUnit.MILLISECONDS) != null) {}
            LiveEvents.routeChanged("/api/v1/heartbeat", "POST");
            assertEquals(Set.of("presence"), take(queue).channels());
            LiveEvents.routeChanged("/api/v1/heartbeat", "POST");
            assertTrue(queue.poll(200, TimeUnit.MILLISECONDS) == null);
        } finally {
            LiveEvents.unsubscribe(queue);
        }
    }

    private static LiveEvents.Event take(BlockingQueue<LiveEvents.Event> queue) throws InterruptedException {
        LiveEvents.Event event = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(event);
        return event;
    }

    // ---------- the stream over real HTTP ----------

    @TempDir
    java.nio.file.Path data;

    HttpServer server;
    String base;
    final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    @BeforeEach
    void startServer() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash("owner-password-123"));
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        OrderStore orders = new OrderStore(data, store);
        orders.load();
        IconService icons = new IconService(
                data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        SessionStore sessions = new SessionStore(data);
        sessions.load();
        Users users = new Users(data);
        users.load();
        AuditLog audit = new AuditLog(data);
        audit.load();
        BotHub hub = new BotHub();
        WeSortManager.Routes routes = WeSortManager.routes(
                config,
                store,
                orders,
                icons,
                new LinkState(config.keyState().generation()),
                List.of(),
                sessions,
                users,
                audit,
                hub,
                new LoginLimiter());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", routes.router());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /**
     * The registered stream speaks SSE and pushes a handled website write: open
     * GET /api/v1/events, POST a (rejected, still handled) order write, and the
     * tab gets an update frame naming the orders channel.
     */
    @Test
    void streamPushesHandledWrites() throws Exception {
        String session = login();
        BlockingQueue<String> frames = new java.util.concurrent.LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> readFrames(session, frames));
        reader.setDaemon(true);
        reader.start();
        try {
            String hello = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(hello, "no hello frame from /api/v1/events");
            assertTrue(hello.contains("event: hello"), hello);

            int status = post(session, "/api/v1/orders/clear", "{}");
            assertTrue(status == 400 || status == 200, "unexpected status " + status);

            String update = null;
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                String frame = frames.poll(2, TimeUnit.SECONDS);
                if (frame == null) break;
                if (frame.contains("event: update") && frame.contains("orders")) {
                    update = frame;
                    break;
                }
            }
            assertNotNull(update, "no orders update frame after the handled write");
        } finally {
            reader.interrupt();
        }
    }

    private String login() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("X-Stash-Manager", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"owner\",\"password\":\"owner-password-123\"}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        for (String cookie : response.headers().allValues("Set-Cookie")) {
            int start = cookie.indexOf(SessionStore.COOKIE + "=");
            if (start >= 0) {
                String value = cookie.substring(start + (SessionStore.COOKIE + "=").length());
                int end = value.indexOf(';');
                return end < 0 ? value : value.substring(0, end);
            }
        }
        throw new IllegalStateException("login set no session cookie: " + response.body());
    }

    private int post(String session, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Stash-Manager", "1")
                .header("Content-Type", "application/json")
                .header("Cookie", SessionStore.COOKIE + "=" + session)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Reads raw SSE frames (separated by a blank line) until interrupted. */
    private void readFrames(String session, BlockingQueue<String> frames) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/events"))
                    .header("Cookie", SessionStore.COOKIE + "=" + session)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                frames.offer("HTTP " + response.statusCode());
                return;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.startsWith("text/event-stream")) {
                frames.offer("BAD CONTENT TYPE " + contentType);
                return;
            }
            java.io.BufferedReader reader =
                    new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8));
            StringBuilder frame = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (frame.length() > 0) {
                        frames.offer(frame.toString());
                        frame.setLength(0);
                    }
                } else {
                    frame.append(line).append('\n');
                }
            }
        } catch (Exception ignored) {
            // Interrupted when the test ends or the server stops.
        }
    }
}
