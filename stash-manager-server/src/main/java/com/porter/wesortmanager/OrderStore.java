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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders per (server, stash), persisted to data/orders/<same name as the index file>.json.
 * Each stash's orders are guarded by their own lock; every change is written to disk before the call returns.
 * The status rules are the ones in PROTOCOL.md ("Orders").
 */
final class OrderStore {
    static final int MAX_LINES = 200;
    static final int MAX_OPEN = 20;
    static final int KEEP = 100;
    /** The anvil's limit for a custom name. */
    static final int MAX_NAME = 50;
    static final int MAX_PHASE = 100;
    static final int MAX_MESSAGE = 300;
    /** The most shulkers one order may need: one double chest (the addon's Order.MAX_BINS). */
    static final int MAX_SHULKERS = 54;
    static final int SHULKER_SLOTS = 27;
    static final Set<String> OPEN = Set.of("pending", "accepted", "in_progress");
    static final Set<String> FINISHED = Set.of("delivered", "failed", "cancelled");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Line(String type, String key, long count) {
        String ref() {
            return type + ":" + key;
        }
    }

    /** A rejected request: HTTP status, message, and the index of the offending line (or -1). */
    static final class OrderException extends Exception {
        final int status;
        final int line;

        OrderException(int status, String message, int line) {
            super(message);
            this.status = status;
            this.line = line;
        }
    }

    static final class Order {
        String id;
        String server;
        String stash;
        List<Line> lines;
        JsonObject sourceGuards; // Compatibility for orders created before slot allocation.
        List<PhysicalReservations.Allocation> allocations;
        long claimedAt;
        long releasedAt;
        String agent;
        JsonObject names; // normalized (see normalizeNames), or null for no naming
        long createdAt;
        long updatedAt;
        String status;
        String phase;
        JsonObject progress;
        String message;
        boolean cancelRequested;
        long cancelRequestedAt; // 0 when unknown (orders from before this field)
        JsonObject result;
        String placedById; // Users.OWNER_ID or a sub-user's id; null for orders from before accounts (the owner's)
        String placedByName; // the name at the time
        String retriedAs; // the order this one was placed again as (Retry, or the planner's "order part again"), or null

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("server", server);
            o.addProperty("stash", stash);
            JsonArray ls = new JsonArray();
            for (Line l : lines) {
                JsonObject lo = new JsonObject();
                lo.addProperty("type", l.type);
                lo.addProperty("key", l.key);
                lo.addProperty("count", l.count);
                ls.add(lo);
            }
            o.add("lines", ls);
            if (sourceGuards != null) o.add("sourceGuards", sourceGuards.deepCopy());
            if (allocations != null) o.add("allocations", Json.GSON.toJsonTree(allocations));
            if (claimedAt > 0) o.addProperty("claimedAt", claimedAt);
            if (agent != null) o.addProperty("agent", agent);
            if (releasedAt > 0) o.addProperty("releasedAt", releasedAt);
            o.add("names", names == null ? null : names.deepCopy());
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("status", status);
            o.addProperty("phase", phase);
            o.add("progress", progress == null ? null : progress.deepCopy());
            o.addProperty("message", message);
            o.addProperty("cancelRequested", cancelRequested);
            o.addProperty("cancelRequestedAt", cancelRequestedAt > 0 ? cancelRequestedAt : null);
            o.add("result", result == null ? null : result.deepCopy());
            if (retriedAs != null && !PENDING.equals(retriedAs)) o.addProperty("retriedAs", retriedAs);
            if (placedById == null) {
                o.add("placedBy", null);
            } else {
                JsonObject by = new JsonObject();
                by.addProperty("id", placedById);
                by.addProperty("name", placedByName);
                o.add("placedBy", by);
            }
            return o;
        }

        static Order fromJson(JsonObject o) {
            Order r = new Order();
            r.id = o.get("id").getAsString();
            r.sourceGuards = o.has("sourceGuards") ? o.getAsJsonObject("sourceGuards").deepCopy() : null;
            if (o.has("allocations")) r.allocations = List.of(Json.GSON.fromJson(
                o.get("allocations"), PhysicalReservations.Allocation[].class));
            r.claimedAt = o.has("claimedAt") ? o.get("claimedAt").getAsLong() : 0;
            r.agent = str(o, "agent");
            r.releasedAt = o.has("releasedAt") ? o.get("releasedAt").getAsLong() : 0;
            r.server = o.get("server").getAsString();
            r.stash = o.get("stash").getAsString();
            r.lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                r.lines.add(new Line(l.get("type").getAsString(), l.get("key").getAsString(), l.get("count").getAsLong()));
            }
            // "note" from orders stored before notes were removed is dropped here and never written again.
            try {
                r.names = normalizeNames(o.get("names"));
            } catch (OrderException e) {
                r.names = null;
            }
            r.createdAt = o.get("createdAt").getAsLong();
            r.updatedAt = o.get("updatedAt").getAsLong();
            r.status = o.get("status").getAsString();
            r.phase = str(o, "phase");
            r.progress = o.has("progress") && o.get("progress").isJsonObject() ? o.getAsJsonObject("progress") : null;
            r.message = str(o, "message");
            r.cancelRequested = o.has("cancelRequested") && o.get("cancelRequested").getAsBoolean();
            r.cancelRequestedAt = o.has("cancelRequestedAt") && o.get("cancelRequestedAt").isJsonPrimitive()
                ? o.get("cancelRequestedAt").getAsLong() : 0;
            r.result = o.has("result") && o.get("result").isJsonObject() ? o.getAsJsonObject("result") : null;
            r.retriedAs = str(o, "retriedAs");
            if (o.has("placedBy") && o.get("placedBy").isJsonObject()) {
                JsonObject by = o.getAsJsonObject("placedBy");
                r.placedById = str(by, "id");
                r.placedByName = str(by, "name");
            }
            return r;
        }

        private static String str(JsonObject o, String f) {
            JsonElement e = o.get(f);
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        }
    }

    /** One stash's orders, oldest first. */
    private static final class Book {
        final String server;
        final String stash;
        final List<Order> orders = new ArrayList<>();

        Book(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    private final Path dir;
    private final IndexStore index;
    private final Map<IndexStore.StashKey, Book> books = new ConcurrentHashMap<>();
    private final Map<String, Book> byOrderId = new ConcurrentHashMap<>();

    OrderStore(Path dataDir, IndexStore index) {
        this.dir = dataDir.resolve("orders");
        this.index = index;
    }

    void load() throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    Book b = book(root.get("server").getAsString(), root.get("stash").getAsString());
                    synchronized (b) {
                        for (JsonElement e : root.getAsJsonArray("orders")) {
                            Order o = Order.fromJson(e.getAsJsonObject());
                            b.orders.add(o);
                            byOrderId.put(o.id, b);
                        }
                        b.orders.sort((x, y) -> Long.compare(x.createdAt, y.createdAt));
                    }
                } catch (RuntimeException e) {
                    System.err.println("Skipping orders file " + file.getFileName() + ": " + e);
                }
            }
        }
    }

    private Book book(String server, String stash) {
        return books.computeIfAbsent(new IndexStore.StashKey(server, stash), k -> new Book(server, stash));
    }

    private void save(Book b) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("server", b.server);
        root.addProperty("stash", b.stash);
        JsonArray list = new JsonArray();
        for (Order o : b.orders) list.add(o.toJson());
        root.add("orders", list);
        AtomicFiles.write(dir.resolve(IndexStore.fileName(b.server, b.stash)), Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private void update(Book book, Order previous, Order next) throws IOException {
        int position = book.orders.indexOf(previous);
        book.orders.set(position, next);
        try {
            save(book);
        } catch (IOException failure) {
            book.orders.set(position, previous);
            throw failure;
        }
        prune(book);
    }

    /** Drops the oldest finished orders beyond KEEP. Open orders are never pruned. */
    private void prune(Book b) {
        int excess = b.orders.size() - KEEP;
        for (int i = 0; i < b.orders.size() && excess > 0; ) {
            Order o = b.orders.get(i);
            if (FINISHED.contains(o.status) && (o.allocations == null || o.claimedAt == 0 || o.releasedAt > 0)) {
                b.orders.remove(i);
                byOrderId.remove(o.id, b);
                excess--;
            } else {
                i++;
            }
        }
    }

    private static Map<String, Long> reserved(Book b) {
        Map<String, Long> out = new HashMap<>();
        for (Order o : b.orders) {
            if (!OPEN.contains(o.status) && !holdsPhysical(o)) continue;
            for (Line l : o.lines) out.merge(l.ref(), l.count, Long::sum);
        }
        return out;
    }

    /**
     * What the stash's open orders reserve, by line ref ("item:&lt;key&gt;" / "shulker:&lt;key&gt;"): the same numbers
     * {@link #place} subtracts from the index. A copy; empty when the stash has no orders.
     */
    Map<String, Long> reservedFor(String server, String stash) {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return Map.of();
        synchronized (b) {
            return Map.copyOf(reserved(b));
        }
    }

    private List<Order> ordersOnServer(String server) {
        var found = new ArrayList<Order>();
        for (var entry : books.entrySet()) {
            if (!entry.getKey().server().equals(server)) continue;
            synchronized (entry.getValue()) {
                found.addAll(entry.getValue().orders);
            }
        }
        return found;
    }

    private static boolean holdsPhysical(Order order) {
        return order.allocations != null && order.releasedAt == 0
            && (OPEN.contains(order.status) || order.claimedAt > 0);
    }

    void releaseFromFreshObservation(String server) throws IOException {
        synchronized (index) {
            PhysicalReservations observations = null;
            for (var entry : books.entrySet()) {
                if (!entry.getKey().server().equals(server)) continue;
                var book = entry.getValue();
                synchronized (book) {
                    var released = new ArrayList<Order>();
                    for (var order : book.orders) {
                        if (!holdsPhysical(order) || !FINISHED.contains(order.status)) continue;
                        if (observations == null) observations = PhysicalReservations.read(index.stock(), server, null);
                        if (observations.refreshedAfter(order.allocations, order.updatedAt)
                            && !index.stock().unsettledPhysicalOrder(order.id)) {
                            released.add(order);
                            order.releasedAt = System.currentTimeMillis();
                        }
                    }
                    if (!released.isEmpty()) {
                        try { save(book); }
                        catch (IOException failure) {
                            released.forEach(order -> order.releasedAt = 0);
                            throw failure;
                        }
                    }
                }
            }
        }
    }

    record Reservation(String order, PhysicalReservations.Allocation allocation) {}

    List<Reservation> physicalHolds(String server) {
        var holds = new ArrayList<Reservation>();
        for (var order : ordersOnServer(server)) if (holdsPhysical(order)) {
            for (var allocation : order.allocations) holds.add(new Reservation(order.id, allocation));
        }
        return List.copyOf(holds);
    }

    Set<PhysicalReservations.Root> heldRoots(String server) {
        var roots = new HashSet<PhysicalReservations.Root>();
        for (var hold : physicalHolds(server)) roots.add(hold.allocation().root());
        return Set.copyOf(roots);
    }

    AutomationGate.Lease acquireSorting(String server, String agent, String token) throws IOException {
        synchronized (index) {
            return hasOpenOnServer(server) || !heldRoots(server).isEmpty() ? null : index.automation().acquire(server, agent, token);
        }
    }

    boolean hasOpenOnServer(String server) {
        for (var entry : books.entrySet()) {
            if (!entry.getKey().server().equals(server)) continue;
            synchronized (entry.getValue()) {
                if (entry.getValue().orders.stream().anyMatch(order -> OPEN.contains(order.status))) return true;
            }
        }
        return false;
    }

    // ---------- browser ----------

    /** Validates (against the current index and open orders), merges duplicate lines, stores and returns the order. */
    Order place(String server, String stash, List<Line> requested, JsonObject names) throws OrderException, IOException {
        return place(server, stash, requested, names, null, null);
    }

    /** The same, recording who placed it (byId null: unknown, counted as the owner's). */
    Order place(String server, String stash, List<Line> requested, JsonObject names, String byId, String byName)
        throws OrderException, IOException {
        synchronized (index) { return placeWithObservationLock(server, stash, requested, names, byId, byName); }
    }

    private Order placeWithObservationLock(String server, String stash, List<Line> requested, JsonObject names, String byId, String byName)
        throws OrderException, IOException {
        if (index.automation().current(server) != null) throw new OrderException(409, "Physical sorting active or awaiting reconciliation", -1);
        if (requested.isEmpty()) throw new OrderException(400, "an order needs at least one line", -1);
        if (requested.size() > MAX_LINES) throw new OrderException(400, "at most " + MAX_LINES + " lines", -1);
        IndexStore.OrderCounts counts = index.orderCounts(server, stash);
        if (counts == null) throw new OrderException(400, "no index for that stash", -1);

        // Merge lines with the same type and key, remembering where each first appeared (for error reporting).
        Map<String, Line> merged = new LinkedHashMap<>();
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            Line l = requested.get(i);
            Line prev = merged.get(l.ref());
            if (prev == null) {
                merged.put(l.ref(), l);
                firstIndex.put(l.ref(), i);
            } else {
                merged.put(l.ref(), new Line(l.type, l.key, prev.count + l.count));
            }
        }

        for (var line : merged.values()) {
            if (line.count < 1 || line.count > Integer.MAX_VALUE) {
                throw new OrderException(400, "invalid item count", firstIndex.get(line.ref()));
            }
        }
        var physical = PhysicalReservations.read(index.stock(), server, stash);
        var existing = ordersOnServer(server);
        if (existing.stream().anyMatch(order -> order.allocations != null && isExecuting(order))) {
            throw new OrderException(409, "Wait for the active physical order to finish before reserving more stock", -1);
        }
        var held = heldRoots(server);
        if (!held.isEmpty() && index.stock().sources().stream().anyMatch(source ->
            source.server().equals(server) && source.status().equals("SUPERSEDED") &&
                held.stream().anyMatch(root -> root.source().equals(source.value().get("id").getAsString())))) {
            throw new OrderException(409, "A reserved physical source changed topology and requires reconciliation", -1);
        }
        boolean legacyHold = existing.stream()
            .anyMatch(order -> order.allocations == null && OPEN.contains(order.status));
        if (physical.hasSnapshots() && legacyHold || !physical.hasSnapshots()
            && (!held.isEmpty() || merged.values().stream().anyMatch(line -> line.type.equals("shulker")))) {
            throw new OrderException(409, "Resolve existing reservations before mixing source observation formats", -1);
        }
        var allocations = physical.hasSnapshots() ? physical.allocate(merged.values(), held, firstIndex) : null;
        Book b = book(server, stash);
        synchronized (b) {
            long open = b.orders.stream().filter(o -> OPEN.contains(o.status)).count();
            if (open >= MAX_OPEN) throw new OrderException(400, "too many open orders for this stash (limit " + MAX_OPEN + ")", -1);
            Map<String, Long> reserved = reserved(b);
            for (Line l : merged.values()) {
                int at = firstIndex.get(l.ref());
                long total = counts.total(l.type, l.key);
                if (total < 0 && allocations == null) throw new OrderException(400, "not in the stash: " + l.key, at);
                long available = Math.max(0, total - reserved.getOrDefault(l.ref(), 0L));
                if (allocations == null && l.count > available) {
                    throw new OrderException(400, "only " + available + " available for " + l.key + " (asked for " + l.count + ")", at);
                }
            }
            long shulkers = shulkersNeeded(merged.values(), counts);
            if (shulkers > MAX_SHULKERS) {
                throw new OrderException(400, "too big: this order needs " + shulkers + " shulkers, and one order can fill at most "
                    + MAX_SHULKERS + " (one double chest)", -1);
            }
            long now = System.currentTimeMillis();
            Order o = new Order();
            o.id = newId();
            o.server = server;
            o.stash = stash;
            o.lines = new ArrayList<>(merged.values());
            o.names = names == null ? null : names.deepCopy();
            o.allocations = allocations;
            o.sourceGuards = sourceGuards(server, stash);
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            o.placedById = byId;
            o.placedByName = byId == null ? null : byName;
            b.orders.add(o);
            try {
                save(b);
            } catch (IOException failure) {
                b.orders.remove(o);
                throw failure;
            }
            byOrderId.put(o.id, b);
            return o;
        }
    }

    /**
     * POST /api/v1/orders/{id}/retry: a new pending order with the old one's server, stash, lines and names, checked
     * like any new order. Only failed and cancelled orders (409 otherwise, 404 for an unknown id). The old one stays.
     */
    Order retry(String id) throws OrderException, IOException {
        return retry(id, null, null);
    }

    /** The same; the new order is recorded as placed by byId (who pressed Retry). */
    Order retry(String id, String byId, String byName) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        List<Line> lines;
        JsonObject names;
        Order old;
        synchronized (b) {
            old = find(b, id);
            if (old == null) throw new OrderException(404, "no such order", -1);
            if (!old.status.equals("failed") && !old.status.equals("cancelled")) {
                throw new OrderException(409, "only failed or cancelled orders can be retried (this one is " + old.status + ")", -1);
            }
            // Once only: a second Retry (or a planner part ordered again) would order the same lines twice.
            if (old.retriedAs != null) {
                throw new OrderException(409, PENDING.equals(old.retriedAs) ? "this order is being retried right now"
                    : "this order was already placed again as " + old.retriedAs, -1);
            }
            old.retriedAs = PENDING;
            lines = new ArrayList<>(old.lines);
            names = old.names;
        }
        Order again;
        try {
            again = place(b.server, b.stash, lines, names, byId, byName);
        } catch (OrderException | IOException | RuntimeException e) {
            synchronized (b) {
                if (PENDING.equals(old.retriedAs)) old.retriedAs = null;
            }
            throw e;
        }
        markRetried(id, again.id);
        return again;
    }

    private static final String PENDING = "\u0000pending";

    /** Records that order id was placed again as newId (so it can't be retried again, and its status follows newId). */
    void markRetried(String id, String newId) throws IOException {
        Book b = byOrderId.get(id);
        if (b == null) return;
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) return;
            o.retriedAs = newId;
            save(b);
        }
    }

    /**
     * Validates and normalizes an order's names (PROTOCOL.md, "Additions: shulker names"). Returns null for no naming
     * (absent, null, a blank "all" name, or an "each" list that is all blank), otherwise {mode: "all", name} or
     * {mode: "each", names: [...]} with every name trimmed, blank entries as "", and trailing blank entries dropped.
     * Throws 400 for a wrong type, an unknown mode, a name over 50 characters or with § or a control character, or
     * an "each" list of more than 54 entries.
     */
    static JsonObject normalizeNames(JsonElement e) throws OrderException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new OrderException(400, "names must be an object or null", -1);
        JsonObject o = e.getAsJsonObject();
        JsonElement mode = o.get("mode");
        String m = mode != null && mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString() ? mode.getAsString() : null;
        if ("all".equals(m)) {
            JsonElement n = o.get("name");
            if (n == null || !n.isJsonPrimitive() || !n.getAsJsonPrimitive().isString()) {
                throw new OrderException(400, "names.name must be a string", -1);
            }
            String name = checkName(n.getAsString(), "names.name");
            if (name.isEmpty()) return null;
            JsonObject out = new JsonObject();
            out.addProperty("mode", "all");
            out.addProperty("name", name);
            return out;
        }
        if ("each".equals(m)) {
            JsonElement list = o.get("names");
            if (list == null || !list.isJsonArray()) throw new OrderException(400, "names.names must be an array", -1);
            JsonArray a = list.getAsJsonArray();
            if (a.size() > MAX_SHULKERS) throw new OrderException(400, "names.names has more than " + MAX_SHULKERS + " entries", -1);
            List<String> names = new ArrayList<>();
            int last = -1;
            for (int i = 0; i < a.size(); i++) {
                JsonElement n = a.get(i);
                String name;
                if (n.isJsonNull()) {
                    name = "";
                } else if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isString()) {
                    name = checkName(n.getAsString(), "names.names[" + i + "]");
                } else {
                    throw new OrderException(400, "names.names[" + i + "] must be a string", -1);
                }
                names.add(name);
                if (!name.isEmpty()) last = i;
            }
            if (last < 0) return null;
            JsonArray outList = new JsonArray();
            for (int i = 0; i <= last; i++) outList.add(names.get(i));
            JsonObject out = new JsonObject();
            out.addProperty("mode", "each");
            out.add("names", outList);
            return out;
        }
        throw new OrderException(400, "names.mode must be \"all\" or \"each\"", -1);
    }

    /** The trimmed name ("" when blank), or 400 when it is too long or has § or a control character. */
    private static String checkName(String raw, String field) throws OrderException {
        String name = raw.strip();
        if (name.length() > MAX_NAME) throw new OrderException(400, field + " is longer than " + MAX_NAME + " characters", -1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') throw new OrderException(400, field + " can't contain §", -1);
            if (Character.isISOControl(c)) throw new OrderException(400, field + " can't contain control characters", -1);
            // Invisible or direction-changing characters (bidi overrides, zero-width, line separators) and broken surrogates
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || (type == Character.SURROGATE && !(Character.isHighSurrogate(c) && i + 1 < name.length() && Character.isLowSurrogate(name.charAt(i + 1)))
                    && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(name.charAt(i - 1))))) {
                throw new OrderException(400, field + " can't contain invisible or direction-changing characters", -1);
            }
        }
        return name;
    }

    /**
     * How many shulkers the addon's packer (Order.pack) needs for these lines: every item line takes
     * ceil(count / maxStack) slots, the slots of all lines fill 27-slot shulkers in sequence, and every shulker line
     * and empty-shulker line (an item line for a shulker box) adds one shulker per unit.
     */
    static long shulkersNeeded(Iterable<Line> lines, IndexStore.OrderCounts counts) {
        long slots = 0;
        long units = 0;
        for (Line l : lines) {
            if (l.type.equals("shulker") || counts.isShulkerBoxKey(l.key)) {
                units += l.count;
            } else {
                long max = counts.maxStack(l.key);
                slots += (l.count + max - 1) / max;
            }
        }
        return units + (slots + SHULKER_SLOTS - 1) / SHULKER_SLOTS;
    }

    private String newId() {
        while (true) {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            String id = "o-" + HexFormat.of().formatHex(bytes);
            if (!byOrderId.containsKey(id)) return id;
        }
    }

    /** { orders: [newest first], available: { items: {key: n}, shulkers: {key: n} } } */
    JsonObject listJson(String server, String stash) {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        Book b = books.get(new IndexStore.StashKey(server, stash));
        Map<String, Long> reserved = Map.of();
        if (b != null) {
            synchronized (b) {
                for (int i = b.orders.size() - 1; i >= 0; i--) list.add(b.orders.get(i).toJson());
                reserved = reserved(b);
            }
        }
        out.add("orders", list);
        JsonObject available = new JsonObject();
        IndexStore.OrderCounts counts = null;
        Map<String, Long> physicalAvailable = null;
        synchronized (index) {
            try {
                if (index.automation().current(server) == null) {
                    var physical = PhysicalReservations.read(index.stock(), server, stash);
                    if (physical.hasSnapshots()) physicalAvailable = physical.available(heldRoots(server));
                    counts = index.orderCounts(server, stash);
                }
            } catch (IOException ignored) {
                counts = null;
            }
        }
        JsonObject items = new JsonObject();
        JsonObject shulkers = new JsonObject();
        if (physicalAvailable != null) {
            physicalAvailable.forEach((ref, count) -> {
                int split = ref.indexOf(':');
                (ref.startsWith("item:") ? items : shulkers).addProperty(ref.substring(split + 1), count);
            });
        } else if (counts != null) {
            for (String key : counts.items().keySet()) {
                items.addProperty(key, Math.max(0, counts.total("item", key) - reserved.getOrDefault("item:" + key, 0L)));
            }
            for (String key : counts.shulkers().keySet()) {
                shulkers.addProperty(key, Math.max(0, counts.total("shulker", key) - reserved.getOrDefault("shulker:" + key, 0L)));
            }
        }
        available.add("items", items);
        available.add("shulkers", shulkers);
        out.add("available", available);
        return out;
    }

    /**
     * Removes every finished order (delivered, failed, cancelled) of a stash and returns how many. Open orders are
     * never touched: they hold reservations and the bot may be working on them.
     */
    int clearFinished(String server, String stash) throws IOException {
        synchronized (index) { return clearFinishedWithObservationLock(server, stash); }
    }

    private int clearFinishedWithObservationLock(String server, String stash) throws IOException {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return 0;
        synchronized (b) {
            int removed = 0;
            for (java.util.Iterator<Order> it = b.orders.iterator(); it.hasNext(); ) {
                Order o = it.next();
                if (!FINISHED.contains(o.status) || holdsPhysical(o)) continue;
                it.remove();
                byOrderId.remove(o.id, b);
                removed++;
            }
            if (removed > 0) save(b);
            return removed;
        }
    }

    /** pending -> cancelled now; accepted/in_progress -> cancelRequested; finished -> 409. */
    Order cancel(String id) throws OrderException, IOException {
        synchronized (index) { return cancelWithObservationLock(id); }
    }

    private Order cancelWithObservationLock(String id) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.toJson());
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.status.equals("pending")) {
                o.status = "cancelled";
                o.message = "Cancelled before the bot started.";
            } else if (!o.cancelRequested) {
                o.cancelRequested = true;
                o.cancelRequestedAt = System.currentTimeMillis();
            }
            o.updatedAt = System.currentTimeMillis();
            update(b, previous, o);
            return o;
        }
    }

    /**
     * Marks an accepted/in_progress order whose cancel was requested as cancelled without the bot's confirmation,
     * which releases its reservations. The bot's later status reports for it get 409 (finished orders can't change).
     * 409 when no cancel was requested or the order isn't held by the bot.
     */
    Order forceCancel(String id) throws OrderException, IOException {
        synchronized (index) { return forceCancelWithObservationLock(id); }
    }

    private Order forceCancelWithObservationLock(String id) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.toJson());
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.allocations != null) {
                throw new OrderException(409, "The assigned agent must finish or reconcile its physical work", -1);
            }
            if (!o.cancelRequested || o.status.equals("pending")) {
                throw new OrderException(409, "request a cancel first; force cancel is for orders the bot hasn't confirmed", -1);
            }
            o.status = "cancelled";
            o.message = "cancelled from the website (bot didn't confirm)";
            o.updatedAt = System.currentTimeMillis();
            update(b, previous, o);
            return o;
        }
    }

    /** Who placed an order: Users.OWNER_ID for old orders without a record; null for an unknown order. */
    String placedBy(String id) {
        Book b = byOrderId.get(id);
        if (b == null) return null;
        synchronized (b) {
            Order o = find(b, id);
            if (o == null) return null;
            return o.placedById == null ? Users.OWNER_ID : o.placedById;
        }
    }

    /** An order's status, or null for an unknown order (never placed, or cleared from the history). */
    String statusOf(String id) {
        // An order placed again stands for its successor: a failed part that was retried is live again.
        for (int hops = 0; hops < 64 && id != null; hops++) {
            Book b = byOrderId.get(id);
            if (b == null) return null;
            String next;
            synchronized (b) {
                Order o = find(b, id);
                if (o == null) return null;
                if (o.retriedAs == null) return o.status;
                if (PENDING.equals(o.retriedAs)) return "pending";
                next = o.retriedAs;
            }
            id = next;
        }
        return "pending";
    }

    /** The (server, stash) an order belongs to, or null. */
    IndexStore.StashKey stashOf(String id) {
        Book b = byOrderId.get(id);
        return b == null ? null : new IndexStore.StashKey(b.server, b.stash);
    }

    /** For the website's bot overview: { open: n, active: { id, status, phase, progress } | null }. */
    JsonObject summaryJson(String server, String stash) {
        JsonObject out = new JsonObject();
        int open = 0;
        JsonObject active = null;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (!OPEN.contains(o.status)) continue;
                    open++;
                    if (active == null && !o.status.equals("pending")) {
                        active = new JsonObject();
                        active.addProperty("id", o.id);
                        active.addProperty("status", o.status);
                        active.addProperty("phase", o.phase);
                        active.add("progress", o.progress == null ? null : o.progress.deepCopy());
                    }
                }
            }
        }
        out.addProperty("open", open);
        out.add("active", active);
        return out;
    }

    private static Order find(Book b, String id) {
        for (Order o : b.orders) if (o.id.equals(id)) return o;
        return null;
    }

    // ---------- addon ----------

    /** { pending: n, cancel: [ids of held orders with a cancel requested] } for the heartbeat reply. */
    JsonObject heartbeatJson(String server, String stash) {
        JsonObject out = new JsonObject();
        JsonArray cancel = new JsonArray();
        int pending = 0;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (o.status.equals("pending")) pending++;
                    else if (o.cancelRequested && (o.status.equals("accepted") || o.status.equals("in_progress"))) cancel.add(o.id);
                }
            }
        }
        out.addProperty("pending", pending);
        out.add("cancel", cancel);
        return out;
    }

    private JsonObject sourceGuards(String server, String stash) throws IOException {
        var out = new JsonObject();
        for (var source : index.stock().sources()) if (source.server().equals(server) && source.stash().equals(stash)
            && source.status().equals("FRESH") && source.value().has("slotFingerprint"))
            out.add(source.value().get("id").getAsString(), source.value().get("slotFingerprint"));
        return out.isEmpty() ? null : out;
    }
    private boolean matchesSources(Order order) throws IOException {
        if (order.allocations != null) {
            return PhysicalReservations.read(index.stock(), order.server, order.stash).matches(order.allocations);
        }
        if (order.sourceGuards == null) return true; // Earlier protocol orders retain their existing aggregate checks.
        var now = sourceGuards(order.server, order.stash);
        if (now == null) return false;
        for (var expected : order.sourceGuards.entrySet()) if (!expected.getValue().equals(now.get(expected.getKey()))) return false;
        return true;
    }

    private static boolean isExecuting(Order order) {
        return order.status.equals("accepted") || order.status.equals("in_progress");
    }

    Order claim(String server, String stash) throws IOException {
        synchronized (index) { return claimWithObservationLock(server, stash, "local"); }
    }

    JsonObject claimPayload(String server, String stash, String agent) throws IOException {
        synchronized (index) {
            Order order = claimWithObservationLock(server, stash, agent);
            if (order == null) return null;
            var payload = order.toJson();
            if (order.allocations != null) {
                var guards = new JsonObject();
                for (var hold : physicalHolds(server)) {
                    if (order.id.equals(hold.order())) continue;
                    var root = hold.allocation().root();
                    if (!guards.has(root.source())) guards.add(root.source(), new JsonArray());
                    guards.getAsJsonArray(root.source()).add(Integer.parseInt(root.path()));
                }
                payload.add("protectedRoots", guards);
            }
            return payload;
        }
    }

    private Order claimWithObservationLock(String server, String stash, String agent) throws IOException {
        var counts = index.orderCounts(server, stash);
        if (counts == null || index.automation().current(server) != null) return null;
        var running = ordersOnServer(server).stream().filter(order -> order.allocations != null && isExecuting(order)).findFirst();
        if (running.isPresent() && (!stash.equals(running.get().stash) || !java.util.Objects.equals(agent, running.get().agent))) return null;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return null;
        synchronized (b) {
            for (Order order : b.orders) {
                if (isExecuting(order)) return orderSourcesReady(order, counts, agent) ? order : null;
            }
            for (Order order : b.orders) {
                if (!order.status.equals("pending") || !orderSourcesReady(order, counts, agent)) continue;
                long before = order.updatedAt;
                order.status = "accepted";
                order.claimedAt = order.updatedAt = System.currentTimeMillis();
                order.agent = order.allocations == null ? null : agent;
                try {
                    save(b);
                } catch (IOException failure) {
                    order.status = "pending";
                    order.claimedAt = 0;
                    order.updatedAt = before;
                    order.agent = null;
                    throw failure;
                }
                return order;
            }
            return null;
        }
    }

    private boolean orderSourcesReady(Order order, IndexStore.OrderCounts counts, String agent) throws IOException {
        if (order.allocations == null) {
            return matchesSources(order) && order.lines.stream().allMatch(line -> counts.total(line.type, line.key) >= line.count);
        }
        return agent != null && matchesSources(order);
    }

    /** A status report from the bot. Fields are validated by the route; the move is checked here. */
    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result)
        throws OrderException, IOException {
        return status(id, status, phase, progress, message, result, "local");
    }

    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result, String agent)
        throws OrderException, IOException {
        synchronized (index) { return statusWithObservationLock(id, status, phase, progress, message, result, agent); }
    }

    private Order statusWithObservationLock(String id, String status, String phase, JsonObject progress, String message,
                                             JsonObject result, String agent)
        throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.toJson());
            if (o.agent != null && !o.agent.equals(agent)) {
                throw new OrderException(403, "Physical order belongs to another agent", -1);
            }
            if (!allowed(o.status, status)) {
                throw new OrderException(409, "can't go from " + o.status + " to " + status, -1);
            }
            o.status = status;
            if (phase != null) o.phase = phase;
            if (progress != null) o.progress = progress;
            if (message != null) o.message = message;
            if (result != null) o.result = result;
            o.updatedAt = System.currentTimeMillis();
            update(b, previous, o);
            return o;
        }
    }

    /**
     * Forward moves only: accepted -> accepted/in_progress/finished, in_progress -> in_progress/finished.
     * (Repeating the current status is how the bot reports phase and progress.) Pending and finished orders
     * can't be changed by the bot.
     */
    static boolean allowed(String from, String to) {
        if (from.equals("accepted")) return to.equals("accepted") || to.equals("in_progress") || FINISHED.contains(to);
        if (from.equals("in_progress")) return to.equals("in_progress") || FINISHED.contains(to);
        return false;
    }
}
