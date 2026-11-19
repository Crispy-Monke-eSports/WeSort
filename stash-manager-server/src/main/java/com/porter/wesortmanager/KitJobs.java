package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** One-box operator jobs. A template is not stock and completion is not a member delivery. */
final class KitJobs {
    static final long TTL = 15_000;
    private final StockLedger ledger;
    private final KitCatalog catalog;
    private final LongSupplier clock;

    KitJobs(StockLedger ledger, KitCatalog catalog) throws IOException {
        this(ledger, catalog, System::currentTimeMillis);
    }

    KitJobs(StockLedger ledger, KitCatalog catalog, LongSupplier clock) throws IOException {
        this.ledger = ledger;
        this.catalog = catalog;
        this.clock = clock;
        // A new Manager process cannot prove what an agent did while disconnected. Never reassign automatically.
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE kit_jobs SET state='NEEDS_RECONCILIATION',payload=json_set(payload,'$.message','Manager restarted during execution') WHERE state IN ('IN_PROGRESS','VERIFYING')");
        } catch (SQLException failure) {
            throw new IOException("Cannot fence interrupted kit jobs", failure);
        }
    }

    private StockLedger.Source destination(String server, String stash, String id) throws IOException {
        return ledger.sources().stream()
                .filter(s -> s.server().equals(server)
                        && s.stash().equals(stash)
                        && s.value().get("id").getAsString().equals(id)
                        && s.status().equals("FRESH")
                        && !s.shared()
                        && (!s.value().has("protected")
                                || !s.value().get("protected").getAsBoolean())
                        && s.value().has("slotFingerprint"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination needs a fresh, unprotected physical observation in this stash"));
    }

    synchronized JsonObject create(String actor, JsonObject request) throws IOException {
        String server = request.get("server").getAsString(),
                stash = request.get("stash").getAsString(),
                source = request.get("destination").getAsString();
        String name = request.get("name").getAsString(),
                fingerprint = request.get("fingerprint").getAsString();
        long revision = request.get("revision").getAsBigDecimal().longValueExact();
        var template = catalog.version(server, name, revision, fingerprint);
        var dest = destination(server, stash, source);
        var work = request.getAsJsonObject("workspace").deepCopy();
        if (!dest.value().get("dimension").equals(work.get("dimension")))
            throw new IllegalArgumentException("Workspace dimension differs from destination");
        for (String coordinate : List.of("x", "y", "z")) {
            int value = work.get(coordinate).getAsBigDecimal().intValueExact();
            if (Math.abs((long) value) > 30_000_000)
                throw new IllegalArgumentException("Workspace outside world limits");
        }
        var job = new JsonObject();
        job.addProperty("id", UUID.randomUUID().toString());
        job.addProperty("server", server);
        job.addProperty("stash", stash);
        job.addProperty("destination", source);
        job.addProperty("name", template.name());
        job.addProperty("revision", revision);
        job.addProperty("fingerprint", fingerprint);
        job.addProperty("createdBy", actor);
        job.addProperty("createdAt", clock.getAsLong());
        job.add("workspace", work);
        job.addProperty(
                "baseline",
                copies(
                        ledger.snapshot(
                                source, dest.value().get("slotFingerprint").getAsString()),
                        template));
        job.addProperty("baselineRevision", dest.revision());
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                requireHead(c, server, template.key(), revision, fingerprint);
                try (var p = c.prepareStatement(
                        "SELECT count(*) FROM kit_jobs WHERE server=? AND state IN ('PLANNED','IN_PROGRESS','VERIFYING','NEEDS_RECONCILIATION')")) {
                    p.setString(1, server);
                    try (var r = p.executeQuery()) {
                        r.next();
                        if (r.getInt(1) > 0)
                            throw new IllegalArgumentException(
                                    "Resolve the existing kit job before starting another on this server");
                    }
                }
                try (var p = c.prepareStatement("INSERT INTO kit_jobs VALUES(?,?,?,'PLANNED','',NULL,0,?)")) {
                    p.setString(1, job.get("id").getAsString());
                    p.setString(2, server);
                    p.setString(3, template.key());
                    p.setString(4, job.toString());
                    p.executeUpdate();
                }
                tx.execute("COMMIT");
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot create kit job", failure);
        }
        job.addProperty("state", "PLANNED");
        return job;
    }

    private static void requireHead(Connection c, String server, String key, long revision, String fingerprint)
            throws SQLException, KitCatalog.Conflict {
        try (var p =
                c.prepareStatement("SELECT revision,fingerprint FROM kit_templates WHERE server=? AND name_key=?")) {
            p.setString(1, server);
            p.setString(2, key);
            try (var r = p.executeQuery()) {
                if (!r.next() || r.getLong(1) != revision || !fingerprint.equals(r.getString(2)))
                    throw new KitCatalog.Conflict();
            }
        }
    }

    private static KitTemplate template(Connection c, JsonObject job) throws SQLException, KitCatalog.Conflict {
        try (var p = c.prepareStatement(
                "SELECT payload FROM kit_template_versions WHERE server=? AND name_key=? AND revision=? AND fingerprint=?")) {
            p.setString(1, job.get("server").getAsString());
            p.setString(2, KitTemplate.nameKey(job.get("name").getAsString()));
            p.setLong(3, job.get("revision").getAsLong());
            p.setString(4, job.get("fingerprint").getAsString());
            try (var r = p.executeQuery()) {
                if (!r.next()) throw new KitCatalog.Conflict();
                return KitTemplate.read(JsonParser.parseString(r.getString(1)).getAsJsonObject());
            }
        }
    }

    private void expire(Connection c) throws SQLException {
        try (var p = c.prepareStatement(
                "UPDATE kit_jobs SET state='NEEDS_RECONCILIATION',payload=json_set(payload,'$.message','Execution lease expired; inspect the physical kit before recovery') WHERE state IN ('IN_PROGRESS','VERIFYING') AND deadline<=?")) {
            p.setLong(1, clock.getAsLong());
            p.executeUpdate();
        }
    }

    static boolean hasActiveWork(StockLedger ledger, String server, String agent) throws IOException {
        try (var connection = ledger.open();
                var query = connection.prepareStatement("""
                SELECT 1 FROM kit_jobs WHERE server=? AND agent=?
                  AND state IN ('IN_PROGRESS','VERIFYING') LIMIT 1
                """)) {
            query.setString(1, server);
            query.setString(2, agent);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException error) {
            throw new IOException("Cannot check active kit work", error);
        }
    }

    synchronized JsonObject claim(String server, String stash, String agent) throws IOException {
        UUID.fromString(agent);
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                expire(c);
                JsonObject job = null;
                String id = null;
                try (var p = c.prepareStatement(
                        "SELECT id,payload FROM kit_jobs WHERE server=? AND state='PLANNED' ORDER BY rowid LIMIT 1")) {
                    p.setString(1, server);
                    try (var r = p.executeQuery()) {
                        if (r.next()) {
                            id = r.getString(1);
                            job = JsonParser.parseString(r.getString(2)).getAsJsonObject();
                        }
                    }
                }
                if (job == null || !stash.equals(job.get("stash").getAsString())) {
                    tx.execute("COMMIT");
                    return null;
                }
                requireHead(
                        c,
                        server,
                        KitTemplate.nameKey(job.get("name").getAsString()),
                        job.get("revision").getAsLong(),
                        job.get("fingerprint").getAsString());
                String token = UUID.randomUUID().toString() + UUID.randomUUID();
                job.addProperty("executionDeadline", Math.addExact(clock.getAsLong(), 300_000));
                try (var p = c.prepareStatement(
                        "UPDATE kit_jobs SET state='IN_PROGRESS',agent=?,token=?,deadline=?,payload=? WHERE id=? AND state='PLANNED'")) {
                    p.setString(1, agent);
                    p.setString(2, token);
                    p.setLong(3, clock.getAsLong() + TTL);
                    p.setString(4, job.toString());
                    p.setString(5, id);
                    if (p.executeUpdate() != 1) throw new KitCatalog.Conflict();
                }
                var exact = template(c, job);
                tx.execute("COMMIT");
                job.addProperty("state", "IN_PROGRESS");
                job.addProperty("token", token);
                job.add("template", exact.json());
                return job;
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot claim kit job", failure);
        }
    }

    synchronized JsonObject advance(String id, String agent, String token, String action) throws IOException {
        return advance(id, agent, token, action, "");
    }

    synchronized JsonObject advance(String id, String agent, String token, String action, String message)
            throws IOException {
        return advance(id, agent, token, action, message, null);
    }

    synchronized JsonObject advance(
            String id, String agent, String token, String action, String message, JsonObject supply)
            throws IOException {
        // Match observation ingestion lock order: ledger monitor, then SQLite transaction.
        synchronized (ledger) {
            try (var c = ledger.open();
                    var tx = c.createStatement()) {
                tx.execute("BEGIN IMMEDIATE");
                try {
                    expire(c);
                    JsonObject job;
                    String state;
                    try (var p = c.prepareStatement(
                            "SELECT payload,state FROM kit_jobs WHERE id=? AND agent=? AND token=?")) {
                        p.setString(1, id);
                        p.setString(2, agent);
                        p.setString(3, token);
                        try (var r = p.executeQuery()) {
                            if (!r.next()) throw new KitCatalog.Conflict();
                            job = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                            state = r.getString(2);
                        }
                    }
                    if (!List.of("IN_PROGRESS", "VERIFYING").contains(state)) {
                        tx.execute("COMMIT");
                        job.addProperty("state", state);
                        return job;
                    }
                    if (supply != null) {
                        var estimate = new JsonObject();
                        for (String key : List.of("built", "possible")) {
                            int count = supply.get(key).getAsBigDecimal().intValueExact();
                            if (count < -1 || count > 1_000_000)
                                throw new IllegalArgumentException("Invalid kit estimate");
                            estimate.addProperty(key, count);
                        }
                        estimate.addProperty(
                                "exact", supply.get("estimateExact").getAsBoolean());
                        String shortage = supply.get("shortage").getAsString();
                        estimate.addProperty("shortage", shortage.substring(0, Math.min(1000, shortage.length())));
                        estimate.addProperty("at", clock.getAsLong());
                        job.add("agentEstimate", estimate);
                    }
                    if ("uncertain".equals(action)) {
                        state = "NEEDS_RECONCILIATION";
                        job.addProperty(
                                "message",
                                message == null ? "" : message.substring(0, Math.min(1000, message.length())));
                    } else if ("packed".equals(action)) state = "VERIFYING";
                    else if ("complete".equals(action)) {
                        if (!state.equals("VERIFYING"))
                            throw new IllegalArgumentException("Packed inventory verification is required first");
                        StockLedger.Source dest = null;
                        try {
                            dest = destination(
                                    job.get("server").getAsString(),
                                    job.get("stash").getAsString(),
                                    job.get("destination").getAsString());
                        } catch (IllegalArgumentException awaitingFreshObservation) {
                            /* Remain VERIFYING; a renewal is not a storage receipt. */
                        }
                        var template = template(c, job);
                        if (dest != null
                                && dest.revision() > job.get("baselineRevision").getAsLong()
                                && copies(
                                                ledger.snapshot(
                                                        job.get("destination").getAsString(),
                                                        dest.value()
                                                                .get("slotFingerprint")
                                                                .getAsString()),
                                                template)
                                        == job.get("baseline").getAsInt() + 1) {
                            state = "COMPLETED";
                            job.remove("message");
                            job.addProperty("receiptRevision", dest.revision());
                            job.addProperty(
                                    "receiptFingerprint",
                                    dest.value().get("slotFingerprint").getAsString());
                        } else job.addProperty("message", "Awaiting a fresh exact destination receipt");
                    } else if (!"renew".equals(action)) throw new IllegalArgumentException("Invalid kit job action");
                    try (var p = c.prepareStatement("UPDATE kit_jobs SET state=?,deadline=?,payload=? WHERE id=?")) {
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
                    tx.execute("COMMIT");
                    job.addProperty("state", state);
                    return job;
                } catch (Exception failure) {
                    tx.execute("ROLLBACK");
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IOException("Cannot update kit job", failure);
            }
        }
    }

    synchronized JsonArray list(String server) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open()) {
            expire(c);
            try (var p = c.prepareStatement(
                    "SELECT payload,state,agent FROM kit_jobs WHERE server=? ORDER BY rowid DESC LIMIT 100")) {
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
            throw new IOException("Cannot read kit jobs", failure);
        }
        return out;
    }
    /** Compare every relative slot, count and full item fingerprint inside each top-level shulker. */
    static int copies(JsonObject snapshot, KitTemplate template) {
        if (snapshot == null) throw new IllegalArgumentException("Physical destination snapshot required");
        Map<String, String> expected = new TreeMap<>();
        for (var located : template.layout().located())
            expected.put(located.path(), located.stack().fingerprint());
        int copies = 0;
        var positions = snapshot.getAsJsonArray("positions");
        for (var value : positions) {
            var root = value.getAsJsonObject();
            if (!root.get("parent").getAsString().isEmpty()
                    || !root.get("kind").getAsString().equals("SHULKER")
                    || root.get("size").getAsInt() != 27) continue;
            String prefix = root.get("path").getAsString() + "/";
            Map<String, String> actual = new TreeMap<>();
            for (var child : positions) {
                var row = child.getAsJsonObject();
                String path = row.get("path").getAsString();
                if (path.startsWith(prefix))
                    actual.put(
                            path.substring(prefix.length()),
                            row.get("fingerprint").getAsString());
            }
            if (expected.equals(actual))
                copies = Math.addExact(copies, root.get("count").getAsInt());
        }
        return copies;
    }
}
