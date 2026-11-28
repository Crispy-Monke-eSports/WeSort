package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.ShulkerUuid;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
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
                    // Adopted: shulker_identity anchors the registration proof
                    // (the verified slot proof that first registered this UUID).
                    noteRegistration(c, uuid, source, at.path(), at.fingerprint(), revision);
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
                    noteRegistration(c, uuid, source, at.path(), at.fingerprint(), revision);
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
                    // An internally moved box keeps its identity; the proof follows the box.
                    noteRegistration(c, uuid, source, at.path(), at.fingerprint(), revision);
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
    /**
     * Adopted export-receipt anchor: shulker_identity keeps the registration
     * proof (the verified slot proof that registered this UUID) and, once an
     * export is verified, the export receipt. The row is an anchor, not a
     * lifecycle mirror: other deregistrations leave it untouched.
     */
    private static void noteRegistration(
            Connection c, String uuid, String source, String path, String fingerprint, long revision)
            throws SQLException {
        String proof = "slot-proof:" + source + ":" + path + ":" + fingerprint + ":rev" + revision;
        try (var p = c.prepareStatement(
                "INSERT INTO shulker_identity(uuid,status,source,slot_path,current_snapshot_ref,current_revision,registration_proof,export_receipt) VALUES(?,'IN_STOCK',?,?,NULL,?,?,NULL)"
                        + " ON CONFLICT(uuid) DO UPDATE SET status='IN_STOCK',source=excluded.source,slot_path=excluded.slot_path,current_revision=excluded.current_revision,registration_proof=excluded.registration_proof,export_receipt=NULL")) {
            p.setString(1, uuid);
            p.setString(2, source);
            p.setString(3, path);
            p.setLong(4, revision);
            p.setString(5, proof);
            p.executeUpdate();
        }
    }

    /** Appends the active UUID row to history and removes it, inside the caller's transaction. */    private static void deregisterInTx(
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
    /**
     * Deregisters every registry row rooted at a withdrawn source inside the
     * caller's transaction (loss acceptance and escalation sweep). Vanished
     * boxes never linger as allocatable after their source is tombstoned.
     */
    void sweepSourceBoxUuids(Connection c, String source, long revision, long now) throws SQLException {
        try (var read = c.prepareStatement("SELECT uuid,server,source,path,status FROM box_uuids WHERE source=?")) {
            read.setString(1, source);
            try (var rows = read.executeQuery()) {
                while (rows.next()) {
                    deregisterInTx(
                            c,
                            rows.getString(2),
                            rows.getString(1),
                            rows.getString(5),
                            rows.getString(3),
                            rows.getString(4),
                            "WITHDRAWN",
                            revision,
                            now);
                }
            }
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
    void releaseOrderBoxes(Connection c, String server, StockLedger.BoxRelease release)
            throws SQLException, IOException {
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
                            ledger.clock.millis());
                }
            }
        }
    }

    /**
     * Verified export-strip: the physical name-strip is confirmed by a fresh
     * observation, then the registry mutation, the identity export receipt
     * and the audit entries commit in the caller's transaction.
     *
     * <p>Fail-closed gates, all read inside the same transaction: the source
     * must hold a fresh observation (state FRESH within the observation TTL),
     * and the projected slot at {@code (source, path)} must no longer carry
     * this UUID (name stripped, slot emptied, or box moved away). When the
     * row is still registered at that slot it is deregistered here with
     * reason {@code EXPORT_STRIPPED}; when the strip observation already
     * swept it (the usual case — the accept sweep owns that deregistration
     * in the observation's own transaction) the {@code VANISHED} history row
     * at the same slot anchors the receipt instead. Anything else — a still
     * applied name, a stale observation, an unknown UUID, or a second export
     * of the same box — fails without writing.
     */
    void exportStrip(
            Connection c,
            String server,
            String source,
            String path,
            String uuid,
            long revision,
            long now,
            String actorId,
            String actorName,
            String address,
            List<AuditLog.Entry> audit)
            throws SQLException, IOException {
        long verifiedAt;
        try (var p = c.prepareStatement("SELECT payload,verified_at FROM stock_sources WHERE id=?")) {
            p.setString(1, source);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new IOException("Unknown physical source");
                var payload = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                verifiedAt = rows.getLong(2);
                if (!payload.has("state") || !payload.get("state").getAsString().equals("FRESH"))
                    throw new IOException("Export requires a fresh observation of the source");
            }
        }
        if (now - verifiedAt > ledger.maxAge)
            throw new IOException("Export requires a fresh observation of the source");
        String observedName = null;
        boolean slotSeen = false;
        try (var p = c.prepareStatement("""
                SELECT v.canonical_variant_snbt FROM slot_projection p
                JOIN variant_register v ON v.variant_id=p.variant_id
                WHERE p.source_id=? AND p.logical_path=? AND p.present=1""")) {
            p.setString(1, source);
            p.setString(2, path);
            try (var rows = p.executeQuery()) {
                if (rows.next()) {
                    slotSeen = true;
                    try {
                        observedName = ShulkerUuid.customName(SnbtStore.variantComponents(rows.getString(1)));
                    } catch (RuntimeException invalid) {
                        throw new IOException("Invalid registered variant descriptor", invalid);
                    }
                }
            }
        }
        UUID still = observedName == null ? null : ShulkerUuid.parse(observedName);
        if (still != null && still.toString().equals(uuid))
            throw new IOException("Export box name is still applied; re-read after stripping");
        String status = null;
        try (var p = c.prepareStatement("SELECT server,source,path,status FROM box_uuids WHERE uuid=?")) {
            p.setString(1, uuid);
            try (var rows = p.executeQuery()) {
                if (rows.next()) {
                    if (!server.equals(rows.getString(1))
                            || !source.equals(rows.getString(2))
                            || !path.equals(rows.getString(3)))
                        throw new StockLedger.BoxReleaseConflict();
                    status = rows.getString(4);
                }
            }
        }
        if (status != null) {
            deregisterInTx(c, server, uuid, status, source, path, "EXPORT_STRIPPED", revision, now);
        } else {
            // The strip observation already swept the row in its own accept
            // transaction; the VANISHED history row at the same slot is the
            // anchor. Anything else means this export was never registered
            // here (or was already exported: export_receipt is set).
            try (var p = c.prepareStatement(
                    "SELECT reason FROM box_uuid_history WHERE uuid=? AND source=? AND path=? ORDER BY recorded_at DESC LIMIT 1")) {
                p.setString(1, uuid);
                p.setString(2, source);
                p.setString(3, path);
                try (var rows = p.executeQuery()) {
                    if (!rows.next() || !rows.getString(1).equals("VANISHED"))
                        throw new StockLedger.BoxReleaseConflict();
                }
            }
            try (var p = c.prepareStatement("SELECT export_receipt FROM shulker_identity WHERE uuid=?")) {
                p.setString(1, uuid);
                try (var rows = p.executeQuery()) {
                    if (rows.next() && rows.getString(1) != null)
                        throw new StockLedger.BoxReleaseConflict();
                }
            }
        }
        String receipt = "export:" + source + ":" + path + ":rev" + revision;
        try (var p = c.prepareStatement(
                "INSERT INTO shulker_identity(uuid,status,source,slot_path,current_snapshot_ref,current_revision,registration_proof,export_receipt) VALUES(?,'EXPORT_STRIPPED',?,?,NULL,?,'',?)"
                        + " ON CONFLICT(uuid) DO UPDATE SET status='EXPORT_STRIPPED',source=excluded.source,slot_path=excluded.slot_path,current_revision=excluded.current_revision,export_receipt=excluded.export_receipt")) {
            p.setString(1, uuid);
            p.setString(2, source);
            p.setString(3, path);
            p.setLong(4, revision);
            p.setString(5, receipt);
            p.executeUpdate();
        }
        audit.add(new AuditLog.Entry(actorId, actorName, "box.export-stripped", uuid + " " + source + "/" + path, address, now));
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
