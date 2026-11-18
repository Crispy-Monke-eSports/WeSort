package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
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
    static final Set<String> FINISHED = Set.of("delivered", "packed", "failed", "cancelled", "needs_reconciliation");
    static final Set<String> REPORTED_TERMINAL = Set.of("delivered", "failed", "cancelled", "needs_reconciliation");
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

    enum Approval { DIRECT, PENDING, APPROVED, REJECTED }

    record ApprovalDecision(Order order, boolean changed) {}

    @FunctionalInterface
    interface RecipientResolver {
        String resolve(OrderIntent intent) throws OrderException;
    }

    static final class Order {
        String id;
        String server;
        String stash;
        String organizationId;
        OrderIntent intent = OrderIntent.DEFAULT;
        Approval approval = Approval.DIRECT;
        String verifiedRecipient;
        String decidedBy;
        long decidedAt;
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
        OrderStage stage;
        String phase;
        JsonObject progress;
        String message;
        boolean cancelRequested;
        long cancelRequestedAt; // 0 when unknown (orders from before this field)
        JsonObject result;
        JsonObject manifest;
        long manifestAcknowledgedAt;
        String placedById; // Users.OWNER_ID or a sub-user's id; null for orders from before accounts (the owner's)
        String placedByName; // the name at the time
        String retriedAs; // the order this one was placed again as (Retry, or the planner's "order part again"), or null

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("server", server);
            o.addProperty("stash", stash);
            if (organizationId != null) o.addProperty("organizationId", organizationId);
            o.addProperty("purpose", intent.purpose().name());
            o.addProperty("declaredRecipient", intent.recipient());
            o.addProperty("verifiedRecipient", verifiedRecipient);
            o.addProperty("recipient", verifiedRecipient == null ? intent.recipient() : verifiedRecipient);
            o.addProperty("deliveryMode", verifiedRecipient == null ? "WAREHOUSE_PICKUP" : "PLAYER");
            var destination = new JsonObject();
            if (verifiedRecipient == null) {
                destination.addProperty("server", server);
                destination.addProperty("stash", stash);
            } else {
                destination.addProperty("player", verifiedRecipient);
            }
            o.add("destination", destination);
            o.addProperty("comment", intent.comment());
            o.addProperty("approvalState", approval.name());
            if (decidedBy != null) {
                o.addProperty("decidedBy", decidedBy);
                o.addProperty("decidedAt", decidedAt);
                if (approval == Approval.APPROVED) o.addProperty("approvedBy", decidedBy);
            }
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
            o.addProperty("state", stage().name());
            o.addProperty("phase", phase);
            o.add("progress", progress == null ? null : progress.deepCopy());
            o.addProperty("message", message);
            o.addProperty("cancelRequested", cancelRequested);
            o.addProperty("cancelRequestedAt", cancelRequestedAt > 0 ? cancelRequestedAt : null);
            o.add("result", result == null ? null : result.deepCopy());
            if (manifest != null) o.add("manifest", manifest.deepCopy());
            if (manifestAcknowledgedAt > 0) o.addProperty("manifestAcknowledgedAt", manifestAcknowledgedAt);
            if (retriedAs != null) o.addProperty("retriedAs", retriedAs);
            if (placedById == null) {
                o.add("placedBy", null);
                o.addProperty("requestedBy", Users.OWNER_ID);
            } else {
                JsonObject by = new JsonObject();
                by.addProperty("id", placedById);
                by.addProperty("name", placedByName);
                o.add("placedBy", by);
                o.addProperty("requestedBy", placedById);
            }
            return o;
        }

        static Order fromJson(JsonObject o) {
            Order r = new Order();
            r.id = o.get("id").getAsString();
            if (o.has("manifest")) r.manifest = o.getAsJsonObject("manifest").deepCopy();
            if (o.has("manifestAcknowledgedAt")) r.manifestAcknowledgedAt = o.get("manifestAcknowledgedAt").getAsLong();
            r.sourceGuards = o.has("sourceGuards") ? o.getAsJsonObject("sourceGuards").deepCopy() : null;
            if (o.has("allocations")) r.allocations = List.of(Json.GSON.fromJson(
                o.get("allocations"), PhysicalReservations.Allocation[].class));
            r.claimedAt = o.has("claimedAt") ? o.get("claimedAt").getAsLong() : 0;
            r.agent = str(o, "agent");
            r.releasedAt = o.has("releasedAt") ? o.get("releasedAt").getAsLong() : 0;
            r.server = o.get("server").getAsString();
            r.stash = o.get("stash").getAsString();
            r.organizationId = str(o, "organizationId");
            var intent = o.deepCopy();
            if (o.has("declaredRecipient")) intent.add("recipient", o.get("declaredRecipient"));
            r.intent = OrderIntent.read(intent);
            r.verifiedRecipient = str(o, "verifiedRecipient");
            if (o.has("approvalState")) r.approval = Approval.valueOf(o.get("approvalState").getAsString());
            r.decidedBy = str(o, "decidedBy");
            r.decidedAt = o.has("decidedAt") ? o.get("decidedAt").getAsLong() : 0;
            r.lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                r.lines.add(new Line(l.get("type").getAsString(), l.get("key").getAsString(), l.get("count").getAsLong()));
            }
            // "note" from orders stored before notes were removed is dropped here and never written again.
            try {
                r.names = readStoredNames(o.get("names"));
            } catch (OrderException e) {
                r.names = null;
            }
            r.createdAt = o.get("createdAt").getAsLong();
            r.updatedAt = o.get("updatedAt").getAsLong();
            r.status = o.get("status").getAsString();
            r.stage = o.has("state") ? OrderStage.valueOf(o.get("state").getAsString()) : OrderStage.legacy(r.status);
            if (r.status.equals("delivered") && r.stage == OrderStage.NEEDS_RECONCILIATION) {
                r.status = "needs_reconciliation";
            }
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

        OrderStage stage() { return stage == null ? OrderStage.legacy(status) : stage; }

        void advance(OrderStage next) throws OrderException {
            if (!stage().allows(next)) throw new OrderException(409, "Order cannot move from " + stage() + " to " + next, -1);
            stage = next;
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
        final Map<String, Receipt> receipts = new LinkedHashMap<>();

        Book(String server, String stash) {
            this.server = server;
            this.stash = stash;
        }
    }

    private record Receipt(String fingerprint, JsonObject order) {
        JsonObject json() {
            var value = new JsonObject();
            value.addProperty("fingerprint", fingerprint);
            value.add("order", order.deepCopy());
            return value;
        }
        static Receipt read(JsonObject value) {
            return new Receipt(value.get("fingerprint").getAsString(), value.getAsJsonObject("order"));
        }
    }

    record Submission(Order order, boolean created) {}

    private static String receiptKey(String action, String actor, String requestId) {
        var parts = new JsonArray();
        parts.add(action);
        parts.add(actor);
        parts.add(requestId);
        return hash(parts);
    }

    private static String hash(JsonElement value) {
        return com.porter.wesort.data.ContainerIdentity.hash(com.porter.wesort.data.CanonicalJson.stringify(value));
    }

    private Receipt previouslyReceived(String key) {
        for (Book book : books.values()) {
            synchronized (book) {
                var saved = book.receipts.get(key);
                if (saved != null) return saved;
            }
        }
        return null;
    }

    private static Order replay(Receipt saved, String fingerprint) throws OrderException {
        if (!saved.fingerprint().equals(fingerprint)) {
            throw new OrderException(409, "This idempotency key has already been used for different content", -1);
        }
        return Order.fromJson(saved.order());
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
                        if (root.has("receipts")) {
                            for (var entry : root.getAsJsonObject("receipts").entrySet()) {
                                b.receipts.put(entry.getKey(), Receipt.read(entry.getValue().getAsJsonObject()));
                            }
                        }
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
        if (!b.receipts.isEmpty()) {
            var receipts = new JsonObject();
            b.receipts.forEach((key, receipt) -> receipts.add(key, receipt.json()));
            root.add("receipts", receipts);
        }
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
            if (o.stage().archiveable() && (o.allocations == null || o.claimedAt == 0 || o.releasedAt > 0)) {
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
                        if (!holdsPhysical(order) || !FINISHED.contains(order.status)
                            || order.stage() == OrderStage.NEEDS_RECONCILIATION) continue;
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
        return place(server, stash, requested, names, byId, byName, null, OrderIntent.DEFAULT);
    }

    Order place(String server, String stash, List<Line> requested, JsonObject names, String byId,
                String byName, String organizationId, OrderIntent intent) throws OrderException, IOException {
        return submit(server, stash, requested, names, byId, byName, organizationId, intent, null, false, ignored -> null).order();
    }

    Submission submit(String server, String stash, List<Line> requested, JsonObject names, String byId,
                      String byName, String organizationId, OrderIntent intent, String idempotencyKey, boolean needsApproval, RecipientResolver recipient)
        throws OrderException, IOException {
        synchronized (index) {
            String key = null, fingerprint = null;
            if (idempotencyKey != null) {
                key = receiptKey("create", byId, idempotencyKey);
                var body = new JsonObject();
                body.addProperty("server", server);
                body.addProperty("stash", stash);
                body.add("lines", Json.GSON.toJsonTree(requested));
                body.add("names", names == null ? JsonNull.INSTANCE : names);
                body.add("intent", Json.GSON.toJsonTree(intent));
                fingerprint = hash(body);
                var saved = previouslyReceived(key);
                if (saved != null) return new Submission(replay(saved, fingerprint), false);
            }
            return new Submission(placeWithObservationLock(server, stash, requested, names, byId, byName,
                organizationId, intent, key, fingerprint, null, needsApproval, recipient.resolve(intent)), true);
        }
    }

    private Order placeWithObservationLock(String server, String stash, List<Line> requested, JsonObject names,
                                           String byId, String byName, String organizationId, OrderIntent intent,
                                           String receiptKey, String fingerprint, Order retryOf, boolean needsApproval, String recipient)
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
            o.organizationId = organizationId;
            o.intent = intent;
            o.approval = needsApproval ? Approval.PENDING : Approval.DIRECT;
            o.verifiedRecipient = recipient;
            o.lines = new ArrayList<>(merged.values());
            o.names = names == null ? null
                : "packageLabel".equals(names.get("mode").getAsString())
                    ? lockPackageLabel(names, Math.toIntExact(shulkers)) : names.deepCopy();
            o.allocations = allocations;
            o.sourceGuards = sourceGuards(server, stash);
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            o.stage = OrderStage.CREATED;
            o.advance(OrderStage.RESERVED);
            o.placedById = byId;
            o.placedByName = byId == null ? null : byName;
            b.orders.add(o);
            if (retryOf != null) retryOf.retriedAs = o.id;
            if (receiptKey != null) b.receipts.put(receiptKey, new Receipt(fingerprint, o.toJson()));
            try {
                save(b);
            } catch (IOException failure) {
                b.orders.remove(o);
                if (retryOf != null) retryOf.retriedAs = null;
                if (receiptKey != null) b.receipts.remove(receiptKey);
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
        return retry(id, byId, byName, true, null, GuildProfile.ApprovalPolicy.DIRECT, ignored -> null);
    }

    Order retry(String id, String byId, String byName, boolean mayOrderForGuild, String organizationId, GuildProfile.ApprovalPolicy policy, RecipientResolver recipient) throws OrderException, IOException {
        synchronized (index) {
            Book book = byOrderId.get(id);
            if (book == null) throw new OrderException(404, "no such order", -1);
            synchronized (book) {
                Order old = find(book, id);
                if (old == null) throw new OrderException(404, "no such order", -1);
                if (old.stage() != OrderStage.FAILED && old.stage() != OrderStage.CANCELLED) {
                    throw new OrderException(409, "Only failed or cancelled orders can be retried", -1);
                }
                if (!mayOrderForGuild && old.intent.requiresGuildPermission()) {
                    throw new OrderException(403, "Your role cannot retry an order for the guild or another recipient", -1);
                }
                if (old.retriedAs != null) {
                    throw new OrderException(409, "This order was already placed again as " + old.retriedAs, -1);
                }
                if (holdsPhysical(old)) {
                    throw new OrderException(409, "Wait for verified release of the previous reservation", -1);
                }
                return placeWithObservationLock(book.server, book.stash, old.lines, old.names, byId, byName,
                    old.organizationId == null ? organizationId : old.organizationId, old.intent, null, null, old, policy.requiresApproval(old.intent), recipient.resolve(old.intent));
            }
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
        if ("packageLabel".equals(m)) {
            return normalizePackageLabel(o);
        }
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

    /** Versioned suffix-only request: only the server may freeze total and number. */
    private static JsonObject normalizePackageLabel(JsonObject raw) throws OrderException {
        try {
            if (!raw.has("version") || !raw.get("version").isJsonPrimitive()
                || !raw.get("version").getAsJsonPrimitive().isNumber()
                || raw.get("version").getAsBigDecimal().intValueExact() != 1) {
                throw new OrderException(400, "packageLabel version must be 1", -1);
            }
        } catch (ArithmeticException invalid) {
            throw new OrderException(400, "packageLabel version must be an exact integer", -1);
        }
        for (var entry : raw.entrySet()) {
            if (!Set.of("mode", "version", "text", "texts").contains(entry.getKey())) {
                throw new OrderException(400, "unexpected packageLabel field: " + entry.getKey(), -1);
            }
        }
        String defaultText = "Beställning";
        if (raw.has("text")) {
            if (!raw.get("text").isJsonPrimitive() || !raw.get("text").getAsJsonPrimitive().isString())
                throw new OrderException(400, "packageLabel text must be a string", -1);
            defaultText = suffix(raw.get("text").getAsString(), "packageLabel.text");
        }
        var normalized = new JsonObject();
        normalized.addProperty("mode", "packageLabel");
        normalized.addProperty("version", 1);
        normalized.addProperty("text", defaultText);
        if (raw.has("texts")) {
            if (!raw.get("texts").isJsonArray()) throw new OrderException(400, "packageLabel texts must be an array", -1);
            JsonArray input = raw.getAsJsonArray("texts");
            if (input.isEmpty() || input.size() > MAX_SHULKERS) throw new OrderException(400, "packageLabel texts needs 1.." + MAX_SHULKERS + " items", -1);
            JsonArray texts = new JsonArray();
            for (int i = 0; i < input.size(); i++) {
                JsonElement value = input.get(i);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new OrderException(400, "packageLabel texts[" + i + "] must be a string", -1);
                String name = value.getAsString().strip();
                texts.add(name.isEmpty() ? defaultText : suffix(name, "packageLabel.texts[" + i + "]"));
            }
            normalized.add("texts", texts);
        }
        return normalized;
    }

    private static String suffix(String raw, String field) throws OrderException {
        String text = checkName(raw, field);
        if (text.length() > 33) throw new OrderException(400, field + " exceeds 33 UTF-16 code units", -1);
        return text.isEmpty() ? "Beställning" : text;
    }

    /**
     * Convert a validated suffix-only request into a server-owned immutable label plan.
     * Existing agents already consume the generated legacy each-list, but the plan
     * must remain pinned and never be accepted as client-supplied packageTotal.
     */
    static JsonObject lockPackageLabel(JsonObject request, int total) throws OrderException {
        if (total < 1 || total > MAX_SHULKERS)
            throw new OrderException(400, "package total must be 1.." + MAX_SHULKERS, -1);
        JsonObject normalized = normalizePackageLabel(request);
        JsonArray texts = normalized.has("texts") ? normalized.getAsJsonArray("texts") : null;
        if (texts != null && texts.size() != total)
            throw new OrderException(400, "per-box text count must equal the locked package total", -1);
        JsonArray labels = new JsonArray();
        for (int i = 0; i < total; i++) {
            String text = texts == null ? normalized.get("text").getAsString() : texts.get(i).getAsString();
            String label = "WeSort (" + (i + 1) + "/" + total + ") - " + text;
            checkName(label, "generated package label");
            labels.add(label);
        }
        JsonObject meta = normalized.deepCopy();
        meta.remove("mode");
        meta.addProperty("total", total);
        JsonObject stored = new JsonObject();
        stored.addProperty("mode", "each");
        stored.add("names", labels);
        stored.add("packageLabel", meta);
        return stored;
    }

    /** Load a pinned generated plan only if its integrity matches the stored suffix policy. */
    private static JsonObject readStoredNames(JsonElement serialized) throws OrderException {
        JsonObject normal = normalizeNames(serialized);
        if (normal == null || !serialized.isJsonObject()
            || !serialized.getAsJsonObject().has("packageLabel")) return normal;
        JsonObject stored = serialized.getAsJsonObject();
        if (!"each".equals(stored.get("mode").getAsString())) return normal;
        try {
            JsonObject meta = stored.getAsJsonObject("packageLabel");
            int total = meta.get("total").getAsBigDecimal().intValueExact();
            JsonObject request = meta.deepCopy();
            request.remove("total");
            request.addProperty("mode", "packageLabel");
            JsonObject planned = lockPackageLabel(request, total);
            return planned.equals(stored) ? planned : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Public new-order API always chooses the versioned server-owned label contract.
     * Legacy free-form names are still decoded for historical disk orders and their
     * retries, but cannot be used to create a new unnamed or spoofed customer order.
     */
    static JsonObject normalizeNewOrderNames(JsonElement value) throws OrderException {
        if (value == null || value.isJsonNull()) {
            JsonObject automatic = new JsonObject();
            automatic.addProperty("mode", "packageLabel");
            automatic.addProperty("version", 1);
            automatic.addProperty("text", "");
            return normalizeNames(automatic);
        }
        JsonObject names = normalizeNames(value);
        if (names == null || !"packageLabel".equals(names.get("mode").getAsString()))
            throw new OrderException(400, "New orders require versioned packageLabel suffixes", -1);
        return names;
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
            var removable = b.orders.stream().filter(o -> o.stage().archiveable() && !holdsPhysical(o)).toList();
            if (removable.isEmpty()) return 0;
            var before = List.copyOf(b.orders);
            b.orders.removeAll(removable);
            try {
                save(b);
            } catch (IOException failure) {
                b.orders.clear();
                b.orders.addAll(before);
                throw failure;
            }
            removable.forEach(o -> byOrderId.remove(o.id, b));
            return removable.size();
        }
    }

    ApprovalDecision review(String id, boolean approve, String actor) throws OrderException, IOException {
        synchronized (index) {
            Book book = byOrderId.get(id);
            if (book == null) throw new OrderException(404, "no such order", -1);
            synchronized (book) {
                Order before = find(book, id);
                if (before == null) throw new OrderException(404, "no such order", -1);
                Approval decision = approve ? Approval.APPROVED : Approval.REJECTED;
                if (before.approval == decision) return new ApprovalDecision(before, false);
                if (!before.status.equals("pending") || before.approval != Approval.PENDING) {
                    throw new OrderException(409, "This order is no longer awaiting approval", -1);
                }
                Order next = Order.fromJson(before.toJson());
                next.approval = decision;
                next.decidedBy = actor;
                next.decidedAt = next.updatedAt = System.currentTimeMillis();
                if (!approve) {
                    next.advance(OrderStage.CANCELLED);
                    next.status = "cancelled";
                }
                update(book, before, next);
                return new ApprovalDecision(next, true);
            }
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
                o.advance(OrderStage.CANCELLED);
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
            o.advance(OrderStage.CANCELLED);
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
    JsonObject summaryJson(String server, String stash, String requester) {
        JsonObject out = new JsonObject();
        int open = 0;
        JsonObject active = null;
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b != null) {
            synchronized (b) {
                for (Order o : b.orders) {
                    if (!OPEN.contains(o.status)) continue;
                    open++;
                    String placedBy = o.placedById == null ? Users.OWNER_ID : o.placedById;
                    if (active == null && !o.status.equals("pending") && (requester == null || requester.equals(placedBy))) {
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
                    if (o.status.equals("pending") && o.approval != Approval.PENDING) pending++;
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
                if (!order.status.equals("pending") || order.approval == Approval.PENDING || !orderSourcesReady(order, counts, agent)) continue;
                long before = order.updatedAt;
                if (!order.stage().allows(OrderStage.PACKING)) continue;
                order.stage = OrderStage.PACKING;
                order.status = "accepted";
                order.claimedAt = order.updatedAt = System.currentTimeMillis();
                order.agent = order.allocations == null ? null : agent;
                try {
                    save(b);
                } catch (IOException failure) {
                    order.stage = OrderStage.RESERVED;
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
        return status(id, status, phase, progress, message, result, agent, null);
    }

    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result,
                 String agent, String reportId) throws OrderException, IOException {
        synchronized (index) {
            var report = new JsonObject();
            report.addProperty("status", status);
            report.addProperty("phase", phase);
            report.add("progress", progress == null ? JsonNull.INSTANCE : progress);
            report.addProperty("message", message);
            report.add("result", result == null ? JsonNull.INSTANCE : result);
            String fingerprint = hash(report);
            String key = receiptKey("report:" + id, agent,
                reportId == null ? "content:" + fingerprint : "id:" + reportId);
            var saved = previouslyReceived(key);
            if (saved != null) return replay(saved, fingerprint);
            return statusWithObservationLock(id, status, phase, progress, message, result, agent, key, fingerprint);
        }
    }

    private Order statusWithObservationLock(String id, String status, String phase, JsonObject progress, String message,
                                             JsonObject result, String agent, String key, String fingerprint)
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
            OrderStage next = reportedStage(o, status, result);
            o.advance(next);
            o.status = switch (next) {
                case PICKUP_READY -> "packed";
                case NEEDS_RECONCILIATION -> "needs_reconciliation";
                default -> status;
            };
            if (phase != null) o.phase = phase;
            if (progress != null) o.progress = progress;
            if (message != null) o.message = message;
            if (next == OrderStage.NEEDS_RECONCILIATION) o.result = null;
            else if (result != null) o.result = result;
            o.updatedAt = System.currentTimeMillis();
            b.receipts.put(key, new Receipt(fingerprint, o.toJson()));
            try {
                update(b, previous, o);
            } catch (IOException failure) {
                b.receipts.remove(key);
                throw failure;
            }
            return o;
        }
    }

    private OrderStage reportedStage(Order order, String status, JsonObject result) throws IOException {
        if (status.equals("accepted") || status.equals("in_progress")) return OrderStage.PACKING;
        if (status.equals("needs_reconciliation") || index.stock().unsettledPhysicalOrder(order.id)) {
            return OrderStage.NEEDS_RECONCILIATION;
        }
        if (status.equals("delivered")) {
            int packages = result != null && result.has("shulkers") ? result.get("shulkers").getAsInt() : 0;
            if (order.agent != null && packages > 0 && packages <= MAX_SHULKERS) {
                order.manifest = index.stock().stagingManifest(order.server, order.stash, order.id, order.agent, packages);
            }
            return order.manifest != null && allLinesStaged(order.lines, result)
                ? OrderStage.PICKUP_READY : OrderStage.NEEDS_RECONCILIATION;
        }
        return status.equals("cancelled") ? OrderStage.CANCELLED : OrderStage.FAILED;
    }

    JsonObject manifest(String id, BotHub.ManifestScope scope) {
        var book = byOrderId.get(id);
        if (book == null) return null;
        synchronized (book) {
            return scopedManifest(find(book, id), scope);
        }
    }

    record ManifestReceipt(long acknowledgedAt, boolean changed) {}

    JsonObject pendingManifest(BotHub.ManifestScope scope) {
        var book = books.get(new IndexStore.StashKey(scope.server(), scope.stash()));
        if (book == null) return null;
        synchronized (book) {
            for (var order : book.orders) {
                if (order.manifestAcknowledgedAt > 0) continue;
                var manifest = scopedManifest(order, scope);
                if (manifest == null) continue;
                var hint = new JsonObject();
                hint.addProperty("orderId", order.id);
                hint.addProperty("packageCount", manifest.getAsJsonArray("packages").size());
                return hint;
            }
            return null;
        }
    }

    ManifestReceipt acknowledgeManifest(String id, BotHub.ManifestScope scope, String hash) throws IOException, OrderException {
        var book = byOrderId.get(id);
        if (book == null) throw new OrderException(404, "No manifest in this scope", -1);
        synchronized (book) {
            var order = find(book, id);
            var manifest = scopedManifest(order, scope);
            if (manifest == null) throw new OrderException(404, "No manifest in this scope", -1);
            if (!hash.equals(manifest.get("hash").getAsString())) throw new OrderException(409, "Manifest hash differs", -1);
            if (order.manifestAcknowledgedAt > 0) return new ManifestReceipt(order.manifestAcknowledgedAt, false);
            var updated = Order.fromJson(order.toJson());
            updated.manifestAcknowledgedAt = System.currentTimeMillis();
            update(book, order, updated);
            return new ManifestReceipt(updated.manifestAcknowledgedAt, true);
        }
    }

    private static JsonObject scopedManifest(Order order, BotHub.ManifestScope scope) {
        if (order == null || order.stage() != OrderStage.PICKUP_READY || order.manifest == null
            || !scope.server().equals(order.server) || !scope.stash().equals(order.stash)
            || !scope.agent().equals(order.agent)) return null;
        return PackageManifest.forAgent(order.manifest, scope.dimension());
    }

    static boolean allLinesStaged(List<Line> lines, JsonObject result) {
        if (result == null || !result.has("lines") || !result.get("lines").isJsonArray()) return false;
        var outstanding = new HashMap<String, Long>();
        for (var line : lines) outstanding.merge(line.key(), line.count(), Long::sum);
        for (var entry : result.getAsJsonArray("lines")) {
            var line = entry.getAsJsonObject();
            var count = outstanding.remove(line.get("key").getAsString());
            if (count == null || count.longValue() != line.get("delivered").getAsLong()) return false;
        }
        return outstanding.isEmpty();
    }

    /**
     * Forward moves only: accepted -> accepted/in_progress/finished, in_progress -> in_progress/finished.
     * (Repeating the current status is how the bot reports phase and progress.) Pending and finished orders
     * can't be changed by the bot.
     */
    static boolean allowed(String from, String to) {
        if (from.equals("accepted")) return to.equals("accepted") || to.equals("in_progress") || REPORTED_TERMINAL.contains(to);
        if (from.equals("in_progress")) return to.equals("in_progress") || REPORTED_TERMINAL.contains(to);
        return false;
    }
}
