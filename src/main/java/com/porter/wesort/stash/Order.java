package com.porter.wesort.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An order from the WeSort Manager server: what to take from the stash, as lines. Also the packer that turns item lines
 * into shulker loads. Nothing here touches Minecraft types, so it can be tested and ported on its own.
 */
public final class Order {
    public static final String ITEM = "item", SHULKER = "shulker", KIT = "kit", CRAFT = "craft";
    /** The most shulkers one order may need. */
    public static final int MAX_BINS = 54;

    public static final int SLOTS = 27;

    /** One line: {@code type} is "item" (key is an items[] key) or "shulker" (key is a shulkers[] group key). */
    public record Line(String type, String key, int count) {}

    /** The stacks to put in one slot: an item key and how many. */
    public record Lot(String key, int count) {}

    /** The longest name the anvil takes. */
    public static final int MAX_NAME = 50;
    /** The most names an "each" list may hold. */
    public static final int MAX_NAMES = 54;

    /**
     * What the shulkers of an order are to be called: none, one name for all ("all"), or one per shulker by position
     * ("each", blank or missing means unnamed). Texts are kept as the server sent them (trimmed); {@link #nameFor}
     * says which are usable.
     */
    public record Names(String mode, String all, List<String> each) {
        public static final String ALL = "all", EACH = "each";

        /** Null when there is nothing to name. */
        public static Names of(String mode, String all, List<String> each) {
            if (ALL.equals(mode)) {
                String t = all == null ? "" : all.trim();
                return t.isBlank() ? null : new Names(ALL, t, List.of());
            }
            if (EACH.equals(mode) && each != null) {
                List<String> list = new ArrayList<>();
                boolean any = false;
                for (int i = 0; i < each.size() && i < MAX_NAMES; i++) {
                    String t = each.get(i) == null ? "" : each.get(i).trim();
                    if (!t.isBlank()) any = true;
                    list.add(t);
                }
                return any ? new Names(EACH, "", list) : null;
            }
            return null;
        }

        /** The name wanted for the i-th shulker (0-based), trimmed; null when it is to stay unnamed. May not pass {@link #valid}. */
        public String nameFor(int i) {
            if (i < 0) return null;
            if (ALL.equals(mode)) return all.isBlank() ? null : all;
            if (i >= each.size()) return null;
            String t = each.get(i);
            return t.isBlank() ? null : t.trim();
        }
    }

    /** A name the bot may put on a shulker: 1 to 50 characters, no control characters and no section sign. */
    public static boolean valid(String name) {
        if (name == null) return false;
        String t = name.trim();
        if (t.isEmpty() || t.length() > MAX_NAME) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isISOControl(c) || c == (char) 0xA7) return false;
        }
        return true;
    }

    /** "3 shulker(s) delivered unnamed: no anvil set, not enough XP levels", or "" when none. */
    public static String unnamedNote(int count, Collection<String> reasons) {
        if (count <= 0) return "";
        return count + " shulker(s) delivered unnamed" + (reasons.isEmpty() ? "" : ": " + String.join(", ", reasons));
    }

    public final String id;
    public final List<Line> lines;
    /** Output batches and the precise ingredient budgets pinned by Manager. */
    public record CraftJob(String item, int count, Map<String, Integer> materials) {}

    public final List<CraftJob> craftJobs;
    /** Null: no naming. */
    public final Names names;

    /** One pinned kit template slot: 0-based box slot, exact item variant key, count. */
    public record TemplateSlot(int slot, String key, int count) {}

    /** Pinned kit templates by kit name (slot layouts the bot packs exactly). */
    public final Map<String, List<TemplateSlot>> kits;

    public final Map<String, Set<Integer>> protectedRoots;
    public final Map<String, Set<Integer>> allocatedRoots;

    public Order(String id, List<Line> lines) {
        this(id, lines, null);
    }

    public Order(String id, List<Line> lines, Names names) {
        this(id, lines, names, Map.of(), Map.of(), Map.of(), List.of());
    }

    private Order(
            String id,
            List<Line> lines,
            Names names,
            Map<String, List<TemplateSlot>> kits,
            Map<String, Set<Integer>> protectedRoots,
            Map<String, Set<Integer>> allocatedRoots,
            List<CraftJob> craftJobs) {
        this.craftJobs = craftJobs;
        this.id = id;
        this.lines = lines;
        this.names = names;
        this.kits = kits;
        this.protectedRoots = protectedRoots;
        this.allocatedRoots = allocatedRoots;
    }

    /**
     * Reads an order object from the server. Null when it has no id or no usable line.
     */
    public static Order parse(JsonObject o) {
        if (o == null || !o.has("id") || !o.has("lines") || !o.get("lines").isJsonArray()) return null;
        List<Line> lines = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("lines");
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject l = e.getAsJsonObject();
            try {
                String type = l.get("type").getAsString();
                String key = l.get("key").getAsString();
                int count = l.get("count").getAsInt();
                if ((type.equals(ITEM) || type.equals(SHULKER) || type.equals(KIT) || type.equals(CRAFT))
                        && !key.isEmpty()
                        && count >= 1) lines.add(new Line(type, key, count));
            } catch (RuntimeException ex) {
                // A broken line is left out and shows up as undelivered
            }
        }
        String id = o.get("id").getAsString();
        if (id.isEmpty() || lines.isEmpty()) return null;
        var guards = new LinkedHashMap<String, Set<Integer>>();
        if (o.has("allocations") && !o.has("protectedRoots"))
            throw new IllegalArgumentException("Missing allocation guard");
        if (o.has("protectedRoots")) {
            for (var entry : o.getAsJsonObject("protectedRoots").entrySet()) {
                var slots = new HashSet<Integer>();
                for (var slot : entry.getValue().getAsJsonArray()) {
                    int value = slot.getAsBigDecimal().intValueExact();
                    if (value < 0 || value > 53) throw new IllegalArgumentException("Invalid physical slot guard");
                    slots.add(value);
                }
                guards.put(entry.getKey(), Set.copyOf(slots));
            }
        }
        var allocated = new LinkedHashMap<String, Set<Integer>>();
        if (o.has("allocations")) {
            for (var value : o.getAsJsonArray("allocations")) {
                var allocation = value.getAsJsonObject();
                var path = allocation.get("path").getAsString();
                int root = Integer.parseInt(path.split("/", 2)[0]);
                if (root < 0 || root > 53) throw new IllegalArgumentException("Invalid reserved physical slot");
                allocated
                        .computeIfAbsent(allocation.get("source").getAsString(), ignored -> new HashSet<>())
                        .add(root);
            }
            if (allocated.isEmpty()) throw new IllegalArgumentException("Empty physical allocation");
        }
        allocated.replaceAll((source, slots) -> Set.copyOf(slots));
        var kits = new LinkedHashMap<String, List<TemplateSlot>>();
        if (o.has("kits") && o.get("kits").isJsonObject()) {
            for (var entry : o.getAsJsonObject("kits").entrySet()) {
                try {
                    var slots = new ArrayList<TemplateSlot>();
                    for (var se : entry.getValue().getAsJsonObject().getAsJsonArray("slots")) {
                        var s = se.getAsJsonObject();
                        int slot = s.get("slot").getAsBigDecimal().intValueExact();
                        String key = s.get("key").getAsString();
                        int count = s.get("count").getAsBigDecimal().intValueExact();
                        if (slot < 0 || slot >= 27 || key.isEmpty() || count < 1)
                            throw new IllegalArgumentException("Invalid kit template slot");
                        slots.add(new TemplateSlot(slot, key, count));
                    }
                    if (slots.isEmpty()) throw new IllegalArgumentException("Empty kit template");
                    slots.sort(java.util.Comparator.comparingInt(TemplateSlot::slot));
                    kits.put(entry.getKey(), List.copyOf(slots));
                } catch (RuntimeException invalid) {
                    // A broken template is left out; its kit lines report the missing template.
                }
            }
        }
        var crafts = new ArrayList<CraftJob>();
        var required = new LinkedHashMap<String, Integer>();
        for (Line line : lines) if (line.type().equals(CRAFT)) required.merge(line.key(), line.count(), Math::addExact);
        if (!required.isEmpty()) {
            if (!o.has("craftPlans") || !o.get("craftPlans").isJsonArray())
                throw new IllegalArgumentException("Missing pinned craft plans");
            var planned = new LinkedHashMap<String, Integer>();
            for (JsonElement e : o.getAsJsonArray("craftPlans")) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("Invalid craft plan");
                JsonObject c = e.getAsJsonObject();
                String item = c.get("item").getAsString();
                int count = c.get("count").getAsInt();
                if (!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                        || count < 1
                        || !c.has("recipeVersion")
                        || !"26.3".equals(c.get("recipeVersion").getAsString()))
                    throw new IllegalArgumentException("Unsupported craft");
                var materials = new LinkedHashMap<String, Integer>();
                for (var m : c.getAsJsonObject("materials").entrySet()) {
                    int quantity = m.getValue().getAsInt();
                    if (!m.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || quantity < 1)
                        throw new IllegalArgumentException("Invalid craft material");
                    materials.put(m.getKey(), quantity);
                }
                if (materials.isEmpty()) throw new IllegalArgumentException("Missing craft materials");
                crafts.add(new CraftJob(item, count, Map.copyOf(materials)));
                planned.merge(item, count, Math::addExact);
            }
            if (!planned.equals(required)) throw new IllegalArgumentException("Craft plans differ from order lines");
        }
        return new Order(
                id,
                lines,
                parseNames(o),
                Map.copyOf(kits),
                Map.copyOf(guards),
                Map.copyOf(allocated),
                List.copyOf(crafts));
    }

    private static boolean isText(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** A malformed frozen label is a protocol error, never permission to use a legacy name. */
    private static Names parseNames(JsonObject o) {
        var label = o.get("packageLabel");
        if (label != null && !label.isJsonNull()) {
            try {
                var totalValue = o.get("packageTotal");
                if (!label.isJsonObject() || totalValue == null || !totalValue.isJsonPrimitive()
                        || !totalValue.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("Missing frozen package total or label");
                int total = totalValue.getAsBigDecimal().intValueExact();
                var generated = com.porter.wesort.data.PackageLabel.generate(label.getAsJsonObject(), total);
                List<String> list = new ArrayList<>();
                for (JsonElement e : generated) list.add(e.getAsString());
                Names names = Names.of(Names.EACH, "", list);
                if (names == null) throw new IllegalArgumentException("Empty frozen package labels");
                return names;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid frozen package label; order must not execute", invalid);
            }
        }
        try {
            if (!o.has("names") || !o.get("names").isJsonObject()) return null;
            JsonObject n = o.getAsJsonObject("names");
            String mode = n.has("mode") && n.get("mode").isJsonPrimitive()
                    ? n.get("mode").getAsString()
                    : "";
            if (Names.ALL.equals(mode)) {
                return Names.of(mode, isText(n.get("name")) ? n.get("name").getAsString() : "", null);
            }
            if (Names.EACH.equals(mode) && n.has("names") && n.get("names").isJsonArray()) {
                List<String> list = new ArrayList<>();
                // Only JSON strings are names: a number or boolean entry is one shulker left unnamed
                for (JsonElement e : n.getAsJsonArray("names")) {
                    if (list.size() >= MAX_NAMES) break;
                    list.add(isText(e) ? e.getAsString() : "");
                }
                return Names.of(mode, "", list);
            }
        } catch (RuntimeException ex) {
            // Unreadable names: the shulkers are delivered unnamed
        }
        return null;
    }

    /** The total asked for per line key and type, lines with the same type and key merged. */
    public Map<String, Integer> requested() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Line l : lines) out.merge(l.key, l.count, Integer::sum);
        return out;
    }

    /**
     * Splits a total of one item into slot loads: full stacks, the remainder last.
     */
    public static List<Lot> expand(String key, int total, int maxStack) {
        List<Lot> lots = new ArrayList<>();
        int max = Math.max(1, maxStack);
        for (int left = total; left > 0; left -= max) lots.add(new Lot(key, Math.min(max, left)));
        return lots;
    }

    /**
     * Packs slot loads into shulkers of 27 slots, any mix of items allowed. Every load takes a slot, so the fewest
     * shulkers is ceil(slots / 27). Loads of one item are kept together, the items with the most loads first, so a shulker
     * mostly holds few kinds of item and needs fewer trips.
     */
    public static List<List<Lot>> pack(List<Lot> lots) {
        Map<String, List<Lot>> byKey = new TreeMap<>();
        for (Lot lot : lots)
            byKey.computeIfAbsent(lot.key, k -> new ArrayList<>()).add(lot);

        List<List<Lot>> groups = new ArrayList<>(byKey.values());
        groups.sort((a, b) -> b.size() - a.size());

        List<Lot> ordered = new ArrayList<>();
        for (List<Lot> g : groups) {
            g.sort((a, b) -> b.count - a.count);
            ordered.addAll(g);
        }

        List<List<Lot>> bins = new ArrayList<>();
        for (Lot lot : ordered) {
            if (bins.isEmpty() || bins.get(bins.size() - 1).size() >= SLOTS) bins.add(new ArrayList<>());
            bins.get(bins.size() - 1).add(lot);
        }
        return bins;
    }
}
