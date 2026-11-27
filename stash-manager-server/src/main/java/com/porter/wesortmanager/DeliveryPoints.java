package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.DeliveryPoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W-037: the registered delivery destinations of the guild network. A point is created PENDING by an operator,
 * becomes VERIFIED once an agent has confirmed reachability and slots, and is PAUSED when a changed or broken
 * destination must block an active assignment before it dumps. Coordinates and dimension are operator-only: a
 * member may select a verified MEMBER or PROJECT destination but never reads where it is, and an INTERNAL point
 * (a secret guild stash) is never shown to a member or viewer at all.
 */
final class DeliveryPoints {
    static final class Conflict extends IOException {
        Conflict() {
            super("Delivery point revision changed; reload before updating");
        }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final Clock clock;
    private final Map<String, Entry> points = new LinkedHashMap<>();

    private record Entry(DeliveryPoint point, JsonArray history) {}

    DeliveryPoints(Path data) throws IOException {
        this(data, Clock.system());
    }

    DeliveryPoints(Path data, Clock clock) throws IOException {
        this.clock = clock;
        this.file = data.resolve("delivery-points.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid delivery point location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("points")) {
                var o = entry.getAsJsonObject();
                var point = DeliveryPoint.read(o);
                points.put(
                        point.id(), new Entry(point, o.getAsJsonArray("history").deepCopy()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read delivery points", invalid);
        }
    }

    synchronized DeliveryPoint create(JsonObject request, String by) throws IOException {
        var point = DeliveryPoint.create(request);
        commit(point.id(), new Entry(point, history("created", by)));
        return point;
    }

    /** An optimistic update: present fields replace the current value, the revision increments. */
    synchronized DeliveryPoint update(String id, JsonObject request, long expectedRevision, String by)
            throws IOException {
        var entry = require(id);
        if (entry.point().revision() != expectedRevision) throw new Conflict();
        var point = entry.point().updated(request);
        var history = entry.history().deepCopy();
        history.add(event("updated", by));
        commit(id, new Entry(point, history));
        return point;
    }

    /** Pauses a point: it stops being selectable and any active assignment toward it is blocked before dumping. */
    synchronized DeliveryPoint pause(String id, long expectedRevision, String by) throws IOException {
        var entry = require(id);
        var point = entry.point();
        if (point.revision() != expectedRevision) throw new Conflict();
        var paused = new DeliveryPoint(
                point.id(),
                point.server(),
                point.dimension(),
                point.x(),
                point.y(),
                point.z(),
                point.type(),
                point.access(),
                point.member(),
                DeliveryPoint.Status.PAUSED,
                Math.addExact(point.revision(), 1));
        var history = entry.history().deepCopy();
        history.add(event("paused", by));
        commit(id, new Entry(paused, history));
        return paused;
    }

    synchronized DeliveryPoint get(String id) {
        var entry = points.get(id);
        return entry == null ? null : entry.point();
    }

    /** The operator's full view: every point with coordinates and its change history. */
    synchronized JsonArray list() {
        var out = new JsonArray();
        for (var entry : points.values()) {
            var o = entry.point().json();
            o.add("history", entry.history().deepCopy());
            out.add(o);
        }
        return out;
    }

    /**
     * What a principal may choose as a destination, optionally constrained to one server so a member ordering from
     * that server can never select a delivery point on another server. The owner sees every verified point with full
     * coordinates; anyone else sees only verified MEMBER (their own) and PROJECT points, redacted, and never a secret
     * INTERNAL stash.
     */
    synchronized JsonArray selectable(Users.Principal p) {
        return selectable(p, null);
    }

    synchronized JsonArray selectable(Users.Principal p, String server) {
        var out = new JsonArray();
        for (var entry : points.values()) {
            var point = entry.point();
            if (point.status() != DeliveryPoint.Status.VERIFIED) continue;
            if (server != null && !point.server().equals(server)) continue; // cross-server selection is refused
            if (p.owner()) {
                out.add(point.json());
                continue;
            }
            if (point.access() == DeliveryPoint.Access.INTERNAL) continue;
            // Display names are mutable and can be reused by a different account.
            if (point.access() == DeliveryPoint.Access.MEMBER && !point.member().equals(p.id())) continue;
            out.add(point.jsonRedacted());
        }
        return out;
    }

    private Entry require(String id) throws Conflict {
        var entry = points.get(id);
        if (entry == null) throw new Conflict();
        return entry;
    }

    private JsonArray history(String event, String by) {
        var out = new JsonArray();
        out.add(event(event, by));
        return out;
    }

    private JsonObject event(String event, String by) {
        var o = new JsonObject();
        o.addProperty("at", clock.millis());
        o.addProperty("event", event);
        o.addProperty("by", by);
        return o;
    }

    /** Publish only a fully persisted state; a failed write must not advance custody or delivery. */
    private void commit(String id, Entry entry) throws IOException {
        var updated = new LinkedHashMap<>(points);
        updated.put(id, entry);
        save(updated);
        points.clear();
        points.putAll(updated);
    }

    private void save(Map<String, Entry> updated) throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        for (var entry : updated.values()) {
            var o = entry.point().json();
            o.add("history", entry.history().deepCopy());
            list.add(o);
        }
        root.add("points", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
