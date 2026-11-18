package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.QuotaRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * W-059: optional, disabled-by-default withdrawal quotas against the shared guild stock. A quota never creates a
 * private inventory; it only limits how much one member may withdraw. {@code reserved} and {@code consumed} are kept
 * separate from physical inventory reservations, and every reserve/release/consume is idempotent by order id so a
 * cancel or a verified delivery affects the quota exactly once, even across a Manager restart. Usage rolls over in
 * fixed, per-rule time windows; a reservation remembers its window so release/consume reconcile the same bucket.
 */
final class Quotas {
    record Line(String ref, long count) {
        Line {
            if (ref == null || ref.isBlank() || ref.length() > 512) throw new IllegalArgumentException("A quota line needs a reference");
            if (count < 1) throw new IllegalArgumentException("A quota line count must be positive");
        }
    }

    static final class Exceeded extends Exception {
        final String ref;
        final long limit;
        Exceeded(String ref, long limit) { super("Withdrawal exceeds the " + limit + " quota for " + ref); this.ref = ref; this.limit = limit; }
    }

    static final class Conflict extends IOException {
        Conflict() { super("Quota policy revision changed; reload before updating"); }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final LongSupplier clock;
    private boolean enabled;
    private long revision = 1;
    private List<QuotaRule> rules = new ArrayList<>();
    private final Map<String, Map<String, Map<Long, Usage>>> usage = new HashMap<>(); // member -> key -> windowStart -> usage
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();

    private record Usage(long consumed, long reserved) {}
    private record ReservedLine(String ref, long count, long windowStart) {}
    private record Reservation(String member, List<ReservedLine> lines, String state) {}

    Quotas(Path data) throws IOException { this(data, System::currentTimeMillis); }
    Quotas(Path data, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.file = data.resolve("quotas.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid quota location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            enabled = root.get("enabled").getAsBoolean();
            revision = root.get("revision").getAsLong();
            rules = QuotaRule.read(root);
            if (root.has("usage")) for (var memberEntry : root.getAsJsonObject("usage").entrySet()) {
                var perKey = new HashMap<String, Map<Long, Usage>>();
                for (var keyEntry : memberEntry.getValue().getAsJsonObject().entrySet()) {
                    var perWindow = new HashMap<Long, Usage>();
                    for (var windowEntry : keyEntry.getValue().getAsJsonObject().entrySet()) {
                        var u = windowEntry.getValue().getAsJsonObject();
                        perWindow.put(Long.parseLong(windowEntry.getKey()), new Usage(u.get("consumed").getAsLong(), u.get("reserved").getAsLong()));
                    }
                    perKey.put(keyEntry.getKey(), perWindow);
                }
                usage.put(memberEntry.getKey(), perKey);
            }
            if (root.has("reservations")) for (var entry : root.getAsJsonObject("reservations").entrySet()) {
                var r = entry.getValue().getAsJsonObject();
                var lines = new ArrayList<ReservedLine>();
                for (var line : r.getAsJsonArray("lines")) {
                    var l = line.getAsJsonObject();
                    lines.add(new ReservedLine(l.get("ref").getAsString(), l.get("count").getAsLong(), l.get("windowStart").getAsLong()));
                }
                reservations.put(entry.getKey(), new Reservation(r.get("member").getAsString(), lines, r.get("state").getAsString()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read quotas", invalid);
        }
    }

    synchronized JsonObject policy() {
        var out = new JsonObject();
        out.addProperty("enabled", enabled);
        out.addProperty("revision", revision);
        var list = new JsonArray();
        rules.forEach(rule -> list.add(rule.json()));
        out.add("rules", list);
        return out;
    }

    synchronized JsonObject update(JsonObject request, long expectedRevision) throws IOException {
        if (revision != expectedRevision) throw new Conflict();
        if (request.has("enabled")) {
            if (!request.get("enabled").isJsonPrimitive() || !request.get("enabled").getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("enabled must be a boolean");
            enabled = request.get("enabled").getAsBoolean();
        }
        if (request.has("rules")) {
            if (!request.get("rules").isJsonArray()) throw new IllegalArgumentException("rules must be an array");
            rules = QuotaRule.read(request);
        }
        revision = Math.addExact(revision, 1);
        save();
        return policy();
    }

    /** Reserves quota for an order; a no-op when quotas are disabled or the order id is already reserved. */
    synchronized void checkAndReserve(String member, List<String> roles, List<Line> lines, String orderId) throws Exceeded, IOException {
        if (!enabled) return;
        if (reservations.containsKey(orderId)) return;
        long now = clock.getAsLong();
        List<ReservedLine> reserved = new ArrayList<>();
        for (Line line : lines) {
            QuotaRule rule = QuotaRule.matching(rules, member, roles, line.ref());
            if (rule == null) continue;
            long window = windowStart(rule.windowSeconds(), now);
            long used = used(member, line.ref(), window);
            if (used + line.count() > rule.max()) throw new Exceeded(line.ref(), rule.max());
            reserved.add(new ReservedLine(line.ref(), line.count(), window));
        }
        for (ReservedLine line : reserved) addUsage(member, line.ref(), line.windowStart(), line.count(), true);
        reservations.put(orderId, new Reservation(member, reserved, "RESERVED"));
        save();
    }

    synchronized void release(String orderId) throws IOException {
        var reservation = reservations.get(orderId);
        if (reservation == null || !reservation.state().equals("RESERVED")) return;
        for (ReservedLine line : reservation.lines()) addUsage(reservation.member(), line.ref(), line.windowStart(), -line.count(), true);
        reservations.put(orderId, new Reservation(reservation.member(), reservation.lines(), "RELEASED"));
        save();
    }

    synchronized void consume(String orderId) throws IOException {
        var reservation = reservations.get(orderId);
        if (reservation == null || !reservation.state().equals("RESERVED")) return;
        for (ReservedLine line : reservation.lines()) {
            addUsage(reservation.member(), line.ref(), line.windowStart(), -line.count(), true);
            addUsage(reservation.member(), line.ref(), line.windowStart(), line.count(), false);
        }
        reservations.put(orderId, new Reservation(reservation.member(), reservation.lines(), "CONSUMED"));
        save();
    }

    /** A member's current consumed and reserved totals, keyed by line reference. */
    synchronized JsonObject usageFor(String member) {
        var out = new JsonObject();
        out.addProperty("member", member);
        var perKey = new JsonObject();
        var map = usage.get(member);
        if (map != null) for (var entry : map.entrySet()) {
            long consumed = 0, reserved = 0;
            for (var window : entry.getValue().values()) { consumed += window.consumed(); reserved += window.reserved(); }
            var row = new JsonObject();
            row.addProperty("consumed", consumed);
            row.addProperty("reserved", reserved);
            perKey.add(entry.getKey(), row);
        }
        out.add("usage", perKey);
        return out;
    }

    private long windowStart(long windowSeconds, long now) {
        long millis = windowSeconds * 1000L;
        return (now / millis) * millis;
    }

    private long used(String member, String key, long window) {
        var perKey = usage.get(member);
        if (perKey == null) return 0;
        var perWindow = perKey.get(key);
        if (perWindow == null) return 0;
        Usage u = perWindow.get(window);
        return u == null ? 0 : u.consumed() + u.reserved();
    }

    private void addUsage(String member, String key, long window, long count, boolean reserved) {
        var perKey = usage.computeIfAbsent(member, ignored -> new HashMap<>());
        var perWindow = perKey.computeIfAbsent(key, ignored -> new HashMap<>());
        Usage before = perWindow.getOrDefault(window, new Usage(0, 0));
        long c = before.consumed(), r = before.reserved();
        if (reserved) r = Math.max(0, r + count);
        else c = Math.max(0, c + count);
        perWindow.put(window, new Usage(c, r));
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("enabled", enabled);
        root.addProperty("revision", revision);
        var list = new JsonArray();
        rules.forEach(rule -> list.add(rule.json()));
        root.add("rules", list);
        var usageJson = new JsonObject();
        for (var memberEntry : usage.entrySet()) {
            var perKey = new JsonObject();
            for (var keyEntry : memberEntry.getValue().entrySet()) {
                var perWindow = new JsonObject();
                for (var windowEntry : keyEntry.getValue().entrySet()) {
                    var u = new JsonObject();
                    u.addProperty("consumed", windowEntry.getValue().consumed());
                    u.addProperty("reserved", windowEntry.getValue().reserved());
                    perWindow.add(String.valueOf(windowEntry.getKey()), u);
                }
                perKey.add(keyEntry.getKey(), perWindow);
            }
            usageJson.add(memberEntry.getKey(), perKey);
        }
        root.add("usage", usageJson);
        var reservationsJson = new JsonObject();
        for (var entry : reservations.entrySet()) {
            var r = new JsonObject();
            r.addProperty("member", entry.getValue().member());
            r.addProperty("state", entry.getValue().state());
            var lines = new JsonArray();
            for (ReservedLine line : entry.getValue().lines()) {
                var l = new JsonObject();
                l.addProperty("ref", line.ref());
                l.addProperty("count", line.count());
                l.addProperty("windowStart", line.windowStart());
                lines.add(l);
            }
            r.add("lines", lines);
            reservationsJson.add(entry.getKey(), r);
        }
        root.add("reservations", reservationsJson);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
