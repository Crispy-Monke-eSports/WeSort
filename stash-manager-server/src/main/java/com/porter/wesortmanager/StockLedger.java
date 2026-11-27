package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

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

    /**
     * PR3 (#140): custody/deposit/handoff/job authority tables. The ledger owns
     * this DDL; each store owns its SQL (mirror the SnbtStore pattern) so order,
     * custody and audit rows commit in one transaction.
     */
    private static final Set<String> REQUIRED_V11_TABLES = Set.of(
            "custody_packages",
            "custody_events",
            "deposit_packages",
            "deposit_events",
            "handoffs",
            "handoff_events",
            "jobs",
            "job_events");

    private final Path file;
    /** Read by the ledger services under the StockLedger lock (PR3 Step 5, Refs #142). */
    final Clock clock;
    /** Read by the ledger services under the StockLedger lock (PR3 Step 5, Refs #142). */
    final long maxAge;
    /** Read by the ledger services under the StockLedger lock (PR3 Step 5, Refs #142). */
    final String run = UUID.randomUUID().toString();

    String epoch;
    /**
     * Servers with a rescan outstanding: stock stops aging at the freeze plus one
     * grace period, so a slow full-stash re-read is not punished as STALE_AGE
     * while the bot is visibly working through it. Expires on its own when the
     * bot goes quiet (see {@link ObservationService#effectiveNow}).
     */
    final Map<String, Long> agingFreeze = new HashMap<>();
    /**
     * A source nobody can reach for this long stops reading as MISSING and
     * escalates to WITHDRAWN: the owner has had a week to accept the loss or
     * fix the container, and the dashboard stops paging forever.
     */
    static final long MISSING_WITHDRAWN_AFTER_MS = 7L * 24 * 3600_000;

    /** PR3 Step 5 services: built once with the ledger, called under its lock (Refs #142). */
    private final ObservationService observation;

    private final BoxUuidRegistry boxRegistry;
    private final ReturnIntakeService returnIntake;
    private final OperationJournal journal;

    ObservationService observation() {
        return observation;
    }

    BoxUuidRegistry boxRegistry() {
        return boxRegistry;
    }

    ReturnIntakeService returnIntake() {
        return returnIntake;
    }

    OperationJournal journal() {
        return journal;
    }

    StockLedger(Path data) throws IOException {
        this(data, Clock.system(), policy());
    }

    StockLedger(Path data, Clock clock) throws IOException {
        this(data, clock, policy());
    }

    StockLedger(Path data, Clock clock, long maxAge) throws IOException {
        if (maxAge < 1) throw new IllegalArgumentException("Positive observation TTL required");
        this.clock = clock;
        this.maxAge = maxAge;
        this.boxRegistry = new BoxUuidRegistry(this);
        this.returnIntake = new ReturnIntakeService(this);
        this.journal = new OperationJournal(this);
        this.observation = new ObservationService(this, boxRegistry);
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
        // PR3 (#140): pre-SQLite custody/deposits/handoffs/jobs JSON files are
        // legacy inputs, never read. Warn loudly so operators preserve or
        // remove them by hand instead of expecting an automatic import.
        for (String stale : List.of("custody.json", "deposits.json", "handoffs.json", "jobs.json")) {
            Path staleFile = data.resolve(stale);
            if (Files.isSymbolicLink(staleFile)) throw new IOException("Invalid guild database file: " + stale);
            if (Files.exists(staleFile))
                System.err.println("WARNING: Data/" + stale
                        + " is a legacy pre-SQLite file and is ignored — authoritative state lives in guild.sqlite. "
                        + "See INCIDENT-RECOVERY.md.");
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
                    if (schema < 0 || schema > 11) throw new IOException("Unsupported guild database schema");
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
                            "CREATE TABLE IF NOT EXISTS order_books(server TEXT NOT NULL,stash TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>0),PRIMARY KEY(server,stash))");
                    // PR3 (#265): row-diff authority. Commits write only changed
                    // order rows + new receipts; order_books keeps the revision
                    // counter, order_rows/order_receipts carry the content.
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS order_rows(order_id TEXT PRIMARY KEY,server TEXT NOT NULL,stash TEXT NOT NULL,location TEXT NOT NULL,payload TEXT NOT NULL,seq INTEGER NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS order_receipts(receipt_key TEXT PRIMARY KEY,server TEXT NOT NULL,stash TEXT NOT NULL,payload TEXT NOT NULL)");
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
                    s.execute("CREATE INDEX IF NOT EXISTS slot_projection_variant ON slot_projection(variant_id)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS shulker_identity(uuid TEXT PRIMARY KEY,status TEXT NOT NULL,source TEXT NOT NULL,slot_path TEXT NOT NULL,current_snapshot_ref TEXT,current_revision INTEGER NOT NULL,registration_proof TEXT NOT NULL,export_receipt TEXT)");
                    // PR3 (#140): transactional audit trail. Business entries join the
                    // surrounding order/stock/custody transaction; login/security use
                    // own-transaction writes through AuditLog.add.
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS audit_log(seq INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,user_id TEXT,user_name TEXT,action TEXT NOT NULL,detail TEXT,address TEXT)");
                    s.execute("CREATE INDEX IF NOT EXISTS audit_log_action ON audit_log(action,seq)");
                    // PR3 (#140): custody/deposit/handoff/job authority. The
                    // ledger owns this DDL; each store owns its SQL, so order,
                    // custody and audit rows commit in one transaction.
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS custody_packages(package_id TEXT PRIMARY KEY,order_id TEXT NOT NULL,state TEXT NOT NULL,agent TEXT,payload TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS custody_events(package_id TEXT NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(package_id,seq))");
                    s.execute("CREATE INDEX IF NOT EXISTS custody_packages_order ON custody_packages(order_id)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS deposit_packages(package_id TEXT PRIMARY KEY,order_id TEXT NOT NULL,kind TEXT NOT NULL,state TEXT NOT NULL,agent TEXT,destination TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS deposit_events(package_id TEXT NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(package_id,seq))");
                    s.execute("CREATE INDEX IF NOT EXISTS deposit_packages_order ON deposit_packages(order_id)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS handoffs(id TEXT PRIMARY KEY,order_id TEXT NOT NULL,state TEXT NOT NULL,agent TEXT,payload TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS handoff_events(id TEXT NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(id,seq))");
                    s.execute("CREATE INDEX IF NOT EXISTS handoffs_order ON handoffs(order_id)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,chain TEXT NOT NULL,category TEXT NOT NULL,state TEXT NOT NULL,agent TEXT,priority INTEGER NOT NULL,deadline INTEGER NOT NULL,token TEXT,payload TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute(
                            "CREATE TABLE IF NOT EXISTS job_events(id TEXT NOT NULL,seq INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(id,seq))");
                    s.execute("CREATE INDEX IF NOT EXISTS jobs_state ON jobs(state)");
                    s.execute("CREATE INDEX IF NOT EXISTS jobs_chain ON jobs(chain)");
                    migrateOrderBooksToRows(c, s);
                    s.execute("PRAGMA user_version=11");
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

    /**
     * PR3 (#265) v10 migration: backfill the row-diff authority from cached
     * full-book payloads, then drop the payload column. Clean volumes skip this
     * (no order_books rows exist yet). History and receipts tables are
     * append-only and need no migration.
     */
    private static void migrateOrderBooksToRows(Connection c, java.sql.Statement s) throws SQLException, IOException {
        boolean hasPayload = false;
        try (var cols = s.executeQuery("PRAGMA table_info(order_books)")) {
            while (cols.next()) {
                if ("payload".equals(cols.getString(2))) {
                    hasPayload = true;
                    break;
                }
            }
        }
        if (!hasPayload) return;
        try (var books = s.executeQuery("SELECT server,stash,revision,payload FROM order_books")) {
            while (books.next()) {
                String server = books.getString(1);
                String stash = books.getString(2);
                long revision = books.getLong(3);
                var root = com.google.gson.JsonParser.parseString(books.getString(4))
                        .getAsJsonObject();
                var plan = OrderChangeSet.plan(null, root);
                try (var upsert = c.prepareStatement(
                        "INSERT INTO order_rows(order_id,server,stash,location,payload,seq,revision) VALUES(?,?,?,?,?,?,?) ON CONFLICT(order_id) DO NOTHING")) {
                    for (var u : plan.upserts()) {
                        upsert.setString(1, u.orderId());
                        upsert.setString(2, server);
                        upsert.setString(3, stash);
                        upsert.setString(4, u.location());
                        upsert.setString(5, u.json());
                        upsert.setInt(6, u.seq());
                        upsert.setLong(7, revision);
                        upsert.executeUpdate();
                    }
                }
                try (var receipt = c.prepareStatement(
                        "INSERT INTO order_receipts(receipt_key,server,stash,payload) VALUES(?,?,?,?) ON CONFLICT(receipt_key) DO NOTHING")) {
                    for (var r : plan.receipts().entrySet()) {
                        receipt.setString(1, r.getKey());
                        receipt.setString(2, server);
                        receipt.setString(3, stash);
                        receipt.setString(4, r.getValue());
                        receipt.executeUpdate();
                    }
                }
            }
        } catch (RuntimeException failure) {
            throw new IOException("Cannot migrate order books to row-diff authority", failure);
        }
        s.execute("ALTER TABLE order_books DROP COLUMN payload");
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
        if (schema >= 10) missing.addAll(Set.of("order_rows", "order_receipts", "audit_log"));
        if (schema >= 11) missing.addAll(REQUIRED_V11_TABLES);
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

    synchronized long revision() throws IOException {
        try (var c = open()) {
            return revision(c);
        } catch (SQLException e) {
            throw new IOException("Cannot read observation revision", e);
        }
    }

    /** A pending rescan may provide one bounded grace period, not renew stock forever. */
    synchronized void freezeAging(String server, long at) {
        observation.freezeAging(server, at);
    }

    synchronized void unfreezeAging(String server) {
        observation.unfreezeAging(server);
    }

    static List<JsonObject> validate(JsonObject index) {
        return ObservationService.validate(index);
    }

    /** One transaction assigns revisions, invalidates old topology, records provenance and persists the index. */
    synchronized void accept(JsonObject index) throws IOException {
        observation.accept(index);
    }

    synchronized void accept(JsonObject index, String server) throws IOException {
        observation.accept(index, server);
    }

    /** Called inside the same transaction that releases/expires an automation lease. */
    void invalidateServer(Connection c, String server) throws SQLException {
        observation.invalidateServer(c, server);
    }

    /**
     * Owner-approved "accept loss": a MISSING, UNREACHABLE or already WITHDRAWN
     * source becomes a WITHDRAWN tombstone with zero quantities and an audit
     * marker ({@code lossAccepted: {by, at}}), so one griefed container stops
     * poisoning the row forever. Idempotent: accepting twice still succeeds.
     * Returns false when the source is not lost.
     */
    synchronized boolean acceptLoss(String id, String by) throws IOException {
        return observation.acceptLoss(id, by);
    }

    long revision(Connection c) throws SQLException {
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

    static long number(JsonObject o, String key) {
        return o.get(key).getAsBigDecimal().longValueExact();
    }

    /**
     * PR3 (#228 Step 6): the normalized SNBT snapshot is the only content
     * authority. Legacy {@code physical_snapshots} rows are read-only evidence
     * and are never read here; clean installs and fresh observations only, no
     * migration or backfill.
     */
    private static PhysicalSlots.Snapshot readSnbtSnapshot(Connection c, String fingerprint)
            throws SQLException, IOException {
        try (var p = c.prepareStatement("SELECT content_snbt FROM snbt_snapshots WHERE sha256=?")) {
            p.setString(1, fingerprint);
            try (var r = p.executeQuery()) {
                if (!r.next()) return null;
                try {
                    var snapshot = PhysicalSlots.Snapshot.fromSnbt(r.getString(1));
                    if (!fingerprint.equals(snapshot.fingerprint()))
                        throw new IllegalArgumentException("Persisted snapshot digest mismatch");
                    return snapshot;
                } catch (RuntimeException invalid) {
                    throw new IOException(
                            "Invalid or unsupported persisted physical SNBT; clean data or restore required", invalid);
                }
            }
        }
    }

    synchronized JsonObject snapshot(String source, String fingerprint) throws IOException {
        try (var c = open()) {
            var snapshot = readSnbtSnapshot(c, fingerprint);
            return snapshot == null ? null : snapshot.json();
        } catch (SQLException e) {
            throw new IOException("Cannot read physical snapshot", e);
        }
    }

    synchronized String snapshotSnbt(String source, String fingerprint) throws IOException {
        try (var c = open()) {
            var snapshot = readSnbtSnapshot(c, fingerprint);
            return snapshot == null ? null : snapshot.snbt();
        } catch (SQLException e) {
            throw new IOException("Cannot read authoritative physical SNBT", e);
        }
    }

    /**
     * PR3 (#228 Step 6): content-addressed SNBT read for item search and
     * rebuild flows. Resolves through the snapshot digest, never through
     * SQL id/timestamp columns.
     */
    synchronized String snapshotSnbtByDigest(String digest) throws IOException {
        try (var c = open()) {
            var snapshot = readSnbtSnapshot(c, digest);
            return snapshot == null ? null : snapshot.snbt();
        } catch (SQLException e) {
            throw new IOException("Cannot read authoritative physical SNBT", e);
        }
    }

    /** One slot location carrying a registered variant; resolved through the variant index. */
    record VariantSlot(String source, String path) {}

    /**
     * PR3 (#228 Step 6): variant-to-slot lookup for item search. The
     * {@code WHERE variant_id=?} predicate resolves through the
     * {@code slot_projection_variant} index, never a scan.
     */
    synchronized List<VariantSlot> slotsForVariant(long variantId) throws IOException {
        var out = new ArrayList<VariantSlot>();
        try (var c = open();
                var p = c.prepareStatement(
                        "SELECT source_id,logical_path FROM slot_projection WHERE variant_id=? AND present=1 ORDER BY source_id,logical_path")) {
            p.setLong(1, variantId);
            try (var r = p.executeQuery()) {
                while (r.next()) out.add(new VariantSlot(r.getString(1), r.getString(2)));
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read variant slots", e);
        }
        return out;
    }

    /**
     * PR3 (#228 Step 6): rebuilds a source's derived projection from the
     * authoritative SNBT snapshots (e.g. after evidence handling removed the
     * derived rows). Byte-identical when no observation intervened.
     */
    synchronized void rebuildProjectionFromSnbt(String source) throws IOException {
        try (var c = open();
                var tx = new SqliteWriteTransaction(c)) {
            SnbtStore.rebuildProjection(c, source);
            tx.commit();
        } catch (SQLException e) {
            throw new IOException("Cannot rebuild slot projection", e);
        }
    }

    /**
     * PR3 (#228 Step 6): slot reads are projection-primary
     * ({@code slot_projection ⨝ variant_register}) with the authoritative
     * SNBT snapshot as the fail-closed gate. Legacy {@code physical_slots}
     * rows are read-only evidence and are never read here: their divergence
     * cannot leak into reads, while any projection/SNBT divergence fails
     * closed. Empty slots are absent rows (no air rows); only present slots
     * are returned, ordered by path.
     */
    synchronized List<JsonObject> physicalSlots(String source) throws IOException {
        try (var c = open()) {
            String fingerprint = null;
            try (var p = c.prepareStatement("SELECT payload FROM stock_sources WHERE id=?")) {
                p.setString(1, source);
                try (var r = p.executeQuery()) {
                    if (r.next()) {
                        var metadata = JsonParser.parseString(r.getString(1)).getAsJsonObject();
                        if (metadata.has("slotFingerprint"))
                            fingerprint = metadata.get("slotFingerprint").getAsString();
                    }
                }
            }
            if (fingerprint == null) return List.of();
            var snapshot = readSnbtSnapshot(c, fingerprint);
            if (snapshot == null) throw new IOException("Missing authoritative physical SNBT");
            Map<String, JsonObject> expected = new HashMap<>();
            for (var value : snapshot.json().getAsJsonArray("positions")) {
                var row = value.getAsJsonObject();
                expected.put(row.get("path").getAsString(), row);
            }
            List<JsonObject> result = new ArrayList<>();
            try (var p = c.prepareStatement("""
                    SELECT p.logical_path,p.parent_path,p.count,p.copies,p.slot_fingerprint,
                           p.content_revision,p.snapshot_ref,
                           v.canonical_variant_snbt
                    FROM slot_projection p JOIN variant_register v ON v.variant_id=p.variant_id
                    WHERE p.source_id=? AND p.present=1 ORDER BY p.logical_path""")) {
                p.setString(1, source);
                try (var r = p.executeQuery()) {
                    while (r.next()) {
                        String path = r.getString(1);
                        var row = expected.remove(path);
                        if (row == null)
                            throw new IOException("Physical slot projection has no SNBT counterpart");
                        // The descriptor covers the full item identity (id, key,
                        // max-stack, kind, size, group, components): two items
                        // sharing component bytes never alias.
                        String descriptor;
                        try {
                            descriptor = SnbtStore.variantCanonical(
                                    row.get("id").getAsString(),
                                    row.get("key").getAsString(),
                                    row.get("maxStack").getAsLong(),
                                    row.get("kind").getAsString(),
                                    row.get("size").getAsLong(),
                                    row.get("group").getAsString(),
                                    row.get("components").getAsString());
                        } catch (RuntimeException invalid) {
                            throw new IOException(
                                    "Physical slot projection differs from authoritative SNBT at " + path, invalid);
                        }
                        if (!row.get("parent").getAsString().equals(r.getString(2))
                                || row.get("count").getAsLong() != r.getLong(3)
                                || row.get("copies").getAsLong() != r.getLong(4)
                                || !row.get("fingerprint").getAsString().equals(r.getString(5))
                                || !r.getString(7).equals(fingerprint)
                                || !descriptor.equals(r.getString(8)))
                            throw new IOException("Physical slot projection differs from authoritative SNBT at "
                                    + path + ": row=" + row + " projection(parent=" + r.getString(2) + ",count="
                                    + r.getLong(3) + ",copies=" + r.getLong(4) + ",fingerprint=" + r.getString(5)
                                    + ",snapshotRef=" + r.getString(7) + ",variant=" + r.getString(8) + ")");
                        row.addProperty("revision", r.getLong(6));
                        row.addProperty("present", true);
                        result.add(row);
                    }
                }
            }
            if (!expected.isEmpty()) throw new IOException("Physical slot projection is incomplete");
            return result;
        } catch (SQLException | RuntimeException e) {
            throw new IOException("Cannot read physical slots", e);
        }
    }

    record ReturnDecision(JsonObject record, boolean changed) {}

    /** UUIDs are installation-wide; evidence in another stash can invalidate both apparent copies. */
    synchronized Set<PhysicalReservations.Root> quarantinedBoxes(String server) throws IOException {
        return boxRegistry.quarantinedBoxes(server);
    }

    /** The identity of each root-level shulker box in a source, derived from its server-observed name and the registry. */
    synchronized JsonArray boxIdentities(String source) throws IOException {
        return boxRegistry.boxIdentities(source);
    }

    /** Check the current location and move the UUID to history inside the order receipt's write transaction. */
    void releaseOrderBoxes(Connection c, String server, BoxRelease release) throws SQLException, IOException {
        boxRegistry.releaseOrderBoxes(c, server, release);
    }

    synchronized JsonArray boxUuids() throws IOException {
        return boxRegistry.boxUuids();
    }

    synchronized JsonArray boxUuidHistory() throws IOException {
        return boxRegistry.boxUuidHistory();
    }

    static final class BoxReleaseConflict extends IOException {
        BoxReleaseConflict() {
            super("Delivered box UUID is not reserved for this order");
        }
    }

    synchronized ReturnDecision recordReturn(
            String source, JsonObject slot, String snapshot, String actor, boolean markUuid, long expectedDecision)
            throws IOException {
        return returnIntake.recordReturn(source, slot, snapshot, actor, markUuid, expectedDecision);
    }

    synchronized JsonArray returnDecisions(String source) throws IOException {
        return returnIntake.returnDecisions(source);
    }

    record BoxRelease(Set<PhysicalReservations.Root> reserved, Set<String> reported) {}

    JsonObject stagingManifest(String server, String stash, String order, String agent, int packages)
            throws IOException {
        return journal.stagingManifest(server, stash, order, agent, packages);
    }

    boolean unsettledPhysicalOrder(String order) throws IOException {
        return journal.unsettledPhysicalOrder(order);
    }

    /** Only a Manager-fenced physical operation is grounds for failing an active order.
     * A live PLANNED, IN_PROGRESS or VERIFYING operation is expected during packing. */
    boolean reconciliationRequiredPhysicalOrder(String order) throws IOException {
        return journal.reconciliationRequiredPhysicalOrder(order);
    }

    JsonArray orderOperationHistory(String order, String server, String stash) throws IOException {
        return journal.orderOperationHistory(order, server, stash);
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
        long now = clock.millis();
        try (var c = open();
                var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT s.server,m.stash,CASE WHEN m.present=1 THEN s.payload ELSE m.payload END,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END,CASE WHEN m.present=1 THEN s.verified_at ELSE m.verified_at END,s.run,(SELECT count(*) FROM stock_members m2 WHERE m2.source=s.id AND m2.present=1),s.id,m.present FROM stock_sources s JOIN stock_members m ON m.source=s.id WHERE m.present=1 OR NOT EXISTS(SELECT 1 FROM stock_members active WHERE active.source=s.id AND active.present=1) ORDER BY s.id,m.present DESC,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END DESC,m.stash")) {
            while (r.next()) {
                var value = JsonParser.parseString(r.getString(3)).getAsJsonObject();
                String state = value.get("state").getAsString();
                long at = observation.effectiveNow(r.getString(1), now, r.getLong(5));
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

    long maxAge() {
        return maxAge;
    }
}
