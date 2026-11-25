package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.ShulkerUuid;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Authoritative observations in one local SQLite transaction. Legacy aggregate indexes are not physical proof. */
final class StockLedger {
    static final class Conflict extends IOException {
        final long revision;
        final String epoch;

        Conflict(long revision, String epoch) {
            super("Observation revision changed; reread the physical sources");
            this.revision = revision;
            this.epoch = epoch;
        }
    }

    record Source(
            String server,
            String stash,
            JsonObject value,
            long revision,
            long verifiedAt,
            String status,
            boolean shared) {}

    private static final int APPLICATION_ID = 0x57535254;
    /** A fully initialized v7 database must never recreate missing authoritative tables. */
    private static final Set<String> REQUIRED_V7_TABLES = Set.of(
            "physical_operations",
            "physical_decisions",
            "kit_jobs",
            "kit_meta",
            "kit_template_versions",
            "kit_templates",
            "physical_snapshots",
            "physical_slots",
            "return_intake",
            "automation_leases",
            "box_uuids",
            "box_uuid_history",
            "box_jobs",
            "stock_meta",
            "stock_sources",
            "stock_parts",
            "stock_members",
            "stock_indexes");

    private final Path file;
    private final LongSupplier clock;
    private final long maxAge;
    private final String run = UUID.randomUUID().toString();
    private String epoch;
    /**
     * Servers with a rescan outstanding: stock stops aging at the freeze plus one
     * grace period, so a slow full-stash re-read is not punished as STALE_AGE
     * while the bot is visibly working through it. Expires on its own when the
     * bot goes quiet (see {@link #effectiveNow}).
     */
    private final Map<String, Long> agingFreeze = new HashMap<>();
    /**
     * A source nobody can reach for this long stops reading as MISSING and
     * escalates to WITHDRAWN: the owner has had a week to accept the loss or
     * fix the container, and the dashboard stops paging forever.
     */
    static final long MISSING_WITHDRAWN_AFTER_MS = 7L * 24 * 3600_000;

    StockLedger(Path data) throws IOException {
        this(data, System::currentTimeMillis, policy());
    }

    StockLedger(Path data, LongSupplier clock, long maxAge) throws IOException {
        if (maxAge < 1) throw new IllegalArgumentException("Positive observation TTL required");
        this.clock = clock;
        this.maxAge = maxAge;
        Files.createDirectories(data);
        file = data.resolve("guild.sqlite");
        // #203: Data/orders/*.json are legacy import inputs, not live mirrors. Warn loudly
        // so humans/tools never read order truth from them; guild.sqlite order_books is authoritative.
        try (var ordersDir = Files.list(data.resolve("orders"))) {
            if (ordersDir
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findAny()
                    .isPresent())
                System.err.println(
                        "WARNING: Data/orders/*.json are legacy import inputs and are ignored — authoritative orders live in guild.sqlite order_books. See INCIDENT-RECOVERY.md.");
        } catch (java.nio.file.NoSuchFileException ignored) {
        }
        for (String suffix : List.of("", "-wal", "-shm"))
            if (Files.isSymbolicLink(Path.of(file + suffix))) throw new IOException("Symlink guild database");
        boolean existed = Files.exists(file);
        Path initialized = data.resolve("guild.sqlite.initialized");
        if (Files.isSymbolicLink(initialized)) throw new IOException("Invalid guild database initialization marker");
        if (!existed
                && (Files.exists(initialized)
                        || Files.exists(Path.of(file + "-wal"))
                        || Files.exists(Path.of(file + "-shm"))))
            throw new IOException("Guild database is missing; restore a consistent backup before starting Manager");
        try {
            Class.forName("org.sqlite.JDBC");
            try (var c = open();
                    var s = c.createStatement()) {
                try (var r = s.executeQuery("PRAGMA application_id")) {
                    if (existed && (!r.next() || r.getInt(1) != APPLICATION_ID))
                        throw new IOException("Foreign guild database format");
                }
                s.execute("PRAGMA journal_mode=WAL");
                int schema;
                try (var r = s.executeQuery("PRAGMA user_version")) {
                    if (!r.next()) throw new IOException("Missing guild database schema");
                    schema = r.getInt(1);
                    if (schema < 0 || schema > 9) throw new IOException("Unsupported guild database schema");
                }
                s.execute("BEGIN IMMEDIATE");
                try {
                    // Validate before any schema repair or recovery writes. A missing
                    // lease or journal table is not equivalent to an empty table.
                    if (existed) validateExistingDatabase(c, schema);
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS physical_operations(id TEXT PRIMARY KEY,server TEXT NOT NULL,agent TEXT NOT NULL,stash TEXT NOT NULL,revision INTEGER NOT NULL,checksum TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,last_seen INTEGER NOT NULL,fence_revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS physical_decisions(id TEXT PRIMARY KEY,operation TEXT NOT NULL,revision INTEGER NOT NULL,checksum TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,issued_at INTEGER NOT NULL,detail TEXT NOT NULL,manager_run TEXT NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS kit_jobs(id TEXT PRIMARY KEY,server TEXT NOT NULL,name_key TEXT NOT NULL,state TEXT NOT NULL,agent TEXT NOT NULL,token TEXT,deadline INTEGER NOT NULL,payload TEXT NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS kit_meta(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL)");
                    s.execute("INSERT OR IGNORE INTO kit_meta VALUES(1,0)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS kit_template_versions(server TEXT NOT NULL,name_key TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,created_by TEXT NOT NULL,PRIMARY KEY(server,name_key,revision))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS kit_templates(server TEXT NOT NULL,name_key TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,revision INTEGER NOT NULL,created_by TEXT NOT NULL,PRIMARY KEY(server,name_key))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS physical_snapshots(source TEXT NOT NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(source,fingerprint))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS physical_slots(source TEXT NOT NULL,path TEXT NOT NULL,parent TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,item_fingerprint TEXT NOT NULL,count INTEGER NOT NULL,copies INTEGER NOT NULL,revision INTEGER NOT NULL,seen_revision INTEGER NOT NULL,present INTEGER NOT NULL,PRIMARY KEY(source,path))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS return_intake(source TEXT NOT NULL,path TEXT NOT NULL,slot_revision INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(source,path,slot_revision))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS automation_leases(server TEXT PRIMARY KEY,agent TEXT NOT NULL,token TEXT NOT NULL,deadline INTEGER NOT NULL,state TEXT NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS box_uuids(uuid TEXT PRIMARY KEY,server TEXT NOT NULL,source TEXT NOT NULL,path TEXT NOT NULL,status TEXT NOT NULL,item_fingerprint TEXT NOT NULL,registered_at INTEGER NOT NULL,verified_at INTEGER NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS box_uuid_history(uuid TEXT NOT NULL,server TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,source TEXT NOT NULL,path TEXT NOT NULL,recorded_at INTEGER NOT NULL,revision INTEGER NOT NULL)");
                    s.execute("CREATE INDEX IF NOT EXISTS box_uuid_history_uuid ON box_uuid_history(uuid)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS signed_request_nonces(nonce TEXT PRIMARY KEY,expires_at INTEGER NOT NULL)");
                    s.execute(
                            "CREATE INDEX IF NOT EXISTS signed_request_nonces_expiry ON signed_request_nonces(expires_at)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS box_jobs(id TEXT PRIMARY KEY,server TEXT NOT NULL,stash TEXT NOT NULL,state TEXT NOT NULL,agent TEXT NOT NULL,token TEXT,deadline INTEGER NOT NULL,payload TEXT NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS stock_meta(id INTEGER PRIMARY KEY CHECK(id=1),epoch TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS stock_sources(id TEXT PRIMARY KEY,server TEXT NOT NULL,dimension TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,proof TEXT NOT NULL,verified_at INTEGER NOT NULL,run TEXT NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS stock_parts(server TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,source TEXT NOT NULL,PRIMARY KEY(server,dimension,x,y,z))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS stock_members(server TEXT NOT NULL,stash TEXT NOT NULL,source TEXT NOT NULL,present INTEGER NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,verified_at INTEGER NOT NULL,PRIMARY KEY(server,stash,source))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS stock_indexes(server TEXT NOT NULL,stash TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(server,stash))");
                    try (var p = c.prepareStatement("INSERT OR IGNORE INTO stock_meta VALUES(1,?,0)")) {
                        p.setString(1, UUID.randomUUID().toString());
                        p.executeUpdate();
                    }
                    s.execute("PRAGMA application_id=" + APPLICATION_ID);
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS order_books(server TEXT NOT NULL,stash TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),payload TEXT NOT NULL,PRIMARY KEY(server,stash))");
                    s.execute("CREATE TABLE IF NOT EXISTS order_storage_meta(id INTEGER PRIMARY KEY CHECK(id=1))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS order_event_log(order_id TEXT NOT NULL,seq INTEGER NOT NULL CHECK(seq>0),server TEXT NOT NULL,stash TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(order_id,seq))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS order_resume_receipts(order_id TEXT PRIMARY KEY,server TEXT NOT NULL,stash TEXT NOT NULL,agent TEXT NOT NULL,payload TEXT NOT NULL)");
                    // PR3 (#140/#228/#267, additive): normalized SNBT authority + variant
                    // register. New writes adopt these; legacy payload-JSON rows stay
                    // untouched until fresh observations supersede them (no migration).
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS snbt_snapshots(snapshot_id TEXT PRIMARY KEY,schema_version INTEGER NOT NULL,canonical_version TEXT NOT NULL,minecraft_version TEXT NOT NULL,registry_context TEXT NOT NULL,content_snbt TEXT NOT NULL,sha256 TEXT NOT NULL,created_revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS variant_register(variant_id INTEGER PRIMARY KEY,codec_domain TEXT NOT NULL,registry_context TEXT NOT NULL,digest BLOB NOT NULL,canonical_variant_snbt TEXT NOT NULL,item_id TEXT NOT NULL,max_stack INTEGER NOT NULL,kind TEXT NOT NULL,metadata_version TEXT NOT NULL,UNIQUE(codec_domain,registry_context,digest))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS slot_projection(source_id TEXT NOT NULL,snapshot_ref TEXT NOT NULL,logical_path TEXT NOT NULL,parent_path TEXT NOT NULL,variant_id INTEGER NOT NULL REFERENCES variant_register(variant_id),count INTEGER NOT NULL,copies INTEGER NOT NULL,slot_fingerprint TEXT NOT NULL,content_revision INTEGER NOT NULL,present INTEGER NOT NULL CHECK(present IN (0,1)),PRIMARY KEY(source_id,logical_path))");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS shulker_identity(uuid TEXT PRIMARY KEY,status TEXT NOT NULL,source TEXT NOT NULL,slot_path TEXT NOT NULL,current_snapshot_ref TEXT,current_revision INTEGER NOT NULL,registration_proof TEXT NOT NULL,export_receipt TEXT)");
                    // PR5 (#44/#140: transaktionell affärsaudit + outbox, additiv).
                    // Security-audit (login) stannar i AuditLog; affärshändelser
                    // (order/job/package/custody/lease/reconciliation) hör hemma
                    // här i samma SQLite-tx som dispositionen, aldrig i audit.json.
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS business_audit(seq INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,actor_id TEXT NOT NULL,actor_name TEXT NOT NULL,action TEXT NOT NULL,order_id TEXT,job_id TEXT,package_id TEXT,correlation_id TEXT,detail TEXT NOT NULL,server TEXT,stash TEXT)");
                    s.execute("CREATE INDEX IF NOT EXISTS business_audit_order ON business_audit(order_id)");
                    s.execute("CREATE INDEX IF NOT EXISTS business_audit_time ON business_audit(at)");
                    s.execute(
                            "CREATE INDEX IF NOT EXISTS business_audit_correlation ON business_audit(correlation_id)");
                    // PR7 (#255, additivt): scoped membership/presence-lookups utan fullscan.
                    s.execute(
                            "CREATE INDEX IF NOT EXISTS stock_members_present ON stock_members(server,stash,present)");
                    s.execute("CREATE INDEX IF NOT EXISTS physical_slots_present ON physical_slots(source,present)");
                    s.execute("PRAGMA user_version=9");
                    var interruptedServers = new ArrayList<String>();
                    try (var leases = s.executeQuery("SELECT server FROM automation_leases WHERE state='ACTIVE'")) {
                        while (leases.next()) interruptedServers.add(leases.getString(1));
                    }
                    s.executeUpdate("UPDATE automation_leases SET state='NEEDS_RECONCILIATION' WHERE state='ACTIVE'");
                    for (String server : interruptedServers) invalidateServer(c, server);
                    s.executeUpdate(
                            "UPDATE physical_decisions SET state='FAILED',detail='Manager restarted; review the current operation' WHERE state='REQUESTED'");
                    s.executeUpdate(
                            "UPDATE physical_operations SET state='NEEDS_RECONCILIATION',fence_revision=revision WHERE state IN ('PLANNED','IN_PROGRESS','VERIFYING')");
                    s.execute("COMMIT");
                } catch (Exception e) {
                    s.execute("ROLLBACK");
                    throw e;
                }
                try (var r = s.executeQuery("SELECT epoch FROM stock_meta WHERE id=1")) {
                    if (!r.next()) throw new SQLException("Missing stock epoch");
                    epoch = r.getString(1);
                }
            }
            // Outside SQLite: losing the database must not also erase proof that
            // its contents were authoritative. Write before any caller can import
            // legacy orders or accept new physical work.
            if (!Files.exists(initialized))
                AtomicFiles.write(
                        initialized,
                        "WeSort guild database initialized\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        true);
        } catch (SQLException | ClassNotFoundException e) {
            throw new IOException("Cannot initialize guild observation database", e);
        }
    }

    private static void validateExistingDatabase(Connection c, int schema) throws SQLException, IOException {
        try (var s = c.createStatement();
                var rows = s.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
                throw new IOException("Guild database failed integrity check");
        }
        // Older supported schemas are migrated by the existing initialization.
        // Once v7 is committed, missing tables/metadata indicate data loss.
        if (schema < 7) return;
        var missing = new TreeSet<>(REQUIRED_V7_TABLES);
        if (schema >= 8)
            missing.addAll(Set.of("order_books", "order_storage_meta", "order_event_log", "signed_request_nonces"));
        if (schema >= 9) missing.add("order_resume_receipts");
        try (var s = c.createStatement();
                var rows = s.executeQuery("SELECT name FROM sqlite_schema WHERE type='table'")) {
            while (rows.next()) missing.remove(rows.getString(1));
        }
        if (!missing.isEmpty()) throw new IOException("Guild database is missing authoritative tables: " + missing);
        if (schema >= 8) {
            try (var s = c.createStatement();
                    var rows = s.executeQuery("""
                        SELECT 1 FROM order_event_log e WHERE NOT EXISTS
                        (SELECT 1 FROM order_books b WHERE b.server=e.server AND b.stash=e.stash) LIMIT 1
                        """)) {
                if (rows.next()) throw new IOException("Order history exists without its durable order book");
            }
        }
        try (var s = c.createStatement();
                var rows = s.executeQuery("SELECT id,epoch,revision FROM stock_meta")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getLong(3) < 0)
                throw new IOException("Invalid guild stock metadata");
            String epoch = rows.getString(2);
            try {
                if (epoch == null || !UUID.fromString(epoch).toString().equals(epoch))
                    throw new IllegalArgumentException("Invalid epoch");
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid guild stock epoch", invalid);
            }
            if (rows.next()) throw new IOException("Duplicate guild stock metadata");
        }
        try (var s = c.createStatement();
                var rows = s.executeQuery("SELECT id,revision FROM kit_meta")) {
            if (!rows.next() || rows.getInt(1) != 1 || rows.getLong(2) < 0 || rows.next())
                throw new IOException("Invalid guild kit metadata");
        }
    }

    private static long policy() {
        String value = System.getenv().getOrDefault("WESORT_STOCK_MAX_AGE_SECONDS", "1800");
        long seconds = Long.parseLong(value);
        if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("WESORT_STOCK_MAX_AGE_SECONDS must be 1..86400");
        // Steady-state invariant from #201: TTL must stay well above the client survey
        // floor (SurveyBatch.MIN_INTERVAL_MS = 60s). Below ~4x the floor a full pass over
        // 69 containers plus one retry can never catch up and staleStock latches permanently.
        if (seconds * 1000 < 4 * 60_000)
            System.err.println("WARNING: WESORT_STOCK_MAX_AGE_SECONDS=" + seconds
                    + " is below 4x the survey floor (60s): steady-state freshness is impossible, see #201.");
        return seconds * 1000;
    }

    Connection open() throws SQLException {
        var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=FULL");
        } catch (SQLException failure) {
            try {
                c.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return c;
    }
    /** Claim a valid signed request before dispatch, atomically across threads and Manager restarts. */
    boolean claimSignedNonce(String nonce, long now, long expiry, int capacity) throws SQLException {
        try (var c = open();
                var tx = new SqliteWriteTransaction(c)) {
            try (var p = c.prepareStatement("DELETE FROM signed_request_nonces WHERE expires_at<=?")) {
                p.setLong(1, now);
                p.executeUpdate();
            }
            boolean claimed;
            try (var p = c.prepareStatement("""
                    INSERT OR IGNORE INTO signed_request_nonces(nonce,expires_at)
                    SELECT ?,? WHERE (SELECT count(*) FROM signed_request_nonces)<?
                    """)) {
                p.setString(1, nonce);
                p.setLong(2, expiry);
                p.setInt(3, capacity);
                claimed = p.executeUpdate() == 1;
            }
            tx.commit();
            return claimed;
        }
    }

    /** Called inside the same transaction that releases/expires an automation lease. */
    void invalidateServer(Connection c, String server) throws SQLException {
        long next = Math.addExact(revision(c), 1);
        int voided = 0;
        try (var p = c.prepareStatement("SELECT id,payload FROM stock_sources WHERE server=?")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var value = JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                    value.addProperty("state", "STALE");
                    try (var update = c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                        update.setString(1, value.toString());
                        update.setLong(2, next);
                        update.setString(3, rows.getString(1));
                        update.executeUpdate();
                    }
                    voided++;
                }
            }
        }
        // #199: bulk-voids are the difference between "rescan in progress" and "ordering
        // dead". Log every one with its trigger site so a 69/69 STALE lock can be told
        // apart from rescan fallout (requestRescan never voids; only this path does).
        if (voided > 0) {
            var trigger = StackWalker.getInstance()
                    .walk(frames -> frames.filter(f -> f.getClassName().startsWith("com.porter.wesortmanager."))
                            .filter(f -> !f.getClassName().endsWith("StockLedger"))
                            .map(f ->
                                    f.getClassName().replace("com.porter.wesortmanager.", "") + "." + f.getMethodName())
                            .findFirst()
                            .orElse("unknown"));
            System.err.println("WARNING: voided " + voided + " stock sources to raw STALE for " + server + " from "
                    + trigger + ": only a physical re-read heals these, freeze-aging does not cover them.");
        }
        try (var p = c.prepareStatement("UPDATE stock_meta SET revision=? WHERE id=1")) {
            p.setLong(1, next);
            p.executeUpdate();
        }
    }

    synchronized long revision() throws IOException {
        try (var c = open()) {
            return revision(c);
        } catch (SQLException e) {
            throw new IOException("Cannot read observation revision", e);
        }
    }

    /** A pending rescan may provide one bounded grace period, not renew stock forever. */
    synchronized void freezeAging(String server, long at) {
        agingFreeze.putIfAbsent(server, at);
    }

    synchronized void unfreezeAging(String server) {
        agingFreeze.remove(server);
    }

    private synchronized long effectiveNow(String server, long now, long observedAt) {
        Long frozen = agingFreeze.get(server);
        if (frozen == null || now < frozen || now - frozen >= Math.min(maxAge / 4, 120_000L)) return now;
        // A source read after the freeze is not a future-dated observation.
        return Math.max(frozen, Math.min(now, observedAt));
    }

    private long revision(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT revision FROM stock_meta WHERE id=1")) {
            if (!r.next()) throw new SQLException("Missing stock revision");
            return r.getLong(1);
        }
    }

    String epoch() {
        return epoch;
    }

    String boot() {
        return run;
    }

    private static long number(JsonObject o, String key) {
        return o.get(key).getAsBigDecimal().longValueExact();
    }

    static List<JsonObject> validate(JsonObject index) {
        if (!index.has("physicalSources")) return List.of();
        long version = number(index, "physicalVersion");
        if (version != 1 && version != 2 && version != 3)
            throw new IllegalArgumentException("Unsupported physical observation version");
        var slots = version == 2 ? new PhysicalSlots.Reader(index.getAsJsonArray("slotVariants")) : null;
        String server = index.get("server").getAsString();
        var values = index.getAsJsonArray("physicalSources");
        if (values.size() > 2048) throw new IllegalArgumentException("Too many physical sources");
        List<JsonObject> result = new ArrayList<>();
        Set<String> occupied = new HashSet<>();
        for (var value : values) {
            JsonObject source = value.getAsJsonObject().deepCopy();
            for (String internal : List.of("slotSnapshot", "slotFingerprint", "slotSchema", "slotCount"))
                source.remove(internal);
            String dimension = source.get("dimension").getAsString();
            List<ContainerIdentity.Position> parts = new ArrayList<>();
            for (var item : source.getAsJsonArray("parts")) {
                var p = item.getAsJsonArray();
                if (p.size() != 3) throw new IllegalArgumentException("Invalid container position");
                parts.add(new ContainerIdentity.Position(
                        p.get(0).getAsBigDecimal().intValueExact(),
                        p.get(1).getAsBigDecimal().intValueExact(),
                        p.get(2).getAsBigDecimal().intValueExact()));
            }
            var identity = new ContainerIdentity(server, dimension, parts);
            if (!identity.id().equals(source.get("id").getAsString()))
                throw new IllegalArgumentException("Container identity mismatch");
            for (var p : parts)
                if (!occupied.add(dimension + ":" + p))
                    throw new IllegalArgumentException("Overlapping inventories in one observation");
            String state = source.get("state").getAsString();
            if (!Set.of("FRESH", "STALE", "UNREACHABLE", "UNKNOWN", "MISSING").contains(state))
                throw new IllegalArgumentException("Invalid observation state");
            if (number(source, "baseRevision") < 0 || number(source, "observedAt") < 0)
                throw new IllegalArgumentException("Invalid observation revision/time");
            if (number(source, "size") != parts.size() * 27L)
                throw new IllegalArgumentException("Inventory topology/slot count differs");
            if (state.equals("FRESH")) {
                if (!source.get("session").getAsString().matches("[0-9a-f-]{36}")
                        || number(source, "sequence") < 1
                        || !source.get("fingerprint").getAsString().matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Missing full server observation proof");
                String kind = source.get("kind").getAsString();
                if (!(kind.equals("minecraft:chest")
                        || kind.equals("minecraft:trapped_chest")
                        || parts.size() == 1
                                && (kind.equals("minecraft:barrel")
                                        || kind.matches("minecraft:(?:[a-z_]+_)?shulker_box"))))
                    throw new IllegalArgumentException("Unsupported inventory kind");
            }
            JsonObject check = new JsonObject();
            check.addProperty("version", 1);
            check.addProperty("server", server);
            check.addProperty("stash", "source");
            check.add("items", source.getAsJsonArray("items"));
            check.add("shulkers", source.getAsJsonArray("shulkers"));
            String error = IndexStore.validate(check);
            if (error != null) throw new IllegalArgumentException("Invalid source contents: " + error);
            for (String field : List.of("items", "shulkers")) {
                Set<String> keys = new HashSet<>();
                for (var entry : source.getAsJsonArray(field)) {
                    var row = entry.getAsJsonObject();
                    if (number(row, "count") < 0 || !keys.add(row.get("key").getAsString()))
                        throw new IllegalArgumentException("Invalid or duplicate physical quantity");
                }
            }
            if (version == 3 && state.equals("FRESH")) {
                // PR4 (#227): v3 sources carry canonical SNBT instead of a variant
                // dictionary. Parse strictly, require canonical round-trip, and
                // bind the fingerprint to the snapshot digest (never JSON).
                if (!source.has("snbt") || !source.get("snbt").isJsonPrimitive())
                    throw new IllegalArgumentException("v3 source is missing canonical snbt");
                String snbt = source.get("snbt").getAsString();
                com.porter.wesort.snbt.SnbtCodec.Tag parsed;
                try {
                    parsed = com.porter.wesort.snbt.SnbtCodec.parse(snbt);
                } catch (com.porter.wesort.snbt.SnbtCodec.SnbtException e) {
                    throw new IllegalArgumentException("Invalid v3 source snbt: " + e.getMessage());
                }
                String canonical = com.porter.wesort.snbt.SnbtCodec.write(parsed);
                if (!canonical.equals(snbt))
                    throw new IllegalArgumentException("v3 source snbt is not canonical");
                String digest = com.porter.wesort.snbt.SnbtCodec.snapshotDigest(canonical);
                if (!digest.equals(source.get("fingerprint").getAsString()))
                    throw new IllegalArgumentException("v3 snapshot digest mismatch");
            }
            if (version == 2 && state.equals("FRESH")) {
                var snapshot = slots.snapshot(Math.toIntExact(number(source, "size")), source.getAsJsonArray("slots"));
                if (!snapshot.fingerprint().equals(source.get("fingerprint").getAsString()))
                    throw new IllegalArgumentException("Physical snapshot fingerprint mismatch");
                if (!amounts(source.getAsJsonArray("items")).equals(snapshot.totals())
                        || !amounts(source.getAsJsonArray("shulkers")).equals(snapshot.shulkers()))
                    throw new IllegalArgumentException("Aggregated quantities do not match physical slots");
                source.add("slotSnapshot", snapshot.json());
            }
            source.remove("slots");
            result.add(source);
        }
        return result;
    }

    private static Map<String, Long> amounts(JsonArray rows) {
        Map<String, Long> result = new TreeMap<>();
        for (var value : rows) {
            var row = value.getAsJsonObject();
            long amount = number(row, "count");
            if (amount != 0) result.merge(row.get("key").getAsString(), amount, Math::addExact);
        }
        return Map.copyOf(result);
    }
    /** One transaction assigns revisions, invalidates old topology, records provenance and persists the index. */
    synchronized void accept(JsonObject index) throws IOException {
        if (!index.has("physicalSources")) return;
        accept(index, index.get("server").getAsString());
    }

    synchronized void accept(JsonObject index, String server) throws IOException {
        if (!index.has("physicalSources")) return;
        // Validate physical IDs against the original wire address, not the Manager's storage key.
        List<JsonObject> sources = validate(index);
        String stash = index.get("stash").getAsString();
        try (var c = open();
                var tx = new SqliteWriteTransaction(c)) {
            long before = revision(c), next = Math.addExact(before, 1);
            long now = clock.getAsLong();
            Map<String, Long> memberTimes = new HashMap<>();
            // Root-level box UUIDs seen in fresh snapshots this batch: source -> uuid -> box.
            Map<String, Map<String, ObservedBox>> freshBoxes = new LinkedHashMap<>();
            for (JsonObject source : sources) {
                String id = source.get("id").getAsString(),
                        dimension = source.get("dimension").getAsString();
                String proof = source.get("session").getAsString() + ":" + number(source, "sequence");
                JsonObject snapshot = source.has("slotSnapshot")
                        ? source.remove("slotSnapshot").getAsJsonObject()
                        : null;
                if (snapshot != null) {
                    source.addProperty("slotSchema", 1);
                    source.addProperty(
                            "slotFingerprint", snapshot.get("fingerprint").getAsString());
                    source.addProperty(
                            "slotCount", snapshot.getAsJsonArray("positions").size());
                }
                String json = source.toString();
                long base = number(source, "baseRevision");
                boolean fresh = source.get("state").getAsString().equals("FRESH"), sameProof = false;
                long oldRevision = 0, verified = 0;
                String verifiedRun = "", previousProof = "";
                JsonObject previous = null;
                try (var p = c.prepareStatement(
                        "SELECT payload,revision,proof,verified_at,run FROM stock_sources WHERE id=?")) {
                    p.setString(1, id);
                    try (var r = p.executeQuery()) {
                        if (r.next()) {
                            previous = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                            oldRevision = r.getLong(2);
                            previousProof = r.getString(3);
                            sameProof = proof.equals(previousProof);
                            verified = r.getLong(4);
                            verifiedRun = r.getString(5);
                        }
                    }
                }
                long overlappingRevision = oldRevision;
                for (var item : source.getAsJsonArray("parts")) {
                    var pos = item.getAsJsonArray();
                    try (var p = c.prepareStatement(
                            "SELECT s.revision FROM stock_parts p JOIN stock_sources s ON s.id=p.source WHERE p.server=? AND p.dimension=? AND p.x=? AND p.y=? AND p.z=?")) {
                        p.setString(1, server);
                        p.setString(2, dimension);
                        for (int i = 0; i < 3; i++) p.setInt(3 + i, pos.get(i).getAsInt());
                        try (var r = p.executeQuery()) {
                            if (r.next()) overlappingRevision = Math.max(overlappingRevision, r.getLong(1));
                        }
                    }
                }
                if (fresh && sameProof) {
                    // A retry cannot change quantities, renew age, or revive a previous connection/run.
                    if (overlappingRevision > oldRevision
                            || !run.equals(verifiedRun)
                            || !previous.get("fingerprint").equals(source.get("fingerprint"))
                            || !previous.get("kind").equals(source.get("kind"))
                            || !previous.get("items").equals(source.get("items"))
                            || !previous.get("shulkers").equals(source.get("shulkers"))
                            || !previous.get("state").getAsString().equals("FRESH")) throw new Conflict(before, epoch);
                    boolean protectedBefore = previous.has("protected")
                            && previous.get("protected").getAsBoolean();
                    boolean protectedNow =
                            source.has("protected") && source.get("protected").getAsBoolean();
                    if (protectedBefore && !protectedNow) throw new Conflict(before, epoch);
                    if (protectedNow && !protectedBefore) {
                        previous.addProperty("protected", true);
                        try (var p = c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                            p.setString(1, previous.toString());
                            p.setLong(2, next);
                            p.setString(3, id);
                            p.executeUpdate();
                        }
                    }
                    memberTimes.put(id, verified);
                    continue;
                }
                if (fresh
                        && (overlappingRevision > base
                                || !source.get("epoch").getAsString().isEmpty()
                                        && !epoch.equals(source.get("epoch").getAsString())
                                || overlappingRevision > 0
                                        && !epoch.equals(source.get("epoch").getAsString())))
                    throw new Conflict(before, epoch);
                if (fresh) {
                    verified = Math.min(now, number(source, "observedAt"));
                    verifiedRun = run;
                } else if (previous != null) {
                    // Loss of access is not an empty inventory. Preserve last quantities as explicitly unknown.
                    source.add("items", previous.get("items"));
                    source.add("shulkers", previous.get("shulkers"));
                    for (String field : List.of("slotSchema", "slotFingerprint", "slotCount"))
                        if (previous.has(field)) source.add(field, previous.get(field));
                    proof = previousProof;
                    json = source.toString();
                }
                memberTimes.put(id, verified);
                String state = source.get("state").getAsString();
                boolean physicalFailure = state.equals("UNREACHABLE") || state.equals("MISSING");
                long sourceRevision = fresh
                                || physicalFailure
                                        && (previous == null
                                                || !previous.get("state")
                                                        .getAsString()
                                                        .equals(state))
                        ? next
                        : oldRevision;
                try (var p = c.prepareStatement(
                        "INSERT INTO stock_sources VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,revision=excluded.revision,proof=excluded.proof,verified_at=excluded.verified_at,run=excluded.run")) {
                    p.setString(1, id);
                    p.setString(2, server);
                    p.setString(3, dimension);
                    p.setString(4, json);
                    p.setLong(5, sourceRevision);
                    p.setString(6, proof);
                    p.setLong(7, verified);
                    p.setString(8, verifiedRun);
                    p.executeUpdate();
                }
                if (fresh) saveSlots(c, id, snapshot, sourceRevision);
                if (snapshot != null && fresh) collectObservedBoxUuids(snapshot, id, freshBoxes);
                for (var item : source.getAsJsonArray("parts")) {
                    var pos = item.getAsJsonArray();
                    if (physicalFailure) {
                        try (var parent = c.prepareStatement(
                                "SELECT s.id,s.payload FROM stock_parts p JOIN stock_sources s ON s.id=p.source WHERE p.server=? AND p.dimension=? AND p.x=? AND p.y=? AND p.z=? AND s.id<>?")) {
                            parent.setString(1, server);
                            parent.setString(2, dimension);
                            for (int i = 0; i < 3; i++)
                                parent.setInt(3 + i, pos.get(i).getAsInt());
                            parent.setString(6, id);
                            try (var r = parent.executeQuery()) {
                                if (r.next()) {
                                    var uncertain = JsonParser.parseString(r.getString(2))
                                            .getAsJsonObject();
                                    if (!uncertain.get("state").getAsString().equals(state)) {
                                        uncertain.addProperty("state", state);
                                        try (var update = c.prepareStatement(
                                                "UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                                            update.setString(1, uncertain.toString());
                                            update.setLong(2, next);
                                            update.setString(3, r.getString(1));
                                            update.executeUpdate();
                                        }
                                    }
                                }
                            }
                        }
                    }
                    try (var p = c.prepareStatement(
                            "INSERT INTO stock_parts VALUES(?,?,?,?,?,?) ON CONFLICT(server,dimension,x,y,z) DO UPDATE SET source=excluded.source WHERE ?=1")) {
                        p.setString(1, server);
                        p.setString(2, dimension);
                        for (int i = 0; i < 3; i++) p.setInt(3 + i, pos.get(i).getAsInt());
                        p.setString(6, id);
                        p.setInt(7, fresh ? 1 : 0);
                        p.executeUpdate();
                    }
                }
            }
            try (var p = c.prepareStatement("UPDATE stock_members SET present=0 WHERE server=? AND stash=?")) {
                p.setString(1, server);
                p.setString(2, stash);
                p.executeUpdate();
            }
            reconcileObservedBoxUuids(c, server, freshBoxes, next, now);
            sweepMissingBoxUuids(c, server, freshBoxes, next, now);
            for (var source : sources)
                try (var p = c.prepareStatement(
                        "INSERT INTO stock_members VALUES(?,?,?,1,?,?,?) ON CONFLICT(server,stash,source) DO UPDATE SET present=1,payload=excluded.payload,revision=excluded.revision,verified_at=excluded.verified_at")) {
                    p.setString(1, server);
                    p.setString(2, stash);
                    p.setString(3, source.get("id").getAsString());
                    p.setString(4, source.toString());
                    p.setLong(5, next);
                    p.setLong(6, memberTimes.get(source.get("id").getAsString()));
                    p.executeUpdate();
                }
            try (var p = c.prepareStatement(
                    "SELECT s.id,s.payload FROM stock_sources s WHERE NOT EXISTS(SELECT 1 FROM stock_members m WHERE m.source=s.id AND m.present=1)")) {
                try (var rows = p.executeQuery()) {
                    while (rows.next()) {
                        JsonObject withdrawn =
                                JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                        if (withdrawn.get("state").getAsString().equals("WITHDRAWN")) continue;
                        withdrawn.addProperty("state", "WITHDRAWN");
                        try (var update =
                                c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                            update.setString(1, withdrawn.toString());
                            update.setLong(2, next);
                            update.setString(3, rows.getString(1));
                            update.executeUpdate();
                        }
                    }
                }
            }
            index.addProperty("stockRevision", next);
            index.addProperty("stockEpoch", epoch);
            try (var p = c.prepareStatement(
                    "INSERT INTO stock_indexes VALUES(?,?,?,?) ON CONFLICT(server,stash) DO UPDATE SET payload=excluded.payload,revision=excluded.revision")) {
                p.setString(1, server);
                p.setString(2, stash);
                var projection = index.deepCopy();
                projection.addProperty("server", server);
                p.setString(3, projection.toString());
                p.setLong(4, next);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("UPDATE stock_meta SET revision=? WHERE id=1")) {
                p.setLong(1, next);
                p.executeUpdate();
            }
            tx.commit();
        } catch (SQLException e) {
            throw new IOException("Observation transaction failed", e);
        }
    }

    private void saveSlots(Connection c, String source, JsonObject snapshot, long revision) throws SQLException {
        if (snapshot != null) {
            try (var p = c.prepareStatement("INSERT OR IGNORE INTO physical_snapshots VALUES(?,?,?,?)")) {
                p.setString(1, source);
                p.setString(2, snapshot.get("fingerprint").getAsString());
                p.setString(3, snapshot.toString());
                p.setLong(4, revision);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement(
                    "INSERT INTO physical_slots VALUES(?,?,?,?,?,?,?,?,?,?,1) ON CONFLICT(source,path) DO UPDATE SET parent=excluded.parent,payload=excluded.payload,item_fingerprint=excluded.item_fingerprint,count=excluded.count,copies=excluded.copies,revision=CASE WHEN physical_slots.present=1 AND physical_slots.fingerprint=excluded.fingerprint AND physical_slots.copies=excluded.copies THEN physical_slots.revision ELSE excluded.revision END,fingerprint=excluded.fingerprint,seen_revision=excluded.seen_revision,present=1")) {
                for (var value : snapshot.getAsJsonArray("positions")) {
                    var row = value.getAsJsonObject();
                    p.setString(1, source);
                    p.setString(2, row.get("path").getAsString());
                    p.setString(3, row.get("parent").getAsString());
                    p.setString(4, row.toString());
                    p.setString(5, row.get("fingerprint").getAsString());
                    p.setString(6, row.get("itemFingerprint").getAsString());
                    p.setLong(7, number(row, "count"));
                    p.setLong(8, number(row, "copies"));
                    p.setLong(9, revision);
                    p.setLong(10, revision);
                    p.addBatch();
                }
                p.executeBatch();
            }
        }
        try (var p = c.prepareStatement(
                "UPDATE physical_slots SET present=0,revision=? WHERE source=? AND present=1 AND seen_revision<>?")) {
            p.setLong(1, revision);
            p.setString(2, source);
            p.setLong(3, revision);
            p.executeUpdate();
        }
    }

    synchronized JsonObject snapshot(String source, String fingerprint) throws IOException {
        try (var c = open();
                var p = c.prepareStatement("SELECT payload FROM physical_snapshots WHERE source=? AND fingerprint=?")) {
            p.setString(1, source);
            p.setString(2, fingerprint);
            try (var r = p.executeQuery()) {
                return r.next() ? JsonParser.parseString(r.getString(1)).getAsJsonObject() : null;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read physical snapshot", e);
        }
    }

    synchronized List<JsonObject> physicalSlots(String source) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        try (var c = open();
                var p = c.prepareStatement(
                        "SELECT payload,revision,present FROM physical_slots WHERE source=? ORDER BY path")) {
            p.setString(1, source);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    var row = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                    row.addProperty("revision", r.getLong(2));
                    row.addProperty("present", r.getBoolean(3));
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read physical slots", e);
        }
        return result;
    }

    record ReturnDecision(JsonObject record, boolean changed) {}

    synchronized ReturnDecision recordReturn(
            String source, JsonObject slot, String snapshot, String actor, boolean markUuid, long expectedDecision)
            throws IOException {
        String path = slot.get("path").getAsString();
        long revision = slot.get("revision").getAsLong();
        var result = new JsonObject();
        result.addProperty("source", source);
        result.addProperty("path", path);
        result.addProperty("slotRevision", revision);
        result.addProperty("stackFingerprint", slot.get("fingerprint").getAsString());
        result.addProperty("snapshotFingerprint", snapshot);
        result.addProperty("markUuid", markUuid);
        result.addProperty("state", markUuid ? "UUID_REQUESTED" : "VERIFIED_KEEP_NAME");
        result.addProperty("actor", actor);
        result.addProperty("recordedAt", clock.getAsLong());
        try (var c = open()) {
            long currentDecision = 0;
            try (var read = c.prepareStatement(
                    "SELECT payload FROM return_intake WHERE source=? AND path=? AND slot_revision=?")) {
                read.setString(1, source);
                read.setString(2, path);
                read.setLong(3, revision);
                try (var rows = read.executeQuery()) {
                    if (rows.next()) {
                        var saved = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                        if (saved.get("markUuid").getAsBoolean() == markUuid) return new ReturnDecision(saved, false);
                        currentDecision = saved.has("decisionRevision")
                                ? saved.get("decisionRevision").getAsLong()
                                : 0;
                    }
                }
            }
            if (expectedDecision != currentDecision) return null;
            result.addProperty("decisionRevision", currentDecision + 1);
            try (var write = c.prepareStatement("""
                    INSERT INTO return_intake VALUES(?,?,?,?)
                    ON CONFLICT(source,path,slot_revision) DO UPDATE SET payload=excluded.payload
                    """)) {
                write.setString(1, source);
                write.setString(2, path);
                write.setLong(3, revision);
                write.setString(4, result.toString());
                write.executeUpdate();
            }
            return new ReturnDecision(result, true);
        } catch (SQLException failure) {
            throw new IOException("Cannot save return intake", failure);
        }
    }

    synchronized JsonArray returnDecisions(String source) throws IOException {
        var decisions = new JsonArray();
        try (var c = open();
                var p = c.prepareStatement("""
                SELECT r.payload FROM return_intake r JOIN physical_slots s
                  ON r.source=s.source AND r.path=s.path AND r.slot_revision=s.revision
                WHERE r.source=? AND s.present=1 ORDER BY s.path
                """)) {
            p.setString(1, source);
            try (var rows = p.executeQuery()) {
                while (rows.next()) decisions.add(JsonParser.parseString(rows.getString(1)));
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read return intake", failure);
        }
        return decisions;
    }
    /** One observed root-level shulker box UUID in a fresh snapshot. */
    private record ObservedBox(String path, String fingerprint) {}

    /**
     * Folds one fresh snapshot's root-level shulker box UUIDs into the batch view: source id to UUID
     * to box. Only UUID-carrying names count; the registry assigns nothing on its own.
     */
    private static void collectObservedBoxUuids(
            JsonObject snapshot, String source, Map<String, Map<String, ObservedBox>> into) {
        if (snapshot == null || !snapshot.has("positions")) return;
        Map<String, ObservedBox> boxes = into.computeIfAbsent(source, k -> new LinkedHashMap<>());
        for (var value : snapshot.getAsJsonArray("positions")) {
            var row = value.getAsJsonObject();
            if (!"SHULKER".equals(row.get("kind").getAsString())) continue;
            String path = row.get("path").getAsString();
            if (path.indexOf('/') >= 0) continue; // only root boxes, never shulkers nested inside other shulkers
            UUID uuid = ShulkerUuid.boxUuid(row.get("components").getAsString());
            if (uuid != null)
                boxes.putIfAbsent(
                        uuid.toString(),
                        new ObservedBox(path, row.get("itemFingerprint").getAsString()));
        }
    }

    /** The registered location of a UUID, or null. */
    private static String[] findBoxUuid(Connection c, String uuid) throws SQLException {
        try (var read = c.prepareStatement("SELECT source,path FROM box_uuids WHERE uuid=?")) {
            read.setString(1, uuid);
            try (var rows = read.executeQuery()) {
                if (rows.next()) return new String[] {rows.getString(1), rows.getString(2)};
            }
        }
        return null;
    }

    /**
     * Reconciles the batch's observed box UUIDs against the registry: new ones are registered (a box
     * found again keeps its UUID and is registered again), ones still where they were are refreshed,
     * ones that moved chests follow the box, and ones seen in two places at once stay on the
     * original record as duplicates.
     */
    private void reconcileObservedBoxUuids(
            Connection c, String server, Map<String, Map<String, ObservedBox>> fresh, long revision, long now)
            throws SQLException {
        for (var bySource : fresh.entrySet()) {
            String source = bySource.getKey();
            for (var seen : bySource.getValue().entrySet()) {
                String uuid = seen.getKey();
                ObservedBox at = seen.getValue();
                String[] where = findBoxUuid(c, uuid);
                if (where == null) {
                    try (var insert = c.prepareStatement("INSERT INTO box_uuids VALUES(?,?,?,?,'IN_STOCK',?,?,?,?)")) {
                        insert.setString(1, uuid);
                        insert.setString(2, server);
                        insert.setString(3, source);
                        insert.setString(4, at.path());
                        insert.setString(5, at.fingerprint());
                        insert.setLong(6, now);
                        insert.setLong(7, now);
                        insert.setLong(8, revision);
                        insert.executeUpdate();
                    }
                    continue;
                }
                if (where[0].equals(source) && where[1].equals(at.path())) {
                    try (var refresh = c.prepareStatement(
                            "UPDATE box_uuids SET verified_at=?,revision=?,item_fingerprint=? WHERE uuid=?")) {
                        refresh.setLong(1, now);
                        refresh.setLong(2, revision);
                        refresh.setString(3, at.fingerprint());
                        refresh.setString(4, uuid);
                        refresh.executeUpdate();
                    }
                    continue;
                }
                // Registered elsewhere: a box that moved chests follows its UUID, but one seen in two
                // places at once is a duplicate and the original record is left untouched. Moves are only
                // followed when the old source was fully observed without the box; otherwise it may sit
                // in an unloaded chunk and nothing changes.
                Map<String, ObservedBox> old = fresh.get(where[0]);
                if (old != null && !atObservedAt(old, where[1], uuid)) {
                    try (var move = c.prepareStatement(
                            "UPDATE box_uuids SET source=?,path=?,item_fingerprint=?,verified_at=?,revision=? WHERE uuid=?")) {
                        move.setString(1, source);
                        move.setString(2, at.path());
                        move.setString(3, at.fingerprint());
                        move.setLong(4, now);
                        move.setLong(5, revision);
                        move.setString(6, uuid);
                        move.executeUpdate();
                    }
                }
            }
        }
    }

    /** Whether the UUID is still observed at its registered path in a fresh batch source. */
    private static boolean atObservedAt(Map<String, ObservedBox> boxes, String path, String uuid) {
        ObservedBox at = boxes.get(uuid);
        return at != null && at.path().equals(path);
    }

    /**
     * Deregisters registry rows that are in no storage chest any more: their last-known source was
     * fully observed in this batch without them. Sources that were not observed (or not fresh) keep
     * their rows: those boxes may sit in an unloaded chunk.
     */
    private void sweepMissingBoxUuids(
            Connection c, String server, Map<String, Map<String, ObservedBox>> fresh, long revision, long now)
            throws SQLException {
        Set<String> seen = new HashSet<>();
        for (var boxes : fresh.values()) seen.addAll(boxes.keySet());
        try (var read = c.prepareStatement("SELECT uuid,source,path,status FROM box_uuids WHERE server=?")) {
            read.setString(1, server);
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    String uuid = rows.getString(1);
                    if (seen.contains(uuid)) continue;
                    if (!fresh.containsKey(rows.getString(2))) continue;
                    // #67: history must point at the real source/path (verified slot
                    // revision); the old call passed uuid/source-id in those columns.
                    deregisterInTx(
                            c,
                            server,
                            uuid,
                            rows.getString(4),
                            rows.getString(2),
                            rows.getString(3),
                            "VANISHED",
                            revision,
                            now);
                }
            }
        }
    }

    /** Appends the active UUID row to history and removes it, inside the caller's transaction. */
    private static void deregisterInTx(
            Connection c,
            String server,
            String uuid,
            String status,
            String source,
            String path,
            String reason,
            long revision,
            long now)
            throws SQLException {
        try (var insert = c.prepareStatement("INSERT INTO box_uuid_history VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1, uuid);
            insert.setString(2, server);
            insert.setString(3, status);
            insert.setString(4, reason);
            insert.setString(5, source);
            insert.setString(6, path);
            insert.setLong(7, now);
            insert.setLong(8, revision);
            insert.executeUpdate();
        }
        try (var del = c.prepareStatement("DELETE FROM box_uuids WHERE uuid=?")) {
            del.setString(1, uuid);
            del.executeUpdate();
        }
    }

    /** UUIDs are installation-wide; evidence in another stash can invalidate both apparent copies. */
    synchronized Set<PhysicalReservations.Root> quarantinedBoxes(String server) throws IOException {
        var unsafe = new HashSet<PhysicalReservations.Root>();
        var registered = new HashMap<String, PhysicalReservations.Root>();
        var seen = new HashMap<String, PhysicalReservations.Root>();
        try (var c = open();
                var registry = c.prepareStatement("SELECT uuid,source,path FROM box_uuids WHERE server=?");
                var slots = c.prepareStatement("""
                    SELECT p.source,p.path,json_extract(p.payload,'$.components'),p.count
                    FROM physical_slots p JOIN stock_sources s ON s.id=p.source
                    WHERE s.server=? AND p.present=1
                    AND json_extract(p.payload,'$.kind')='SHULKER'
                    AND EXISTS (SELECT 1 FROM stock_members m WHERE m.source=p.source AND m.present=1)
                    """)) {
            registry.setString(1, server);
            try (var rows = registry.executeQuery()) {
                while (rows.next())
                    registered.put(
                            rows.getString(1), new PhysicalReservations.Root(rows.getString(2), rows.getString(3)));
            }
            slots.setString(1, server);
            try (var rows = slots.executeQuery()) {
                while (rows.next()) {
                    String name = ShulkerUuid.customName(rows.getString(3));
                    if (name == null
                            || !name.strip().regionMatches(true, 0, ShulkerUuid.PREFIX, 0, ShulkerUuid.PREFIX.length()))
                        continue;
                    var fullPath = rows.getString(2);
                    int nested = fullPath.indexOf('/');
                    // PR6 (#269/#67): nested UUID subtrees quarantine the root —
                    // read() checks the top segment, so report the top segment.
                    var root = new PhysicalReservations.Root(
                            rows.getString(1), nested < 0 ? fullPath : fullPath.substring(0, nested));
                    UUID uuid = ShulkerUuid.parse(name);
                    if (uuid == null) {
                        unsafe.add(root);
                        continue;
                    }
                    String key = uuid.toString();
                    var other = seen.putIfAbsent(key, root);
                    if (other != null) {
                        unsafe.add(other);
                        unsafe.add(root);
                    }
                    if (rows.getLong(4) != 1 || !root.equals(registered.get(key))) unsafe.add(root);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot check physical box identities", failure);
        }
        return unsafe;
    }

    /** The identity of each root-level shulker box in a source, derived from its server-observed name and the registry. */
    synchronized JsonArray boxIdentities(String source) throws IOException {
        var out = new JsonArray();
        try (var c = open()) {
            for (var slot : physicalSlots(source)) {
                if (!"SHULKER".equals(slot.get("kind").getAsString())) continue;
                if (!slot.get("present").getAsBoolean()) continue;
                String path = slot.get("path").getAsString();
                if (path.indexOf('/') >= 0) continue;
                String components = slot.get("components").getAsString();
                String name = ShulkerUuid.customName(components);
                UUID uuid = ShulkerUuid.boxUuid(components);
                var identity = new JsonObject();
                identity.addProperty("path", path);
                identity.addProperty("hasUUID", uuid != null);
                if (uuid != null) identity.addProperty("boxUUID", uuid.toString());
                identity.addProperty("identityStatus", statusOfBox(c, source, path, name, uuid));
                out.add(identity);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read box identities", failure);
        }
        return out;
    }

    private static String statusOfBox(Connection c, String source, String path, String name, UUID uuid)
            throws SQLException {
        if (name == null) return "UNKNOWN";
        if (uuid == null) return "INVALID";
        try (var read = c.prepareStatement("SELECT source,path FROM box_uuids WHERE uuid=?")) {
            read.setString(1, uuid.toString());
            try (var rows = read.executeQuery()) {
                if (rows.next())
                    return rows.getString(1).equals(source) && rows.getString(2).equals(path)
                            ? "REGISTERED"
                            : "DUPLICATE";
            }
        }
        try (var read = c.prepareStatement("SELECT 1 FROM box_uuid_history WHERE uuid=? LIMIT 1")) {
            read.setString(1, uuid.toString());
            try (var rows = read.executeQuery()) {
                if (rows.next()) return "DUPLICATE";
            }
        }
        return "UNREGISTERED";
    }

    static final class BoxReleaseConflict extends IOException {
        BoxReleaseConflict() {
            super("Delivered box UUID is not reserved for this order");
        }
    }

    record BoxRelease(Set<PhysicalReservations.Root> reserved, Set<String> reported) {}

    /** Check the current location and move the UUID to history inside the order receipt's write transaction. */
    void releaseOrderBoxes(Connection c, String server, BoxRelease release) throws SQLException, IOException {
        try (var read = c.prepareStatement("SELECT server,source,path,status FROM box_uuids WHERE uuid=?")) {
            for (String uuid : release.reported()) {
                read.setString(1, uuid);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) continue; // Newly assembled box or already removed by an older Manager.
                    String source = rows.getString(2), path = rows.getString(3);
                    if (!server.equals(rows.getString(1))
                            || !release.reserved().contains(new PhysicalReservations.Root(source, path)))
                        throw new BoxReleaseConflict();
                    deregisterInTx(
                            c,
                            server,
                            uuid,
                            rows.getString(4),
                            source,
                            path,
                            "DEREGISTERED_FOR_ORDER",
                            revision(c),
                            clock.getAsLong());
                }
            }
        }
    }

    synchronized JsonArray boxUuids() throws IOException {
        var out = new JsonArray();
        try (var c = open();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT uuid,server,source,path,status,item_fingerprint,registered_at,verified_at,revision FROM box_uuids ORDER BY registered_at")) {
            while (r.next()) {
                var row = new JsonObject();
                row.addProperty("uuid", r.getString(1));
                row.addProperty("server", r.getString(2));
                row.addProperty("source", r.getString(3));
                row.addProperty("path", r.getString(4));
                row.addProperty("status", r.getString(5));
                row.addProperty("itemFingerprint", r.getString(6));
                row.addProperty("registeredAt", r.getLong(7));
                row.addProperty("verifiedAt", r.getLong(8));
                row.addProperty("revision", r.getLong(9));
                out.add(row);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read box UUIDs", failure);
        }
        return out;
    }

    synchronized JsonArray boxUuidHistory() throws IOException {
        var out = new JsonArray();
        try (var c = open();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT uuid,server,status,reason,source,path,recorded_at,revision FROM box_uuid_history ORDER BY recorded_at DESC LIMIT 512")) {
            while (r.next()) {
                var row = new JsonObject();
                row.addProperty("uuid", r.getString(1));
                row.addProperty("server", r.getString(2));
                row.addProperty("status", r.getString(3));
                row.addProperty("reason", r.getString(4));
                row.addProperty("source", r.getString(5));
                row.addProperty("path", r.getString(6));
                row.addProperty("recordedAt", r.getLong(7));
                row.addProperty("revision", r.getLong(8));
                out.add(row);
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read box UUID history", failure);
        }
        return out;
    }

    synchronized List<JsonObject> indexes() throws IOException {
        List<JsonObject> out = new ArrayList<>();
        try (var c = open();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT payload FROM stock_indexes ORDER BY revision")) {
            while (r.next()) out.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());
        } catch (SQLException e) {
            throw new IOException("Cannot load observation indexes", e);
        }
        return out;
    }

    synchronized List<Source> sources() throws IOException {
        List<Source> out = new ArrayList<>();
        long now = clock.getAsLong();
        try (var c = open();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT s.server,m.stash,CASE WHEN m.present=1 THEN s.payload ELSE m.payload END,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END,CASE WHEN m.present=1 THEN s.verified_at ELSE m.verified_at END,s.run,(SELECT count(*) FROM stock_members m2 WHERE m2.source=s.id AND m2.present=1),s.id,m.present FROM stock_sources s JOIN stock_members m ON m.source=s.id WHERE m.present=1 OR NOT EXISTS(SELECT 1 FROM stock_members active WHERE active.source=s.id AND active.present=1) ORDER BY s.id,m.present DESC,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END DESC,m.stash")) {
            while (r.next()) {
                var value = JsonParser.parseString(r.getString(3)).getAsJsonObject();
                String state = value.get("state").getAsString();
                long at = effectiveNow(r.getString(1), now, r.getLong(5));
                int activeParts = 0;
                try (var p = c.prepareStatement("SELECT count(*) FROM stock_parts WHERE source=?")) {
                    p.setString(1, r.getString(8));
                    try (var n = p.executeQuery()) {
                        if (n.next()) activeParts = n.getInt(1);
                    }
                }
                if (activeParts != value.getAsJsonArray("parts").size()) state = "SUPERSEDED";
                else if (value.has("protected") && value.get("protected").getAsBoolean()) state = "PROTECTED";
                else if (r.getInt(9) == 0) state = "WITHDRAWN";
                else if (state.equals("FRESH") && !run.equals(r.getString(6))) state = "STALE_STARTUP";
                else if (state.equals("FRESH") && (at < r.getLong(5) || at - r.getLong(5) >= maxAge))
                    state = "STALE_AGE";
                else if (state.equals("MISSING") && at - r.getLong(5) >= MISSING_WITHDRAWN_AFTER_MS)
                    state = "WITHDRAWN";
                out.add(new Source(
                        r.getString(1), r.getString(2), value, r.getLong(4), r.getLong(5), state, r.getInt(7) > 1));
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read physical sources", e);
        }
        return out;
    }

    /**
     * Owner-approved "accept loss": a MISSING, UNREACHABLE or already WITHDRAWN
     * source becomes a WITHDRAWN tombstone with zero quantities and an audit
     * marker ({@code lossAccepted: {by, at}}), so one griefed container stops
     * poisoning the row forever. Idempotent: accepting twice still succeeds.
     * Returns false when the source is not lost.
     */
    synchronized boolean acceptLoss(String id, String by) throws IOException {
        long now = clock.getAsLong();
        try (var c = open();
                var tx = new SqliteWriteTransaction(c)) {
            JsonObject value;
            long next;
            try (var p = c.prepareStatement("SELECT payload FROM stock_sources WHERE id=?")) {
                p.setString(1, id);
                try (var rows = p.executeQuery()) {
                    if (!rows.next()) return false;
                    value = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                }
            }
            String state = value.has("state") ? value.get("state").getAsString() : "";
            if (!state.equals("MISSING") && !state.equals("UNREACHABLE") && !state.equals("WITHDRAWN")) return false;
            value.addProperty("state", "WITHDRAWN");
            value.add("items", new JsonArray());
            value.add("shulkers", new JsonArray());
            var accepted = new JsonObject();
            accepted.addProperty("by", by);
            accepted.addProperty("at", now);
            value.add("lossAccepted", accepted);
            next = Math.addExact(revision(c), 1);
            try (var p = c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                p.setString(1, value.toString());
                p.setLong(2, next);
                p.setString(3, id);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("UPDATE stock_meta SET revision=? WHERE id=1")) {
                p.setLong(1, next);
                p.executeUpdate();
            }
            tx.commit();
            return true;
        } catch (SQLException e) {
            throw new IOException("Cannot record accepted loss", e);
        }
    }

    JsonObject stagingManifest(String server, String stash, String order, String agent, int packages)
            throws IOException {
        var proofs = new ArrayList<JsonObject>(packages);
        try (var c = open();
                var p = c.prepareStatement("""
                SELECT payload FROM physical_operations
                WHERE state='COMPLETED' AND server=? AND stash=? AND agent=?
                  AND json_extract(payload,'$.context.orderId')=?
                  AND json_extract(payload,'$.context.execution')='STAGE_PACKAGE'
                ORDER BY json_extract(payload,'$.createdAt'), id LIMIT ?
                """)) {
            p.setString(1, server);
            p.setString(2, stash);
            p.setString(3, agent);
            p.setString(4, order);
            p.setInt(5, packages + 1);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var proof = PackageManifest.transfer(
                            JsonParser.parseString(rows.getString(1)).getAsJsonObject());
                    if (proof == null) return null;
                    proofs.add(proof);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read verified warehouse staging", failure);
        }
        return PackageManifest.staged(order, proofs, packages);
    }

    /**
     * Abandoned journal evidence cannot fence an order forever: an operation unseen for a full
     * day counts as settled for release purposes (its NEEDS_RECONCILIATION fence, if any, still
     * drives order recovery through {@link #reconciliationRequiredPhysicalOrder}).
     */
    static final long OPERATION_FENCE_MS = 24 * 60 * 60 * 1000;

    boolean unsettledPhysicalOrder(String order) throws IOException {
        try (var c = open();
                var p = c.prepareStatement("""
                SELECT 1 FROM physical_operations
                WHERE state NOT IN ('COMPLETED','FAILED') AND json_extract(payload,'$.context.orderId')=?
                  AND last_seen > ? LIMIT 1
                """)) {
            p.setString(1, order);
            p.setLong(2, clock.getAsLong() - OPERATION_FENCE_MS);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot verify referenced physical work", failure);
        }
    }

    /** Only a Manager-fenced physical operation is grounds for failing an active order.
     * A live PLANNED, IN_PROGRESS or VERIFYING operation is expected during packing. */
    boolean reconciliationRequiredPhysicalOrder(String order) throws IOException {
        try (var c = open();
                var p = c.prepareStatement("""
                SELECT 1 FROM physical_operations
                WHERE state='NEEDS_RECONCILIATION'
                  AND json_extract(payload,'$.context.orderId')=? LIMIT 1
                """)) {
            p.setString(1, order);
            try (var rows = p.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot verify physical reconciliation fence", failure);
        }
    }

    JsonArray orderOperationHistory(String order, String server, String stash) throws IOException {
        var history = new JsonArray();
        try (var connection = open();
                var query = connection.prepareStatement("""
                SELECT id,agent,state,revision,last_seen,json_extract(payload,'$.context.kind'),
                    json_extract(payload,'$.context.jobId'),json_extract(payload,'$.context.execution')
                FROM physical_operations WHERE server=? AND stash=? AND json_extract(payload,'$.context.orderId')=?
                ORDER BY last_seen DESC,id LIMIT 128
                """)) {
            query.setString(1, server);
            query.setString(2, stash);
            query.setString(3, order);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var event = new JsonObject();
                    event.addProperty("id", rows.getString(1));
                    event.addProperty("actor", rows.getString(2));
                    event.addProperty("state", rows.getString(3));
                    event.addProperty("revision", rows.getLong(4));
                    event.addProperty("at", rows.getLong(5));
                    event.addProperty("kind", rows.getString(6));
                    event.addProperty("jobId", rows.getString(7));
                    event.addProperty("execution", rows.getString(8));
                    history.add(event);
                }
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read order operation history", failure);
        }
        return history;
    }

    long maxAge() {
        return maxAge;
    }
}
