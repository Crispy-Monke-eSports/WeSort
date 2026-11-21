package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The website's live feed (GET /api/v1/events, Server-Sent Events).
 *
 * <p>The page holds one stream per tab and turns every {@code update} event into TanStack Query
 * invalidations — no polling. Each event names coarse channels ({@code orders}, {@code index},
 * {@code stock}, {@code stashes}, {@code bots}, {@code presence}, {@code operations}); the page
 * maps them to query keys and refetches.
 *
 * <p>Streams are short on purpose: at most ~50 s (under the server's max response time), then the
 * browser's EventSource reconnects and resyncs. Permission is re-checked while the stream runs, so
 * a revoked user stops receiving within seconds. Offline transitions (a bot that simply stops
 * talking) are detected by a per-stream presence sweep, since no request marks that moment.
 */
final class LiveRoutes {
    /** Idle ping: keeps NATs and proxies from killing a quiet stream. */
    static final long PING_MS = 15_000;
    /** A stream never outlives this (under maxRspTime); EventSource reconnects by itself. */
    static final long MAX_AGE_MS = 50_000;
    /** How often a stream re-checks bot/stash presence for silent offline transitions. */
    static final long PRESENCE_SWEEP_MS = 10_000;

    private final BotHub hub;
    private final IndexStore store;
    private Router router;
    private final ExecutorService pool;

    LiveRoutes(BotHub hub, IndexStore store) {
        this.hub = hub;
        this.store = store;
        this.pool = Executors.newCachedThreadPool(daemonThreads());
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "wesort-live-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    void register(Router router) {
        this.router = router;
        router.exact(Router.GET, "/api/v1/events", Router.Guard.SESSION, Router.Perm.VIEW, this::stream);
    }

    private void stream(HttpExchange ex) throws IOException {
        String token = (String) Http.attr(ex, Router.SESSION_TOKEN);
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        // Another thread owns the exchange from here; the Router must not close it.
        Http.attr(ex, Http.DETACHED, Boolean.TRUE);
        pool.execute(() -> run(ex, token));
    }

    private void run(HttpExchange ex, String token) {
        BlockingQueue<LiveEvents.Event> queue = LiveEvents.subscribe();
        try {
            OutputStream out = ex.getResponseBody();
            long start = System.currentTimeMillis();
            long lastWrite = start;
            long lastSweep = 0;
            Set<String> presence = fingerprint();
            send(out, "hello", "{\"rev\":" + LiveEvents.rev() + "}");
            while (true) {
                long now = System.currentTimeMillis();
                if (now - start >= MAX_AGE_MS) break;
                if (router == null || !router.stillAllowed(token, Router.Perm.VIEW, null)) break;
                if (now - lastSweep >= PRESENCE_SWEEP_MS) {
                    lastSweep = now;
                    Set<String> current = fingerprint();
                    if (!current.equals(presence)) {
                        presence = current;
                        LiveEvents.bump("presence");
                    }
                }
                LiveEvents.Event event;
                try {
                    event = queue.poll(1_000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                now = System.currentTimeMillis();
                if (event != null) {
                    JsonObject data = new JsonObject();
                    data.addProperty("rev", event.rev());
                    JsonArray channels = new JsonArray();
                    for (String channel : event.channels()) channels.add(channel);
                    data.add("channels", channels);
                    send(out, "update", Json.GSON.toJson(data));
                    lastWrite = now;
                } else if (now - lastWrite >= PING_MS) {
                    sendComment(out, "ping");
                    lastWrite = now;
                }
            }
        } catch (IOException gone) {
            // The tab went away mid-stream. Nothing useful to send back.
        } finally {
            LiveEvents.unsubscribe(queue);
            try {
                ex.close();
            } catch (RuntimeException ignored) {
                // Headers were probably already sent.
            }
        }
    }

    /**
     * Who is currently online (bots plus stash indexes). Heartbeats refresh this constantly, so the
     * sweep only fires when an online flag actually flips — the silent offline transition.
     */
    private Set<String> fingerprint() {
        Set<String> out = new HashSet<>();
        long now = hub.now();
        try {
            for (BotHub.Bot bot : hub.all()) {
                boolean online;
                synchronized (bot) {
                    online = BotHub.online(bot, now);
                }
                out.add("bot:" + bot.botId + ":" + online);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            for (com.google.gson.JsonElement element : store.listJson()) {
                JsonObject o = element.getAsJsonObject();
                out.add("stash:" + o.get("stash").getAsString() + ":"
                        + (o.has("online") && o.get("online").getAsBoolean()));
            }
        } catch (RuntimeException ignored) {
            // listJson is best-effort here; the next sweep retries.
        }
        return out;
    }

    private static void send(OutputStream out, String event, String data) throws IOException {
        StringBuilder frame = new StringBuilder();
        frame.append("event: ").append(event).append('\n');
        for (String line : data.split("\n", -1)) frame.append("data: ").append(line).append('\n');
        frame.append('\n');
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sendComment(OutputStream out, String comment) throws IOException {
        out.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
