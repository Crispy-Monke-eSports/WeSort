package com.porter.wesort.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.snbt.SnbtCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /**
     * Envelope wire version, carried by {@code physicalVersion:4} observations
     * (#227). Bump for any envelope wire-shape change; old wire is never
     * accepted as new wire (fail-closed). The SNBT codec contract
     * ({@link SnbtCodec#CONTRACT_VERSION}, pinned to Minecraft
     * {@link SnbtCodec#MINECRAFT_DATA}) versions the canonical bytes inside.
     */
    public static final int WIRE_VERSION = 1;

    /** Observed native counts are carried as evidence in the range 1..99. */
    public static final int MAX_WIRE_COUNT = 99;

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
            // Normalize to canonical bytes: non-canonical equivalents ({b:1} vs
            // {"b":1}) are the same variant, so digests and totals merge them.
            itemSnbt = SnbtCodec.write(tag);
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

    /** Canonical slot-list SNBT for digesting (slot order significant). Built with
     * the codec model so the hashed bytes are always canonical. */
    public String canonicalSlotsSnbt() {
        List<SlotEntry> ordered = new ArrayList<>(slots);
        ordered.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        List<SnbtCodec.Tag> entries = new ArrayList<>();
        for (SlotEntry e : ordered) {
            Map<String, SnbtCodec.Tag> entry = new LinkedHashMap<>();
            entry.put("slot", new SnbtCodec.TInt(e.slot()));
            entry.put("count", new SnbtCodec.TInt(e.count()));
            entry.put("item", SnbtCodec.parse(e.itemSnbt()));
            entries.add(new SnbtCodec.TCompound(entry));
        }
        Map<String, SnbtCodec.Tag> root = new LinkedHashMap<>();
        root.put("capacity", new SnbtCodec.TInt(capacity));
        root.put("slots", new SnbtCodec.TList(entries));
        return SnbtCodec.write(new SnbtCodec.TCompound(root));
    }

    /**
     * Capture-side construction path (#226): the single way to build an
     * envelope from an observed inventory. The source is the authoritative
     * {@link PhysicalSlots.Snapshot}; every captured slot must resolve to an
     * exact variant (#266, fail-closed), and double-chest meta is enforced by
     * the constructor. Transport (#227) and persistence (#228) adopt this
     * converter; production code never hand-builds envelopes from raw strings.
     */
    public static SnapshotEnvelope fromPhysicalSnapshot(
            SourceKind kind, PhysicalSlots.Snapshot snapshot, Map<String, String> storageMeta, Provenance provenance) {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (snapshot == null) throw new IllegalArgumentException("snapshot required");
        if (provenance == null) throw new IllegalArgumentException("provenance required");
        List<SlotEntry> entries = new ArrayList<>();
        for (PhysicalSlots.Slot s : snapshot.slots()) {
            ItemIdentity.BaseItemId base = new ItemIdentity.BaseItemId(s.item().id(), provenance.registryContext());
            // Fail closed: a captured slot is always an exact variant, never a
            // bare id or truncated display key.
            ItemIdentity.ExactVariantId variant = ItemIdentity.ExactVariantId.of(
                    base, SnbtCodec.parse(s.item().components()));
            Map<String, SnbtCodec.Tag> item = new LinkedHashMap<>();
            item.put("id", new SnbtCodec.TString(s.item().id()));
            item.put("components", SnbtCodec.parse(variant.canonicalComponentsSnbt()));
            entries.add(new SlotEntry(s.slot(), SnbtCodec.write(new SnbtCodec.TCompound(item)), s.count()));
        }
        return new SnapshotEnvelope(kind, snapshot.size(), entries, storageMeta, provenance);
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

    /**
     * Source-kind rule shared by capture (#227) and the Manager cross-check:
     * the observed block kind plus the part count determines the single legal
     * envelope kind. A chest with one part is single, with two parts one
     * logical double chest; barrels and shulker boxes are always one part.
     * Anything else (wrong part count, unknown block) fails closed.
     */
    public static SourceKind kindForBlock(String blockKind, int partCount) {
        if (blockKind == null) throw new IllegalArgumentException("block kind required");
        if (blockKind.equals("minecraft:chest") || blockKind.equals("minecraft:trapped_chest")) {
            if (partCount == 1) return SourceKind.CHEST_SINGLE;
            if (partCount == 2) return SourceKind.CHEST_DOUBLE;
            throw new IllegalArgumentException("chest part count must be 1 or 2, was " + partCount);
        }
        if (partCount == 1) {
            if (blockKind.equals("minecraft:barrel")) return SourceKind.BARREL;
            if (blockKind.matches("minecraft:(?:[a-z_]+_)?shulker_box")) return SourceKind.SHULKER_PLACED;
        }
        throw new IllegalArgumentException("unsupported inventory kind: " + blockKind + " x" + partCount);
    }

    /**
     * Canonical wire form (#227): the single serialization of an envelope.
     * Every item SNBT is re-emitted through {@code SnbtCodec.write(parse(...))}
     * so the wire bytes are always canonical; storage meta keys are sorted for
     * determinism; the digest lets the Manager recompute instead of trusting.
     */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("wireVersion", WIRE_VERSION);
        out.addProperty("kind", kind.name());
        out.addProperty("capacity", capacity);
        JsonArray wireSlots = new JsonArray();
        List<SlotEntry> ordered = new ArrayList<>(slots);
        ordered.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        for (SlotEntry e : ordered) {
            JsonObject row = new JsonObject();
            row.addProperty("slot", e.slot());
            // Re-emit through the codec: the wire never carries non-canonical bytes.
            row.addProperty("itemSnbt", SnbtCodec.write(SnbtCodec.parse(e.itemSnbt())));
            row.addProperty("count", e.count());
            wireSlots.add(row);
        }
        out.add("slots", wireSlots);
        JsonObject meta = new JsonObject();
        new TreeMap<>(storageMeta).forEach(meta::addProperty);
        out.add("storageMeta", meta);
        JsonObject prov = new JsonObject();
        prov.addProperty("server", provenance.server());
        prov.addProperty("dimension", provenance.dimension());
        prov.addProperty("session", provenance.session());
        prov.addProperty("sequence", provenance.sequence());
        prov.addProperty("observedAt", provenance.observedAtMs());
        prov.addProperty("registryContext", provenance.registryContext());
        out.add("provenance", prov);
        out.addProperty("snapshotDigest", snapshotDigest());
        return out;
    }

    /**
     * Reads the single wire serialization from raw JSON text. Duplicate object
     * keys are rejected before Gson collapses them (fail-closed), then the
     * object form below applies: exact key sets, known source kind, canonical
     * item SNBT only, recomputed digest.
     */
    public static SnapshotEnvelope fromJson(String raw) {
        if (raw == null) throw new IllegalArgumentException("envelope required");
        rejectDuplicateKeys(raw);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid envelope JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("envelope must be a JSON object");
        return fromJson(parsed.getAsJsonObject());
    }

    /**
     * Reads the single wire serialization from an already-parsed object.
     * Canonical-only: every item SNBT must satisfy
     * {@code write(parse(x)) == x}; non-canonical, air, out-of-range or
     * duplicate slots fail closed, as do unknown fields, unknown source kinds
     * and digest mismatches.
     */
    public static SnapshotEnvelope fromJson(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("envelope required");
        requireKeys(
                json, Set.of("wireVersion", "kind", "capacity", "slots", "storageMeta", "provenance", "snapshotDigest"));
        if (!json.get("wireVersion").isJsonPrimitive()
                || !json.getAsJsonPrimitive("wireVersion").isNumber()
                || !isWireVersion(json.getAsJsonPrimitive("wireVersion")))
            throw new IllegalArgumentException("unsupported envelope wire version");
        SourceKind kind;
        String kindName = stringField(json, "kind");
        try {
            kind = SourceKind.valueOf(kindName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown envelope source kind: " + kindName);
        }
        int capacity = intField(json, "capacity");
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("envelope capacity out of range");
        if (!json.get("slots").isJsonArray()) throw new IllegalArgumentException("envelope slots must be an array");
        List<SlotEntry> entries = new ArrayList<>();
        Set<Integer> seenSlots = new HashSet<>();
        for (JsonElement value : json.getAsJsonArray("slots")) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("envelope slot must be an object");
            JsonObject row = value.getAsJsonObject();
            requireKeys(row, Set.of("slot", "itemSnbt", "count"));
            int slot = intField(row, "slot");
            int count = intField(row, "count");
            if (slot < 0 || slot >= capacity) throw new IllegalArgumentException("envelope slot out of range: " + slot);
            if (!seenSlots.add(slot)) throw new IllegalArgumentException("duplicate envelope slot: " + slot);
            if (count < 1 || count > MAX_WIRE_COUNT)
                throw new IllegalArgumentException("envelope count out of range: " + count);
            if (!row.get("itemSnbt").isJsonPrimitive() || !row.getAsJsonPrimitive("itemSnbt").isString())
                throw new IllegalArgumentException("envelope item must be an SNBT string");
            String itemSnbt = row.getAsJsonPrimitive("itemSnbt").getAsString();
            requireCanonicalItem(itemSnbt);
            entries.add(new SlotEntry(slot, itemSnbt, count));
        }
        if (!json.get("storageMeta").isJsonObject())
            throw new IllegalArgumentException("envelope storageMeta must be an object");
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("storageMeta").entrySet()) {
            if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("envelope storageMeta values must be strings");
            meta.put(e.getKey(), e.getValue().getAsString());
        }
        if (!json.get("provenance").isJsonObject())
            throw new IllegalArgumentException("envelope provenance must be an object");
        JsonObject prov = json.getAsJsonObject("provenance");
        requireKeys(prov, Set.of("server", "dimension", "session", "sequence", "observedAt", "registryContext"));
        String server = stringField(prov, "server");
        String dimension = stringField(prov, "dimension");
        String session = stringField(prov, "session");
        String registryContext = stringField(prov, "registryContext");
        if (server.isEmpty() || dimension.isEmpty() || session.isEmpty() || registryContext.isEmpty())
            throw new IllegalArgumentException("envelope provenance identity required");
        long sequence = longField(prov, "sequence");
        long observedAt = longField(prov, "observedAt");
        if (sequence < 1) throw new IllegalArgumentException("envelope sequence out of range");
        if (observedAt < 0) throw new IllegalArgumentException("envelope observedAt out of range");
        SnapshotEnvelope envelope =
                new SnapshotEnvelope(kind, capacity, entries, meta, new Provenance(server, dimension, session, sequence, observedAt, registryContext));
        if (!json.get("snapshotDigest").isJsonPrimitive() || !json.getAsJsonPrimitive("snapshotDigest").isString())
            throw new IllegalArgumentException("envelope snapshotDigest must be a string");
        String claimed = json.getAsJsonPrimitive("snapshotDigest").getAsString();
        if (!envelope.snapshotDigest().equals(claimed))
            throw new IllegalArgumentException("envelope snapshotDigest mismatch");
        return envelope;
    }

    /** Wire items are always {@code {id, components}} compounds in canonical bytes. */
    private static void requireCanonicalItem(String itemSnbt) {
        SnbtCodec.Tag parsed;
        try {
            parsed = SnbtCodec.parse(itemSnbt);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("envelope item is not valid SNBT: " + e.getMessage(), e);
        }
        if (!SnbtCodec.write(parsed).equals(itemSnbt))
            throw new IllegalArgumentException("envelope item SNBT is not canonical");
        if (!(parsed instanceof SnbtCodec.TCompound compound)
                || !compound.value().keySet().equals(Set.of("components", "id")))
            throw new IllegalArgumentException("envelope item must be an {id, components} compound");
        if (!(compound.get("id") instanceof SnbtCodec.TString id)
                || !id.value().contains(":")
                || !(compound.get("components") instanceof SnbtCodec.TCompound))
            throw new IllegalArgumentException("envelope item needs a namespaced id and compound components");
    }

    private static void requireKeys(JsonObject json, Set<String> keys) {
        if (!json.keySet().equals(keys)) throw new IllegalArgumentException("unexpected envelope fields");
    }

    private static String stringField(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("envelope field '" + field + "' must be a string");
        return value.getAsString();
    }

    private static boolean isWireVersion(com.google.gson.JsonPrimitive value) {
        try {
            return value.getAsBigDecimal().intValueExact() == WIRE_VERSION;
        } catch (ArithmeticException e) {
            return false;
        }
    }

    private static int intField(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("envelope field '" + field + "' must be an integer");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("envelope field '" + field + "' must be an integer");
        }
    }

    private static long longField(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("envelope field '" + field + "' must be an integer");
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("envelope field '" + field + "' must be an integer");
        }
    }

    /**
     * Rejects duplicate keys in any JSON object before parsing (Gson keeps the
     * last occurrence silently, which would hide a malformed envelope).
     * String-aware: braces, brackets, commas and colons inside strings (with
     * backslash escapes and unicode escapes) never affect structure; keys are
     * compared by unescaped value so {@code "a"} and {@code "\u0061"} collide.
     */
    static void rejectDuplicateKeys(String raw) {
        int[] pos = {0};
        skipWs(raw, pos);
        parseValue(raw, pos);
        skipWs(raw, pos);
        if (pos[0] != raw.length()) throw new IllegalArgumentException("trailing content after envelope JSON");
    }

    private static void parseValue(String raw, int[] pos) {
        skipWs(raw, pos);
        if (pos[0] >= raw.length()) throw new IllegalArgumentException("truncated envelope JSON");
        char c = raw.charAt(pos[0]);
        if (c == '{') parseObject(raw, pos);
        else if (c == '[') parseArray(raw, pos);
        else if (c == '"') parseString(raw, pos);
        else parseLiteral(raw, pos);
    }

    private static void parseObject(String raw, int[] pos) {
        pos[0]++; // {
        Set<String> keys = new HashSet<>();
        skipWs(raw, pos);
        if (consume(raw, pos, '}')) return;
        while (true) {
            skipWs(raw, pos);
            if (pos[0] >= raw.length() || raw.charAt(pos[0]) != '"')
                throw new IllegalArgumentException("envelope object keys must be strings");
            String key = parseString(raw, pos);
            if (!keys.add(key)) throw new IllegalArgumentException("duplicate envelope field: " + key);
            skipWs(raw, pos);
            if (!consume(raw, pos, ':')) throw new IllegalArgumentException("expected ':' in envelope object");
            parseValue(raw, pos);
            skipWs(raw, pos);
            if (consume(raw, pos, '}')) return;
            if (!consume(raw, pos, ',')) throw new IllegalArgumentException("expected ',' or '}' in envelope object");
        }
    }

    private static void parseArray(String raw, int[] pos) {
        pos[0]++; // [
        skipWs(raw, pos);
        if (consume(raw, pos, ']')) return;
        while (true) {
            parseValue(raw, pos);
            skipWs(raw, pos);
            if (consume(raw, pos, ']')) return;
            if (!consume(raw, pos, ',')) throw new IllegalArgumentException("expected ',' or ']' in envelope array");
            skipWs(raw, pos);
        }
    }

    private static String parseString(String raw, int[] pos) {
        pos[0]++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos[0] >= raw.length()) throw new IllegalArgumentException("unterminated envelope string");
            char c = raw.charAt(pos[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos[0] >= raw.length()) throw new IllegalArgumentException("unterminated envelope escape");
                char e = raw.charAt(pos[0]++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos[0] + 4 > raw.length())
                            throw new IllegalArgumentException("truncated unicode escape in envelope");
                        try {
                            sb.append((char) Integer.parseInt(raw.substring(pos[0], pos[0] + 4), 16));
                        } catch (NumberFormatException bad) {
                            throw new IllegalArgumentException("invalid unicode escape in envelope");
                        }
                        pos[0] += 4;
                    }
                    default -> throw new IllegalArgumentException("invalid escape in envelope string");
                }
            } else sb.append(c);
        }
    }

    private static void parseLiteral(String raw, int[] pos) {
        int start = pos[0];
        while (pos[0] < raw.length() && ",:]}".indexOf(raw.charAt(pos[0])) < 0
                && !Character.isWhitespace(raw.charAt(pos[0]))) pos[0]++;
        if (start == pos[0]) throw new IllegalArgumentException("expected value in envelope JSON at " + start);
    }

    private static void skipWs(String raw, int[] pos) {
        while (pos[0] < raw.length()) {
            char c = raw.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos[0]++;
            else break;
        }
    }

    private static boolean consume(String raw, int[] pos, char c) {
        if (pos[0] < raw.length() && raw.charAt(pos[0]) == c) {
            pos[0]++;
            return true;
        }
        return false;
    }
}
