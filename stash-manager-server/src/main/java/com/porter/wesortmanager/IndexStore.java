package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stash indexes and heartbeat state, served from memory with legacy JSON mirrors.
 * Physical observations and their index snapshots are authoritative in StockLedger.
 * Each index is kept as its serialized bytes (served as-is) plus a few derived fields; the JSON tree is not retained.
 */
public final class IndexStore {
    public static final long ONLINE_WINDOW_MS = 45_000;
    public static final int MAX_STASHES = 64;
    private static final int MAX_NAME_PART = 64;

    private final Path dir;
    private StockLedger stock;
    private AutomationGate automation;

    synchronized AutomationGate automation() throws IOException {
        if (automation == null) automation = new AutomationGate(stock());
        return automation;
    }

    private final Set<StashKey> physicalIndexes = ConcurrentHashMap.newKeySet();
    private final Map<StashKey, Long> freshnessRequests = new ConcurrentHashMap<>();

    synchronized StockLedger stock() throws IOException {
        if (stock == null) stock = new StockLedger(dir.getParent());
        return stock;
    }

    private final CreativeTabs tabs;
    private final Map<StashKey, Stash> stashes = new ConcurrentHashMap<>();
    private final Object createLock = new Object();

    public record StashKey(String server, String stash) {}

    /** Stash-level blockers the web UI never surfaces: stash name -> alert codes present. */
    Map<String, List<String>> stashBlockers() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : stashes.entrySet()) {
            var s = e.getValue();
            synchronized (s) {
                List<String> codes = new ArrayList<>();
                if (s.orderBlocker != null) codes.add("ORDER_BLOCKED");
                if (s.storage != null && s.storage.chests() > 0 && s.storage.full() >= s.storage.chests())
                    codes.add("DELIVERY_FULL");
                if (s.rescanBlocked != null) codes.add("RESCAN_BLOCKED");
                if (!codes.isEmpty()) out.put(e.getKey().stash(), codes);
            }
        }
        return out;
    }

    /** What the icon proxy needs to fetch an id's icon. */
    public record IconInfo(String englishName, String mcVersion) {}

    /** A prepared index plus the bytes to write to disk (as the bot sent it, without the creative-tab fallback). */
    private record Built(Prepared p, byte[] disk) {}

    /** A stored index ready to serve: the bytes and their ETag. */
    public record IndexBytes(byte[] bytes, byte[] gzip, String etag) {}

    /** Thrown when a new (server, stash) would go past {@link #MAX_STASHES}. */
    public static final class FullException extends Exception {
        FullException() {
            super("too many stashes (limit " + MAX_STASHES + ")");
        }
    }

    /** An index after validation, merging and serialization, built outside any lock. */
    private record Prepared(
            String server,
            String stash,
            String player,
            String indexHash,
            long receivedAt,
            int itemTypes,
            long itemTotal,
            byte[] bytes,
            byte[] gzip,
            String etag,
            Map<String, String> idNames,
            String mcVersion,
            OrderCounts orderCounts,
            long[] containers,
            Map<String, CreativeTabs.Entry> tabOrder) {}

    /**
     * What an order may take from one index. items: item key -> count; itemIds: item key -> item id;
     * shulkers: shulker group key -> count; filledByItemKey: shulker item key -> filled shulkers of it (from
     * shulkers[].itemKey); filledById: the same by item id, for groups without itemKey (older indexes).
     */
    public record OrderCounts(
            Map<String, Long> items,
            Map<String, String> itemIds,
            Map<String, Integer> maxStacks,
            Map<String, Long> shulkers,
            Map<String, Long> filledByItemKey,
            Map<String, Long> filledById) {
        /**
         * Units the index holds for a line, before reservations; -1 when the key isn't in the index.
         * An item line for a shulker box means EMPTY shulkers: its count minus the filled ones of that id.
         */
        public long total(String type, String key) {
            if (type.equals("shulker")) return shulkers.getOrDefault(key, -1L);
            Long count = items.get(key);
            if (count == null) return -1;
            String id = itemIds.get(key);
            if (id != null && isShulkerBox(id)) {
                long filled = filledByItemKey.getOrDefault(key, 0L) + filledById.getOrDefault(id, 0L);
                return Math.max(0, count - filled);
            }
            return count;
        }

        /** The item's stack size from the index (64 when it gave none, at least 1). */
        public int maxStack(String key) {
            return maxStacks.getOrDefault(key, 64);
        }

        /** Whether an item line for this key means empty shulkers. */
        public boolean isShulkerBoxKey(String key) {
            String id = itemIds.get(key);
            return id != null && isShulkerBox(id);
        }

        static boolean isShulkerBox(String id) {
            return id.startsWith("minecraft:") && id.endsWith("shulker_box");
        }
    }

    /** The delivery container the bot reported in its heartbeat. */
    public record Delivery(int x, int y, int z, String dimension) {}

    /**
     * Whether the bot can name shulkers: an anvil is set (and looks like one), its XP level (-1 = unknown), and where
     * its anvil comes from ("set", "found", "placed" or "none"; null for an older bot that doesn't report it).
     */
    public record Naming(boolean anvil, int xpLevels, String anvilSource) {
        public static final java.util.Set<String> ANVIL_SOURCES =
                java.util.Set.of("set", "manager", "found", "placed", "none");

        public Naming(boolean anvil, int xpLevels) {
            this(anvil, xpLevels, null);
        }
    }

    /**
     * Whether the bot can craft: auto-crafting is on, a crafting table is set (and, if loaded, really is one), and where
     * its table comes from ("set", "found", "placed", "crafted" or "none"; null for an older bot that doesn't report it).
     */
    public record Crafting(boolean enabled, boolean table, String tableSource) {
        public static final java.util.Set<String> TABLE_SOURCES =
                java.util.Set.of("set", "manager", "found", "placed", "crafted", "none");

        public Crafting(boolean enabled, boolean table) {
            this(enabled, table, null);
        }
    }

    /**
     * The delivery storage (plugin 1.9.0 / addon 0.4.0-dev.5 heartbeat "deliveryStorage"): how many delivery chests
     * there are, how many are full, whether more can be put up, and why not. Never positions.
     */
    public record DeliveryStorage(boolean autoExpand, int chests, int full, boolean canExpand, String blocked) {}

    /**
     * The bot-status fields of one heartbeat. A field is only changed when the heartbeat carried it
     * (hasDelivery / hasBlocker / hasNaming / hasCrafting / hasChests), so an older addon that never sends them leaves them null.
     */
    public record BotStatus(
            boolean hasDelivery,
            Delivery delivery,
            boolean hasBlocker,
            String orderBlocker,
            long rescanDone,
            boolean hasRescanBlocked,
            String rescanBlocked,
            boolean hasNaming,
            Naming naming,
            boolean hasCrafting,
            Crafting crafting,
            boolean hasStorage,
            DeliveryStorage storage,
            boolean hasChests,
            List<ChestStates.Chest> chests) {
        public static final BotStatus NONE = new BotStatus(
                false, null, false, null, -1, false, null, false, null, false, null, false, null, false, null);

        public BotStatus(
                boolean hasDelivery,
                Delivery delivery,
                boolean hasBlocker,
                String orderBlocker,
                long rescanDone,
                boolean hasRescanBlocked,
                String rescanBlocked,
                boolean hasNaming,
                Naming naming,
                boolean hasCrafting,
                Crafting crafting) {
            this(
                    hasDelivery,
                    delivery,
                    hasBlocker,
                    orderBlocker,
                    rescanDone,
                    hasRescanBlocked,
                    rescanBlocked,
                    hasNaming,
                    naming,
                    hasCrafting,
                    crafting,
                    false,
                    null,
                    false,
                    null);
        }
    }

    /** A stash's stored player and when it was last seen, for adopting owners on load. */
    public record PlayerSeen(String player, long seen) {}

    /** Every stash's stored player (from its index or heartbeats). */
    public Map<StashKey, PlayerSeen> players() {
        Map<StashKey, PlayerSeen> out = new HashMap<>();
        for (Map.Entry<StashKey, Stash> e : stashes.entrySet()) {
            Stash s = e.getValue();
            synchronized (s) {
                if (s.player != null) out.put(e.getKey(), new PlayerSeen(s.player, s.lastSeen));
            }
        }
        return out;
    }

    /** Whether the server holds anything (index or heartbeat state) for the pair. */
    /** Every server address the store knows a stash for. */
    public java.util.Set<String> servers() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (StashKey k : stashes.keySet()) out.add(k.server());
        return out;
    }

    public boolean has(String server, String stash) {
        return stashes.containsKey(new StashKey(server, stash));
    }

    /** What a heartbeat reply needs: whether to push the index, and the unacknowledged rescan id (or null). */
    public record HeartbeatResult(boolean needIndex, Long rescan) {}

    /** One (server, stash). The monitor guards the state fields; writeLock orders disk writes. */
    private static final class Stash {
        final String server;
        final String stash;
        String player;
        long lastSeen;
        Delivery delivery;
        Naming naming;
        Crafting crafting;
        DeliveryStorage storage;
        String orderBlocker;
        long rescanRequested; // latest rescan request id, 0 = none
        long rescanDone; // highest id the bot acknowledged
        String rescanBlocked;
        Prepared index;
        long version;

        final Object writeLock = new Object();
        long writtenVersion; // guarded by writeLock

        Stash(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    public IndexStore(Path dataDir) {
        this(dataDir, CreativeTabs.bundled());
    }

    IndexStore(Path dataDir, CreativeTabs tabs) {
        this(dataDir, tabs, null);
    }

    IndexStore(Path dataDir, CreativeTabs tabs, StockLedger stock) {
        this.stock = stock;
        this.dir = dataDir.resolve("indexes");
        this.tabs = tabs;
    }

    // ---------- loading ----------

    public void load() throws IOException {
        Files.createDirectories(dir);
        record Loaded(Path file, Prepared p, byte[] disk) {}
        Map<StashKey, Loaded> newest = new HashMap<>();
        List<Path> superseded = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                    String error = validate(json);
                    if (error != null) {
                        System.err.println("Skipping " + file.getFileName() + ": " + error);
                        continue;
                    }
                    JsonObject index = json.getAsJsonObject();
                    long receivedAt = isNumber(index, "receivedAt")
                            ? index.get("receivedAt").getAsLong()
                            : Files.getLastModifiedTime(file).toMillis();
                    Built b = prepare(index, receivedAt, tabs);
                    Prepared p = b.p;
                    StashKey key = new StashKey(p.server, p.stash);
                    Loaded old = newest.get(key);
                    if (old == null || old.p.receivedAt < p.receivedAt) {
                        if (old != null) superseded.add(old.file);
                        newest.put(key, new Loaded(file, p, b.disk));
                    } else {
                        superseded.add(file);
                    }
                } catch (RuntimeException | IOException e) {
                    System.err.println("Skipping " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        java.util.Set<Path> keep = new java.util.HashSet<>();
        for (Loaded l : newest.values()) {
            if (stashes.size() >= MAX_STASHES) {
                System.err.println("Not loading " + l.file.getFileName() + ": more than " + MAX_STASHES + " stashes");
                continue;
            }
            Stash s = new Stash(l.p.server, l.p.stash);
            s.index = l.p;
            s.player = l.p.player;
            s.lastSeen = l.p.receivedAt;
            stashes.put(new StashKey(s.server, s.stash), s);
            // Files from before the hash suffix (or otherwise misnamed) are moved to the current name.
            Path expected = dir.resolve(fileName(s.server, s.stash));
            keep.add(expected.getFileName());
            if (!l.file.getFileName().equals(expected.getFileName())) {
                AtomicFiles.write(expected, l.disk);
                superseded.add(l.file);
            }
        }
        for (Path p : superseded) {
            if (keep.contains(p.getFileName())) continue;
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("Could not delete old index file " + p.getFileName() + ": " + e.getMessage());
            }
        }
        {
            for (var index : stock().indexes()) {
                long receivedAt = index.get("receivedAt").getAsLong();
                Prepared p = prepare(index, receivedAt, tabs).p;
                Stash loaded = new Stash(p.server, p.stash);
                loaded.index = p;
                loaded.player = p.player;
                loaded.lastSeen = receivedAt;
                stashes.put(new StashKey(p.server, p.stash), loaded);
                physicalIndexes.add(new StashKey(p.server, p.stash));
            }
        }
    }

    public int size() {
        return stashes.size();
    }

    private Stash getOrCreate(String server, String stash) throws FullException {
        StashKey key = new StashKey(server, stash);
        Stash s = stashes.get(key);
        if (s != null) return s;
        synchronized (createLock) {
            s = stashes.get(key);
            if (s != null) return s;
            if (stashes.size() >= MAX_STASHES) evictOne();
            if (stashes.size() >= MAX_STASHES) throw new FullException();
            s = new Stash(server, stash);
            stashes.put(key, s);
            return s;
        }
    }

    /** Makes room by dropping an offline stash that never sent an index (heartbeat-only entries). */
    private void evictOne() {
        long now = System.currentTimeMillis();
        for (Map.Entry<StashKey, Stash> e : stashes.entrySet()) {
            Stash s = e.getValue();
            synchronized (s) {
                if (s.index == null && now - s.lastSeen >= ONLINE_WINDOW_MS) {
                    stashes.remove(e.getKey(), s);
                    return;
                }
            }
        }
    }

    // ---------- validation ----------

    /** Returns null when the index has the v1 shape, otherwise a short reason. */
    public static String validate(JsonElement element) {
        if (element == null || !element.isJsonObject()) return "body must be a JSON object";
        JsonObject o = element.getAsJsonObject();
        if (!isNumber(o, "version") || o.get("version").getAsDouble() != 1) return "version must be 1";
        if (!isNonEmptyString(o, "server")) return "server must be a non-empty string";
        if (!isNonEmptyString(o, "stash")) return "stash must be a non-empty string";
        for (String field : new String[] {"player", "mcVersion", "dimension", "indexHash"}) {
            if (o.has(field) && !o.get(field).isJsonNull() && !isString(o, field)) return field + " must be a string";
        }
        // Missing mcVersion remains valid for API v1; it is interpreted as 26.3.
        // An explicitly older version is always unsupported.
        String mcVersion = stringOrNull(o, "mcVersion");
        if (mcVersion != null && !IconService.DEFAULT_MC.equals(mcVersion)) return "mcVersion must be 26.3";
        if (o.has("generatedAt") && !o.get("generatedAt").isJsonNull() && !isNumber(o, "generatedAt"))
            return "generatedAt must be a number";
        if (o.has("containers") && !o.get("containers").isJsonNull()) {
            if (!o.get("containers").isJsonObject()) return "containers must be an object";
            JsonObject c = o.getAsJsonObject("containers");
            for (String field : new String[] {"known", "total", "stale"}) {
                if (c.has(field) && !isNumber(c, field)) return "containers." + field + " must be a number";
            }
        }
        if (!o.has("items") || !o.get("items").isJsonArray()) return "items must be an array";
        JsonArray items = o.getAsJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonElement e = items.get(i);
            if (!e.isJsonObject()) return "items[" + i + "] must be an object";
            JsonObject item = e.getAsJsonObject();
            if (!isNonEmptyString(item, "key")) return "items[" + i + "].key must be a non-empty string";
            if (!isNonEmptyString(item, "id")) return "items[" + i + "].id must be a non-empty string";
            for (String field : new String[] {"name", "englishName"}) {
                if (item.has(field) && !isString(item, field)) return "items[" + i + "]." + field + " must be a string";
            }
            if (!isNumber(item, "count")) return "items[" + i + "].count must be a number";
            if (item.has("maxStack") && !isNumber(item, "maxStack"))
                return "items[" + i + "].maxStack must be a number";
            if (item.has("enchanted")
                    && !(item.get("enchanted").isJsonPrimitive()
                            && item.get("enchanted").getAsJsonPrimitive().isBoolean())) {
                return "items[" + i + "].enchanted must be a boolean";
            }
            if (item.has("tab")
                    && !item.get("tab").isJsonNull()
                    && (!isString(item, "tab") || item.get("tab").getAsString().length() > 40))
                return "items[" + i + "].tab must be a short string";
            if (item.has("tabOrder") && !item.get("tabOrder").isJsonNull() && !isNumber(item, "tabOrder"))
                return "items[" + i + "].tabOrder must be a number";
            if (item.has("details")) {
                if (!item.get("details").isJsonArray()) return "items[" + i + "].details must be an array";
                for (JsonElement d : item.getAsJsonArray("details")) {
                    if (!(d.isJsonPrimitive() && d.getAsJsonPrimitive().isString()))
                        return "items[" + i + "].details must hold strings";
                }
            }
        }
        if (o.has("shulkers") && !o.get("shulkers").isJsonNull()) {
            if (!o.get("shulkers").isJsonArray()) return "shulkers must be an array";
            JsonArray shulkers = o.getAsJsonArray("shulkers");
            for (int i = 0; i < shulkers.size(); i++) {
                String at = "shulkers[" + i + "]";
                if (!shulkers.get(i).isJsonObject()) return at + " must be an object";
                JsonObject g = shulkers.get(i).getAsJsonObject();
                if (!isNonEmptyString(g, "key") || g.get("key").getAsString().length() > 64)
                    return at + ".key must be a short non-empty string";
                if (!isNonEmptyString(g, "id")) return at + ".id must be a non-empty string";
                if (g.has("name") && !g.get("name").isJsonNull() && !isString(g, "name"))
                    return at + ".name must be a string";
                if (g.has("itemKey") && !g.get("itemKey").isJsonNull() && !isString(g, "itemKey"))
                    return at + ".itemKey must be a string";
                if (!isNumber(g, "count")) return at + ".count must be a number";
                if (g.has("contents") && !g.get("contents").isJsonNull()) {
                    if (!g.get("contents").isJsonArray()) return at + ".contents must be an array";
                    JsonArray contents = g.getAsJsonArray("contents");
                    if (contents.size() > 27) return at + ".contents has more than 27 slots";
                    for (int j = 0; j < contents.size(); j++) {
                        String cat = at + ".contents[" + j + "]";
                        if (!contents.get(j).isJsonObject()) return cat + " must be an object";
                        JsonObject c = contents.get(j).getAsJsonObject();
                        if (!isNumber(c, "slot")
                                || c.get("slot").getAsDouble() < 0
                                || c.get("slot").getAsDouble() > 26) return cat + ".slot must be 0..26";
                        if (!isNonEmptyString(c, "key")) return cat + ".key must be a non-empty string";
                        if (!isNumber(c, "count")) return cat + ".count must be a number";
                    }
                }
            }
        }
        return null;
    }

    private static boolean isString(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isNonEmptyString(JsonObject o, String field) {
        return isString(o, field) && !o.get(field).getAsString().isEmpty();
    }

    private static boolean isNumber(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        double d = e.getAsDouble();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static String stringOrNull(JsonObject o, String field) {
        return isString(o, field) ? o.get(field).getAsString() : null;
    }

    // ---------- preparing ----------

    /**
     * Merges items with the same key (counts are summed, the first entry's other fields kept), adds receivedAt,
     * serializes, and derives the fields the server needs. index must already pass {@link #validate}.
     * The disk bytes are the index as the bot sent it; the served bytes also have the creative-tab fallback
     * filled in (PROTOCOL.md, "creative tabs for bots that don't send them"), so a newer table fixes stored indexes.
     */
    private static Built prepare(JsonObject index, long receivedAt, CreativeTabs tabs) {
        JsonArray items = index.getAsJsonArray("items");
        Map<String, JsonObject> byKey = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (JsonElement e : items) {
            JsonObject item = e.getAsJsonObject();
            String key = item.get("key").getAsString();
            long count = Math.max(0, item.get("count").getAsLong());
            byKey.putIfAbsent(key, item);
            counts.merge(key, count, Long::sum);
        }
        if (byKey.size() != items.size()) {
            JsonArray merged = new JsonArray(byKey.size());
            for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
                JsonObject item = e.getValue();
                item.addProperty("count", counts.get(e.getKey()));
                merged.add(item);
            }
            index.add("items", merged);
        }

        long total = 0;
        Map<String, String> idNames = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            total += counts.get(e.getKey());
            JsonObject item = e.getValue();
            String id = item.get("id").getAsString();
            String english = stringOrNull(item, "englishName");
            String existing = idNames.get(id);
            if (existing == null || (existing.isEmpty() && english != null && !english.isEmpty())) {
                idNames.put(id, english == null ? "" : english);
            }
        }

        Map<String, Long> itemCounts = new HashMap<>(counts);
        Map<String, String> itemIds = new HashMap<>();
        Map<String, Integer> maxStacks = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : byKey.entrySet()) {
            JsonObject item = e.getValue();
            itemIds.put(e.getKey(), item.get("id").getAsString());
            // Same rule as the page: floor, at least 1, 64 when missing. (int) of a huge double saturates.
            int max = isNumber(item, "maxStack")
                    ? (int) Math.max(1, Math.floor(item.get("maxStack").getAsDouble()))
                    : 64;
            maxStacks.put(e.getKey(), max);
        }
        Map<String, Long> shulkerCounts = new HashMap<>();
        Map<String, Long> filledByItemKey = new HashMap<>();
        Map<String, Long> filledById = new HashMap<>();
        if (index.has("shulkers") && index.get("shulkers").isJsonArray()) {
            for (JsonElement e : index.getAsJsonArray("shulkers")) {
                JsonObject g = e.getAsJsonObject();
                long n = Math.max(0, g.get("count").getAsLong());
                shulkerCounts.merge(g.get("key").getAsString(), n, Long::sum);
                String itemKey = stringOrNull(g, "itemKey");
                if (itemKey != null && !itemKey.isEmpty()) filledByItemKey.merge(itemKey, n, Long::sum);
                else filledById.merge(g.get("id").getAsString(), n, Long::sum);
            }
        }
        OrderCounts orderCounts = new OrderCounts(
                Map.copyOf(itemCounts),
                Map.copyOf(itemIds),
                Map.copyOf(maxStacks),
                Map.copyOf(shulkerCounts),
                Map.copyOf(filledByItemKey),
                Map.copyOf(filledById));

        index.addProperty("receivedAt", receivedAt);
        byte[] disk = Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = tabs.fill(index) > 0 ? Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8) : disk;
        Map<String, CreativeTabs.Entry> tabOrder = new HashMap<>();
        for (JsonElement e : index.getAsJsonArray("items")) {
            JsonObject item = e.getAsJsonObject();
            JsonElement tab = item.get("tab");
            if (tab == null
                    || !tab.isJsonPrimitive()
                    || !tab.getAsJsonPrimitive().isString()) continue;
            int order = isNumber(item, "tabOrder")
                    ? (int) Math.max(
                            Integer.MIN_VALUE,
                            Math.min(Integer.MAX_VALUE, item.get("tabOrder").getAsDouble()))
                    : 1_000_000;
            tabOrder.putIfAbsent(item.get("key").getAsString(), new CreativeTabs.Entry(tab.getAsString(), order));
        }
        String mc = stringOrNull(index, "mcVersion");
        if (mc == null) mc = IconService.DEFAULT_MC;
        return new Built(
                new Prepared(
                        index.get("server").getAsString(),
                        index.get("stash").getAsString(),
                        stringOrNull(index, "player"),
                        stringOrNull(index, "indexHash"),
                        receivedAt,
                        byKey.size(),
                        total,
                        bytes,
                        Http.gzip(bytes),
                        "\"" + sha1Hex(bytes).substring(0, 20) + "\"",
                        Map.copyOf(idNames),
                        mc,
                        orderCounts,
                        containers(index),
                        Map.copyOf(tabOrder)),
                disk);
    }

    /** {known, total, stale} from the index's containers object (missing numbers are 0), or null. */
    private static long[] containers(JsonObject index) {
        if (!index.has("containers") || !index.get("containers").isJsonObject()) return null;
        JsonObject c = index.getAsJsonObject("containers");
        long[] out = new long[3];
        String[] fields = {"known", "total", "stale"};
        for (int i = 0; i < 3; i++)
            out[i] = isNumber(c, fields[i]) ? Math.max(0, c.get(fields[i]).getAsLong()) : 0;
        return out;
    }

    // ---------- updates ----------

    /**
     * Commits server-observed physical inventory to SQLite before updating the
     * in-memory projection and optional JSON cache. A failed cache write must not
     * turn a committed physical observation into a failed agent acknowledgement.
     */
    public synchronized void putIndex(JsonObject index) throws IOException, FullException {
        putIndex(index, null);
    }

    synchronized void putIndex(JsonObject index, String server) throws IOException, FullException {
        String versionError = validate(index);
        if (versionError != null) throw new IllegalArgumentException(versionError);
        if (server == null) server = index.get("server").getAsString();
        long now = System.currentTimeMillis();
        // Bound index count before committing the authoritative physical observation.
        String stash = index.get("stash").getAsString();
        getOrCreate(server, stash);
        index.addProperty("receivedAt", now);
        boolean physical = index.has("physicalSources");
        if (physical) {
            stock().accept(index, server);
            physicalIndexes.add(new StashKey(server, stash));
        }
        // Physical IDs are bound to the wire server; only the Manager projection uses the storage key.
        if (!server.equals(index.get("server").getAsString())) {
            index = index.deepCopy();
            index.addProperty("server", server);
        }
        Built b = prepare(index, now, tabs);
        Prepared p = b.p;
        Stash s = getOrCreate(p.server, p.stash);
        long version;
        synchronized (s) {
            s.index = p;
            if (p.player != null) s.player = p.player;
            s.lastSeen = now;
            version = ++s.version;
        }
        synchronized (s.writeLock) {
            // A newer index may already be on disk; never let an older one overwrite it.
            if (version <= s.writtenVersion) return;
            Path mirror = dir.resolve(fileName(s.server, s.stash));
            try {
                AtomicFiles.write(mirror, b.disk);
                s.writtenVersion = version;
            } catch (IOException failure) {
                if (!physical) throw failure;
                // The snapshot and physical stock revision were already committed
                // to guild.sqlite. Do not ask the agent to retry that accepted
                // observation just because a redundant JSON mirror is unwritable.
                System.err.println("Could not update optional physical index mirror " + mirror.getFileName() + ": "
                        + failure.getMessage());
            }
        }
    }

    /** Records a heartbeat and returns whether the addon should push its index. */
    public HeartbeatResult heartbeat(String server, String stash, String player, String indexHash, BotStatus status)
            throws FullException {
        Stash s = getOrCreate(server, stash);
        synchronized (s) {
            s.lastSeen = System.currentTimeMillis();
            if (player != null) s.player = player;
            if (status.hasDelivery()) s.delivery = status.delivery();
            if (status.hasBlocker()) s.orderBlocker = status.orderBlocker();
            if (status.hasNaming()) s.naming = status.naming();
            if (status.hasCrafting()) s.crafting = status.crafting();
            if (status.hasStorage()) s.storage = status.storage();
            if (status.hasRescanBlocked()) s.rescanBlocked = status.rescanBlocked();
            if (status.rescanDone() > s.rescanDone) s.rescanDone = status.rescanDone();
            if (stock != null)
                try {
                    var stale = stock.sources().stream()
                            .filter(source -> source.server().equals(server)
                                    && source.stash().equals(stash)
                                    && (source.status().equals("STALE_AGE")
                                            || source.status().equals("STALE_STARTUP")
                                            || source.status().equals("STALE")
                                            || source.status().equals("UNKNOWN")))
                            .toList();
                    long staleRevision = stale.stream()
                            .mapToLong(StockLedger.Source::revision)
                            .max()
                            .orElse(0);
                    var key = new StashKey(server, stash);
                    if (physicalIndexes.contains(key) && !freshnessRequests.containsKey(key)
                            || !stale.isEmpty() && staleRevision > freshnessRequests.getOrDefault(key, -1L)) {
                        freshnessRequests.put(key, staleRevision);
                        requestRescan(server, stash);
                    }
                } catch (IOException ignored) {
                    /* Availability queries fail closed if SQLite is unavailable. */
                }
            boolean needIndex = s.index == null || indexHash == null || !indexHash.equals(s.index.indexHash);
            return new HeartbeatResult(needIndex, rescanPending(s));
        }
    }

    private static Long rescanPending(Stash s) {
        return s.rescanRequested > 0 && s.rescanDone < s.rescanRequested ? s.rescanRequested : null;
    }

    /**
     * Records a manual rescan request and returns its id, or -1 when the server knows no such stash. Ids start at the
     * current Unix time in seconds, so they keep increasing across server restarts (an addon that acknowledged an id
     * before a restart doesn't swallow the next request) and still fit a 32-bit int.
     */
    public long requestRescan(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return -1;
        synchronized (s) {
            long id = Math.max(Math.max(s.rescanRequested, s.rescanDone) + 1, System.currentTimeMillis() / 1000);
            s.rescanRequested = id;
            return id;
        }
    }

    // ---------- reads ----------

    public JsonArray listJson() {
        long now = System.currentTimeMillis();
        List<Stash> list = new ArrayList<>(stashes.values());
        list.sort(Comparator.comparing((Stash s) -> s.server).thenComparing(s -> s.stash));
        JsonArray out = new JsonArray();
        for (Stash s : list) {
            JsonObject o = new JsonObject();
            synchronized (s) {
                o.addProperty("server", s.server);
                o.addProperty("stash", s.stash);
                o.addProperty("player", s.player);
                o.addProperty("online", s.lastSeen > 0 && now - s.lastSeen < ONLINE_WINDOW_MS);
                o.addProperty("lastSeen", s.lastSeen > 0 ? s.lastSeen : null);
                o.addProperty("indexedAt", s.index != null ? s.index.receivedAt : null);
                o.addProperty("itemTypes", s.index != null ? s.index.itemTypes : 0);
                o.addProperty("itemTotal", s.index != null ? s.index.itemTotal : 0);
                if (s.delivery != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("x", s.delivery.x());
                    d.addProperty("y", s.delivery.y());
                    d.addProperty("z", s.delivery.z());
                    d.addProperty("dimension", s.delivery.dimension());
                    o.add("delivery", d);
                } else {
                    o.add("delivery", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.naming != null) {
                    JsonObject n = new JsonObject();
                    n.addProperty("anvil", s.naming.anvil());
                    n.addProperty("xpLevels", s.naming.xpLevels());
                    if (s.naming.anvilSource() != null) n.addProperty("anvilSource", s.naming.anvilSource());
                    else n.add("anvilSource", com.google.gson.JsonNull.INSTANCE);
                    o.add("naming", n);
                } else {
                    o.add("naming", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.crafting != null) {
                    JsonObject c = new JsonObject();
                    c.addProperty("enabled", s.crafting.enabled());
                    c.addProperty("table", s.crafting.table());
                    // Only from bots that report it (an older bot's entry stays {enabled, table}).
                    if (s.crafting.tableSource() != null) c.addProperty("tableSource", s.crafting.tableSource());
                    o.add("crafting", c);
                } else {
                    o.add("crafting", com.google.gson.JsonNull.INSTANCE);
                }
                if (s.storage != null) {
                    JsonObject ds = new JsonObject();
                    ds.addProperty("autoExpand", s.storage.autoExpand());
                    ds.addProperty("chests", s.storage.chests());
                    ds.addProperty("full", s.storage.full());
                    ds.addProperty("canExpand", s.storage.canExpand());
                    ds.addProperty("blocked", s.storage.blocked());
                    o.add("deliveryStorage", ds);
                } else {
                    o.add("deliveryStorage", com.google.gson.JsonNull.INSTANCE);
                }
                o.addProperty("orderBlocker", s.orderBlocker);
                o.addProperty("rescanPending", rescanPending(s));
                o.addProperty("rescanBlocked", s.rescanBlocked);
                long[] c = s.index != null ? s.index.containers : null;
                if (c != null) {
                    JsonObject co = new JsonObject();
                    co.addProperty("known", c[0]);
                    co.addProperty("total", c[1]);
                    co.addProperty("stale", c[2]);
                    o.add("containers", co);
                } else {
                    o.add("containers", com.google.gson.JsonNull.INSTANCE);
                }
            }
            out.add(o);
        }
        return out;
    }

    /** The stored index as served by GET /api/v1/index, or null. The bytes are shared: don't modify them. */
    public IndexBytes index(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : new IndexBytes(p.bytes, p.gzip, p.etag);
    }

    /** Every item id in one stash's index, with what the icon fetcher needs. Empty when there is no index. */
    public Map<String, IconInfo> iconIds(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return Map.of();
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? Map.of() : toIconInfo(p);
    }

    /** Order availability data for a stash's current index, or null when it has none. */
    /** Live agent-reported table readiness; absent/disabled means no new crafting orders. */
    boolean craftingReady(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return false;
        synchronized (s) {
            return s.crafting != null
                    && s.crafting.enabled()
                    && s.crafting.table()
                    // Demand-driven member orders must use an explicitly
                    // selected table, never an untracked auto-found station.
                    && ("manager".equals(s.crafting.tableSource()) || "set".equals(s.crafting.tableSource()));
        }
    }

    public OrderCounts orderCounts(String server, String stash) {
        return countsFor(server, stash, false);
    }

    /**
     * Last-verified counts, including stale-but-untouched sources (FRESH, STALE_AGE, STALE_STARTUP).
     * For queueing orders while no bot is online: verified counts read zero then, but the last
     * observation is still the best estimate. Explicitly invalidated (STALE), withdrawn, missing,
     * unreachable, protected and shared sources stay excluded, like topology-changed ones.
     * Ported from PR #89 (offline order queue).
     */
    public OrderCounts orderCountsIncludingStale(String server, String stash) {
        return countsFor(server, stash, true);
    }

    private OrderCounts countsFor(String server, String stash, boolean includeStale) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        if (p == null) return null;
        try {
            Map<String, Long> items = new HashMap<>(),
                    shulkers = new HashMap<>(),
                    filledKeys = new HashMap<>(),
                    filledIds = new HashMap<>();
            for (var source : stock().sources()) {
                if (!source.server().equals(server)
                        || !source.stash().equals(stash)
                        || !counted(source.status(), includeStale)
                        || source.shared()) continue;
                for (var e : source.value().getAsJsonArray("items")) {
                    var item = e.getAsJsonObject();
                    items.merge(item.get("key").getAsString(), item.get("count").getAsLong(), Math::addExact);
                }
                for (var e : source.value().getAsJsonArray("shulkers")) {
                    var box = e.getAsJsonObject();
                    long count = box.get("count").getAsLong();
                    shulkers.merge(box.get("key").getAsString(), count, Math::addExact);
                    if (box.has("itemKey")) filledKeys.merge(box.get("itemKey").getAsString(), count, Math::addExact);
                    else filledIds.merge(box.get("id").getAsString(), count, Math::addExact);
                }
            }
            // Known-but-unsafe keys remain visible with zero availability, not an invented fresh total.
            p.orderCounts.items.keySet().forEach(key -> items.putIfAbsent(key, 0L));
            p.orderCounts.shulkers.keySet().forEach(key -> shulkers.putIfAbsent(key, 0L));
            return new OrderCounts(
                    Map.copyOf(items),
                    p.orderCounts.itemIds,
                    p.orderCounts.maxStacks,
                    Map.copyOf(shulkers),
                    Map.copyOf(filledKeys),
                    Map.copyOf(filledIds));
        } catch (IOException | ArithmeticException e) {
            return null;
        }
    }

    /** Verified stock, plus — for offline queueing only — last-verified stock nothing has touched. */
    private static boolean counted(String status, boolean includeStale) {
        if (status.equals("FRESH")) return true;
        // STALE is written when a physical operation voids the observation; only aged-out
        // (STALE_AGE) or restart-fenced (STALE_STARTUP) observations are still last-known-good.
        return includeStale && (status.equals("STALE_AGE") || status.equals("STALE_STARTUP"));
    }

    /**
     * Each item key's creative tab and tabOrder in a stash's current index (with the creative-tab fallback filled in),
     * for keys that have a tab. Empty when there is no index.
     */
    Map<String, CreativeTabs.Entry> tabOrder(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return Map.of();
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? Map.of() : p.tabOrder;
    }

    /** The Minecraft version of a stash's current index, or null when it has none. */
    String mcVersion(String server, String stash) {
        Stash s = stashes.get(new StashKey(server, stash));
        if (s == null) return null;
        Prepared p;
        synchronized (s) {
            p = s.index;
        }
        return p == null ? null : p.mcVersion;
    }

    /** The mcVersion of any stored index (for icons that are not in an index), or the default. */
    public String anyMcVersion() {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null) return p.mcVersion;
        }
        return IconService.DEFAULT_MC;
    }

    /** Whether some stored index was made with this Minecraft version. */
    public boolean hasMcVersion(String mcVersion) {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null && p.mcVersion.equals(mcVersion)) return true;
        }
        return false;
    }

    /** Every item id in every stored index (the first index that has an id wins). */
    public Map<String, IconInfo> allIconIds() {
        Map<String, IconInfo> out = new LinkedHashMap<>();
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p != null) toIconInfo(p).forEach(out::putIfAbsent);
        }
        return out;
    }

    private static Map<String, IconInfo> toIconInfo(Prepared p) {
        Map<String, IconInfo> out = new LinkedHashMap<>();
        p.idNames.forEach((id, name) -> out.put(id, new IconInfo(name, p.mcVersion)));
        return out;
    }

    /** The English name and Minecraft version for an item id that appears in some stored index, or null. */
    public IconInfo iconInfo(String id) {
        for (Stash s : stashes.values()) {
            Prepared p;
            synchronized (s) {
                p = s.index;
            }
            if (p == null) continue;
            String name = p.idNames.get(id);
            if (name != null) return new IconInfo(name, p.mcVersion);
        }
        return null;
    }

    // ---------- file names ----------

    /** sanitized server + "__" + sanitized stash + "-" + 8 hex of sha1(server \0 stash), so sanitizing can't make two stashes collide. */
    static String fileName(String server, String stash) {
        String hash = sha1Hex((server + "\0" + stash).getBytes(StandardCharsets.UTF_8))
                .substring(0, 8);
        return sanitize(server) + "__" + sanitize(stash) + "-" + hash + ".json";
    }

    static String sanitize(String value) {
        String lower = Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < MAX_NAME_PART; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        // A leading dot would make hidden files or "..", so swap it.
        if (sb.length() > 0 && sb.charAt(0) == '.') sb.setCharAt(0, '_');
        return sb.length() == 0 ? "_" : sb.toString();
    }

    private static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
