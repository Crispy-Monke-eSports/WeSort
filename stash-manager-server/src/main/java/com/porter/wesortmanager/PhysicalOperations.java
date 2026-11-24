package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.inventory.PhysicalJournal;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Append-only agent evidence, with separate Manager fences and autonomous read-only inspection. */
final class PhysicalOperations {
    static final class Conflict extends IOException {
        Conflict(String reason) {
            super(reason);
        }
    }

    /**
     * Automatic re-inspections per operation revision before the loop gives up and waits for an
     * operator. A fenced operation whose agent keeps failing inspection without ever posting
     * accepted proof would otherwise mint REQUESTED decisions forever.
     */
    static final int MAX_AUTO_INSPECT_PER_REVISION = 5;
    /** Clock skew tolerated before a journal from slightly in the future is treated as expired. */
    static final long FUTURE_SKEW_MS = 5_000;

    private final StockLedger ledger;
    private final LongSupplier clock;

    PhysicalOperations(StockLedger ledger) {
        this(ledger, System::currentTimeMillis);
    }

    PhysicalOperations(StockLedger ledger, LongSupplier clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    synchronized JsonObject accept(String agent, JsonObject record) throws IOException {
        return accept(agent, record, null);
    }

    synchronized JsonObject accept(String agent, JsonObject record, String serverKey) throws IOException {
        UUID.fromString(agent);
        record = PhysicalJournal.validate(record);
        var context = record.getAsJsonObject("context");
        if (!agent.equals(context.get("agent").getAsString()))
            throw new IllegalArgumentException("Agent journal scope differs");
        if (record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > PhysicalJournal.MAX_BYTES)
            throw new IllegalArgumentException("Physical journal too large");
        String id = record.get("id").getAsString(),
                server = serverKey == null ? context.get("server").getAsString() : serverKey,
                checksum = record.get("checksum").getAsString();
        long revision = record.get("revision").getAsLong();
        String state = record.get("state").getAsString();
        boolean inspectionVerified = false;
        long fence = state.equals("NEEDS_RECONCILIATION") ? revision : 0;
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                expire(c);
                boolean priorTerminal = false;
                try (var p =
                        c.prepareStatement("SELECT payload,state,fence_revision FROM physical_operations WHERE id=?")) {
                    p.setString(1, id);
                    try (var rows = p.executeQuery()) {
                        if (rows.next()) {
                            var previous =
                                    JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                            long before = previous.get("revision").getAsLong();
                            if (!previous.getAsJsonObject("context").equals(context) || revision < before)
                                throw new Conflict("Journal scope or revision changed");
                            if (revision == before
                                    && !checksum.equals(previous.get("checksum").getAsString()))
                                throw new Conflict("Conflicting physical revision");
                            if (revision > before) {
                                var events = record.getAsJsonArray("events");
                                var old = previous.getAsJsonArray("events");
                                for (int i = 0; i < old.size(); i++)
                                    if (!old.get(i).equals(events.get(i)))
                                        throw new Conflict("Physical history cannot be rewritten");
                            }
                            fence = rows.getLong(3);
                            priorTerminal = List.of("COMPLETED", "FAILED").contains(rows.getString(2));
                            if (rows.getString(2).equals("NEEDS_RECONCILIATION")) {
                                if (!state.equals("NEEDS_RECONCILIATION")) {
                                    inspectionVerified = inspected(c, id, record, fence);
                                    if (!inspectionVerified) state = "NEEDS_RECONCILIATION";
                                }
                            } else if (state.equals("NEEDS_RECONCILIATION")) fence = revision;
                        }
                    }
                }
                heldByOther(c, server, agent, priorTerminal);
                try (var p = c.prepareStatement(
                        "INSERT INTO physical_operations VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                                + "revision=excluded.revision,checksum=excluded.checksum,payload=excluded.payload,state="
                                + "excluded.state,last_seen=excluded.last_seen,fence_revision=excluded.fence_revision")) {
                    p.setString(1, id);
                    p.setString(2, server);
                    p.setString(3, agent);
                    p.setString(4, context.has("stash") ? context.get("stash").getAsString() : "");
                    p.setLong(5, revision);
                    p.setString(6, checksum);
                    p.setString(7, record.toString());
                    p.setString(8, state);
                    p.setLong(9, clock.getAsLong());
                    p.setLong(10, fence);
                    p.executeUpdate();
                }
                String released =
                        inspectionVerified && List.of("COMPLETED", "FAILED").contains(state)
                                ? releaseInspectedLease(c, server, agent, context)
                                : null;
                if (released == null
                        && List.of("COMPLETED", "FAILED").contains(state)
                        && context.has("leaseFingerprint")
                        && !hasLease(c, server)) {
                    // Replay of a terminal journal after the lease is already gone: the first
                    // response carried the release, this one must repeat it. With no lease row
                    // left for the server, any token the agent still holds is stale by definition,
                    // so echoing its own fingerprint back cannot free anything that is still held.
                    released = context.get("leaseFingerprint").getAsString();
                }
                tx.execute("COMMIT");
                var out = new JsonObject();
                out.addProperty("id", id);
                out.addProperty("revision", revision);
                out.addProperty("checksum", checksum);
                out.addProperty("state", state);
                if (released != null) out.addProperty("releasedLease", released);
                return out;
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot commit physical evidence", failure);
        }
    }

    /**
     * A journal names its own agent, but the sorting lease names its holder: evidence from any
     * other agent is rejected before it is stored, so a new identity can neither inspect nor
     * prolong someone else's fence. The rejection also keeps the orphan-lease collector
     * working — foreign journals would otherwise count as unsettled evidence forever.
     */
    private static void heldByOther(Connection c, String server, String agent, boolean priorTerminal)
            throws SQLException, Conflict {
        if (priorTerminal) return; // A replay of settled evidence re-affirms it; it cannot prolong a fence.
        try (var p = c.prepareStatement("SELECT agent FROM automation_leases WHERE server=?")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) {
                if (rows.next() && !agent.equals(rows.getString(1))) {
                    System.err.println("Physical journal from '" + agent + "' rejected: sorting lease on '"
                            + server + "' is held by another agent");
                    throw new Conflict("lease held by other agent");
                }
            }
        }
    }

    private String releaseInspectedLease(Connection c, String server, String agent, JsonObject context)
            throws SQLException {
        if (!context.has("leaseFingerprint")) return null;
        String fingerprint = context.get("leaseFingerprint").getAsString();
        try (var p = c.prepareStatement(
                "SELECT token FROM automation_leases WHERE server=? AND agent=? AND state='NEEDS_RECONCILIATION'")) {
            p.setString(1, server);
            p.setString(2, agent);
            try (var rows = p.executeQuery()) {
                if (!rows.next()
                        || !fingerprint.equals(com.porter.wesort.data.ContainerIdentity.hash(rows.getString(1))))
                    return null;
            }
        }
        try (var p = c.prepareStatement("SELECT 1 FROM physical_operations WHERE server=? AND state IN "
                + "('PLANNED','IN_PROGRESS','VERIFYING','NEEDS_RECONCILIATION') LIMIT 1")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) {
                if (rows.next()) return null;
            }
        }
        try (var p = c.prepareStatement(
                "DELETE FROM automation_leases WHERE server=? AND agent=? AND state='NEEDS_RECONCILIATION'")) {
            p.setString(1, server);
            p.setString(2, agent);
            p.executeUpdate();
        }
        ledger.invalidateServer(c, server);
        return fingerprint;
    }

    private static boolean hasLease(Connection c, String server) throws SQLException {
        try (var p = c.prepareStatement("SELECT 1 FROM automation_leases WHERE server=? LIMIT 1")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean inspected(Connection c, String id, JsonObject record, long fence) throws SQLException {
        try (var p = c.prepareStatement("SELECT revision,id FROM physical_decisions WHERE operation=? AND state IN "
                + "('REQUESTED','COMPLETED') AND action='INSPECT' AND manager_run=?")) {
            p.setString(1, id);
            p.setString(2, ledger.boot());
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    long reviewed = rows.getLong(1);
                    if (reviewed < fence) continue;
                    for (var entry : record.getAsJsonArray("events")) {
                        var event = entry.getAsJsonObject();
                        var evidence = event.getAsJsonObject("evidence");
                        if (event.get("seq").getAsLong() > reviewed
                                && List.of("RECONCILED", "INSPECTED_NO_MUTATION")
                                        .contains(event.get("type").getAsString())
                                && evidence.has("playerInventory")
                                && evidence.has("decisionId")
                                && rows.getString(2)
                                        .equals(evidence.get("decisionId").getAsString())) return true;
                    }
                }
            }
        }
        return false;
    }

    private void expire(Connection c) throws SQLException {
        try (var p = c.prepareStatement(
                "UPDATE physical_operations SET state='NEEDS_RECONCILIATION',fence_revision=revision WHERE state IN "
                        + "('PLANNED','IN_PROGRESS','VERIFYING') AND (last_seen<=? OR last_seen>?)")) {
            p.setLong(1, clock.getAsLong() - 15_000);
            // A journal slightly in the future is clock skew, not evidence of life: only a
            // timestamp beyond the skew budget fences the operation.
            p.setLong(2, clock.getAsLong() + FUTURE_SKEW_MS);
            p.executeUpdate();
        }
    }

    /**
     * Expiry without a read: lets the order sweep fence silent operations even when nobody
     * opens the operations view (whose list() is the only other expire trigger).
     */
    synchronized void sweepStale() throws IOException {
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                expire(c);
                tx.execute("COMMIT");
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot expire silent physical operations", failure);
        }
    }

    synchronized JsonArray list() throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open()) {
            expire(c);
            try (var p = c.prepareStatement("""
                     SELECT o.payload,o.state,o.last_seen,
                       (SELECT d.state FROM physical_decisions d WHERE d.operation=o.id ORDER BY d.issued_at DESC LIMIT 1),
                       (SELECT d.detail FROM physical_decisions d WHERE d.operation=o.id ORDER BY d.issued_at DESC LIMIT 1),
                       (SELECT count(*) FROM physical_decisions d WHERE d.operation=o.id AND d.action='INSPECT')
                     FROM physical_operations o
                     ORDER BY (o.state='NEEDS_RECONCILIATION') DESC,o.last_seen DESC LIMIT 128
                     """);
                    var rows = p.executeQuery()) {
                while (rows.next()) {
                    var value =
                            summary(JsonParser.parseString(rows.getString(1)).getAsJsonObject(), rows.getString(2));
                    value.addProperty("lastSeen", rows.getLong(3));
                    if (rows.getString(4) != null) {
                        value.addProperty("inspectionState", rows.getString(4));
                        value.addProperty("inspectionDetail", rows.getString(5));
                    }
                    value.addProperty("inspectionAttempts", rows.getInt(6));
                    out.add(value);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot list physical operations", failure);
        }
        return out;
    }

    private static JsonObject summary(JsonObject record, String state) {
        var summary = new JsonObject();
        for (String key : List.of("id", "revision", "checksum", "phase", "interruptedPhase"))
            if (record.has(key)) summary.add(key, record.get(key));
        var context = record.getAsJsonObject("context");
        for (String key : List.of("server", "agent", "dimension", "kind", "orderId", "jobId"))
            if (context.has(key)) summary.add(key, context.get(key));
        // Persisted journal fences are internal; clients only see autonomous recovery.
        summary.addProperty("managerState", state.equals("NEEDS_RECONCILIATION") ? "RECOVERING" : state);
        var standing = new LinkedHashMap<String, JsonObject>();
        if (state.equals("NEEDS_RECONCILIATION"))
            for (var entry : record.getAsJsonArray("events")) {
                var row = entry.getAsJsonObject();
                if (!"INTENT".equals(row.get("type").getAsString())) continue;
                var body = row.getAsJsonObject("evidence");
                if (!body.has("blocks")) continue;
                for (var block : body.getAsJsonArray("blocks")) {
                    var position = block.getAsJsonObject();
                    String key = position.get("x") + "," + position.get("y") + "," + position.get("z");
                    String type = position.get("block").getAsString();
                    if (type.equals("minecraft:shulker_box") || type.endsWith("_shulker_box"))
                        standing.put(key, position);
                    // A break without a verified pickup can still leave a dropped shulker here.
                }
            }
        var orphaned = new JsonArray();
        standing.values().forEach(block -> {
            var out = new JsonObject();
            for (String key : List.of("x", "y", "z", "block")) out.add(key, block.get(key));
            orphaned.add(out);
        });
        summary.add("possibleOrphans", orphaned);
        return summary;
    }

    synchronized JsonObject inspect(String id, long revision, String checksum, String actor) throws IOException {
        UUID.fromString(id);
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                expire(c);
                try (var p = c.prepareStatement("SELECT revision,checksum,state FROM physical_operations WHERE id=?")) {
                    p.setString(1, id);
                    try (var rows = p.executeQuery()) {
                        if (!rows.next() || revision != rows.getLong(1) || !checksum.equals(rows.getString(2)))
                            throw new Conflict("Reload the current physical operation before inspecting");
                        if (!rows.getString(3).equals("NEEDS_RECONCILIATION"))
                            throw new Conflict("Only a paused physical operation may be inspected");
                    }
                }
                String decision = null;
                try (var p = c.prepareStatement(
                        "SELECT id FROM physical_decisions WHERE operation=? AND revision=? AND checksum=? AND "
                                + "action='INSPECT' AND state='REQUESTED' AND manager_run=?")) {
                    p.setString(1, id);
                    p.setLong(2, revision);
                    p.setString(3, checksum);
                    p.setString(4, ledger.boot());
                    try (var rows = p.executeQuery()) {
                        if (rows.next()) decision = rows.getString(1);
                    }
                }
                if (decision == null) {
                    decision = UUID.randomUUID().toString();
                    try (var p = c.prepareStatement(
                            "INSERT INTO physical_decisions VALUES(?,?,?,?,?,'INSPECT','REQUESTED',?,'',?)")) {
                        p.setString(1, decision);
                        p.setString(2, id);
                        p.setLong(3, revision);
                        p.setString(4, checksum);
                        p.setString(5, actor);
                        p.setLong(6, clock.getAsLong());
                        p.setString(7, ledger.boot());
                        p.executeUpdate();
                    }
                }
                try (var p = c.prepareStatement("UPDATE physical_operations SET "
                        + "state='NEEDS_RECONCILIATION',fence_revision=max(fence_revision,revision) WHERE id=?")) {
                    p.setString(1, id);
                    p.executeUpdate();
                }
                tx.execute("COMMIT");
                var out = new JsonObject();
                out.addProperty("id", decision);
                out.addProperty("operation", id);
                // The first automatically dispatched decision must carry exactly
                // the same revision and checksum as decisions loaded on later
                // polls. Without them the client rejects this valid recovery
                // request as stale and reports a permanent inspection failure.
                out.addProperty("revision", revision);
                out.addProperty("checksum", checksum);
                out.addProperty("action", "INSPECT");
                return out;
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot request physical inspection", failure);
        }
    }

    /**
     * Request a revision-pinned, read-only inspection when a fenced agent reconnects.
     * A request for an older snapshot cannot block recovery of the current snapshot.
     * An outcome without accepted journal proof is retried after the backoff.
     */
    synchronized JsonObject inspectAutomatically(String agent, JsonObject receipt) throws IOException {
        if (!"NEEDS_RECONCILIATION".equals(receipt.get("state").getAsString())) return null;
        UUID.fromString(agent);
        String operation = receipt.get("id").getAsString();
        long revision = receipt.get("revision").getAsLong();
        String checksum = receipt.get("checksum").getAsString();
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                    SELECT d.state,d.issued_at
                    FROM physical_operations o
                    LEFT JOIN physical_decisions d ON d.id = (
                        SELECT id FROM physical_decisions
                        WHERE operation=o.id AND manager_run=? AND action='INSPECT'
                          AND revision=o.revision AND checksum=o.checksum
                        ORDER BY issued_at DESC,rowid DESC LIMIT 1
                    )
                    WHERE o.id=? AND o.agent=? AND o.state='NEEDS_RECONCILIATION'
                      AND o.revision=? AND o.checksum=?
                    """)) {
            p.setString(1, ledger.boot());
            p.setString(2, operation);
            p.setString(3, agent);
            p.setLong(4, revision);
            p.setString(5, checksum);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                String prior = rows.getString(1);
                if ("REQUESTED".equals(prior)) return null;
                // A claimed completion is not proof of the physical postcondition.
                // If the operation remains fenced, a fresh inspection is still needed.
                if (prior != null && clock.getAsLong() - rows.getLong(2) < 60_000) return null;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot schedule automatic physical inspection", failure);
        }
        try {
            if (autoInspections(operation, revision, checksum) >= MAX_AUTO_INSPECT_PER_REVISION) return null;
            return inspect(operation, revision, checksum, "system:self-heal");
        } catch (Conflict changed) {
            return null; // The operation advanced since the readback receipt.
        }
    }

    /** Automatic inspection attempts for one operation revision under this Manager run. */
    private int autoInspections(String operation, long revision, String checksum) throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT count(*) FROM physical_decisions WHERE operation=? AND revision=? "
                        + "AND checksum=? AND action='INSPECT' AND manager_run=?")) {
            p.setString(1, operation);
            p.setLong(2, revision);
            p.setString(3, checksum);
            p.setString(4, ledger.boot());
            try (var rows = p.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot schedule automatic physical inspection", failure);
        }
    }

    synchronized JsonObject decision(String agent, String operation) throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT d.id,d.revision,d.checksum,d.issued_at FROM physical_decisions d JOIN "
                                + "physical_operations o ON o.id=d.operation WHERE d.operation=? AND o.agent=? "
                                + "AND o.state='NEEDS_RECONCILIATION' AND d.revision=o.revision AND d.checksum=o.checksum "
                                + "AND d.action='INSPECT' AND d.state='REQUESTED' AND d.manager_run=? "
                                + "ORDER BY d.issued_at DESC,d.rowid DESC LIMIT 1")) {
            p.setString(1, operation);
            p.setString(2, agent);
            p.setString(3, ledger.boot());
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return null;
                var out = new JsonObject();
                out.addProperty("id", rows.getString(1));
                out.addProperty("operation", operation);
                out.addProperty("revision", rows.getLong(2));
                out.addProperty("checksum", rows.getString(3));
                out.addProperty("issuedAt", rows.getLong(4));
                out.addProperty("action", "INSPECT");
                return out;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read physical decision", failure);
        }
    }

    /**
     * Records the agent's inspection outcome. Returns false when no pending request matched,
     * so callers surface a 409 instead of silently dropping the outcome.
     */
    synchronized boolean decisionResult(String agent, String id, String outcome, String detail) throws IOException {
        if (!List.of("COMPLETED", "FAILED").contains(outcome))
            throw new IllegalArgumentException("Invalid inspection outcome");
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "UPDATE physical_decisions SET state=?,detail=? WHERE id=? AND state='REQUESTED' AND operation IN "
                                + "(SELECT id FROM physical_operations WHERE agent=?)")) {
            p.setString(1, outcome);
            p.setString(2, detail == null ? "" : detail.substring(0, Math.min(1000, detail.length())));
            p.setString(3, id);
            p.setString(4, agent);
            return p.executeUpdate() > 0;
        } catch (SQLException failure) {
            throw new IOException("Cannot record physical inspection outcome", failure);
        }
    }
}
