package com.porter.wesortmanager;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The central push bus behind the website's live feed (GET /api/v1/events).
 *
 * <p>Mutations bump named channels; each connected browser stream holds a queue and gets the event
 * within milliseconds. Channels are coarse on purpose — the page refetches through TanStack Query,
 * which de-duplicates and keeps the cache consistent — so a bump is always safe to send even when
 * nothing visible changed.
 *
 * <p>Static on purpose: the stores and the hub stay free of wiring, and bumps from new routes work
 * without registration. {@link Router} calls {@link #routeChanged} after every handled mutating API
 * call, so new endpoints are live by default.
 */
final class LiveEvents {
    /** One broadcast: the revision after the bump and the channels it touches. */
    record Event(long rev, Set<String> channels) {}

    static final int MAX_PENDING_EVENTS = 128;
    private static final AtomicLong REV = new AtomicLong();
    private static final Set<BlockingQueue<Event>> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> THROTTLED = new ConcurrentHashMap<>();

    private LiveEvents() {}

    /** The current revision (sent as the stream's hello so a client can tell it is current). */
    static long rev() {
        return REV.get();
    }

    static BlockingQueue<Event> subscribe() {
        BlockingQueue<Event> queue = new LinkedBlockingQueue<>(MAX_PENDING_EVENTS);
        SUBSCRIBERS.add(queue);
        return queue;
    }

    static void unsubscribe(BlockingQueue<Event> queue) {
        SUBSCRIBERS.remove(queue);
    }

    /** Broadcasts to every connected stream. Returns the new revision. */
    static long bump(String... channels) {
        return publish(Set.of(channels));
    }

    static long publish(Set<String> channels) {
        long rev = REV.incrementAndGet();
        Event event = new Event(rev, Set.copyOf(channels));
        for (BlockingQueue<Event> queue : SUBSCRIBERS) {
            if (!queue.offer(event)) {
                // A slow subscriber must resync, not retain an unbounded backlog of stale updates.
                queue.clear();
                queue.offer(new Event(rev, Set.of("__unknown__")));
            }
        }
        return rev;
    }

    /**
     * Broadcasts at most once per {@code minIntervalMillis} per key (heartbeats). Returns the new
     * revision, or -1 when suppressed.
     */
    static long bumpThrottled(String key, long minIntervalMillis, String... channels) {
        long now = System.currentTimeMillis();
        Long last = THROTTLED.get(key);
        if (last != null && now - last < minIntervalMillis) return -1;
        THROTTLED.put(key, now);
        return publish(Set.of(channels));
    }

    /** Clears throttle markers (tests only). */
    static void resetThrottlesForTests() {
        THROTTLED.clear();
    }

    /**
     * Channel bumps for a handled mutating API call. Heartbeats only refresh presence (throttled);
     * everything else pushes immediately.
     */
    static void routeChanged(String path, String method) {
        if (!"POST".equals(method) || path == null || !path.startsWith("/api/v1/")) return;
        if (path.equals("/api/v1/index")) {
            bump("index", "stock", "stashes", "operations");
            return;
        }
        if (path.equals("/api/v1/heartbeat")) {
            bumpThrottled("heartbeat", 15_000, "presence");
            return;
        }
        if (path.startsWith("/api/v1/orders")) {
            bump("orders", "operations", "stashes");
            return;
        }
        if (path.startsWith("/api/v1/bot/")) {
            bump("bots", "operations");
            return;
        }
        if (path.startsWith("/api/v1/bots/")) {
            bump("bots");
            return;
        }
        if (path.equals("/api/v1/rescan")) {
            bump("index", "operations");
            return;
        }
        // Any other website write (boxes, custody, handoffs, deposits, jobs, physical,
        // guild, quotas, server, stash-defs, ...): the operations view may show it.
        bump("operations");
    }
}
