package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Authoritative observations in one local SQLite transaction. Legacy aggregate indexes are not physical proof. */
final class StockLedger {
    static final class Conflict extends IOException {
        final long revision; final String epoch;
        Conflict(long revision, String epoch) { super("Observation revision changed; reread the physical sources"); this.revision = revision; this.epoch = epoch; }
    }
    record Source(String server, String stash, JsonObject value, long revision, long verifiedAt, String status, boolean shared) {}
    private static final int APPLICATION_ID = 0x57535254;
    private final Path file;
    private final LongSupplier clock;
    private final long maxAge;
    private final String run = UUID.randomUUID().toString();
    private String epoch;

    StockLedger(Path data) throws IOException { this(data, System::currentTimeMillis, policy()); }
    StockLedger(Path data, LongSupplier clock, long maxAge) throws IOException {
        if (maxAge < 1) throw new IllegalArgumentException("Positive observation TTL required");
        this.clock = clock; this.maxAge = maxAge; Files.createDirectories(data);
        file = data.resolve("guild.sqlite");
        for (String suffix : List.of("", "-wal", "-shm")) if (Files.isSymbolicLink(Path.of(file + suffix))) throw new IOException("Symlink guild database");
        boolean existed = Files.exists(file);
        try {
            Class.forName("org.sqlite.JDBC");
            try (var c = open(); var s = c.createStatement()) {
                try (var r = s.executeQuery("PRAGMA application_id")) {
                    if (existed && (!r.next() || r.getInt(1) != APPLICATION_ID)) throw new IOException("Foreign guild database format");
                }
                s.execute("PRAGMA journal_mode=WAL");
                try (var r = s.executeQuery("PRAGMA user_version")) { if (r.next() && r.getInt(1) > 5) throw new IOException("Unsupported guild database schema"); }
                s.execute("BEGIN IMMEDIATE");
                try {
                    s.execute("CREATE TABLE IF NOT EXISTS physical_operations(id TEXT PRIMARY KEY,server TEXT NOT NULL,agent TEXT NOT NULL,stash TEXT NOT NULL,revision INTEGER NOT NULL,checksum TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,last_seen INTEGER NOT NULL,fence_revision INTEGER NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS physical_decisions(id TEXT PRIMARY KEY,operation TEXT NOT NULL,revision INTEGER NOT NULL,checksum TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,issued_at INTEGER NOT NULL,detail TEXT NOT NULL,manager_run TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS kit_jobs(id TEXT PRIMARY KEY,server TEXT NOT NULL,name_key TEXT NOT NULL,state TEXT NOT NULL,agent TEXT NOT NULL,token TEXT,deadline INTEGER NOT NULL,payload TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS kit_meta(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL)");
                    s.execute("INSERT OR IGNORE INTO kit_meta VALUES(1,0)");
                    s.execute("CREATE TABLE IF NOT EXISTS kit_template_versions(server TEXT NOT NULL,name_key TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,created_by TEXT NOT NULL,PRIMARY KEY(server,name_key,revision))");
                    s.execute("CREATE TABLE IF NOT EXISTS kit_templates(server TEXT NOT NULL,name_key TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,revision INTEGER NOT NULL,created_by TEXT NOT NULL,PRIMARY KEY(server,name_key))");
                    s.execute("CREATE TABLE IF NOT EXISTS physical_snapshots(source TEXT NOT NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(source,fingerprint))");
                    s.execute("CREATE TABLE IF NOT EXISTS physical_slots(source TEXT NOT NULL,path TEXT NOT NULL,parent TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,item_fingerprint TEXT NOT NULL,count INTEGER NOT NULL,copies INTEGER NOT NULL,revision INTEGER NOT NULL,seen_revision INTEGER NOT NULL,present INTEGER NOT NULL,PRIMARY KEY(source,path))");
                    s.execute("CREATE TABLE IF NOT EXISTS automation_leases(server TEXT PRIMARY KEY,agent TEXT NOT NULL,token TEXT NOT NULL,deadline INTEGER NOT NULL,state TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS stock_meta(id INTEGER PRIMARY KEY CHECK(id=1),epoch TEXT NOT NULL,revision INTEGER NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS stock_sources(id TEXT PRIMARY KEY,server TEXT NOT NULL,dimension TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,proof TEXT NOT NULL,verified_at INTEGER NOT NULL,run TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS stock_parts(server TEXT NOT NULL,dimension TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,source TEXT NOT NULL,PRIMARY KEY(server,dimension,x,y,z))");
                    s.execute("CREATE TABLE IF NOT EXISTS stock_members(server TEXT NOT NULL,stash TEXT NOT NULL,source TEXT NOT NULL,present INTEGER NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,verified_at INTEGER NOT NULL,PRIMARY KEY(server,stash,source))");
                    s.execute("CREATE TABLE IF NOT EXISTS stock_indexes(server TEXT NOT NULL,stash TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(server,stash))");
                    try (var p = c.prepareStatement("INSERT OR IGNORE INTO stock_meta VALUES(1,?,0)")) { p.setString(1, UUID.randomUUID().toString()); p.executeUpdate(); }
                    s.execute("PRAGMA application_id=" + APPLICATION_ID); s.execute("PRAGMA user_version=5");
                    var interruptedServers=new ArrayList<String>();
                    try(var leases=s.executeQuery("SELECT server FROM automation_leases WHERE state='ACTIVE'")){while(leases.next())interruptedServers.add(leases.getString(1));}
                    s.executeUpdate("UPDATE automation_leases SET state='NEEDS_RECONCILIATION' WHERE state='ACTIVE'");
                    for(String server:interruptedServers)invalidateServer(c,server);
                    s.executeUpdate("UPDATE physical_decisions SET state='FAILED',detail='Manager restarted; review the current operation' WHERE state='REQUESTED'");
                    s.executeUpdate("UPDATE physical_operations SET state='NEEDS_RECONCILIATION',fence_revision=revision WHERE state IN ('PLANNED','IN_PROGRESS','VERIFYING')");
                    s.execute("COMMIT");
                } catch (Exception e) { s.execute("ROLLBACK"); throw e; }
                try (var r = s.executeQuery("SELECT epoch FROM stock_meta WHERE id=1")) { if (!r.next()) throw new SQLException("Missing stock epoch"); epoch = r.getString(1); }
            }
        } catch (SQLException | ClassNotFoundException e) { throw new IOException("Cannot initialize guild observation database", e); }
    }
    private static long policy() {
        String value = System.getenv().getOrDefault("WESORT_STOCK_MAX_AGE_SECONDS", "300");
        long seconds = Long.parseLong(value);
        if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException("WESORT_STOCK_MAX_AGE_SECONDS must be 1..86400");
        return seconds * 1000;
    }
    Connection open() throws SQLException {
        var c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var s = c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA synchronous=FULL"); }
        return c;
    }
    /** Called inside the same transaction that releases/expires an automation lease. */
    void invalidateServer(Connection c, String server) throws SQLException {
        long next = Math.addExact(revision(c), 1);
        try (var p = c.prepareStatement("SELECT id,payload FROM stock_sources WHERE server=?")) {
            p.setString(1, server);
            try (var rows = p.executeQuery()) { while (rows.next()) {
                var value = JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                value.addProperty("state", "STALE");
                try (var update = c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                    update.setString(1, value.toString()); update.setLong(2, next); update.setString(3, rows.getString(1)); update.executeUpdate();
                }
            } }
        }
        try (var p = c.prepareStatement("UPDATE stock_meta SET revision=? WHERE id=1")) { p.setLong(1, next); p.executeUpdate(); }
    }
    synchronized long revision() throws IOException {
        try (var c = open()) { return revision(c); } catch (SQLException e) { throw new IOException("Cannot read observation revision", e); }
    }
    private long revision(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT revision FROM stock_meta WHERE id=1")) { if (!r.next()) throw new SQLException("Missing stock revision"); return r.getLong(1); }
    }
    String epoch() { return epoch; }
    String boot() { return run; }
    private static long number(JsonObject o, String key) { return o.get(key).getAsBigDecimal().longValueExact(); }
    static List<JsonObject> validate(JsonObject index) {
        if (!index.has("physicalSources")) return List.of();
        long version = number(index, "physicalVersion");
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported physical observation version");
        var slots = version == 2 ? new PhysicalSlots.Reader(index.getAsJsonArray("slotVariants")) : null;
        String server = index.get("server").getAsString();
        var values = index.getAsJsonArray("physicalSources");
        if (values.size() > 2048) throw new IllegalArgumentException("Too many physical sources");
        List<JsonObject> result = new ArrayList<>(); Set<String> occupied = new HashSet<>();
        for (var value : values) {
            JsonObject source = value.getAsJsonObject().deepCopy();
            for (String internal : List.of("slotSnapshot", "slotFingerprint", "slotSchema", "slotCount")) source.remove(internal);
            String dimension = source.get("dimension").getAsString();
            List<ContainerIdentity.Position> parts = new ArrayList<>();
            for (var item : source.getAsJsonArray("parts")) {
                var p = item.getAsJsonArray(); if (p.size() != 3) throw new IllegalArgumentException("Invalid container position");
                parts.add(new ContainerIdentity.Position(p.get(0).getAsBigDecimal().intValueExact(), p.get(1).getAsBigDecimal().intValueExact(), p.get(2).getAsBigDecimal().intValueExact()));
            }
            var identity = new ContainerIdentity(server, dimension, parts);
            if (!identity.id().equals(source.get("id").getAsString())) throw new IllegalArgumentException("Container identity mismatch");
            for (var p : parts) if (!occupied.add(dimension + ":" + p)) throw new IllegalArgumentException("Overlapping inventories in one observation");
            String state = source.get("state").getAsString();
            if (!Set.of("FRESH", "STALE", "UNREACHABLE", "UNKNOWN", "MISSING").contains(state)) throw new IllegalArgumentException("Invalid observation state");
            if (number(source, "baseRevision") < 0 || number(source, "observedAt") < 0) throw new IllegalArgumentException("Invalid observation revision/time");
            if (number(source, "size") != parts.size() * 27L) throw new IllegalArgumentException("Inventory topology/slot count differs");
            if (state.equals("FRESH")) {
                if (!source.get("session").getAsString().matches("[0-9a-f-]{36}") || number(source, "sequence") < 1
                    || !source.get("fingerprint").getAsString().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Missing full server observation proof");
                String kind = source.get("kind").getAsString();
                if (!(kind.equals("minecraft:chest") || kind.equals("minecraft:trapped_chest") || parts.size() == 1
                    && (kind.equals("minecraft:barrel") || kind.matches("minecraft:(?:[a-z_]+_)?shulker_box")))) throw new IllegalArgumentException("Unsupported inventory kind");
            }
            JsonObject check = new JsonObject(); check.addProperty("version", 1); check.addProperty("server", server); check.addProperty("stash", "source");
            check.add("items", source.getAsJsonArray("items")); check.add("shulkers", source.getAsJsonArray("shulkers"));
            String error = IndexStore.validate(check); if (error != null) throw new IllegalArgumentException("Invalid source contents: " + error);
            for (String field : List.of("items", "shulkers")) {
                Set<String> keys = new HashSet<>();
                for (var entry : source.getAsJsonArray(field)) {
                    var row = entry.getAsJsonObject();
                    if (number(row, "count") < 0 || !keys.add(row.get("key").getAsString()))
                        throw new IllegalArgumentException("Invalid or duplicate physical quantity");
                }
            }
            if (version == 2 && state.equals("FRESH")) {
                var snapshot = slots.snapshot(Math.toIntExact(number(source, "size")), source.getAsJsonArray("slots"));
                if (!snapshot.fingerprint().equals(source.get("fingerprint").getAsString())) throw new IllegalArgumentException("Physical snapshot fingerprint mismatch");
                if (!amounts(source.getAsJsonArray("items")).equals(snapshot.totals()) || !amounts(source.getAsJsonArray("shulkers")).equals(snapshot.shulkers()))
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
            var row = value.getAsJsonObject(); long amount = number(row, "count");
            if (amount != 0) result.merge(row.get("key").getAsString(), amount, Math::addExact);
        }
        return Map.copyOf(result);
    }
    /** One transaction assigns revisions, invalidates old topology, records provenance and persists the index. */
    synchronized void accept(JsonObject index) throws IOException {
        if (!index.has("physicalSources")) return;
        List<JsonObject> sources = validate(index);
        String server = index.get("server").getAsString(), stash = index.get("stash").getAsString();
        try (var c = open(); var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                long before = revision(c), next = Math.addExact(before, 1); long now = clock.getAsLong();
                Map<String,Long> memberTimes = new HashMap<>();
                for (JsonObject source : sources) {
                    String id = source.get("id").getAsString(), dimension = source.get("dimension").getAsString();
                    String proof = source.get("session").getAsString() + ":" + number(source, "sequence");
                    JsonObject snapshot = source.has("slotSnapshot") ? source.remove("slotSnapshot").getAsJsonObject() : null;
                    if (snapshot != null) {
                        source.addProperty("slotSchema", 1); source.addProperty("slotFingerprint", snapshot.get("fingerprint").getAsString());
                        source.addProperty("slotCount", snapshot.getAsJsonArray("positions").size());
                    }
                    String json = source.toString(); long base = number(source, "baseRevision");
                    boolean fresh = source.get("state").getAsString().equals("FRESH"), sameProof = false;
                    long oldRevision = 0, verified = 0; String verifiedRun = "", previousProof = "";
                    JsonObject previous = null;
                    try (var p = c.prepareStatement("SELECT payload,revision,proof,verified_at,run FROM stock_sources WHERE id=?")) {
                        p.setString(1, id); try (var r = p.executeQuery()) { if (r.next()) {
                            previous = JsonParser.parseString(r.getString(1)).getAsJsonObject(); oldRevision = r.getLong(2);
                            previousProof = r.getString(3); sameProof = proof.equals(previousProof); verified = r.getLong(4); verifiedRun = r.getString(5);
                        } }
                    }
                    long overlappingRevision = oldRevision;
                    for (var item : source.getAsJsonArray("parts")) {
                        var pos = item.getAsJsonArray();
                        try (var p = c.prepareStatement("SELECT s.revision FROM stock_parts p JOIN stock_sources s ON s.id=p.source WHERE p.server=? AND p.dimension=? AND p.x=? AND p.y=? AND p.z=?")) {
                            p.setString(1, server); p.setString(2, dimension); for (int i=0;i<3;i++) p.setInt(3+i, pos.get(i).getAsInt());
                            try (var r = p.executeQuery()) { if (r.next()) overlappingRevision = Math.max(overlappingRevision, r.getLong(1)); }
                        }
                    }
                    if (fresh && sameProof) {
                        // A retry cannot change quantities, renew age, or revive a previous connection/run.
                        if (overlappingRevision > oldRevision || !run.equals(verifiedRun) || !previous.get("fingerprint").equals(source.get("fingerprint")) || !previous.get("kind").equals(source.get("kind"))
                            || !previous.get("items").equals(source.get("items")) || !previous.get("shulkers").equals(source.get("shulkers"))
                            || !previous.get("state").getAsString().equals("FRESH")) throw new Conflict(before, epoch);
                        boolean protectedBefore = previous.has("protected") && previous.get("protected").getAsBoolean();
                        boolean protectedNow = source.has("protected") && source.get("protected").getAsBoolean();
                        if (protectedBefore && !protectedNow) throw new Conflict(before, epoch);
                        if (protectedNow && !protectedBefore) {
                            previous.addProperty("protected", true);
                            try (var p = c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")) {
                                p.setString(1, previous.toString()); p.setLong(2, next); p.setString(3, id); p.executeUpdate();
                            }
                        }
                        memberTimes.put(id, verified); continue;
                    }
                    if (fresh && (overlappingRevision > base || !source.get("epoch").getAsString().isEmpty() && !epoch.equals(source.get("epoch").getAsString())
                        || overlappingRevision > 0 && !epoch.equals(source.get("epoch").getAsString()))) throw new Conflict(before, epoch);
                    if (fresh) { verified = Math.min(now, number(source, "observedAt")); verifiedRun = run; }
                    else if (previous != null) {
                        // Loss of access is not an empty inventory. Preserve last quantities as explicitly unknown.
                        source.add("items", previous.get("items")); source.add("shulkers", previous.get("shulkers"));
                        for (String field : List.of("slotSchema", "slotFingerprint", "slotCount")) if (previous.has(field)) source.add(field, previous.get(field));
                        proof = previousProof; json = source.toString();
                    }
                    memberTimes.put(id, verified);
                    String state = source.get("state").getAsString();
                    boolean physicalFailure = state.equals("UNREACHABLE") || state.equals("MISSING");
                    long sourceRevision = fresh || physicalFailure && (previous == null || !previous.get("state").getAsString().equals(state)) ? next : oldRevision;
                    try (var p = c.prepareStatement("INSERT INTO stock_sources VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,revision=excluded.revision,proof=excluded.proof,verified_at=excluded.verified_at,run=excluded.run")) {
                        p.setString(1,id); p.setString(2,server); p.setString(3,dimension); p.setString(4,json); p.setLong(5,sourceRevision);
                        p.setString(6,proof); p.setLong(7,verified); p.setString(8,verifiedRun); p.executeUpdate();
                    }
                    if (fresh) saveSlots(c, id, snapshot, sourceRevision);
                    for (var item : source.getAsJsonArray("parts")) {
                        var pos = item.getAsJsonArray();
                        if (physicalFailure) {
                            try (var parent = c.prepareStatement("SELECT s.id,s.payload FROM stock_parts p JOIN stock_sources s ON s.id=p.source WHERE p.server=? AND p.dimension=? AND p.x=? AND p.y=? AND p.z=? AND s.id<>?")) {
                                parent.setString(1,server);parent.setString(2,dimension);for(int i=0;i<3;i++)parent.setInt(3+i,pos.get(i).getAsInt());parent.setString(6,id);
                                try(var r=parent.executeQuery()){if(r.next()){
                                    var uncertain=JsonParser.parseString(r.getString(2)).getAsJsonObject();
                                    if(!uncertain.get("state").getAsString().equals(state)){
                                        uncertain.addProperty("state",state);
                                        try(var update=c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")){update.setString(1,uncertain.toString());update.setLong(2,next);update.setString(3,r.getString(1));update.executeUpdate();}
                                    }
                                }}
                            }
                        }
                        try (var p = c.prepareStatement("INSERT INTO stock_parts VALUES(?,?,?,?,?,?) ON CONFLICT(server,dimension,x,y,z) DO UPDATE SET source=excluded.source WHERE ?=1")) {
                            p.setString(1,server); p.setString(2,dimension); for(int i=0;i<3;i++)p.setInt(3+i,pos.get(i).getAsInt());p.setString(6,id);p.setInt(7,fresh?1:0);p.executeUpdate();
                        }
                    }
                }
                try (var p = c.prepareStatement("UPDATE stock_members SET present=0 WHERE server=? AND stash=?")) { p.setString(1,server);p.setString(2,stash);p.executeUpdate(); }
                for (var source : sources) try (var p = c.prepareStatement("INSERT INTO stock_members VALUES(?,?,?,1,?,?,?) ON CONFLICT(server,stash,source) DO UPDATE SET present=1,payload=excluded.payload,revision=excluded.revision,verified_at=excluded.verified_at")) {
                    p.setString(1,server);p.setString(2,stash);p.setString(3,source.get("id").getAsString());p.setString(4,source.toString());p.setLong(5,next);p.setLong(6,memberTimes.get(source.get("id").getAsString()));p.executeUpdate();
                }
                try (var p=c.prepareStatement("SELECT s.id,s.payload FROM stock_sources s WHERE NOT EXISTS(SELECT 1 FROM stock_members m WHERE m.source=s.id AND m.present=1)")) {
                    try (var rows=p.executeQuery()) { while(rows.next()) {
                        JsonObject withdrawn=JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                        if(withdrawn.get("state").getAsString().equals("WITHDRAWN"))continue;
                        withdrawn.addProperty("state","WITHDRAWN");
                        try(var update=c.prepareStatement("UPDATE stock_sources SET payload=?,revision=? WHERE id=?")){update.setString(1,withdrawn.toString());update.setLong(2,next);update.setString(3,rows.getString(1));update.executeUpdate();}
                    } }
                }
                index.addProperty("stockRevision", next); index.addProperty("stockEpoch", epoch);
                try(var p=c.prepareStatement("INSERT INTO stock_indexes VALUES(?,?,?,?) ON CONFLICT(server,stash) DO UPDATE SET payload=excluded.payload,revision=excluded.revision")){p.setString(1,server);p.setString(2,stash);p.setString(3,index.toString());p.setLong(4,next);p.executeUpdate();}
                try(var p=c.prepareStatement("UPDATE stock_meta SET revision=? WHERE id=1")){p.setLong(1,next);p.executeUpdate();}
                tx.execute("COMMIT");
            } catch (Exception e) { tx.execute("ROLLBACK"); if(e instanceof IOException io)throw io; if(e instanceof RuntimeException re)throw re;throw e; }
        } catch(SQLException e){throw new IOException("Observation transaction failed",e);}
    }
    private void saveSlots(Connection c, String source, JsonObject snapshot, long revision) throws SQLException {
        if (snapshot != null) {
            try (var p = c.prepareStatement("INSERT OR IGNORE INTO physical_snapshots VALUES(?,?,?,?)")) {
                p.setString(1, source); p.setString(2, snapshot.get("fingerprint").getAsString()); p.setString(3, snapshot.toString()); p.setLong(4, revision); p.executeUpdate();
            }
            try (var p = c.prepareStatement("INSERT INTO physical_slots VALUES(?,?,?,?,?,?,?,?,?,?,1) ON CONFLICT(source,path) DO UPDATE SET parent=excluded.parent,payload=excluded.payload,item_fingerprint=excluded.item_fingerprint,count=excluded.count,copies=excluded.copies,revision=CASE WHEN physical_slots.present=1 AND physical_slots.fingerprint=excluded.fingerprint AND physical_slots.copies=excluded.copies THEN physical_slots.revision ELSE excluded.revision END,fingerprint=excluded.fingerprint,seen_revision=excluded.seen_revision,present=1")) {
                for (var value : snapshot.getAsJsonArray("positions")) {
                    var row = value.getAsJsonObject();
                    p.setString(1, source); p.setString(2, row.get("path").getAsString()); p.setString(3, row.get("parent").getAsString());
                    p.setString(4, row.toString()); p.setString(5, row.get("fingerprint").getAsString()); p.setString(6, row.get("itemFingerprint").getAsString());
                    p.setLong(7, number(row, "count")); p.setLong(8, number(row, "copies")); p.setLong(9, revision); p.setLong(10, revision); p.addBatch();
                }
                p.executeBatch();
            }
        }
        try (var p = c.prepareStatement("UPDATE physical_slots SET present=0,revision=? WHERE source=? AND present=1 AND seen_revision<>?")) {
            p.setLong(1, revision); p.setString(2, source); p.setLong(3, revision); p.executeUpdate();
        }
    }
    synchronized JsonObject snapshot(String source, String fingerprint) throws IOException {
        try (var c = open(); var p = c.prepareStatement("SELECT payload FROM physical_snapshots WHERE source=? AND fingerprint=?")) {
            p.setString(1, source); p.setString(2, fingerprint);
            try (var r = p.executeQuery()) { return r.next() ? JsonParser.parseString(r.getString(1)).getAsJsonObject() : null; }
        } catch (SQLException e) { throw new IOException("Cannot read physical snapshot", e); }
    }
    synchronized List<JsonObject> physicalSlots(String source) throws IOException {
        List<JsonObject> result = new ArrayList<>();
        try (var c = open(); var p = c.prepareStatement("SELECT payload,revision,present FROM physical_slots WHERE source=? ORDER BY path")) {
            p.setString(1, source);
            try (var r = p.executeQuery()) { while (r.next()) {
                var row = JsonParser.parseString(r.getString(1)).getAsJsonObject(); row.addProperty("revision", r.getLong(2)); row.addProperty("present", r.getBoolean(3)); result.add(row);
            } }
        } catch (SQLException e) { throw new IOException("Cannot read physical slots", e); }
        return result;
    }
    synchronized List<JsonObject> indexes() throws IOException {
        List<JsonObject> out=new ArrayList<>();
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM stock_indexes ORDER BY revision")){while(r.next())out.add(JsonParser.parseString(r.getString(1)).getAsJsonObject());}
        catch(SQLException e){throw new IOException("Cannot load observation indexes",e);}return out;
    }
    synchronized List<Source> sources() throws IOException {
        List<Source> out=new ArrayList<>();long now=clock.getAsLong();
        try(var c=open();var s=c.createStatement();var r=s.executeQuery("SELECT s.server,m.stash,CASE WHEN m.present=1 THEN s.payload ELSE m.payload END,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END,CASE WHEN m.present=1 THEN s.verified_at ELSE m.verified_at END,s.run,(SELECT count(*) FROM stock_members m2 WHERE m2.source=s.id AND m2.present=1),s.id,m.present FROM stock_sources s JOIN stock_members m ON m.source=s.id WHERE m.present=1 OR NOT EXISTS(SELECT 1 FROM stock_members active WHERE active.source=s.id AND active.present=1) ORDER BY s.id,m.present DESC,CASE WHEN m.present=1 THEN s.revision ELSE m.revision END DESC,m.stash")){
            while(r.next()){
                var value=JsonParser.parseString(r.getString(3)).getAsJsonObject();String state=value.get("state").getAsString();
                int activeParts=0;try(var p=c.prepareStatement("SELECT count(*) FROM stock_parts WHERE source=?")){p.setString(1,r.getString(8));try(var n=p.executeQuery()){if(n.next())activeParts=n.getInt(1);}}
                if(activeParts!=value.getAsJsonArray("parts").size())state="SUPERSEDED";
                else if(value.has("protected") && value.get("protected").getAsBoolean())state="PROTECTED";
                else if(r.getInt(9)==0)state="WITHDRAWN";
                else if(state.equals("FRESH") && !run.equals(r.getString(6)))state="STALE_STARTUP";
                else if(state.equals("FRESH") && (now<r.getLong(5)||now-r.getLong(5)>=maxAge))state="STALE_AGE";
                out.add(new Source(r.getString(1),r.getString(2),value,r.getLong(4),r.getLong(5),state,r.getInt(7)>1));
            }
        }catch(SQLException e){throw new IOException("Cannot read physical sources",e);}return out;
    }
    long maxAge(){return maxAge;}
}
