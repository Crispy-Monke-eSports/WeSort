package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.ShulkerUuid;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Manager-authorized box UUID registration jobs (W-063). The Owner asks the Manager to mark a verified,
 * unreserved root shulker box; the Manager mints a fresh UUID v4 and an agent performs the anvil rename.
 * Completion is only accepted once the registry has re-observed the issued UUID at the exact target.
 */
final class BoxJobs {
    static final long TTL = 15_000;
    private static final Set<String> KINDS = Set.of("REGISTER_BOX", "RETURN_INTAKE");
    private final StockLedger ledger;
    private final LongSupplier clock;

    BoxJobs(StockLedger ledger) throws IOException {
        this(ledger, System::currentTimeMillis);
    }

    BoxJobs(StockLedger ledger, LongSupplier clock) throws IOException {
        this.ledger = ledger;
        this.clock = clock;
        // A new Manager process cannot prove what an agent did while disconnected; never reassign automatically.
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE box_jobs SET state='NEEDS_RECONCILIATION',payload=json_set(payload,'$.message','Manager restarted during execution') WHERE state IN ('IN_PROGRESS','VERIFYING')");
        } catch (SQLException failure) {
            throw new IOException("Cannot fence interrupted box jobs", failure);
        }
    }

    private StockLedger.Source target(String server, String stash, String source) throws IOException {
        return ledger.sources().stream()
                .filter(s -> s.server().equals(server)
                        && s.stash().equals(stash)
                        && s.value().get("id").getAsString().equals(source)
                        && s.status().equals("FRESH")
                        && !s.shared()
                        && (!s.value().has("protected")
                                || !s.value().get("protected").getAsBoolean())
                        && s.value().has("slotFingerprint"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The target needs a fresh, unprotected physical observation in this stash"));
    }

    synchronized JsonObject create(String actor, JsonObject request) throws IOException {
        String server = request.get("server").getAsString(),
                stash = request.get("stash").getAsString();
        String source = request.get("source").getAsString(),
                path = request.get("path").getAsString();
        String kind = request.get("kind").getAsString();
        if (!KINDS.contains(kind))
            throw new IllegalArgumentException("Box job kind must be REGISTER_BOX or RETURN_INTAKE");
        var destination = target(server, stash, source);
        if (!path.matches("0|[1-9][0-9]?")) throw new IllegalArgumentException("A root slot path is required");
        JsonObject slot = ledger.physicalSlots(source).stream()
                .filter(s -> path.equals(s.get("path").getAsString())
                        && s.get("present").getAsBoolean())
                .findFirst()
                .orElse(null);
        if (slot == null
                || !"SHULKER".equals(slot.get("kind").getAsString())
                || slot.get("count").getAsInt() != 1
                || slot.get("size").getAsInt() != 27)
            throw new IllegalArgumentException("The selected slot no longer holds the verified shulker");
        if (ShulkerUuid.boxUuid(slot.get("components").getAsString()) != null)
            throw new IllegalArgumentException("The selected box already carries a WeSort UUID");
        UUID uuid = UUID.randomUUID();
        var job = new JsonObject();
        job.addProperty("id", UUID.randomUUID().toString());
        job.addProperty("server", server);
        job.addProperty("stash", stash);
        job.addProperty("kind", kind);
        job.addProperty("source", source);
        job.addProperty("path", path);
        job.addProperty("uuid", uuid.toString());
        job.addProperty("name", ShulkerUuid.label(uuid));
        job.addProperty("createdBy", actor);
        job.addProperty("createdAt", clock.getAsLong());
        job.addProperty(
                "baselineFingerprint",
                destination.value().get("slotFingerprint").getAsString());
        job.addProperty("baselineRevision", destination.revision());
        job.addProperty("stackFingerprint", slot.get("fingerprint").getAsString());
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            try {
                try (var p = c.prepareStatement(
                        "SELECT count(*) FROM box_jobs WHERE server=? AND state IN ('PLANNED','IN_PROGRESS','VERIFYING','NEEDS_RECONCILIATION')")) {
                    p.setString(1, server);
                    try (var r = p.executeQuery()) {
                        r.next();
                        if (r.getInt(1) > 0)
                            throw new IllegalArgumentException(
                                    "Resolve the existing box job before starting another on this server");
                    }
                }
                try (var p = c.prepareStatement("INSERT INTO box_jobs VALUES(?,?,?,'PLANNED','',NULL,0,?)")) {
                    p.setString(1, job.get("id").getAsString());
                    p.setString(2, server);
                    p.setString(3, stash);
                    p.setString(4, job.toString());
                    p.executeUpdate();
                }
                tx.commit();
            } catch (Exception failure) {
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot create box job", failure);
        }
        job.addProperty("state", "PLANNED");
        return job;
    }

    private void expire(Connection c) throws SQLException {
        try (var p = c.prepareStatement(
                "UPDATE box_jobs SET state='NEEDS_RECONCILIATION',payload=json_set(payload,'$.message','Execution lease expired; inspect the physical box before recovery') WHERE state IN ('IN_PROGRESS','VERIFYING') AND deadline<=?")) {
            p.setLong(1, clock.getAsLong());
            p.executeUpdate();
        }
    }

    static boolean hasActiveWork(StockLedger ledger, String server, String agent) throws IOException {
        try (var connection = ledger.open();
                var query = connection.prepareStatement(
                        "SELECT 1 FROM box_jobs WHERE server=? AND agent=? AND state IN ('IN_PROGRESS','VERIFYING') LIMIT 1")) {
            query.setString(1, server);
            query.setString(2, agent);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw new IOException("Cannot check active box work", error);
        }
    }

    synchronized JsonObject claim(String server, String stash, String agent) throws IOException {
        UUID.fromString(agent);
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            try {
                expire(c);
                JsonObject job = null;
                String id = null;
                try (var p = c.prepareStatement(
                        "SELECT id,payload FROM box_jobs WHERE server=? AND state='PLANNED' ORDER BY rowid LIMIT 1")) {
                    p.setString(1, server);
                    try (var r = p.executeQuery()) {
                        if (r.next()) {
                            id = r.getString(1);
                            job = JsonParser.parseString(r.getString(2)).getAsJsonObject();
                        }
                    }
                }
                if (job == null || !stash.equals(job.get("stash").getAsString())) {
                    tx.commit();
                    return null;
                }
                String token = UUID.randomUUID().toString() + UUID.randomUUID();
                job.addProperty("executionDeadline", Math.addExact(clock.getAsLong(), 300_000));
                try (var p = c.prepareStatement(
                        "UPDATE box_jobs SET state='IN_PROGRESS',agent=?,token=?,deadline=?,payload=? WHERE id=? AND state='PLANNED'")) {
                    p.setString(1, agent);
                    p.setString(2, token);
                    p.setLong(3, clock.getAsLong() + TTL);
                    p.setString(4, job.toString());
                    p.setString(5, id);
                    if (p.executeUpdate() != 1) throw new IllegalArgumentException("Box job claim conflict");
                }
                tx.commit();
                job.addProperty("state", "IN_PROGRESS");
                job.addProperty("token", token);
                return job;
            } catch (Exception failure) {
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot claim box job", failure);
        }
    }

    synchronized JsonObject advance(String id, String agent, String token, String action, String message)
            throws IOException {
        synchronized (ledger) {
            try (var c = ledger.open();
                    var tx = new SqliteWriteTransaction(c)) {
                try {
                    expire(c);
                    JsonObject job;
                    String state;
                    try (var p = c.prepareStatement(
                            "SELECT payload,state FROM box_jobs WHERE id=? AND agent=? AND token=?")) {
                        p.setString(1, id);
                        p.setString(2, agent);
                        p.setString(3, token);
                        try (var r = p.executeQuery()) {
                            if (!r.next()) throw new IllegalArgumentException("Box job lease conflict");
                            job = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                            state = r.getString(2);
                        }
                    }
                    if (!List.of("IN_PROGRESS", "VERIFYING").contains(state)) {
                        tx.commit();
                        job.addProperty("state", state);
                        return job;
                    }
                    if ("uncertain".equals(action)) {
                        state = "NEEDS_RECONCILIATION";
                        job.addProperty(
                                "message",
                                message == null ? "" : message.substring(0, Math.min(1000, message.length())));
                    } else if ("renamed".equals(action)) {
                        state = "VERIFYING";
                    } else if ("complete".equals(action)) {
                        if (!state.equals("VERIFYING"))
                            throw new IllegalArgumentException("The renamed box must be re-observed first");
                        if (registered(c, job)) {
                            state = "COMPLETED";
                            job.remove("message");
                            job.addProperty("completedAt", clock.getAsLong());
                        } else
                            job.addProperty(
                                    "message",
                                    "Awaiting a fresh observation that carries the issued UUID at the target");
                    } else if (!"renew".equals(action)) throw new IllegalArgumentException("Invalid box job action");
                    try (var p = c.prepareStatement("UPDATE box_jobs SET state=?,deadline=?,payload=? WHERE id=?")) {
                        p.setString(1, state);
                        p.setLong(
                                2,
                                Math.min(
                                        clock.getAsLong() + TTL,
                                        job.get("executionDeadline").getAsLong()));
                        p.setString(3, job.toString());
                        p.setString(4, id);
                        p.executeUpdate();
                    }
                    tx.commit();
                    job.addProperty("state", state);
                    return job;
                } catch (Exception failure) {
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IOException("Cannot update box job", failure);
            }
        }
    }

    private boolean registered(Connection c, JsonObject job) throws SQLException {
        try (var p = c.prepareStatement("SELECT source,path,status FROM box_uuids WHERE uuid=? AND server=?")) {
            p.setString(1, job.get("uuid").getAsString());
            p.setString(2, job.get("server").getAsString());
            try (var r = p.executeQuery()) {
                if (!r.next()) return false;
                return r.getString(1).equals(job.get("source").getAsString())
                        && r.getString(2).equals(job.get("path").getAsString())
                        && r.getString(3).equals("IN_STOCK");
            }
        }
    }

    synchronized JsonArray list(String server) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open()) {
            expire(c);
            try (var p = c.prepareStatement(
                    "SELECT payload,state,agent FROM box_jobs WHERE server=? ORDER BY rowid DESC LIMIT 100")) {
                p.setString(1, server);
                try (var r = p.executeQuery()) {
                    while (r.next()) {
                        var job = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                        job.addProperty("state", r.getString(2));
                        job.addProperty("agent", r.getString(3));
                        out.add(job);
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read box jobs", failure);
        }
        return out;
    }
}
