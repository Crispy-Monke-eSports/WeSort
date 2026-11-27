package com.porter.wesortmanager;

import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.snbt.SnbtCodec;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * PR3 (#228/#267): normalized SNBT authority writes.
 *
 * <p>Persists the authoritative snapshot ({@code snbt_snapshots}), deduplicates
 * exact variants ({@code variant_register}) and derives per-source slot
 * projections ({@code slot_projection}) — all on the caller's connection, so
 * the surrounding {@link SqliteWriteTransaction} commits or rolls back the
 * legacy payload rows and the normalized rows atomically (#140).
 *
 * <p>Rules: exact canonical bytes are verified before a registered variant is
 * reused (collision quarantines fail-closed, never overwrite); empty slots are
 * absent entries (no rows); legacy {@code physical_snapshots}/
 * {@code physical_slots} rows stay untouched.
 *
 * <p>PR3 (#228 Step 6): a variant is the full item identity (id, key,
 * max-stack, kind, size, group plus canonical components), never bare
 * components: an empty diamond and an empty shulker share the bytes
 * {@code {}} but are different variants and must never alias one row.
 */
final class SnbtStore {
    private SnbtStore() {}

    /** Codec domain used for every variant registered from physical snapshots. */
    static final String VARIANT_DOMAIN = "physical-item";

    static void persistSnapshot(Connection c, String sourceId, PhysicalSlots.Snapshot decoded, long revision)
            throws SQLException {
        String snbt = decoded.snbt();
        String shaHex = decoded.fingerprint();
        try (var p = c.prepareStatement(
                "INSERT INTO snbt_snapshots(snapshot_id,schema_version,canonical_version,minecraft_version,registry_context,content_snbt,sha256,created_revision) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(snapshot_id) DO NOTHING")) {
            p.setString(1, shaHex);
            p.setInt(2, 2);
            p.setString(3, SnbtCodec.CONTRACT_VERSION);
            p.setString(4, SnbtCodec.MINECRAFT_DATA);
            p.setString(5, SnbtCodec.MINECRAFT_DATA);
            p.setString(6, snbt);
            p.setString(7, shaHex);
            p.setLong(8, revision);
            p.executeUpdate();
        }
        // Stable per-slot revisions: an unchanged slot (same stack digest and
        // copies) keeps its content revision so return-intake decisions keyed
        // by slot revision survive unrelated observations.
        java.util.Map<String, long[]> keep = new java.util.HashMap<>();
        try (var p = c.prepareStatement(
                "SELECT logical_path,slot_fingerprint,copies,content_revision FROM slot_projection WHERE source_id=?")) {
            p.setString(1, sourceId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) keep.put(rows.getString(1), new long[] {rows.getLong(4), rows.getLong(3)});
            }
        }
        java.util.Map<String, String> fingerprints = new java.util.HashMap<>();
        try (var p = c.prepareStatement("SELECT logical_path,slot_fingerprint FROM slot_projection WHERE source_id=?")) {
            p.setString(1, sourceId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) fingerprints.put(rows.getString(1), rows.getString(2));
            }
        }
        try (var del = c.prepareStatement("DELETE FROM slot_projection WHERE source_id=?")) {
            del.setString(1, sourceId);
            del.executeUpdate();
        }
        for (var located : decoded.located()) {
            var item = located.stack().item();
            long variantId = variantId(c, item);
            long slotRevision = revision;
            String oldFingerprint = fingerprints.get(located.path());
            long[] old = keep.get(located.path());
            if (old != null
                    && located.stack().fingerprint().equals(oldFingerprint)
                    && located.copies() == old[1]) slotRevision = old[0];
            try (var p = c.prepareStatement(
                    "INSERT INTO slot_projection(source_id,snapshot_ref,logical_path,parent_path,variant_id,count,copies,slot_fingerprint,content_revision,present) VALUES(?,?,?,?,?,?,?,?,?,1)")) {
                p.setString(1, sourceId);
                p.setString(2, shaHex);
                p.setString(3, located.path());
                p.setString(4, located.parent());
                p.setLong(5, variantId);
                p.setLong(6, located.stack().count());
                p.setLong(7, located.copies());
                p.setString(8, located.stack().fingerprint());
                p.setLong(9, slotRevision);
                p.executeUpdate();
            }
        }
    }

    /**
     * PR3 (#228 Step 6): rebuilds a source's slot projection byte-identically
     * from the authoritative SNBT snapshots (e.g. after evidence handling
     * deleted the derived rows). Uses the snapshot's creation revision so a
     * rebuild with no intervening observation is identical, revision included.
     */
    static void rebuildProjection(Connection c, String sourceId) throws SQLException, java.io.IOException {
        String fingerprint;
        try (var p = c.prepareStatement("SELECT payload FROM stock_sources WHERE id=?")) {
            p.setString(1, sourceId);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new java.io.IOException("Unknown physical source");
                var metadata =
                        com.google.gson.JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                if (!metadata.has("slotFingerprint"))
                    throw new java.io.IOException("Source has no SNBT snapshot to rebuild from");
                fingerprint = metadata.get("slotFingerprint").getAsString();
            }
        }
        String snbt;
        long revision;
        try (var p = c.prepareStatement("SELECT content_snbt,created_revision FROM snbt_snapshots WHERE sha256=?")) {
            p.setString(1, fingerprint);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) throw new java.io.IOException("Missing authoritative physical SNBT");
                snbt = rows.getString(1);
                revision = rows.getLong(2);
            }
        }
        PhysicalSlots.Snapshot decoded;
        try {
            decoded = PhysicalSlots.Snapshot.fromSnbt(snbt);
        } catch (RuntimeException invalid) {
            throw new java.io.IOException("Invalid persisted physical SNBT", invalid);
        }
        if (!decoded.fingerprint().equals(fingerprint))
            throw new java.io.IOException("Persisted snapshot digest mismatch");
        persistSnapshot(c, sourceId, decoded, revision);
    }

    /**
     * Full canonical variant identity: every scalar that distinguishes two
     * items plus the canonical component bytes. {@link SnbtCodec#write} sorts
     * compound keys, so the descriptor is byte-deterministic.
     */
    static String variantCanonical(
            String id, String key, long maxStack, String kind, long size, String group, String components) {
        var tree = new SnbtCodec.TCompound(java.util.Map.of(
                "id", new SnbtCodec.TString(id),
                "key", new SnbtCodec.TString(key),
                "maxStack", new SnbtCodec.TInt(Math.toIntExact(maxStack)),
                "kind", new SnbtCodec.TString(kind),
                "size", new SnbtCodec.TInt(Math.toIntExact(size)),
                "group", new SnbtCodec.TString(group),
                "components", SnbtCodec.compound(components)));
        return SnbtCodec.write(tree);
    }

    static String variantCanonical(PhysicalSlots.Item item) {
        return variantCanonical(
                item.id(), item.key(), item.maxStack(), item.kind().name(), item.size(), item.group(),
                item.components());
    }

    /** Extracts the canonical component bytes from a registered variant descriptor. */
    static String variantComponents(String canonical) {
        return SnbtCodec.write(SnbtCodec.compound(canonical).get("components"));
    }

    /**
     * Returns the registered variant id for an item, inserting it when new.
     * Reuse verifies the exact canonical descriptor: a digest collision with
     * different bytes fails closed instead of silently aliasing two variants.
     */
    static long variantId(Connection c, PhysicalSlots.Item item) throws SQLException {
        String canonical = variantCanonical(item);
        // Fail-closed canonical form: never register non-canonical bytes.
        if (!canonical.equals(SnbtCodec.write(SnbtCodec.parse(canonical))))
            throw new IllegalArgumentException("Variant descriptor is not canonical SNBT");
        byte[] digest = sha256(canonical);
        try (var find = c.prepareStatement(
                "SELECT variant_id,canonical_variant_snbt FROM variant_register WHERE codec_domain=? AND registry_context=? AND digest=?")) {
            find.setString(1, VARIANT_DOMAIN);
            find.setString(2, SnbtCodec.MINECRAFT_DATA);
            find.setBytes(3, digest);
            try (var rows = find.executeQuery()) {
                if (rows.next()) {
                    if (!canonical.equals(rows.getString(2)))
                        throw new IllegalArgumentException("Variant digest collision with different canonical bytes");
                    return rows.getLong(1);
                }
            }
        }
        try (var p = c.prepareStatement(
                "INSERT INTO variant_register(codec_domain,registry_context,digest,canonical_variant_snbt,item_id,max_stack,kind,metadata_version) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, VARIANT_DOMAIN);
            p.setString(2, SnbtCodec.MINECRAFT_DATA);
            p.setBytes(3, digest);
            p.setString(4, canonical);
            p.setString(5, item.id());
            p.setLong(6, item.maxStack());
            p.setString(7, item.kind().name());
            p.setString(8, SnbtCodec.CONTRACT_VERSION);
            p.executeUpdate();
            try (var keys = p.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        // Lost a concurrent insert race: reread the winner and verify bytes.
        try (var find = c.prepareStatement(
                "SELECT variant_id,canonical_variant_snbt FROM variant_register WHERE codec_domain=? AND registry_context=? AND digest=?")) {
            find.setString(1, VARIANT_DOMAIN);
            find.setString(2, SnbtCodec.MINECRAFT_DATA);
            find.setBytes(3, digest);
            try (var rows = find.executeQuery()) {
                if (rows.next()) {
                    if (!canonical.equals(rows.getString(2)))
                        throw new IllegalArgumentException("Variant digest collision with different canonical bytes");
                    return rows.getLong(1);
                }
            }
        }
        throw new SQLException("Variant registration produced no row");
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static String shaHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
