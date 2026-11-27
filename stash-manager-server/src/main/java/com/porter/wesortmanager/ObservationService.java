package com.porter.wesortmanager;
    
    import com.google.gson.JsonArray;
    import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
    import com.porter.wesort.data.ContainerIdentity;
    import com.porter.wesort.data.PhysicalSlots;
    import java.io.IOException;
    import java.sql.Connection;
    import java.sql.SQLException;
    import java.util.ArrayList;
    import java.util.HashMap;
    import java.util.HashSet;
    import java.util.LinkedHashMap;
    import java.util.List;
    import java.util.Map;
    import java.util.Set;
import java.util.TreeMap;
    import java.util.UUID;
    
    /**
     * PR3 Step 5: physical observations (validation, the accept transaction, loss
     * acceptance and lease-expiry invalidation), moved verbatim out of StockLedger
     * (no behavior change, Refs #142). Runs under the StockLedger lock.
     */
    final class ObservationService {
        private final StockLedger ledger;
        private final BoxUuidRegistry boxes;
    
        ObservationService(StockLedger ledger, BoxUuidRegistry boxes) {
            this.ledger = ledger;
            this.boxes = boxes;
        }
    
        /** A pending rescan may provide one bounded grace period, not renew stock forever. */
    void freezeAging(String server, long at) {
        ledger.agingFreeze.putIfAbsent(server, at);
    }

    void unfreezeAging(String server) {
        ledger.agingFreeze.remove(server);
    }

    long effectiveNow(String server, long now, long observedAt) {
        Long frozen = ledger.agingFreeze.get(server);
        if (frozen == null || now < frozen || now - frozen >= Math.min(ledger.maxAge / 4, 120_000L)) return now;
        // A source read after the freeze is not a future-dated observation.
        return Math.max(frozen, Math.min(now, observedAt));
    }
    static List<JsonObject> validate(JsonObject index) {
        if (!index.has("physicalSources")) return List.of();
        long version = StockLedger.number(index, "physicalVersion");
        if (version != 1 && version != 3)
            throw new IllegalArgumentException("Unsupported physical observation version");
        if (version == 3 && index.has("slotVariants"))
            throw new IllegalArgumentException("SNBT observations cannot contain a variant dictionary");
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
            if (StockLedger.number(source, "baseRevision") < 0 || StockLedger.number(source, "observedAt") < 0)
                throw new IllegalArgumentException("Invalid observation revision/time");
            if (StockLedger.number(source, "size") != parts.size() * 27L)
                throw new IllegalArgumentException("Inventory topology/slot count differs");
            if (state.equals("FRESH")) {
                if (!source.get("session").getAsString().matches("[0-9a-f-]{36}")
                        || StockLedger.number(source, "sequence") < 1
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
                    if (StockLedger.number(row, "count") < 0 || !keys.add(row.get("key").getAsString()))
                        throw new IllegalArgumentException("Invalid or duplicate physical quantity");
                }
            }
            if (version == 3) {
                if (source.has("slots") || !source.has("snbt"))
                    throw new IllegalArgumentException("Expected SNBT-only physical observation");
                if (!state.equals("FRESH") && !source.get("snbt").isJsonNull())
                    throw new IllegalArgumentException("Unverified source cannot publish new SNBT");
                if (state.equals("FRESH")
                        && (!source.get("snbt").isJsonPrimitive()
                                || !source.get("snbt").getAsJsonPrimitive().isString()))
                    throw new IllegalArgumentException("Fresh source requires canonical SNBT");
            }
            if (version == 3 && state.equals("FRESH")) {
                var snapshot =
                        PhysicalSlots.Snapshot.fromSnbt(source.get("snbt").getAsString());
                if (snapshot.size() != StockLedger.number(source, "size"))
                    throw new IllegalArgumentException("SNBT inventory capacity mismatch");
                if (!snapshot.fingerprint().equals(source.get("fingerprint").getAsString()))
                    throw new IllegalArgumentException("Physical snapshot fingerprint mismatch");
                if (!amounts(source.getAsJsonArray("items")).equals(snapshot.totals())
                        || !amounts(source.getAsJsonArray("shulkers")).equals(snapshot.shulkers()))
                    throw new IllegalArgumentException("Aggregated quantities do not match physical slots");
                var view = snapshot.json();
                view.addProperty("snbt", snapshot.snbt());
                source.add("slotSnapshot", view);
            }
            source.remove("slots");
            source.remove("snbt");
            result.add(source);
        }
        return result;
    }
    private static Map<String, Long> amounts(JsonArray rows) {
        Map<String, Long> result = new TreeMap<>();
        for (var value : rows) {
            var row = value.getAsJsonObject();
            long amount = StockLedger.number(row, "count");
            if (amount != 0) result.merge(row.get("key").getAsString(), amount, Math::addExact);
        }
        return Map.copyOf(result);
    }
    /** One transaction assigns revisions, invalidates old topology, records provenance and persists the index. */
    void accept(JsonObject index) throws IOException {
        if (!index.has("physicalSources")) return;
        accept(index, index.get("server").getAsString());
    }

    void accept(JsonObject index, String server) throws IOException {
        if (!index.has("physicalSources")) return;
        // Validate physical IDs against the original wire address, not the Manager's storage key.
        List<JsonObject> sources = validate(index);
        String stash = index.get("stash").getAsString();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            long before = ledger.revision(c), next = Math.addExact(before, 1);
            long now = ledger.clock.getAsLong();
            Map<String, Long> memberTimes = new HashMap<>();
            // Root-level box UUIDs seen in fresh snapshots this batch: source -> uuid -> box.
            Map<String, Map<String, BoxUuidRegistry.ObservedBox>> freshBoxes = new LinkedHashMap<>();
            for (JsonObject source : sources) {
                String id = source.get("id").getAsString(),
                        dimension = source.get("dimension").getAsString();
                String proof = source.get("session").getAsString() + ":" + StockLedger.number(source, "sequence");
                JsonObject snapshot = source.has("slotSnapshot")
                        ? source.remove("slotSnapshot").getAsJsonObject()
                        : null;
                if (snapshot != null) {
                    source.addProperty("slotSchema", 2);
                    source.addProperty(
                            "slotFingerprint", snapshot.get("fingerprint").getAsString());
                    source.addProperty(
                            "slotCount", snapshot.getAsJsonArray("positions").size());
                }
                String json = source.toString();
                long base = StockLedger.number(source, "baseRevision");
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
                    // A retry cannot change quantities, renew age, or revive a previous connection/ledger.run.
                    if (overlappingRevision > oldRevision
                            || !ledger.run.equals(verifiedRun)
                            || !previous.get("fingerprint").equals(source.get("fingerprint"))
                            || !previous.get("kind").equals(source.get("kind"))
                            || !previous.get("items").equals(source.get("items"))
                            || !previous.get("shulkers").equals(source.get("shulkers"))
                            || !previous.get("state").getAsString().equals("FRESH")) throw new StockLedger.Conflict(before, ledger.epoch);
                    boolean protectedBefore = previous.has("protected")
                            && previous.get("protected").getAsBoolean();
                    boolean protectedNow =
                            source.has("protected") && source.get("protected").getAsBoolean();
                    if (protectedBefore && !protectedNow) throw new StockLedger.Conflict(before, ledger.epoch);
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
                                        && !ledger.epoch.equals(source.get("epoch").getAsString())
                                || overlappingRevision > 0
                                        && !ledger.epoch.equals(source.get("epoch").getAsString())))
                    throw new StockLedger.Conflict(before, ledger.epoch);
                if (fresh) {
                    verified = Math.min(now, StockLedger.number(source, "observedAt"));
                    verifiedRun = ledger.run;
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
                if (snapshot != null && fresh) BoxUuidRegistry.collectObservedBoxUuids(snapshot, id, freshBoxes);
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
            boxes.reconcileObservedBoxUuids(c, server, freshBoxes, next, now);
            boxes.sweepMissingBoxUuids(c, server, freshBoxes, next, now);
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
            index.addProperty("stockEpoch", ledger.epoch);
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
    /**
     * PR3 (#228 Step 6): legacy {@code physical_snapshots}/{@code physical_slots}
     * rows are read-only evidence. They stay readable for forensics but are
     * never written and never read as content; the normalized SNBT authority
     * ({@code snbt_snapshots}/{@code slot_projection}/{@code variant_register})
     * is the only writer and reader. No migration or backfill: clean installs
     * and fresh observations only, legacy JSON stays untouched.
     */
    static final boolean WRITE_LEGACY_SLOT_PAYLOADS = false;

    private void saveSlots(Connection c, String source, JsonObject snapshot, long revision) throws SQLException {
        if (snapshot != null) {
            PhysicalSlots.Snapshot decoded;
            try {
                String snbt = snapshot.get("snbt").getAsString();
                decoded = PhysicalSlots.Snapshot.fromSnbt(snbt);
                if (!decoded.fingerprint().equals(snapshot.get("fingerprint").getAsString()))
                    throw new IllegalArgumentException("SNBT snapshot fingerprint mismatch before persistence");
                var expected = snapshot.deepCopy();
                expected.remove("snbt");
                if (!decoded.json().equals(expected))
                    throw new IllegalArgumentException("Physical projection differs from SNBT");
            } catch (RuntimeException invalid) {
                throw new SQLException("Invalid SNBT snapshot", invalid);
            }
            if (WRITE_LEGACY_SLOT_PAYLOADS) {
                try (var p = c.prepareStatement("INSERT OR IGNORE INTO physical_snapshots VALUES(?,?,?,?)")) {
                    p.setString(1, source);
                    p.setString(2, snapshot.get("fingerprint").getAsString());
                    p.setString(3, snapshot.get("snbt").getAsString());
                    p.setLong(4, revision);
                    p.executeUpdate();
                }
                // physical_slots is a rebuildable index, never a second content authority.
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
                        p.setLong(7, StockLedger.number(row, "count"));
                        p.setLong(8, StockLedger.number(row, "copies"));
                        p.setLong(9, revision);
                        p.setLong(10, revision);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
            // PR3 (#228/#267): normalized SNBT authority + variant register on the
            // same connection, so legacy rows and normalized rows commit atomically.
            SnbtStore.persistSnapshot(c, source, decoded, revision);
            if (WRITE_LEGACY_SLOT_PAYLOADS) {
                try (var p = c.prepareStatement(
                        "UPDATE physical_slots SET present=0,revision=? WHERE source=? AND present=1 AND seen_revision<>?")) {
                    p.setLong(1, revision);
                    p.setString(2, source);
                    p.setLong(3, revision);
                    p.executeUpdate();
                }
            }
            return;
        }
        // A fresh observation without SNBT (legacy v1 wire) supersedes the
        // derived projection without publishing new content.
        if (WRITE_LEGACY_SLOT_PAYLOADS) {
            try (var p = c.prepareStatement(
                    "UPDATE physical_slots SET present=0,revision=? WHERE source=? AND present=1 AND seen_revision<>?")) {
                p.setLong(1, revision);
                p.setString(2, source);
                p.setLong(3, revision);
                p.executeUpdate();
            }
        }
        try (var p = c.prepareStatement("DELETE FROM slot_projection WHERE source_id=?")) {
            p.setString(1, source);
            p.executeUpdate();
        }
    }
    /** Called inside the same transaction that releases/expires an automation lease. */
    void invalidateServer(Connection c, String server) throws SQLException {
        long next = Math.addExact(ledger.revision(c), 1);
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
    /**
     * Owner-approved "accept loss": a MISSING, UNREACHABLE or already WITHDRAWN
     * source becomes a WITHDRAWN tombstone with zero quantities and an audit
     * marker ({@code lossAccepted: {by, at}}), so one griefed container stops
     * poisoning the row forever. Idempotent: accepting twice still succeeds.
     * Returns false when the source is not lost.
     */
    boolean acceptLoss(String id, String by) throws IOException {
        long now = ledger.clock.getAsLong();
        try (var c = ledger.open();
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
            next = Math.addExact(ledger.revision(c), 1);
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
}
