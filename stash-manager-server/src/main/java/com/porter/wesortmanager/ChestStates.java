package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-chest freshness reported by the agent, and the Manager's recheck decisions (inversion of
 * control: the agent tells the Manager which chests exist and when each was last read; the Manager,
 * which owns the database, answers with the positions it wants re-read).
 *
 * <p>A heartbeat may carry {@code chests: [{x, y, z, readAt}]}. The report replaces the stored set
 * for that server+stash; an absent field leaves the stored set alone. The heartbeat reply carries
 * {@code recheck: [{x, y, z}]} with the chests whose last read is older than the chest max age.
 * A chest is asked for once per read, and — so a laggy server never loses a chest whose reads
 * keep failing — again once the last request itself is older than the max age. In memory only,
 * like {@link DeliveryContents}.
 */
final class ChestStates {
    static final int MAX_STASHES = 256;
    static final int MAX_CHESTS = 4096;
    static final int MAX_RECHECK = 128;
    static final int COORD = 30_000_000;

    /** One known chest: position and when the agent last read it (epoch millis, 0 = never). */
    record Chest(int x, int y, int z, long readAt) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            return o;
        }
    }

    private record Pos(int x, int y, int z) {}

    private record Entry(long readAt, long seenAt, long lastRequestedReadAt, long lastRequestedAt) {}

    private final Clock clock;
    private final long maxAgeMillis;
    private final LinkedHashMap<String, Map<Pos, Entry>> byStash = new LinkedHashMap<>(); // oldest first

    ChestStates() {
        this(Clock.system(), policy());
    }

    ChestStates(Clock clock, long maxAgeMillis) {
        if (maxAgeMillis < 1) throw new IllegalArgumentException("Positive chest max age required");
        this.clock = clock;
        this.maxAgeMillis = maxAgeMillis;
    }

    private static long policy() {
        String value = System.getenv().getOrDefault("WESORT_CHEST_MAX_AGE_SECONDS", "300");
        long seconds = Long.parseLong(value);
        if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("WESORT_CHEST_MAX_AGE_SECONDS must be 1..86400");
        return seconds * 1000;
    }

    private static String key(String server, String stash) {
        return server + "\u0000" + stash;
    }

    /** Validates a heartbeat {@code chests} value and returns it cleaned, or throws with the reason. */
    static List<Chest> parse(JsonElement e) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("chests must be a list");
        JsonArray in = e.getAsJsonArray();
        if (in.size() > MAX_CHESTS) throw new IllegalArgumentException("at most " + MAX_CHESTS + " chests");
        List<Chest> out = new ArrayList<>(in.size());
        for (JsonElement c : in) {
            if (!c.isJsonObject()) throw new IllegalArgumentException("every chest must be an object");
            JsonObject o = c.getAsJsonObject();
            Integer x = whole(o.get("x")), y = whole(o.get("y")), z = whole(o.get("z"));
            if (x == null || y == null || z == null)
                throw new IllegalArgumentException("chest x, y and z must be whole numbers");
            if (Math.abs(x) > COORD || Math.abs(z) > COORD || y < -2048 || y > 2048)
                throw new IllegalArgumentException("chest position is out of range");
            Long readAt = wholeLong(o.get("readAt"));
            if (readAt == null || readAt < 0)
                throw new IllegalArgumentException("chest readAt must be a whole number >= 0");
            out.add(new Chest(x, y, z, readAt));
        }
        return out;
    }

    private static Integer whole(JsonElement e) {
        Long v = wholeLong(e);
        if (v == null) return null;
        try {
            return Math.toIntExact(v);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static Long wholeLong(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            BigDecimal d = e.getAsBigDecimal();
            if (d.stripTrailingZeros().scale() > 0) return null;
            return d.longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    /** Replaces the stored chest set for a server+stash with a fresh report. */
    synchronized void report(String server, String stash, List<Chest> chests) {
        String k = key(server, stash);
        Map<Pos, Entry> old = byStash.remove(k);
        Map<Pos, Entry> set = new LinkedHashMap<>();
        long now = clock.millis();
        for (Chest c : chests) {
            Pos p = new Pos(c.x(), c.y(), c.z());
            // A chest the agent still knows keeps its suppression, so one report never re-asks
            // for everything at once: carry over the requested read and time per position.
            long requestedRead = -1;
            long requestedAt = 0;
            if (old != null) {
                Entry prev = old.get(p);
                if (prev != null && prev.lastRequestedReadAt() == c.readAt()) {
                    requestedRead = prev.lastRequestedReadAt();
                    requestedAt = prev.lastRequestedAt();
                }
            }
            set.put(p, new Entry(c.readAt(), now, requestedRead, requestedAt));
        }
        byStash.put(k, set);
        while (byStash.size() > MAX_STASHES) {
            Iterator<String> it = byStash.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * Positions the Manager wants re-read: chests last read longer ago than the chest max age,
     * oldest first, at most {@link #MAX_RECHECK}. A chest is asked for once per read, and — so a
     * laggy server never loses a chest forever when reads keep failing — again once the last
     * request itself is older than the max age, even with no newer read. Chests never read
     * ({@code readAt <= 0}) are the agent's own business (it reads unknown chests by itself)
     * and are never requested.
     */
    synchronized JsonArray due(String server, String stash) {
        JsonArray out = new JsonArray();
        Map<Pos, Entry> set = byStash.get(key(server, stash));
        if (set == null || set.isEmpty()) return out;
        long now = clock.millis();
        List<Map.Entry<Pos, Entry>> old = new ArrayList<>();
        for (var en : set.entrySet()) {
            Entry e = en.getValue();
            if (e.readAt() <= 0) continue;
            if (now - e.readAt() < maxAgeMillis) continue;
            if (e.lastRequestedReadAt() == e.readAt() && now - e.lastRequestedAt() < maxAgeMillis) continue;
            old.add(en);
        }
        old.sort((a, b) -> Long.compare(a.getValue().readAt(), b.getValue().readAt()));
        int n = 0;
        for (var en : old) {
            if (n >= MAX_RECHECK) break;
            Entry e = set.get(en.getKey());
            if (e == null || (e.lastRequestedReadAt() == e.readAt() && now - e.lastRequestedAt() < maxAgeMillis))
                continue;
            set.put(en.getKey(), new Entry(e.readAt(), e.seenAt(), e.readAt(), now));
            out.add(chestJson(en.getKey()));
            n++;
        }
        return out;
    }

    private static JsonObject chestJson(Pos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        return o;
    }

    /** For tests: how many stashes are held. */
    synchronized int size() {
        return byStash.size();
    }
}
