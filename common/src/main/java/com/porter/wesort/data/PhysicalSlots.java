package com.porter.wesort.data;

import com.google.gson.*;
import java.util.*;

/** Versioned physical snapshots. A fingerprint describes content; only source + slot path identifies its location. */
public final class PhysicalSlots {
    public static final int MAX_DEPTH = 8, MAX_NODES = 8192, MAX_VARIANTS = 16384;
    public enum Kind { ITEM, SHULKER, BUNDLE, CONTAINER }
    private PhysicalSlots() {}

    /** A visible identity claim is not proof of globally unique registered ownership. */
    public record BoxIdentity(boolean hasUUID, String boxUUID, String identityStatus) {
        static BoxIdentity unregistered() { return new BoxIdentity(false, null, "UNREGISTERED"); }
        static BoxIdentity invalid() { return new BoxIdentity(false, null, "INVALID"); }
    }
    private static final java.util.regex.Pattern BOX_UUID = java.util.regex.Pattern.compile(
        "WeSort-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    /**
     * Parse vanilla 26.3's typed CUSTOM_NAME, not an untrusted hasUUID field.
     * Only an independent verified Manager registry may upgrade UNKNOWN to REGISTERED.
     */
    public static BoxIdentity boxIdentity(Kind kind, String components) {
        if (kind != Kind.SHULKER) return BoxIdentity.unregistered();
        try {
            JsonObject patch = JsonParser.parseString(components).getAsJsonObject();
            JsonObject value = patch;
            if (patch.has("type")) {
                if (patch.get("type").getAsInt() != 10 || !patch.get("value").isJsonObject())
                    return BoxIdentity.invalid();
                value = patch.getAsJsonObject("value");
            }
            JsonElement name = value.get("minecraft:custom_name");
            if (name == null) return BoxIdentity.unregistered();
            JsonObject typed = name.getAsJsonObject();
            if (typed.get("type").getAsInt() != 8 || !typed.get("value").getAsJsonPrimitive().isString())
                return BoxIdentity.invalid();
            JsonElement component = JsonParser.parseString(typed.get("value").getAsString());
            String visible;
            if (component.isJsonPrimitive() && component.getAsJsonPrimitive().isString()) {
                visible = component.getAsString();
            } else if (component.isJsonObject() && component.getAsJsonObject().size() == 1
                && component.getAsJsonObject().has("text")
                && component.getAsJsonObject().get("text").getAsJsonPrimitive().isString()) {
                visible = component.getAsJsonObject().get("text").getAsString();
            } else return BoxIdentity.invalid();
            if (BOX_UUID.matcher(visible).matches()) {
                return new BoxIdentity(true, visible.substring("WeSort-".length()), "UNKNOWN");
            }
            return visible.startsWith("WeSort-") ? BoxIdentity.invalid() : BoxIdentity.unregistered();
        } catch (RuntimeException malformed) {
            return BoxIdentity.invalid();
        }
    }

    public record Slot(int slot, int count, Item item) {
        public Slot {
            Objects.requireNonNull(item);
            if (slot < 0 || count < 1 || count > item.maxStack()) throw new IllegalArgumentException("Invalid physical stack quantity/slot");
        }
        public String fingerprint() { return hash("stack-1", item.fingerprint(), new JsonPrimitive(count)); }
    }

    public static final class Item {
        private final String id, key, components, group, fingerprint;
        private final int maxStack, size;
        private final Kind kind;
        private final List<Slot> contents;
        public Item(String id, String key, int maxStack, String components, Kind kind, int size, List<Slot> contents, String group) {
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || key == null || key.isEmpty() || key.length() > 512
                || maxStack < 1 || size < 0 || size > 256 || components == null || components.length() > 1_000_000)
                throw new IllegalArgumentException("Invalid physical item");
            JsonElement patch = JsonParser.parseString(components);
            if (!patch.isJsonObject()) throw new IllegalArgumentException("Expected structured component patch");
            this.id = id; this.key = key; this.maxStack = maxStack; this.components = CanonicalJson.stringify(patch);
            this.kind = Objects.requireNonNull(kind); this.size = size; this.group = group == null ? "" : group;
            if (this.group.length() > 256 || kind == Kind.ITEM && (size != 0 || !contents.isEmpty())
                || kind == Kind.SHULKER && size != 27) throw new IllegalArgumentException("Invalid physical container shape");
            this.contents = ordered(contents, size);
            JsonArray identity = new JsonArray(); identity.add("physical-item-1"); identity.add(id); identity.add(maxStack);
            identity.add(patch); identity.add(kind.name()); identity.add(size); identity.add(slotIdentities(this.contents));
            fingerprint = ContainerIdentity.hash(CanonicalJson.stringify(identity));
        }
        public String id() { return id; }
        public String key() { return key; }
        public String components() { return components; }
        public String group() { return group; }
        public String fingerprint() { return fingerprint; }
        public Kind kind() { return kind; }
        public int maxStack() { return maxStack; }
        public int size() { return size; }
        public List<Slot> contents() { return contents; }
        public BoxIdentity boxIdentity() { return PhysicalSlots.boxIdentity(kind, components); }
    }

    public record Located(String path, String parent, Slot stack, long copies) {
        public String resource(String source) { return source + "/" + path; }
    }

    public static final class Snapshot {
        private final int size;
        private final List<Slot> slots;
        private final List<Located> located;
        private final String fingerprint;
        public Snapshot(int size, List<Slot> slots) {
            if (size < 1 || size > 256) throw new IllegalArgumentException("Invalid physical inventory size");
            this.size = size; this.slots = ordered(slots, size);
            List<Located> positions = new ArrayList<>();
            walk(this.slots, "", 1, 0, positions);
            long componentBytes = 0;
            for (Located position : positions) {
                componentBytes = Math.addExact(componentBytes, position.stack.item.components.length());
                if (componentBytes > 4_000_000) throw new IllegalArgumentException("Physical component snapshot too large");
            }
            this.located = List.copyOf(positions);
            fingerprint = hash("physical-inventory-1", String.valueOf(size), slotIdentities(this.slots));
        }
        public int size() { return size; }
        public List<Slot> slots() { return slots; }
        public List<Located> located() { return located; }
        public String fingerprint() { return fingerprint; }
        public Map<String, Long> totals() {
            Map<String, Long> totals = new TreeMap<>();
            for (Located position : located) totals.merge(position.stack.item.key(), Math.multiplyExact(position.copies, position.stack.count), Math::addExact);
            return Map.copyOf(totals);
        }
        public Map<String, Long> shulkers() {
            Map<String, Long> totals = new TreeMap<>();
            for (Slot slot : slots) if (slot.item.kind == Kind.SHULKER && !slot.item.group.isEmpty())
                totals.merge(slot.item.group, (long) slot.count, Math::addExact);
            return Map.copyOf(totals);
        }
        public JsonObject json() {
            JsonObject out = new JsonObject(); out.addProperty("schema", 1); out.addProperty("size", size); out.addProperty("fingerprint", fingerprint);
            JsonArray positions = new JsonArray();
            for (Located position : located) {
                Slot slot = position.stack; Item item = slot.item;
                JsonObject row = new JsonObject(); row.addProperty("path", position.path); row.addProperty("parent", position.parent);
                row.addProperty("slot", slot.slot); row.addProperty("count", slot.count); row.addProperty("copies", position.copies);
                row.addProperty("id", item.id); row.addProperty("key", item.key); row.addProperty("maxStack", item.maxStack);
                row.addProperty("kind", item.kind.name()); row.addProperty("size", item.size); row.addProperty("group", item.group);
                row.addProperty("components", item.components); row.addProperty("itemFingerprint", item.fingerprint);
                row.addProperty("fingerprint", slot.fingerprint());
                if (item.kind == Kind.SHULKER) {
                    BoxIdentity identity = item.boxIdentity();
                    row.addProperty("hasUUID", identity.hasUUID());
                    if (identity.boxUUID() != null) row.addProperty("boxUUID", identity.boxUUID());
                    else row.add("boxUUID", JsonNull.INSTANCE);
                    row.addProperty("identityStatus", identity.identityStatus());
                }
                positions.add(row);
            }
            out.add("positions", positions); return out;
        }
    }

    /** Compact wire dictionary: full components once per variant; source rows are [slot, variant, count]. */
    public static final class Writer {
        private final Map<String, Integer> refs = new HashMap<>();
        private final JsonArray variants = new JsonArray();
        public JsonArray variants() { return variants.deepCopy(); }
        public JsonArray slots(List<Slot> slots) {
            JsonArray out = new JsonArray();
            for (Slot slot : slots) {
                int ref = intern(slot.item);
                JsonArray row = new JsonArray(); row.add(slot.slot); row.add(ref); row.add(slot.count); out.add(row);
            }
            return out;
        }
        private int intern(Item item) {
            Integer known = refs.get(item.fingerprint); if (known != null) return known;
            JsonArray children = slots(item.contents);
            if (variants.size() >= MAX_VARIANTS) throw new IllegalArgumentException("Too many physical variants");
            int ref = variants.size(); JsonObject value = new JsonObject(); value.addProperty("id", item.id); value.addProperty("key", item.key);
            value.addProperty("maxStack", item.maxStack); value.addProperty("components", item.components); value.addProperty("kind", item.kind.name());
            value.addProperty("size", item.size); value.addProperty("group", item.group); value.add("slots", children);
            refs.put(item.fingerprint, ref); variants.add(value); return ref;
        }
    }

    public static final class Reader {
        private final JsonArray variants;
        private final Item[] cache;
        private final Set<Integer> resolving = new HashSet<>();
        public Reader(JsonArray variants) {
            if (variants == null || variants.size() > MAX_VARIANTS) throw new IllegalArgumentException("Invalid physical dictionary");
            this.variants = variants.deepCopy(); cache = new Item[variants.size()];
        }
        public Snapshot snapshot(int size, JsonArray slots) { return new Snapshot(size, slots(slots, 0)); }
        private List<Slot> slots(JsonArray values, int depth) {
            if (values == null || values.size() > 256 || depth > MAX_DEPTH && !values.isEmpty()) throw new IllegalArgumentException("Physical tree too deep/large");
            List<Slot> slots = new ArrayList<>();
            for (JsonElement value : values) {
                JsonArray row = value.getAsJsonArray(); if (row.size() != 3) throw new IllegalArgumentException("Invalid physical slot row");
                slots.add(new Slot(integer(row.get(0)), integer(row.get(2)), item(integer(row.get(1)), depth)));
            }
            return slots;
        }
        private Item item(int ref, int depth) {
            if (ref < 0 || ref >= cache.length || depth > MAX_DEPTH) throw new IllegalArgumentException("Invalid physical variant reference");
            if (cache[ref] != null) return cache[ref];
            if (!resolving.add(ref)) throw new IllegalArgumentException("Cyclic physical inventory");
            try {
                JsonObject value = variants.get(ref).getAsJsonObject();
                Item item = new Item(value.get("id").getAsString(), value.get("key").getAsString(), integer(value.get("maxStack")),
                    value.get("components").getAsString(), Kind.valueOf(value.get("kind").getAsString()), integer(value.get("size")),
                    slots(value.getAsJsonArray("slots"), depth + 1), value.has("group") ? value.get("group").getAsString() : "");
                cache[ref] = item; return item;
            } finally { resolving.remove(ref); }
        }
    }

    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        return value.getAsBigDecimal().intValueExact();
    }
    private static List<Slot> ordered(List<Slot> slots, int size) {
        var sorted = slots.stream().sorted(Comparator.comparingInt(Slot::slot)).toList(); int previous = -1;
        for (Slot slot : sorted) {
            if (slot.slot <= previous || slot.slot >= size) throw new IllegalArgumentException("Duplicate or out-of-range physical slot");
            previous = slot.slot;
        }
        return sorted;
    }
    private static void walk(List<Slot> slots, String parent, long copies, int depth, List<Located> out) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Physical hierarchy too deep");
        for (Slot slot : slots) {
            if (out.size() >= MAX_NODES) throw new IllegalArgumentException("Physical hierarchy too large");
            String path = parent.isEmpty() ? String.valueOf(slot.slot) : parent + "/" + slot.slot;
            out.add(new Located(path, parent, slot, copies));
            if (!slot.item.contents.isEmpty()) walk(slot.item.contents, path, Math.multiplyExact(copies, slot.count), depth + 1, out);
        }
    }
    private static JsonArray slotIdentities(List<Slot> slots) {
        JsonArray out = new JsonArray();
        for (Slot slot : slots) { JsonArray row = new JsonArray(); row.add(slot.slot); row.add(slot.fingerprint()); out.add(row); }
        return out;
    }
    private static String hash(String domain, String value, JsonElement details) {
        JsonArray out = new JsonArray(); out.add(domain); out.add(value); out.add(details); return ContainerIdentity.hash(CanonicalJson.stringify(out));
    }
}
