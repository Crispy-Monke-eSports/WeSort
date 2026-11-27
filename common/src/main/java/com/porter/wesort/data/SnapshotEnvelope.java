package com.porter.wesort.data;

import com.porter.wesort.snbt.SnbtCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR2 (#226): authoritative snapshot envelope (additive).
 *
 * <p>A verified server observation as canonical SNBT plus its provenance. This
 * type does not replace {@link PhysicalSlots} yet (wire/persistence adoption
 * lands in #227/#228); it is the capture-side contract new code must produce.
 *
 * <p>Rules:
 * <ul>
 *   <li>Empty slots are absent entries, never fake {@code air} stacks.</li>
 *   <li>Counts are observed native counts (1..99); handlers quarantine
 *       above-max-stack for physical work instead of clamping.</li>
 *   <li>Double chests are one logical 54-slot snapshot with facing/type and
 *       both block positions recorded; never two independent snapshots.</li>
 *   <li>Cursor/equipment/ender sources are separate envelopes, never merged
 *       into container stock.</li>
 * </ul>
 */
public final class SnapshotEnvelope {
    public enum SourceKind {
        PLAYER_MAIN,
        PLAYER_EQUIPMENT,
        PLAYER_OFFHAND,
        PLAYER_ENDER,
        CURSOR_TRANSIENT,
        CHEST_SINGLE,
        CHEST_DOUBLE,
        BARREL,
        SHULKER_PLACED,
        SHULKER_CARRIED,
        SHULKER_NESTED,
        BUNDLE,
        OTHER_CONTAINER
    }

    public record SlotEntry(int slot, String itemSnbt, int count) {
        public SlotEntry {
            if (slot < 0) throw new IllegalArgumentException("slot must be >= 0");
            if (itemSnbt == null || itemSnbt.isEmpty()) throw new IllegalArgumentException("item SNBT required");
            if (count <= 0) throw new IllegalArgumentException("count must be > 0");
            // Empty slots are absent entries: a fake air stack is never a slot entry.
            SnbtCodec.Tag tag = SnbtCodec.parse(itemSnbt);
            if (tag instanceof SnbtCodec.TCompound c) {
                SnbtCodec.Tag id = c.value().get("id");
                if (id instanceof SnbtCodec.TString s && s.value().equals("minecraft:air"))
                    throw new IllegalArgumentException("empty slots must be absent, never air stacks");
            }
        }
    }

    public record Provenance(
            String server, String dimension, String session, long sequence, long observedAtMs, String registryContext) {
        public Provenance {
            if (server == null || server.isEmpty()) throw new IllegalArgumentException("server required");
            if (dimension == null || dimension.isEmpty()) throw new IllegalArgumentException("dimension required");
            if (registryContext == null || registryContext.isEmpty())
                throw new IllegalArgumentException("registryContext required");
        }
    }

    private final SourceKind kind;
    private final int capacity;
    private final List<SlotEntry> slots;
    private final Map<String, String> storageMeta;
    private final Provenance provenance;

    public SnapshotEnvelope(
            SourceKind kind,
            int capacity,
            List<SlotEntry> slots,
            Map<String, String> storageMeta,
            Provenance provenance) {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (capacity <= 0 || capacity > 256) throw new IllegalArgumentException("capacity 1..256");
        if (slots == null) throw new IllegalArgumentException("slots required");
        if (provenance == null) throw new IllegalArgumentException("provenance required");
        Map<String, String> meta = storageMeta == null ? Map.of() : Map.copyOf(storageMeta);
        // Double chests are one logical 54-slot snapshot: facing, type and both
        // block positions are required so the two halves can never be recorded
        // as independent snapshots.
        if (kind == SourceKind.CHEST_DOUBLE) {
            if (capacity != 54)
                throw new IllegalArgumentException("double chest snapshots are one logical 54-slot envelope");
            for (String key : List.of("facing", "type", "posA", "posB")) {
                String value = meta.get(key);
                if (value == null || value.isEmpty())
                    throw new IllegalArgumentException("double chest storageMeta requires '" + key + "'");
            }
        }
        this.kind = kind;
        this.capacity = capacity;
        this.slots = List.copyOf(slots);
        this.storageMeta = meta;
        this.provenance = provenance;
        for (SlotEntry e : this.slots) {
            if (e.slot() >= capacity)
                throw new IllegalArgumentException("slot " + e.slot() + " outside capacity " + capacity);
        }
    }

    public SourceKind kind() {
        return kind;
    }

    public int capacity() {
        return capacity;
    }

    public List<SlotEntry> slots() {
        return slots;
    }

    public Map<String, String> storageMeta() {
        return storageMeta;
    }

    public Provenance provenance() {
        return provenance;
    }

    /** Canonical slot-list SNBT for digesting (slot order significant). */
    public String canonicalSlotsSnbt() {
        StringBuilder sb = new StringBuilder();
        sb.append("{capacity:").append(capacity).append(",slots:[");
        List<SlotEntry> ordered = new ArrayList<>(slots);
        ordered.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) sb.append(',');
            SlotEntry e = ordered.get(i);
            sb.append("{slot:")
                    .append(e.slot())
                    .append(",count:")
                    .append(e.count())
                    .append(",item:")
                    .append(e.itemSnbt())
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public String snapshotDigest() {
        return SnbtCodec.snapshotDigest(canonicalSlotsSnbt());
    }

    /** Derived storage summary (never authoritative). */
    public Map<String, Integer> derivedTotals() {
        Map<String, Integer> totals = new TreeMap<>();
        for (SlotEntry e : slots) totals.merge(e.itemSnbt(), e.count(), Integer::sum);
        return totals;
    }
}
