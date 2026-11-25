package com.porter.wesort.data;

import com.porter.wesort.snbt.SnbtCodec;

/**
 * PR2 (#266): separated item identity domains.
 *
 * <p>Three domains that must never be confused:
 * <ul>
 *   <li>{@link BaseItemId} — registry id + schema context (e.g. {@code minecraft:diamond_sword}).
 *       Search/display grouping only, never a variant identity.</li>
 *   <li>{@link ExactVariantId} — id + FULL count-independent canonical component SNBT.
 *       Exact equality only; digest is full SHA-256 (never truncated {@code #sha1(..).substring(0,8)}).
 *       No bare-id fallback: encode failure is fail-closed.</li>
 *   <li>{@link CatalogGroupId} — documented grouping policy (BulkKey/tier/Categories/s:-content-group).
 *       Sorting/search policy only, never a reservation or lot identity.</li>
 * </ul>
 *
 * <p>Physical identity (which box) stays in {@link ContainerIdentity} (where) +
 * {@link ShulkerUuid} (which physical box) and is independent of all three above.
 */
public final class ItemIdentity {
    private ItemIdentity() {}

    /** Registry id + schema context. */
    public record BaseItemId(String id, String schemaContext) {
        public BaseItemId {
            if (id == null || id.isEmpty() || !id.contains(":"))
                throw new IllegalArgumentException("base item id must be namespaced: " + id);
            if (schemaContext == null || schemaContext.isEmpty())
                throw new IllegalArgumentException("schema context required");
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** Exact variant: full canonical component SNBT + SHA-256 digest. */
    public record ExactVariantId(BaseItemId base, String canonicalComponentsSnbt, String digest) {
        public ExactVariantId {
            if (base == null) throw new IllegalArgumentException("base required");
            if (canonicalComponentsSnbt == null)
                throw new IllegalArgumentException("canonical components SNBT required (no bare-id fallback)");
            // Validate round-trip eagerly: fail closed on non-canonical input.
            SnbtCodec.Tag parsed = SnbtCodec.parse(canonicalComponentsSnbt);
            String canonical = SnbtCodec.write(parsed);
            if (!canonical.equals(canonicalComponentsSnbt))
                throw new IllegalArgumentException("components SNBT is not canonical");
            String expected = SnbtCodec.contentDigest(base.id() + "\n" + canonical);
            if (!expected.equals(digest))
                throw new IllegalArgumentException("variant digest does not match content");
        }

        public static ExactVariantId of(BaseItemId base, SnbtCodec.Tag components) {
            String canonical = SnbtCodec.write(components);
            return new ExactVariantId(base, canonical, SnbtCodec.contentDigest(base.id() + "\n" + canonical));
        }
    }

    /** Catalog grouping policy result. Opaque string, versioned policy name. */
    public record CatalogGroupId(String policy, String value) {
        public CatalogGroupId {
            if (policy == null || policy.isEmpty()) throw new IllegalArgumentException("policy required");
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("value required");
        }

        @Override
        public String toString() {
            return policy + ":" + value;
        }
    }
}
