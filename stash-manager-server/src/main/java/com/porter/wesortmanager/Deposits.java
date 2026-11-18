package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.Deposit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * W-038: the physical deposition ledger. A package is deposited either as a {@code DELIVERY} to a recipient outside
 * the stock (a withdrawal) or a {@code TRANSFER} to an internal guild stash (a relocation, no change to the guild
 * total) — the kind is explicit and never inferred. {@code DELIVERED} is reached only after a server-confirmed,
 * non-empty chest content snapshot, so a double report or reconnect can never create a double delivery: replaying a
 * confirmation of an already delivered package returns the same record. An uncertain outcome goes to
 * {@code NEEDS_RECONCILIATION}. Every change is an append-only event.
 */
final class Deposits {
    static final class Conflict extends IOException {
        Conflict(String reason) { super(reason); }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Entry> deposits = new LinkedHashMap<>();

    private record Entry(Deposit deposit, JsonArray events) {}

    Deposits(Path data) throws IOException { this(data, System::currentTimeMillis); }
    Deposits(Path data, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.file = data.resolve("deposits.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid deposit location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("deposits")) {
                var o = entry.getAsJsonObject();
                var deposit = Deposit.read(o);
                deposits.put(deposit.packageId(), new Entry(deposit, o.getAsJsonArray("events").deepCopy()));
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read deposits", invalid);
        }
    }

    /** Begins a deposit; idempotent for the same package + order + kind + destination. */
    synchronized Deposit begin(String packageId, String orderId, Deposit.Kind kind, String destination, String agent) throws IOException {
        var existing = deposits.get(packageId);
        if (existing != null) {
            var d = existing.deposit();
            if (!d.orderId().equals(orderId) || d.kind() != kind || !d.destination().equals(destination))
                throw new Conflict("Package already has a different deposit");
            return d;
        }
        var d = Deposit.begin(packageId, orderId, kind, destination, agent);
        var events = new JsonArray();
        events.add(event("DEPOSITING", agent, null));
        deposits.put(packageId, new Entry(d, events));
        save();
        return d;
    }

    /** Confirms a deposit: only a server-confirmed, non-empty content snapshot reaches DELIVERED. Replay is a no-op. */
    synchronized Deposit confirm(String packageId, String agent, JsonObject snapshot) throws IOException {
        var entry = require(packageId);
        var d = entry.deposit();
        if (d.state() == Deposit.State.DELIVERED) return d; // idempotent: no double delivery
        if (snapshot == null || !snapshot.has("chests") || !snapshot.get("chests").isJsonArray()
            || snapshot.getAsJsonArray("chests").isEmpty())
            throw new Conflict("A confirmed, non-empty chest content snapshot is required before DELIVERED");
        var confirmed = advance(d, Deposit.State.DELIVERED, agent);
        var events = entry.events().deepCopy();
        events.add(event("DELIVERED", agent, snapshot));
        deposits.put(packageId, new Entry(confirmed, events));
        save();
        return confirmed;
    }

    /** Marks an uncertain outcome for operator reconciliation. */
    synchronized Deposit reconcile(String packageId, String agent, String reason) throws IOException {
        var entry = require(packageId);
        var d = entry.deposit();
        if (d.state() == Deposit.State.NEEDS_RECONCILIATION) return d;
        var reconciled = advance(d, Deposit.State.NEEDS_RECONCILIATION, agent);
        var events = entry.events().deepCopy();
        var ev = event("NEEDS_RECONCILIATION", agent, null);
        ev.addProperty("reason", reason == null ? "" : reason);
        events.add(ev);
        deposits.put(packageId, new Entry(reconciled, events));
        save();
        return reconciled;
    }

    synchronized Deposit get(String packageId) {
        var entry = deposits.get(packageId);
        return entry == null ? null : entry.deposit();
    }

    synchronized JsonArray list(String orderId) {
        var out = new JsonArray();
        for (var entry : deposits.values()) {
            if (orderId != null && !entry.deposit().orderId().equals(orderId)) continue;
            var o = entry.deposit().json();
            o.add("events", entry.events().deepCopy());
            out.add(o);
        }
        return out;
    }

    private static Deposit advance(Deposit d, Deposit.State next, String agent) throws Conflict {
        try { return d.advance(next, agent); } catch (IllegalArgumentException e) { throw new Conflict(e.getMessage()); }
    }

    private Entry require(String packageId) throws Conflict {
        var entry = deposits.get(packageId);
        if (entry == null) throw new Conflict("Unknown package");
        return entry;
    }

    private JsonObject event(String name, String agent, JsonObject snapshot) {
        var o = new JsonObject();
        o.addProperty("at", clock.getAsLong());
        o.addProperty("event", name);
        o.addProperty("agent", agent);
        if (snapshot != null) o.add("snapshot", snapshot.deepCopy());
        return o;
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        for (var entry : deposits.values()) {
            var o = entry.deposit().json();
            o.add("events", entry.events().deepCopy());
            list.add(o);
        }
        root.add("deposits", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
