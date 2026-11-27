package com.porter.wesortmanager;
    
    import com.google.gson.JsonArray;
    import com.google.gson.JsonObject;
    import com.google.gson.JsonParser;
    import com.porter.wesort.data.ShulkerUuid;
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
    import java.util.UUID;
    
    /**
     * PR3 Step 5: the box-UUID registry (observation reconciliation, sweeps,
     * identities and order releases), moved verbatim out of StockLedger (no
     * behavior change, Refs #142). Runs under the StockLedger lock.
     */
    final class BoxUuidRegistry {
        private final StockLedger ledger;
    
        BoxUuidRegistry(StockLedger ledger) {
            this.ledger = ledger;
        }
    
        /** One observed root-level shulker box UUID in a fresh snapshot. */
    record ObservedBox(String path, String fingerprint) {}

    /**
     * Folds one fresh snapshot's root-level shulker box UUIDs into the batch view: source id to UUID
     * to box. Only UUID-carrying names count; the registry assigns nothing on its own.
     */
    static void collectObservedBoxUuids(
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
    void reconcileObservedBoxUuids(
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
    void sweepMissingBoxUuids(
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
    Set<PhysicalReservations.Root> quarantinedBoxes(String server) throws IOException {
        var unsafe = new HashSet<PhysicalReservations.Root>();
        var registered = new HashMap<String, PhysicalReservations.Root>();
        var seen = new HashMap<String, PhysicalReservations.Root>();
        try (var c = ledger.open();
                var registry = c.prepareStatement("SELECT uuid,source,path FROM box_uuids WHERE server=?");
                // PR3 (#228 Step 6): quarantine reads the projection authority
                // (slot_projection ⨝ variant_register), never the read-only
                // legacy physical_slots evidence.
                var slots = c.prepareStatement("""
                    SELECT p.source_id,p.logical_path,v.canonical_variant_snbt,p.count
                    FROM slot_projection p JOIN variant_register v ON v.variant_id=p.variant_id
                    JOIN stock_sources s ON s.id=p.source_id
                    WHERE s.server=? AND p.present=1 AND instr(p.logical_path,'/')=0
                    AND v.kind='SHULKER'
                    AND EXISTS (SELECT 1 FROM stock_members m WHERE m.source=p.source_id AND m.present=1)
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
                    String components;
                    try {
                        components = SnbtStore.variantComponents(rows.getString(3));
                    } catch (RuntimeException invalid) {
                        throw new IOException("Invalid registered variant descriptor", invalid);
                    }
                    String name = ShulkerUuid.customName(components);
                    if (name == null
                            || !name.strip().regionMatches(true, 0, ShulkerUuid.PREFIX, 0, ShulkerUuid.PREFIX.length()))
                        continue;
                    var root = new PhysicalReservations.Root(rows.getString(1), rows.getString(2));
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
    JsonArray boxIdentities(String source) throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open()) {
            for (var slot : ledger.physicalSlots(source)) {
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
    /** Check the current location and move the UUID to history inside the order receipt's write transaction. */
    void releaseOrderBoxes(Connection c, String server, StockLedger.BoxRelease release) throws SQLException, IOException {
        try (var read = c.prepareStatement("SELECT server,source,path,status FROM box_uuids WHERE uuid=?")) {
            for (String uuid : release.reported()) {
                read.setString(1, uuid);
                try (var rows = read.executeQuery()) {
                    if (!rows.next()) continue; // Newly assembled box or already removed by an older Manager.
                    String source = rows.getString(2), path = rows.getString(3);
                    if (!server.equals(rows.getString(1))
                            || !release.reserved().contains(new PhysicalReservations.Root(source, path)))
                        throw new StockLedger.BoxReleaseConflict();
                    deregisterInTx(
                            c,
                            server,
                            uuid,
                            rows.getString(4),
                            source,
                            path,
                            "DEREGISTERED_FOR_ORDER",
                            ledger.revision(c),
                            ledger.clock.getAsLong());
                }
            }
        }
    }
    JsonArray boxUuids() throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open();
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
    JsonArray boxUuidHistory() throws IOException {
        var out = new JsonArray();
        try (var c = ledger.open();
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
}
