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
        try (var del = c.prepareStatement("DELETE FROM slot_projection WHERE source_id=?")) {
            del.setString(1, sourceId);
            del.executeUpdate();
        }
        for (var located : decoded.located()) {
            var item = located.stack().item();
            long variantId = variantId(c, item);
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
                p.setLong(9, revision);
                p.executeUpdate();
            }
        }
    }

    /**
     * Returns the registered variant id for an item, inserting it when new.
     * Reuse verifies exact canonical bytes: a digest collision with different
     * bytes fails closed instead of silently aliasing two variants.
     */
    static long variantId(Connection c, PhysicalSlots.Item item) throws SQLException {
        String canonical = item.components();
        // Fail-closed canonical form: never register non-canonical bytes.
        if (!canonical.equals(SnbtCodec.write(SnbtCodec.parse(canonical))))
            throw new IllegalArgumentException("Variant components are not canonical SNBT");
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
