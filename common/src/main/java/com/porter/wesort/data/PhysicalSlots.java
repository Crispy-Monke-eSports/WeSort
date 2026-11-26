package com.porter.wesort.data;

import com.google.gson.*;
import com.porter.wesort.snbt.SnbtCodec;
import java.util.*;

/** Versioned physical snapshots. A fingerprint describes content; only source + slot path identifies its location. */
public final class PhysicalSlots {
    public static final int MAX_DEPTH = 8, MAX_NODES = 8192, MAX_VARIANTS = 16384;

    public enum Kind {
        ITEM,
        SHULKER,
        BUNDLE,
        CONTAINER
    }

    private PhysicalSlots() {}

    public record Slot(int slot, int count, Item item) {
        public Slot {
            Objects.requireNonNull(item);
            if (slot < 0 || count < 1 || count > item.maxStack())
                throw new IllegalArgumentException("Invalid physical stack quantity/slot");
        }

        public String fingerprint() {
            return hash("stack", item.fingerprint(), integerTag(count));
        }
    }

    public static final class Item {
        private final String id, key, components, group, fingerprint;
        private final int maxStack, size;
        private final Kind kind;
        private final List<Slot> contents;

        public Item(
                String id,
                String key,
                int maxStack,
                String components,
                Kind kind,
                int size,
                List<Slot> contents,
                String group) {
            if (id == null
                    || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || key == null
                    || key.isEmpty()
                    || key.length() > 512
                    || maxStack < 1
                    || size < 0
                    || size > 256
                    || components == null
                    || components.length() > 1_000_000) throw new IllegalArgumentException("Invalid physical item");
            var patch = SnbtCodec.compound(components);
            if (patch.value().containsKey("type") && patch.value().containsKey("value"))
                throw new IllegalArgumentException("Legacy typed JSON component patches are not supported");
            this.id = id;
            this.key = key;
            this.maxStack = maxStack;
            this.components = SnbtCodec.write(patch);
            this.kind = Objects.requireNonNull(kind);
            this.size = size;
            this.group = group == null ? "" : group;
            if (this.group.length() > 256
                    || kind == Kind.ITEM && (size != 0 || !contents.isEmpty())
                    || kind == Kind.SHULKER && size != 27)
                throw new IllegalArgumentException("Invalid physical container shape");
            this.contents = ordered(contents, size);
            fingerprint = SnbtCodec.fingerprint(
                    "physical-item",
                    new SnbtCodec.TList(List.of(
                            text(id),
                            text(key),
                            text(this.group),
                            integerTag(maxStack),
                            patch,
                            text(kind.name()),
                            integerTag(size),
                            slotIdentities(this.contents))));
        }

        public String id() {
            return id;
        }

        public String key() {
            return key;
        }

        public String components() {
            return components;
        }

        public String group() {
            return group;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public Kind kind() {
            return kind;
        }

        public int maxStack() {
            return maxStack;
        }

        public int size() {
            return size;
        }

        public List<Slot> contents() {
            return contents;
        }
    }

    public record Located(String path, String parent, Slot stack, long copies) {
        public String resource(String source) {
            return source + "/" + path;
        }
    }

    public static final class Snapshot {
        private final int size;
        private final List<Slot> slots;
        private final List<Located> located;
        private final String fingerprint, snbt;

        public Snapshot(int size, List<Slot> slots) {
            if (size < 1 || size > 256) throw new IllegalArgumentException("Invalid physical inventory size");
            this.size = size;
            this.slots = ordered(slots, size);
            List<Located> positions = new ArrayList<>();
            walk(this.slots, "", 1, 0, positions);
            long componentBytes = 0;
            for (Located position : positions) {
                componentBytes = Math.addExact(componentBytes, position.stack.item.components.length());
                if (componentBytes > 4_000_000)
                    throw new IllegalArgumentException("Physical component snapshot too large");
            }
            this.located = List.copyOf(positions);
            var tree = new SnbtCodec.TCompound(Map.of(
                    "schema", integerTag(2),
                    "size", integerTag(size),
                    "slots", encodeSlots(this.slots)));
            snbt = SnbtCodec.write(tree);
            fingerprint = SnbtCodec.fingerprint("physical-inventory", tree);
        }

        public int size() {
            return size;
        }

        public List<Slot> slots() {
            return slots;
        }

        public List<Located> located() {
            return located;
        }

        public String fingerprint() {
            return fingerprint;
        }

        /** The complete versioned, authoritative snapshot; JSON is a derived view only. */
        public String snbt() {
            return snbt;
        }

        public static Snapshot fromSnbt(String snbt) {
            var root = SnbtCodec.compound(snbt);
            requireKeys(root, "schema", "size", "slots");
            if (intValue(root.get("schema")) != 2)
                throw new IllegalArgumentException("Unsupported SNBT snapshot version");
            var result = new Snapshot(intValue(root.get("size")), decodeSlots(root.get("slots"), 0, new int[1]));
            if (!snbt.equals(result.snbt()))
                throw new IllegalArgumentException("Noncanonical or inconsistent physical SNBT");
            return result;
        }

        /** JSON transport envelope; the SNBT field is the only inventory content. */
        public JsonObject envelope() {
            var out = new JsonObject();
            out.addProperty("schema", 2);
            out.addProperty("size", size);
            out.addProperty("fingerprint", fingerprint);
            out.addProperty("snbt", snbt);
            return out;
        }

        public static Snapshot readEnvelope(JsonObject envelope) {
            if (integer(envelope.get("schema")) != 2 || envelope.has("slots") || envelope.has("slotVariants"))
                throw new IllegalArgumentException("Expected SNBT-only snapshot envelope");
            var result = fromSnbt(envelope.get("snbt").getAsString());
            if (result.size != integer(envelope.get("size"))
                    || !result.fingerprint.equals(envelope.get("fingerprint").getAsString()))
                throw new IllegalArgumentException("SNBT snapshot metadata mismatch");
            return result;
        }

        public Map<String, Long> totals() {
            var totals = new QuantityCounter();
            for (Located position : located)
                totals.add(
                        position.stack.item.key(),
                        QuantityCounter.multiplyChecked(position.copies, position.stack.count));
            return totals.snapshot();
        }

        public Map<String, Long> shulkers() {
            var totals = new QuantityCounter();
            for (Slot slot : slots)
                if (slot.item.kind == Kind.SHULKER && !slot.item.group.isEmpty())
                    totals.add(slot.item.group, slot.count);
            return totals.snapshot();
        }

        public JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("schema", 2);
            out.addProperty("size", size);
            out.addProperty("fingerprint", fingerprint);
            JsonArray positions = new JsonArray();
            for (Located position : located) {
                Slot slot = position.stack;
                Item item = slot.item;
                JsonObject row = new JsonObject();
                row.addProperty("path", position.path);
                row.addProperty("parent", position.parent);
                row.addProperty("slot", slot.slot);
                row.addProperty("count", slot.count);
                row.addProperty("copies", position.copies);
                row.addProperty("id", item.id);
                row.addProperty("key", item.key);
                row.addProperty("maxStack", item.maxStack);
                row.addProperty("kind", item.kind.name());
                row.addProperty("size", item.size);
                row.addProperty("group", item.group);
                row.addProperty("components", item.components);
                row.addProperty("itemFingerprint", item.fingerprint);
                row.addProperty("fingerprint", slot.fingerprint());
                positions.add(row);
            }
            out.add("positions", positions);
            return out;
        }
    }

    /** Compact wire dictionary: full components once per variant; source rows are [slot, variant, count]. */
    public static final class Writer {
        private final Map<String, Integer> refs = new HashMap<>();
        private final JsonArray variants = new JsonArray();

        public JsonArray variants() {
            return variants.deepCopy();
        }

        public JsonArray slots(List<Slot> slots) {
            JsonArray out = new JsonArray();
            for (Slot slot : slots) {
                int ref = intern(slot.item);
                JsonArray row = new JsonArray();
                row.add(slot.slot);
                row.add(ref);
                row.add(slot.count);
                out.add(row);
            }
            return out;
        }

        private int intern(Item item) {
            Integer known = refs.get(item.fingerprint);
            if (known != null) return known;
            JsonArray children = slots(item.contents);
            if (variants.size() >= MAX_VARIANTS) throw new IllegalArgumentException("Too many physical variants");
            int ref = variants.size();
            JsonObject value = new JsonObject();
            value.addProperty("id", item.id);
            value.addProperty("key", item.key);
            value.addProperty("maxStack", item.maxStack);
            value.addProperty("components", item.components);
            value.addProperty("kind", item.kind.name());
            value.addProperty("size", item.size);
            value.addProperty("group", item.group);
            value.add("slots", children);
            refs.put(item.fingerprint, ref);
            variants.add(value);
            return ref;
        }
    }

    public static final class Reader {
        private final JsonArray variants;
        private final Item[] cache;
        private final Set<Integer> resolving = new HashSet<>();

        public Reader(JsonArray variants) {
            if (variants == null || variants.size() > MAX_VARIANTS)
                throw new IllegalArgumentException("Invalid physical dictionary");
            this.variants = variants.deepCopy();
            cache = new Item[variants.size()];
        }

        public Snapshot snapshot(int size, JsonArray slots) {
            return new Snapshot(size, slots(slots, 0));
        }

        private List<Slot> slots(JsonArray values, int depth) {
            if (values == null || values.size() > 256 || depth > MAX_DEPTH && !values.isEmpty())
                throw new IllegalArgumentException("Physical tree too deep/large");
            List<Slot> slots = new ArrayList<>();
            for (JsonElement value : values) {
                JsonArray row = value.getAsJsonArray();
                if (row.size() != 3) throw new IllegalArgumentException("Invalid physical slot row");
                slots.add(new Slot(integer(row.get(0)), integer(row.get(2)), item(integer(row.get(1)), depth)));
            }
            return slots;
        }

        private Item item(int ref, int depth) {
            if (ref < 0 || ref >= cache.length || depth > MAX_DEPTH)
                throw new IllegalArgumentException("Invalid physical variant reference");
            if (cache[ref] != null) return cache[ref];
            if (!resolving.add(ref)) throw new IllegalArgumentException("Cyclic physical inventory");
            try {
                JsonObject value = variants.get(ref).getAsJsonObject();
                Item item = new Item(
                        value.get("id").getAsString(),
                        value.get("key").getAsString(),
                        integer(value.get("maxStack")),
                        value.get("components").getAsString(),
                        Kind.valueOf(value.get("kind").getAsString()),
                        integer(value.get("size")),
                        slots(value.getAsJsonArray("slots"), depth + 1),
                        value.has("group") ? value.get("group").getAsString() : "");
                cache[ref] = item;
                return item;
            } finally {
                resolving.remove(ref);
            }
        }
    }

    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer");
        return value.getAsBigDecimal().intValueExact();
    }

    private static List<Slot> ordered(List<Slot> slots, int size) {
        var sorted = slots.stream().sorted(Comparator.comparingInt(Slot::slot)).toList();
        int previous = -1;
        for (Slot slot : sorted) {
            if (slot.slot <= previous || slot.slot >= size)
                throw new IllegalArgumentException("Duplicate or out-of-range physical slot");
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
            if (!slot.item.contents.isEmpty())
                walk(slot.item.contents, path, Math.multiplyExact(copies, slot.count), depth + 1, out);
        }
    }

    private static SnbtCodec.TList encodeSlots(List<Slot> slots) {
        List<SnbtCodec.Tag> values = new ArrayList<>();
        for (Slot slot : slots) {
            Item item = slot.item();
            // Native stack fields are typed SNBT, not a JSON string or an item hash.
            var stack = new SnbtCodec.TCompound(Map.of(
                    "id", text(item.id()),
                    "count", integerTag(slot.count()),
                    "components", SnbtCodec.compound(item.components())));
            values.add(new SnbtCodec.TCompound(Map.of(
                    "slot", integerTag(slot.slot()),
                    "stack", stack,
                    "key", text(item.key()),
                    "maxStack", integerTag(item.maxStack()),
                    "kind", text(item.kind().name()),
                    "capacity", integerTag(item.size()),
                    "group", text(item.group()),
                    "children", encodeSlots(item.contents()))));
        }
        return new SnbtCodec.TList(values);
    }

    private static List<Slot> decodeSlots(SnbtCodec.Tag value, int depth, int[] nodes) {
        if (!(value instanceof SnbtCodec.TList list)
                || list.value().size() > 256
                || (depth > MAX_DEPTH && !list.value().isEmpty()))
            throw new IllegalArgumentException("Invalid SNBT slot sequence");
        List<Slot> slots = new ArrayList<>();
        for (var entry : list.value()) {
            if (++nodes[0] > MAX_NODES) throw new IllegalArgumentException("SNBT physical hierarchy too large");
            var row = requireCompound(entry);
            requireKeys(row, "slot", "stack", "key", "maxStack", "kind", "capacity", "group", "children");
            var stack = requireCompound(row.get("stack"));
            requireKeys(stack, "id", "count", "components");
            var item = new Item(
                    textValue(stack.get("id")),
                    textValue(row.get("key")),
                    intValue(row.get("maxStack")),
                    SnbtCodec.write(requireCompound(stack.get("components"))),
                    Kind.valueOf(textValue(row.get("kind"))),
                    intValue(row.get("capacity")),
                    decodeSlots(row.get("children"), depth + 1, nodes),
                    textValue(row.get("group")));
            validateNativeChildren(item, requireCompound(stack.get("components")));
            slots.add(new Slot(intValue(row.get("slot")), intValue(stack.get("count")), item));
        }
        return slots;
    }

    /** Verify every derived child against the authoritative native component before indexing it. */
    private static void validateNativeChildren(Item item, SnbtCodec.TCompound patch) {
        var nativeChildren = new TreeMap<Integer, SnbtCodec.TCompound>();
        var container = patch.get("minecraft:container");
        if (container != null) {
            if (!(container instanceof SnbtCodec.TList list))
                throw new IllegalArgumentException("Invalid native container");
            for (var value : list.value()) {
                var entry = requireCompound(value);
                int slot = intValue(entry.get("slot"));
                if (item.kind == Kind.ITEM
                        || item.kind == Kind.BUNDLE
                        || nativeChildren.put(slot, requireCompound(entry.get("item"))) != null)
                    throw new IllegalArgumentException("Conflicting native container slots");
            }
        }
        var bundle = patch.get("minecraft:bundle_contents");
        if (bundle != null) {
            if (!(bundle instanceof SnbtCodec.TList list)) throw new IllegalArgumentException("Invalid native bundle");
            if (!list.value().isEmpty() && (item.kind != Kind.BUNDLE || !nativeChildren.isEmpty()))
                throw new IllegalArgumentException("Conflicting native bundle contents");
            for (var value : list.value()) nativeChildren.put(nativeChildren.size(), requireCompound(value));
        }
        if (nativeChildren.size() != item.contents.size()
                || (item.kind == Kind.BUNDLE && item.size != nativeChildren.size()))
            throw new IllegalArgumentException("Native child count mismatch");
        for (Slot child : item.contents) {
            var original = nativeChildren.get(child.slot());
            if (original == null || !child.item.id.equals(textValue(original.get("id"))))
                throw new IllegalArgumentException("Native child identity mismatch");
            int count = original.get("count") == null ? 1 : intValue(original.get("count"));
            String components = original.get("components") == null
                    ? "{}"
                    : SnbtCodec.write(requireCompound(original.get("components")));
            if (count != child.count || !components.equals(child.item.components))
                throw new IllegalArgumentException("Native child content mismatch");
        }
    }

    private static SnbtCodec.TCompound requireCompound(SnbtCodec.Tag value) {
        if (!(value instanceof SnbtCodec.TCompound c)) throw new IllegalArgumentException("Expected compound");
        return c;
    }

    private static void requireKeys(SnbtCodec.TCompound value, String... keys) {
        if (!value.value().keySet().equals(Set.of(keys)))
            throw new IllegalArgumentException("Unexpected SNBT snapshot fields");
    }

    private static int intValue(SnbtCodec.Tag value) {
        if (!(value instanceof SnbtCodec.TInt n)) throw new IllegalArgumentException("Expected int tag");
        return Math.toIntExact(n.value());
    }

    private static String textValue(SnbtCodec.Tag value) {
        if (!(value instanceof SnbtCodec.TString text)) throw new IllegalArgumentException("Expected string tag");
        return text.value();
    }

    private static SnbtCodec.TString text(String value) {
        return new SnbtCodec.TString(value);
    }

    private static SnbtCodec.TInt integerTag(int value) {
        return new SnbtCodec.TInt(value);
    }

    private static SnbtCodec.TList slotIdentities(List<Slot> slots) {
        List<SnbtCodec.Tag> out = new ArrayList<>();
        for (Slot slot : slots)
            out.add(new SnbtCodec.TList(List.of(integerTag(slot.slot()), text(slot.fingerprint()))));
        return new SnbtCodec.TList(out);
    }

    private static String hash(String domain, String value, SnbtCodec.Tag details) {
        return SnbtCodec.fingerprint(domain, new SnbtCodec.TList(List.of(text(value), details)));
    }
}
