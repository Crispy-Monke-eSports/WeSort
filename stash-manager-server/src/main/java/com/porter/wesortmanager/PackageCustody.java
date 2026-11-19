package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * W-029: custody tracking for a physical order package, from warehouse staging through pickup, transport and a
 * verified deposit or player handoff. The {@code packageId} is the Manager's own persistent identity — never a
 * Minecraft UUID, and never the visible {@code "WeSort (X/Y) - text"} label, so two packages with the same visible
 * name never merge. Each package binds its order, pack job, manifest fingerprint, destination and visible label, and
 * every custody event records the server-confirmed physical source (server/dimension/stash/slot), the agent and the
 * evidence. Custody is mutually exclusive: after an agent picks a package up, only that agent (or an explicit
 * reconciliation) may advance it, so two couriers can never claim the same package. Every change is an append-only
 * event and a reboot or replayed receipt does not change custody.
 */
final class PackageCustody {
    enum State {
        STAGED,
        PICKUP_VERIFIED,
        IN_TRANSIT,
        DEPOSIT_VERIFIED,
        PLAYER_HANDOFF_PENDING,
        NEEDS_RECONCILIATION;

        boolean allows(State next) {
            return switch (this) {
                case STAGED -> next == PICKUP_VERIFIED || next == NEEDS_RECONCILIATION;
                case PICKUP_VERIFIED -> next == IN_TRANSIT || next == NEEDS_RECONCILIATION;
                case IN_TRANSIT ->
                    next == DEPOSIT_VERIFIED || next == PLAYER_HANDOFF_PENDING || next == NEEDS_RECONCILIATION;
                case DEPOSIT_VERIFIED, PLAYER_HANDOFF_PENDING -> next == NEEDS_RECONCILIATION;
                case NEEDS_RECONCILIATION -> false;
            };
        }
    }

    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Package> packages = new LinkedHashMap<>();

    record Package(
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            State state,
            String agent,
            JsonObject location,
            long revision,
            JsonArray events) {
        JsonObject json() {
            var out = new JsonObject();
            out.addProperty("packageId", packageId);
            out.addProperty("orderId", orderId);
            out.addProperty("manifest", manifest);
            out.addProperty("jobId", jobId);
            out.addProperty("destination", destination);
            out.addProperty("label", label);
            out.addProperty("state", state.name());
            out.addProperty("agent", agent);
            out.add("location", location == null ? new JsonObject() : location.deepCopy());
            out.addProperty("revision", revision);
            out.add("events", events.deepCopy());
            return out;
        }
    }

    PackageCustody(Path data) throws IOException {
        this(data, System::currentTimeMillis);
    }

    PackageCustody(Path data, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.file = data.resolve("custody.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid custody location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("packages")) {
                var p = entry.getAsJsonObject();
                packages.put(
                        p.get("packageId").getAsString(),
                        new Package(
                                p.get("packageId").getAsString(),
                                p.get("orderId").getAsString(),
                                str(p, "manifest"),
                                str(p, "jobId"),
                                str(p, "destination"),
                                str(p, "label"),
                                State.valueOf(p.get("state").getAsString()),
                                str(p, "agent"),
                                p.has("location") ? p.getAsJsonObject("location") : new JsonObject(),
                                p.get("revision").getAsLong(),
                                p.getAsJsonArray("events").deepCopy()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read package custody", invalid);
        }
    }

    private static String str(JsonObject o, String field) {
        var value = o.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** Stages a package at the warehouse; idempotent for the same package id and binding. */
    synchronized Package stage(String packageId, String orderId, JsonObject location) throws IOException {
        return stage(packageId, orderId, null, null, null, null, location);
    }

    synchronized Package stage(
            String packageId,
            String orderId,
            String manifest,
            String jobId,
            String destination,
            String label,
            JsonObject location)
            throws IOException {
        UUID.fromString(packageId);
        var existing = packages.get(packageId);
        if (existing != null) {
            if (!existing.orderId().equals(orderId)
                    || !eq(existing.manifest(), manifest)
                    || !eq(existing.jobId(), jobId)
                    || !eq(existing.destination(), destination)) {
                throw new Conflict("Package already belongs to another order or binding");
            }
            return existing;
        }
        var p = new Package(
                packageId,
                orderId,
                manifest,
                jobId,
                destination,
                label,
                State.STAGED,
                null,
                location == null ? new JsonObject() : location,
                0,
                new JsonArray());
        p = append(p, "STAGED", null, location, new JsonObject());
        packages.put(packageId, p);
        save();
        return p;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Advances a package to {@code next} by {@code agent}. A claim (the first agent to move a staged package) is
     * mutually exclusive; afterwards only the same agent, or a reconciliation, may move it.
     */
    synchronized Package transition(
            String packageId, String orderId, String agent, State next, JsonObject location, JsonObject evidence)
            throws IOException {
        var p = packages.get(packageId);
        if (p == null) throw new Conflict("Unknown package");
        if (!p.orderId().equals(orderId)) throw new Conflict("Package belongs to another order");
        if (next != State.NEEDS_RECONCILIATION) {
            if (!p.state().allows(next)) throw new Conflict("Package cannot move from " + p.state() + " to " + next);
            if (p.agent() != null && !p.agent().equals(agent)) {
                throw new Conflict("Another agent holds custody; physical reconciliation is required");
            }
        }
        p = append(p, next.name(), agent, location, evidence == null ? new JsonObject() : evidence);
        packages.put(packageId, p);
        save();
        return p;
    }

    synchronized Package get(String packageId) {
        return packages.get(packageId);
    }

    synchronized JsonArray list(String orderId) {
        var out = new JsonArray();
        packages.values().stream()
                .filter(p -> orderId == null || p.orderId().equals(orderId))
                .forEach(p -> out.add(p.json()));
        return out;
    }

    private Package append(Package p, String event, String agent, JsonObject location, JsonObject evidence) {
        long revision = p.revision() + 1;
        var row = new JsonObject();
        row.addProperty("seq", revision);
        row.addProperty("at", clock.getAsLong());
        row.addProperty("event", event);
        row.addProperty("agent", agent);
        row.add("location", location == null ? new JsonObject() : location.deepCopy());
        row.add("evidence", evidence.deepCopy());
        var events = p.events().deepCopy();
        events.add(row);
        return new Package(
                p.packageId(),
                p.orderId(),
                p.manifest(),
                p.jobId(),
                p.destination(),
                p.label(),
                State.valueOf(event),
                agent == null ? p.agent() : agent,
                location == null ? p.location() : location,
                revision,
                events);
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        packages.values().forEach(p -> list.add(p.json()));
        root.add("packages", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
