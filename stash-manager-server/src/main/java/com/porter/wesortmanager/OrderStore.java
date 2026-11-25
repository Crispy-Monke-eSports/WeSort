package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.wesort.data.PackageLabel;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Orders per (server, stash), persisted in the shared guild SQLite database.
 * Each stash's orders are guarded by their own lock; snapshots and append-only transition history commit together.
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
    static final Set<String> FINISHED = Set.of("delivered", "packed", "failed", "cancelled");
    static final Set<String> REPORTED_TERMINAL = Set.of("delivered", "failed", "cancelled", "needs_reconciliation");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Line(String type, String key, long count) {
        String ref() {
            return type + ":" + key;
        }
    }

    /**
     * Kit lines exploded to their component item lines: one kit is exactly one
     * box, so ordering N kits of a template reserves N times every slot.
     * Errors point at the original kit line, naming the missing component.
     */
    static final class KitExplosion {
        /** Component item lines merged with any real item lines (ref to line). */
        final Map<String, Line> lines = new LinkedHashMap<>();
        /** Every line ref (exploded or plain) to the request index for error reporting. */
        final Map<String, Integer> firstIndex = new HashMap<>();
        /** Template snapshots to pin on the order (name to template JSON). */
        final Map<String, JsonObject> snapshots = new LinkedHashMap<>();
    }

    /**
     * Explodes merged kit lines using the current templates. Unknown kits are
     * 404 (like unknown stashes); shortages are reported against components.
     */
    static KitExplosion explodeKits(Map<String, Line> merged, Map<String, Integer> firstIndex, KitTemplates kits)
            throws OrderException {
        var out = new KitExplosion();
        for (var l : merged.values()) {
            if (!l.type.equals("kit")) {
                var prev = out.lines.get(l.ref());
                out.lines.put(l.ref(), prev == null ? l : new Line(l.type, l.key, prev.count + l.count));
                out.firstIndex.putIfAbsent(l.ref(), firstIndex.get(l.ref()));
                continue;
            }
            var template = kits.get(l.key);
            if (template == null) throw new OrderException(404, "no such kit: " + l.key, firstIndex.get(l.ref()));
            out.snapshots.put(l.key, template.json());
            for (var entry : template.components().entrySet()) {
                var need = new Line("item", entry.getKey(), entry.getValue() * l.count);
                var prev = out.lines.get(need.ref());
                out.lines.put(need.ref(), prev == null ? need : new Line("item", need.key, prev.count + need.count));
                // A component shared with a real item line reports the earliest line.
                out.firstIndex.merge(need.ref(), firstIndex.get(l.ref()), Math::min);
            }
            if (template.components().isEmpty())
                throw new OrderException(400, "kit '" + l.key + "' is empty", firstIndex.get(l.ref()));
        }
        return out;
    }

    /** Explodes an order's kit lines from its pinned snapshots (no live templates needed). */
    static List<Line> explodeOrderLines(Order o) {
        var out = new ArrayList<Line>();
        for (Line l : o.lines) {
            if (l.type.equals("craft")) continue;
            if (!l.type.equals("kit") || o.kits == null || !o.kits.containsKey(l.key)) {
                out.add(l);
                continue;
            }
            var slots = o.kits.get(l.key).getAsJsonArray("slots");
            var perKit = new LinkedHashMap<String, Long>();
            for (var e : slots) {
                var s = e.getAsJsonObject();
                perKit.merge(s.get("key").getAsString(), s.get("count").getAsLong(), Long::sum);
            }
            for (var e : perKit.entrySet()) out.add(new Line("item", e.getKey(), e.getValue() * l.count));
        }
        if (o.craftMaterials != null) o.craftMaterials.forEach((key, count) -> out.add(new Line("item", key, count)));
        return out;
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

    enum Approval {
        DIRECT,
        PENDING,
        APPROVED,
        REJECTED
    }

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
        JsonObject packageLabel; // normalized (see normalizePackageLabel), or null for the default label
        int packageTotal; // frozen number of boxes Y, 0 when unknown (legacy orders)
        /** Kit template snapshots pinned at submit (name to template JSON), or null without kit lines. */
        Map<String, JsonObject> kits;
        /** Server-pinned table craft steps and the exact ingredient reservation. */
        JsonArray craftPlans;

        Map<String, Long> craftMaterials;

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
        /** Durable outbox: UUID registry mutations still pending after a saved order transition. */
        JsonArray pendingUuidDeregistrations;

        long manifestAcknowledgedAt;
        String placedById; // Users.OWNER_ID or a sub-user's id; null for orders from before accounts (the owner's)
        String placedByName; // the name at the time
        String retriedAs; // the order this one was placed again as (Retry, or the planner's "order part again"), or
        // null
        boolean queuedOffline; // true when reserved against last-verified (stale) counts via the explicit offline queue
        /** Pending request accepted while another physical order runs; no stock is reserved yet. */
        boolean reservationDeferred;
        /** First unsuccessful attempt to reserve this never-dispatched order. */
        long deferredBlockedAt;

        private JsonArray history = new JsonArray();

        void record(String action, String actor, long time) {
            var event = new JsonObject();
            event.addProperty("action", action);
            event.addProperty("actor", actor);
            event.addProperty("at", time);
            event.addProperty("state", stage().name());
            history.add(event);
        }

        JsonObject snapshot() {
            var saved = toJson();
            if (!history.isEmpty()) saved.add("history", history.deepCopy());
            if (pendingUuidDeregistrations != null && !pendingUuidDeregistrations.isEmpty())
                saved.add("pendingUuidDeregistrations", pendingUuidDeregistrations.deepCopy());
            return saved;
        }

        JsonObject auditJson() {
            var shown = new JsonObject();
            var full = toJson();
            for (String key : List.of(
                    "id",
                    "server",
                    "stash",
                    "createdAt",
                    "updatedAt",
                    "state",
                    "purpose",
                    "requestedBy",
                    "placedBy",
                    "recipient",
                    "comment",
                    "approvalState",
                    "decidedBy",
                    "decidedAt",
                    "lines",
                    "retriedAs")) {
                if (full.has(key)) shown.add(key, full.get(key));
            }
            var events = history.deepCopy();
            for (int i = 0; i < events.size(); i++)
                events.get(i).getAsJsonObject().addProperty("id", id + ":" + (i + 1));
            shown.add("events", events);
            var packageIds = new JsonArray();
            if (manifest != null) {
                for (var item : manifest.getAsJsonArray("packages"))
                    packageIds.add(item.getAsJsonObject().get("packageId"));
            }
            shown.add("packageIds", packageIds);
            if (releasedAt > 0) shown.addProperty("reservationReleasedAt", releasedAt);
            if (stage() == OrderStage.PICKUP_READY || stage() == OrderStage.DELIVERED) {
                if (result != null && result.has("lines"))
                    shown.add("stagedAmounts", result.get("lines").deepCopy());
            }
            return shown;
        }

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
            o.add("packageLabel", packageLabel == null ? null : packageLabel.deepCopy());
            if (packageTotal > 0) o.addProperty("packageTotal", packageTotal);
            if (craftPlans != null) o.add("craftPlans", craftPlans.deepCopy());
            if (craftMaterials != null) o.add("craftMaterials", Json.GSON.toJsonTree(craftMaterials));
            if (kits != null) {
                var kj = new JsonObject();
                for (var e : kits.entrySet()) kj.add(e.getKey(), e.getValue().deepCopy());
                o.add("kits", kj);
            }
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("status", status);
            o.addProperty("state", stage().name());
            o.addProperty("queuedOffline", queuedOffline);
            o.addProperty("reservationDeferred", reservationDeferred);
            if (deferredBlockedAt > 0) o.addProperty("deferredBlockedAt", deferredBlockedAt);
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
            if (o.has("history")) r.history = o.getAsJsonArray("history").deepCopy();
            if (o.has("pendingUuidDeregistrations"))
                r.pendingUuidDeregistrations =
                        o.getAsJsonArray("pendingUuidDeregistrations").deepCopy();
            if (o.has("manifest")) r.manifest = o.getAsJsonObject("manifest").deepCopy();
            if (o.has("manifestAcknowledgedAt"))
                r.manifestAcknowledgedAt = o.get("manifestAcknowledgedAt").getAsLong();
            r.sourceGuards =
                    o.has("sourceGuards") ? o.getAsJsonObject("sourceGuards").deepCopy() : null;
            if (o.has("allocations"))
                r.allocations =
                        List.of(Json.GSON.fromJson(o.get("allocations"), PhysicalReservations.Allocation[].class));
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
            if (o.has("approvalState"))
                r.approval = Approval.valueOf(o.get("approvalState").getAsString());
            r.decidedBy = str(o, "decidedBy");
            r.decidedAt = o.has("decidedAt") ? o.get("decidedAt").getAsLong() : 0;
            r.lines = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("lines")) {
                JsonObject l = e.getAsJsonObject();
                r.lines.add(new Line(
                        l.get("type").getAsString(),
                        l.get("key").getAsString(),
                        l.get("count").getAsLong()));
            }
            // "note" from orders stored before notes were removed is dropped here and never written again.
            try {
                r.names = normalizeNames(o.get("names"));
            } catch (OrderException e) {
                r.names = null;
            }
            try {
                r.packageLabel = normalizePackageLabel(o.get("packageLabel"));
            } catch (OrderException e) {
                r.packageLabel = null;
            }
            r.packageTotal = o.has("packageTotal") ? o.get("packageTotal").getAsInt() : 0;
            if (o.has("craftPlans") && o.get("craftPlans").isJsonArray())
                r.craftPlans = o.getAsJsonArray("craftPlans").deepCopy();
            if (o.has("craftMaterials") && o.get("craftMaterials").isJsonObject()) {
                r.craftMaterials = new LinkedHashMap<>();
                o.getAsJsonObject("craftMaterials")
                        .entrySet()
                        .forEach(e ->
                                r.craftMaterials.put(e.getKey(), e.getValue().getAsLong()));
            }
            if (o.has("kits") && o.get("kits").isJsonObject()) {
                r.kits = new LinkedHashMap<>();
                for (var e : o.getAsJsonObject("kits").entrySet())
                    r.kits.put(e.getKey(), e.getValue().getAsJsonObject().deepCopy());
            }
            r.queuedOffline = o.has("queuedOffline") && o.get("queuedOffline").getAsBoolean();
            r.reservationDeferred =
                    o.has("reservationDeferred") && o.get("reservationDeferred").getAsBoolean();
            r.deferredBlockedAt =
                    o.has("deferredBlockedAt") ? o.get("deferredBlockedAt").getAsLong() : 0;
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
            r.cancelRequested =
                    o.has("cancelRequested") && o.get("cancelRequested").getAsBoolean();
            r.cancelRequestedAt =
                    o.has("cancelRequestedAt") && o.get("cancelRequestedAt").isJsonPrimitive()
                            ? o.get("cancelRequestedAt").getAsLong()
                            : 0;
            r.result = o.has("result") && o.get("result").isJsonObject() ? o.getAsJsonObject("result") : null;
            r.retriedAs = str(o, "retriedAs");
            if (o.has("placedBy") && o.get("placedBy").isJsonObject()) {
                JsonObject by = o.getAsJsonObject("placedBy");
                r.placedById = str(by, "id");
                r.placedByName = str(by, "name");
            }
            return r;
        }

        OrderStage stage() {
            return stage == null ? OrderStage.legacy(status) : stage;
        }

        /** The generated W-064 full names for a frozen package total, or null for legacy free-name orders. */
        JsonArray generatedNames() {
            if (packageLabel == null || packageTotal <= 0) return null;
            return PackageLabel.generate(packageLabel, packageTotal);
        }

        void advance(OrderStage next) throws OrderException {
            if (!stage().allows(next))
                throw new OrderException(409, "Order cannot move from " + stage() + " to " + next, -1);
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
        final List<Order> archived = new ArrayList<>();
        final Map<String, Receipt> receipts = new LinkedHashMap<>();
        long persistenceRevision;

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
    /** Injected clock (#142): domain timestamps never read the wall clock directly, so
     * timeouts (auto-cancel, recovering, deferred retry) are deterministic in tests. */
    private final LongSupplier clock;

    private OrderBookRepository repository;
    private final Map<IndexStore.StashKey, Book> books = new ConcurrentHashMap<>();
    private final Map<String, Book> byOrderId = new ConcurrentHashMap<>();

    OrderStore(Path dataDir, IndexStore index) {
        this(dataDir, index, System::currentTimeMillis);
    }

    OrderStore(Path dataDir, IndexStore index, LongSupplier clock) {
        this.dir = dataDir.resolve("orders");
        this.index = index;
        this.clock = clock;
    }

    private volatile KitTemplates kits;

    /** The shared kit catalog (loaded once; the routes layer reuses this instance). */
    synchronized KitTemplates kits() throws IOException {
        if (kits == null) kits = new KitTemplates(dir.getParent());
        return kits;
    }

    void load() throws IOException {
        var storage = new OrderBookRepository(index.stock());
        var restored = new LinkedHashMap<IndexStore.StashKey, Book>();
        var orderIds = new HashSet<String>();
        var receiptKeys = new HashSet<String>();
        storage.load(dir, snapshot -> {
            var root = snapshot.payload();
            var b = new Book(snapshot.server(), snapshot.stash());
            b.persistenceRevision = snapshot.revision();
            if (root.has("receipts")) {
                for (var entry : root.getAsJsonObject("receipts").entrySet()) {
                    if (!receiptKeys.add(entry.getKey())) throw new IllegalArgumentException("Duplicate order receipt");
                    b.receipts.put(entry.getKey(), Receipt.read(entry.getValue().getAsJsonObject()));
                }
            }
            for (String key : List.of("orders", "archived")) {
                if ("archived".equals(key) && !root.has(key)) continue;
                for (var entry : root.getAsJsonArray(key)) {
                    var order = Order.fromJson(entry.getAsJsonObject());
                    if (!b.server.equals(order.server) || !b.stash.equals(order.stash) || !orderIds.add(order.id))
                        throw new IllegalArgumentException("Invalid or duplicate persisted order scope");
                    ("orders".equals(key) ? b.orders : b.archived).add(order);
                }
            }
            trimArchive(b);
            b.orders.sort((x, y) -> Long.compare(x.createdAt, y.createdAt));
            if (restored.putIfAbsent(new IndexStore.StashKey(b.server, b.stash), b) != null)
                throw new IllegalArgumentException("Duplicate persisted order book");
        });
        synchronized (index) {
            repository = storage;
            books.clear();
            byOrderId.clear();
            books.putAll(restored);
            for (Book b : restored.values()) {
                for (Order order : b.orders) byOrderId.put(order.id, b);
            }
        }
        // Complete previously committed registry side effects before serving any new requests.
        // Never advertise readiness while the durable outbox still has work to replay.
        synchronized (index) {
            for (Book b : books.values()) {
                synchronized (b) {
                    for (Order order : List.copyOf(b.orders)) finishPendingUuidDeregistrations(b, order);
                }
            }
            // A Manager restart must not reset an already expired cancellation.
            for (String server : books.keySet().stream()
                    .map(IndexStore.StashKey::server)
                    .distinct()
                    .toList()) autoHeal(server);
        }
    }

    private Book book(String server, String stash) {
        return books.computeIfAbsent(new IndexStore.StashKey(server, stash), k -> new Book(server, stash));
    }

    private void save(Book b) throws IOException {
        save(b, null);
    }

    private void save(Book b, StockLedger.BoxRelease release) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("server", b.server);
        root.addProperty("stash", b.stash);
        JsonArray list = new JsonArray();
        for (Order o : b.orders) list.add(o.snapshot());
        root.add("orders", list);
        if (!b.archived.isEmpty()) {
            var archived = new JsonArray();
            for (Order o : b.archived) archived.add(o.snapshot());
            root.add("archived", archived);
        }
        if (!b.receipts.isEmpty()) {
            var receipts = new JsonObject();
            b.receipts.forEach((key, receipt) -> receipts.add(key, receipt.json()));
            root.add("receipts", receipts);
        }
        if (repository == null) throw new IOException("Order storage has not been loaded");
        b.persistenceRevision = repository.commit(b.server, b.stash, b.persistenceRevision, root, release);
    }

    private void update(Book book, Order previous, Order next) throws IOException {
        update(book, previous, next, null);
    }

    private void update(Book book, Order previous, Order next, StockLedger.BoxRelease release) throws IOException {
        int position = book.orders.indexOf(previous);
        var before = book.orders.size() > KEEP ? List.copyOf(book.orders) : null;
        var archiveBefore = before == null ? null : List.copyOf(book.archived);
        book.orders.set(position, next);
        var pruned = prune(book);
        try {
            save(book, release);
        } catch (IOException failure) {
            if (before != null && !pruned.isEmpty()) {
                book.orders.clear();
                book.orders.addAll(before);
                book.archived.clear();
                book.archived.addAll(archiveBefore);
            } else book.orders.set(position, previous);
            throw failure;
        }
        pruned.forEach(order -> byOrderId.remove(order.id, book));
    }

    /** Moves the oldest finished orders beyond KEEP out of the operational order list. */
    private List<Order> prune(Book b) {
        if (b.orders.size() <= KEEP) return List.of();
        var pruned = new ArrayList<Order>();
        int excess = b.orders.size() - KEEP;
        for (int i = 0; i < b.orders.size() && excess > 0; ) {
            Order o = b.orders.get(i);
            if (o.stage().archiveable() && (o.allocations == null || o.claimedAt == 0 || o.releasedAt > 0)) {
                b.orders.remove(i);
                b.archived.add(o);
                pruned.add(o);
                excess--;
            } else {
                i++;
            }
        }
        if (!pruned.isEmpty()) trimArchive(b);
        return pruned;
    }

    private static void trimArchive(Book book) {
        book.archived.sort(java.util.Comparator.comparingLong(order -> order.updatedAt));
        int excess = book.archived.size() - KEEP;
        if (excess > 0) book.archived.subList(0, excess).clear();
    }

    JsonArray auditOrders(Users.Principal who, java.util.function.Predicate<Order> matches, int limit) {
        synchronized (index) {
            var rows = new ArrayList<Order>();
            for (Book book : books.values()) {
                if (!who.perms().seesStash(book.stash)) continue;
                synchronized (book) {
                    for (var orders : List.of(book.orders, book.archived)) {
                        for (Order order : orders) if (matches.test(order)) rows.add(order);
                    }
                }
            }
            rows.sort(java.util.Comparator.<Order>comparingLong(order -> order.updatedAt)
                    .reversed()
                    .thenComparing(order -> order.id));
            var out = new JsonArray();
            rows.stream().limit(limit).map(Order::auditJson).forEach(out::add);
            return out;
        }
    }

    int stuckOrders(String server, long now) {
        int stuck = 0;
        for (Book book : books.values()) {
            if (!book.server.equals(server)) continue;
            synchronized (book) {
                for (Order order : book.orders) {
                    OrderStage stage = order.stage();
                    // A packed order waiting for its recipient is normal, not a stuck operation.
                    if (stage == OrderStage.PICKUP_READY && !holdsPhysical(order)) continue;
                    // Recovery is expected right after a restart or fence: it only
                    // counts as stuck when nothing has moved it for a while.
                    if ((stage == OrderStage.RECOVERING || stage == OrderStage.NEEDS_RECONCILIATION)
                            && now - order.updatedAt < RECOVERING_STUCK_AFTER_MS) continue;
                    if (stage == OrderStage.RECOVERING
                            || stage == OrderStage.NEEDS_RECONCILIATION
                            || (!stage.archiveable()
                                    && stage != OrderStage.CREATED
                                    && stage != OrderStage.RESERVED
                                    && now - order.updatedAt >= AUTO_CANCEL_AFTER_MS)
                            // A reservation that was never claimed: either its source changed
                            // under it (fingerprint mismatch on every claim skips silently without
                            // touching updatedAt) or the bot has been down for a long time.
                            // Both deserve the alert; fresh reservations never trip it.
                            || (stage == OrderStage.RESERVED
                                    && order.allocations != null
                                    && now - order.updatedAt >= AUTO_CANCEL_AFTER_MS)) stuck++;
                }
            }
        }
        return stuck;
    }

    /**
     * Orders in recovery right now, however fresh: the health view tells "recovering"
     * (expected, transient) apart from "stuck" (no progress for {@link #RECOVERING_STUCK_AFTER_MS}).
     */
    int recoveringOrders(String server) {
        int recovering = 0;
        for (Book book : books.values()) {
            if (!book.server.equals(server)) continue;
            synchronized (book) {
                for (Order order : book.orders) {
                    OrderStage stage = order.stage();
                    if (stage == OrderStage.RECOVERING || stage == OrderStage.NEEDS_RECONCILIATION) recovering++;
                }
            }
        }
        return recovering;
    }

    /** Count actual order reservations separately from lifecycle/recovery health. */
    int reservationHoldingOrders(String server) {
        return (int) ordersOnServer(server).stream()
                .filter(OrderStore::holdsPhysical)
                .count();
    }

    Map<String, Integer> activeStates(Users.Principal who) {
        var totals = new java.util.TreeMap<String, Integer>();
        for (Book book : books.values()) {
            if (!who.perms().seesStash(book.stash)) continue;
            synchronized (book) {
                for (Order order : book.orders)
                    if (!order.stage().archiveable()) {
                        totals.merge(order.stage().name(), 1, Integer::sum);
                    }
            }
        }
        return totals;
    }

    private static Map<String, Long> reserved(Book b) {
        Map<String, Long> out = new HashMap<>();
        for (Order o : b.orders) {
            // The waiting queue is intentionally *not* a physical or aggregate
            // reservation. Availability must not advertise it as committed stock.
            if (o.reservationDeferred || (!OPEN.contains(o.status) && !holdsPhysical(o))) continue;
            for (Line l : explodeOrderLines(o)) out.merge(l.ref(), l.count, Long::sum);
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
        return order.allocations != null
                && order.releasedAt == 0
                && (OPEN.contains(order.status) || order.claimedAt > 0);
    }

    /** UI-independent sweep; one indexed server is reconciled under its normal lock. */
    void selfHealAll() throws IOException {
        for (String server : books.keySet().stream()
                .map(IndexStore.StashKey::server)
                .distinct()
                .toList()) releaseFromFreshObservation(server);
    }

    void releaseFromFreshObservation(String server) throws IOException {
        synchronized (index) {
            // Expire silent operations first: without this, only opening the operations view
            // (or an agent heartbeat) ever flips them to NEEDS_RECONCILIATION, and the 15-second
            // scheduler below would keep seeing a healthy IN_PROGRESS forever.
            new PhysicalOperations(index.stock()).sweepStale();
            PhysicalReservations observations = null;
            for (var entry : books.entrySet()) {
                if (!entry.getKey().server().equals(server)) continue;
                var book = entry.getValue();
                synchronized (book) {
                    var released = new ArrayList<Order>();
                    for (var order : book.orders) {
                        if (!holdsPhysical(order)
                                || !FINISHED.contains(order.status)
                                || order.stage() == OrderStage.NEEDS_RECONCILIATION) continue;
                        if (observations == null) observations = PhysicalReservations.read(index.stock(), server, null);
                        if (observations.refreshedAfter(order.allocations, order.updatedAt)
                                && !index.stock().unsettledPhysicalOrder(order.id)) {
                            released.add(order);
                            order.releasedAt = clock.getAsLong();
                        }
                    }
                    if (!released.isEmpty()) {
                        try {
                            save(book);
                        } catch (IOException failure) {
                            released.forEach(order -> order.releasedAt = 0);
                            throw failure;
                        }
                    }
                }
            }
            autoHeal(server);
        }
    }

    /** Timeout for unconfirmed cancellations and legacy, unallocated orders. */
    static final long AUTO_CANCEL_AFTER_MS = 10 * 60 * 1000;
    /**
     * Recovery without any progress for this long counts as stuck. Matches the
     * self-heal timeout so a freshly fenced order never pages the dashboard.
     */
    static final long RECOVERING_STUCK_AFTER_MS = AUTO_CANCEL_AFTER_MS;

    /**
     * A rollback the bot never finishes must not hold stock forever. The bot-driven fast path
     * (a cancelled/failed report) is untouched; only a rollback silent for this long fails
     * automatically and releases its holds through the normal sweep.
     */
    static final long RECOVERING_AFTER_MS = 30 * 60 * 1000;

    /**
     * Packed orders release their holds this long after the last bot report when the bot stays
     * silent. Overridable with -Dwesort.pickupReadyReleaseMs=&lt;ms&gt; (tests use old timestamps
     * against the default instead).
     */
    static long pickupReadyReleaseAfterMs() {
        return Long.getLong("wesort.pickupReadyReleaseMs", 30 * 60 * 1000);
    }

    static final long DEFERRED_RETRY_AFTER_MS = 5 * 60 * 1000;

    /**
     * Self-healing on fresh indexes and periodic sweeps, without operator actions. A
     * matching original source slot is not proof that packing or staging succeeded.
     * Uncertain orders terminate only after their physical journals settle and reserved
     * sources are freshly observed; otherwise all holds remain protected.
     */
    private void autoHeal(String server) throws IOException {
        long now = clock.getAsLong();
        for (var entry : books.entrySet()) {
            if (!entry.getKey().server().equals(server)) continue;
            var book = entry.getValue();
            synchronized (book) {
                // Copy-on-write through update(): a failed save rolls back that order only,
                // exactly like the release sweep above.
                for (Order previous : List.copyOf(book.orders)) {
                    Order next;
                    try {
                        next = healed(book, previous, now);
                    } catch (OrderException conflict) {
                        continue;
                    }
                    if (next != null) update(book, previous, next);
                }
            }
        }
    }

    /** One order's automatic step, or null when it stays as it is. Copy-on-write: the caller saves. */
    private Order healed(Book book, Order previous, long now) throws IOException, OrderException {
        if (previous.stage() == OrderStage.NEEDS_RECONCILIATION) {
            // Migrate a pre-upgrade order to the same automatic rollback
            // protocol as new failures. Never expose the old operator state.
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.RECOVERING);
            o.status = "in_progress";
            o.cancelRequested = true;
            if (o.cancelRequestedAt == 0) o.cancelRequestedAt = now;
            o.phase = "returning items to stash";
            o.message = "Automatic rollback requested after interrupted physical operation";
            o.updatedAt = now;
            o.record("order.auto-recovery.migrate", Users.OWNER_ID, now);
            return o;
        }
        if (previous.stage() != OrderStage.RECOVERING
                && (previous.status.equals("accepted") || previous.status.equals("in_progress"))
                && index.stock().reconciliationRequiredPhysicalOrder(previous.id)) {
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.RECOVERING);
            o.status = "in_progress";
            o.cancelRequested = true;
            o.cancelRequestedAt = now;
            o.phase = "returning items to stash";
            o.message = "Physical operation interrupted; automatic rollback requested";
            o.updatedAt = now;
            o.record("order.auto-recovery", Users.OWNER_ID, now);
            return o;
        }
        if (previous.cancelRequested
                && previous.allocations != null
                && isExecuting(previous)
                && previous.stage() != OrderStage.RECOVERING
                && previous.cancelRequestedAt > 0
                && now - previous.cancelRequestedAt > AUTO_CANCEL_AFTER_MS) {
            // The deadline runs on the cancel request alone: repeated bot progress reports
            // (which touch updatedAt) must not defer the rollback forever.
            // Keep the allocation and original agent, rather than treating a
            // missing bot acknowledgement as permission to abandon items.
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.RECOVERING);
            o.status = "in_progress";
            o.phase = "returning items to stash";
            o.message = "Cancel timed out; automatic rollback requested";
            o.updatedAt = now;
            o.record("order.auto-rollback", Users.OWNER_ID, now);
            return o;
        }
        if (previous.cancelRequested
                && previous.allocations == null
                && (previous.status.equals("accepted") || previous.status.equals("in_progress"))
                && previous.cancelRequestedAt > 0
                && now - previous.cancelRequestedAt > AUTO_CANCEL_AFTER_MS
                && now - previous.updatedAt > AUTO_CANCEL_AFTER_MS) {
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled automatically: requested, bot silent, nothing held";
            o.updatedAt = now;
            o.record("order.auto-force-cancel", Users.OWNER_ID, now);
            return o;
        }
        if (previous.stage() == OrderStage.RECOVERING && now - previous.updatedAt > RECOVERING_AFTER_MS) {
            // The exit a silent bot never takes: fail the rollback so its holds release
            // through the normal sweep. A reporting bot still finishes first via the
            // cancelled/failed fast path, which this never pre-empts.
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.FAILED);
            o.status = "failed";
            o.phase = "automatic rollback timed out";
            o.message = "Automatic rollback timed out waiting for the agent;"
                    + " holds release on the next verified observation";
            o.updatedAt = now;
            o.record("order.auto-recovery.timeout", Users.OWNER_ID, now);
            return o;
        }
        if (previous.stage() == OrderStage.PICKUP_READY
                && previous.releasedAt == 0
                && now - previous.updatedAt > pickupReadyReleaseAfterMs()) {
            // A packed order whose bot went silent: the goods are staged and waiting,
            // so the hold may lapse while the order itself stays collectible.
            Order o = Order.fromJson(previous.snapshot());
            o.releasedAt = now;
            o.message = "Released automatically: packed, bot silent, goods await pickup";
            o.updatedAt = now;
            o.record("order.pickup-release", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval != Approval.PENDING
                && previous.allocations != null
                && previous.claimedAt == 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.updatedAt > AUTO_CANCEL_AFTER_MS) {
            // A reservation the bot never picked up must not gate the sorting lease
            // forever. Nothing was ever touched, so cancelling releases cleanly.
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled automatically: reserved but never claimed by a bot";
            o.updatedAt = now;
            o.record("order.auto-cancel-unclaimed", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval != Approval.PENDING
                && previous.allocations == null
                && previous.claimedAt == 0
                && previous.deferredBlockedAt > 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.deferredBlockedAt > AUTO_CANCEL_AFTER_MS
                && !index.stock().unsettledPhysicalOrder(previous.id)) {
            // Scout runda 3: a drift-stamped aggregate reservation that could never
            // be re-reserved. Nothing was ever touched, so cancelling releases
            // cleanly instead of parking the queue forever with no human to intervene.
            Order o = Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message =
                    "cancelled automatically: sources changed and stock could not be re-reserved; nothing was taken";
            o.updatedAt = now;
            o.record("order.auto-cancel-drift", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval == Approval.PENDING
                && previous.claimedAt == 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.updatedAt > AUTO_CANCEL_AFTER_MS) {
            // #59: no human can approve. A pending approval that waits forever gates
            // the shared stock and the single agent's queue, so default-deny releases
            // cleanly after a bounded wait. Claims always skip PENDING approvals, so
            // the bot never touched anything; the order must simply be placed again.
            Order o = Order.fromJson(previous.snapshot());
            o.approval = Approval.REJECTED;
            o.decidedBy = Users.OWNER_ID;
            o.decidedAt = now;
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled automatically: approval pending too long with no approver; nothing was taken";
            o.updatedAt = now;
            o.record("order.approval.auto-reject", Users.OWNER_ID, now);
            return o;
        }
        return null;
    }

    record Reservation(String order, PhysicalReservations.Allocation allocation) {}

    /** Orders waiting on a human decision: PENDING with approval still PENDING, in the viewer's scope. */
    int pendingApprovals(Users.Principal who) {
        synchronized (index) {
            int n = 0;
            for (var book : books.values()) {
                if (!who.perms().seesStash(book.stash)) continue;
                synchronized (book) {
                    for (Order o : book.orders) if (o.status.equals("pending") && o.approval == Approval.PENDING) n++;
                }
            }
            return n;
        }
    }

    /** Open orders with a requested cancel: visible so a silent bot cannot hide them, in the viewer's scope. */
    int cancelRequestedOpen(Users.Principal who) {
        synchronized (index) {
            int n = 0;
            for (var book : books.values()) {
                if (!who.perms().seesStash(book.stash)) continue;
                synchronized (book) {
                    for (Order o : book.orders) if (o.cancelRequested && OPEN.contains(o.status)) n++;
                }
            }
            return n;
        }
    }

    List<Reservation> physicalHolds(String server) {
        var holds = new ArrayList<Reservation>();
        for (var order : ordersOnServer(server))
            if (holdsPhysical(order)) {
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
            if (!heldRoots(server).isEmpty()) return null;
            if (hasOpenOnServer(server)) {
                // A new unreserved order arriving during compaction must NOT
                // revoke the current lease mid-click. Allow ONLY this same
                // active holder to finish its in-flight physical transaction,
                // then it releases the lease before the queued order can claim.
                boolean allDeferred = ordersOnServer(server).stream()
                        .filter(o -> OPEN.contains(o.status))
                        .allMatch(o -> o.reservationDeferred);
                AutomationGate.Lease current = index.automation().current(server);
                if (!allDeferred
                        || current == null
                        || !current.state().equals("ACTIVE")
                        || !current.agent().equals(agent)
                        || !current.token().equals(token)) return null;
            }
            return index.automation().acquire(server, agent, token);
        }
    }

    /**
     * Whether any order still owns work on this server. A RECOVERING order is a fenced,
     * interrupted claim whose agent may be dead: it must not block lease renewal for everyone.
     * Such an order is only resumable by its own agent through {@link #resumeClaim}, whose
     * agent check and rollback keep the recovery fail-closed while the sorting slot stays open.
     */
    boolean hasOpenOnServer(String server) {
        for (var entry : books.entrySet()) {
            if (!entry.getKey().server().equals(server)) continue;
            synchronized (entry.getValue()) {
                if (entry.getValue().orders.stream()
                        .anyMatch(order -> OPEN.contains(order.status) && order.stage() != OrderStage.RECOVERING))
                    return true;
            }
        }
        return false;
    }

    // ---------- browser ----------

    /** Validates (against the current index and open orders), merges duplicate lines, stores and returns the order. */
    Order place(String server, String stash, List<Line> requested, JsonObject names)
            throws OrderException, IOException {
        return place(server, stash, requested, names, null, null);
    }

    /** The same, recording who placed it (byId null: unknown, counted as the owner's). */
    Order place(String server, String stash, List<Line> requested, JsonObject names, String byId, String byName)
            throws OrderException, IOException {
        return place(server, stash, requested, names, byId, byName, null, OrderIntent.DEFAULT);
    }

    /**
     * The same with an explicit offline-queue request: when verified counts fall short, the order
     * may be queued as pending against last-verified counts instead of failing. Without the flag
     * an expired source keeps its shortage (400/409, no order row).
     */
    Order place(String server, String stash, List<Line> requested, JsonObject names, boolean queueWhenStale)
            throws OrderException, IOException {
        return submit(
                        server,
                        stash,
                        requested,
                        names,
                        null,
                        null,
                        null,
                        null,
                        OrderIntent.DEFAULT,
                        null,
                        false,
                        ignored -> null,
                        queueWhenStale)
                .order();
    }

    Order place(
            String server,
            String stash,
            List<Line> requested,
            JsonObject names,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent)
            throws OrderException, IOException {
        return submit(
                        server,
                        stash,
                        requested,
                        names,
                        null,
                        byId,
                        byName,
                        organizationId,
                        intent,
                        null,
                        false,
                        ignored -> null)
                .order();
    }

    Submission submit(
            String server,
            String stash,
            List<Line> requested,
            JsonObject names,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            RecipientResolver recipient)
            throws OrderException, IOException {
        return submit(
                server,
                stash,
                requested,
                names,
                null,
                byId,
                byName,
                organizationId,
                intent,
                idempotencyKey,
                needsApproval,
                recipient);
    }

    Submission submit(
            String server,
            String stash,
            List<Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            RecipientResolver recipient)
            throws OrderException, IOException {
        return submit(
                server,
                stash,
                requested,
                names,
                packageLabel,
                byId,
                byName,
                organizationId,
                intent,
                idempotencyKey,
                needsApproval,
                recipient,
                false);
    }

    Submission submit(
            String server,
            String stash,
            List<Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            RecipientResolver recipient,
            boolean queueWhenStale)
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
                body.add("packageLabel", packageLabel == null ? JsonNull.INSTANCE : packageLabel);
                body.add("intent", Json.GSON.toJsonTree(intent));
                body.addProperty("queueWhenStale", queueWhenStale);
                fingerprint = hash(body);
                var saved = previouslyReceived(key);
                if (saved != null) return new Submission(replay(saved, fingerprint), false);
            }
            return new Submission(
                    placeWithObservationLock(
                            server,
                            stash,
                            requested,
                            names,
                            packageLabel,
                            byId,
                            byName,
                            organizationId,
                            intent,
                            key,
                            fingerprint,
                            null,
                            needsApproval,
                            recipient.resolve(intent),
                            queueWhenStale),
                    true);
        }
    }

    private Order placeWithObservationLock(
            String server,
            String stash,
            List<Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String receiptKey,
            String fingerprint,
            Order retryOf,
            boolean needsApproval,
            String recipient,
            boolean queueWhenStale)
            throws OrderException, IOException {
        // A sorting lease cannot coexist with a new physical reservation.
        // Accept a transparently UNRESERVED request and let the sorter finish
        // or reconcile before any later claim. Never reject the customer's order
        // just because an autonomous tier-optimization pass is in progress.
        boolean sortingLeaseActive = index.automation().current(server) != null;
        if (requested.isEmpty()) throw new OrderException(400, "an order needs at least one line", -1);
        if (requested.size() > MAX_LINES) throw new OrderException(400, "at most " + MAX_LINES + " lines", -1);
        IndexStore.OrderCounts fresh = index.orderCounts(server, stash);
        if (fresh == null) throw new OrderException(400, "no index for that stash", -1);

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
        var held = heldRoots(server);
        boolean executingPhysical =
                existing.stream().anyMatch(order -> order.allocations != null && isExecuting(order));
        // Never extend an agent's already issued protected-root list mid-order.
        // Persist the request instead, explicitly unreserved until the next
        // claim revalidates the actual physical slots against fresh observations.
        if (executingPhysical || sortingLeaseActive) {
            KitExplosion pinned = explodeKits(merged, firstIndex, kits());
            for (Line line : merged.values())
                if (line.type().equals("craft")
                        && (line.count() > CraftingCatalog.MAX_PREVIEW
                                || CraftingCatalog.RECIPES == null
                                || CraftingCatalog.RECIPES.producing(line.key()).isEmpty()))
                    throw new OrderException(
                            400, "No supported craft recipe or invalid craft quantity", firstIndex.get(line.ref()));
            return commitOrder(
                    server,
                    stash,
                    merged,
                    firstIndex,
                    pinned,
                    new CraftExpansion(),
                    names,
                    packageLabel,
                    byId,
                    byName,
                    organizationId,
                    intent,
                    receiptKey,
                    fingerprint,
                    retryOf,
                    needsApproval,
                    recipient,
                    fresh,
                    physical,
                    held,
                    false,
                    true);
        }
        if (!held.isEmpty()
                && index.stock().sources().stream()
                        .anyMatch(source -> source.server().equals(server)
                                && source.status().equals("SUPERSEDED")
                                && held.stream()
                                        .anyMatch(root -> root.source()
                                                .equals(source.value().get("id").getAsString())))) {
            throw new OrderException(
                    409, "A reserved physical source changed topology and requires reconciliation", -1);
        }
        boolean legacyHold =
                existing.stream().anyMatch(order -> order.allocations == null && OPEN.contains(order.status));
        if (physical.hasSnapshots() && legacyHold
                || !physical.hasSnapshots()
                        && (!held.isEmpty()
                                || merged.values().stream().anyMatch(line -> line.type.equals("shulker")))) {
            throw new OrderException(409, "Resolve existing reservations before mixing source observation formats", -1);
        }
        // Kit lines reserve their components, never a "kit:" ref: explode against
        // the current templates and pin the snapshots on the order, so the bot
        // packs the exact layout even if the template changes later.
        KitExplosion exploded = explodeKits(merged, firstIndex, kits());
        CraftExpansion crafting = expandCrafts(server, stash, exploded, physical, held, firstIndex);
        try {
            return commitOrder(
                    server,
                    stash,
                    merged,
                    firstIndex,
                    exploded,
                    crafting,
                    names,
                    packageLabel,
                    byId,
                    byName,
                    organizationId,
                    intent,
                    receiptKey,
                    fingerprint,
                    retryOf,
                    needsApproval,
                    recipient,
                    fresh,
                    physical,
                    held,
                    false,
                    false);
        } catch (OrderException shortfall) {
            // The offline queue is explicit, never silent: only a caller that opts in with
            // queueWhenStale may fall back to last-verified counts. An expired source on the
            // default path keeps the original shortage (400/409, no order row), so stale stock
            // is never reserved behind the requester's back. Claim still requires a fresh
            // observation, so the bot re-verifies before it touches anything. Ported from PR #89.
            if (!queueWhenStale || !isShortage(shortfall)) throw shortfall;
            IndexStore.OrderCounts stale = index.orderCountsIncludingStale(server, stash);
            if (stale == null) throw shortfall;
            try {
                return commitOrder(
                        server,
                        stash,
                        merged,
                        firstIndex,
                        exploded,
                        crafting,
                        names,
                        packageLabel,
                        byId,
                        byName,
                        organizationId,
                        intent,
                        receiptKey,
                        fingerprint,
                        retryOf,
                        needsApproval,
                        recipient,
                        stale,
                        physical,
                        held,
                        true,
                        false);
            } catch (OrderException retryFailed) {
                throw isShortage(retryFailed) ? shortfall : retryFailed;
            }
        }
    }

    /** A 400/409 against one order line: a real shortage, not a policy refusal. */
    private static boolean isShortage(OrderException e) {
        return e.line >= 0 && (e.status == 400 || e.status == 409);
    }

    /** Craft outputs remain order lines; only their reserved materials become physical lines. */
    private static final class CraftExpansion {
        final Map<String, Line> lines = new LinkedHashMap<>();
        final Map<String, Integer> firstIndex = new HashMap<>();
        final Map<String, Long> materials = new LinkedHashMap<>();
        final JsonArray plans = new JsonArray();

        void add(Line line, int at) {
            var existing = lines.get(line.ref());
            lines.put(
                    line.ref(),
                    existing == null
                            ? line
                            : new Line(line.type(), line.key(), Math.addExact(existing.count(), line.count())));
            firstIndex.merge(line.ref(), at, Math::min);
        }
    }

    /**
     * Resolve recipe alternatives before reserving any stock. Direct lines and
     * kits take precedence; multiple craft requests share one diminishing pool.
     * Only fresh, physical, base-ID ingredient slots can back a craft.
     */
    private CraftExpansion expandCrafts(
            String server,
            String stash,
            KitExplosion exploded,
            PhysicalReservations physical,
            Set<PhysicalReservations.Root> held,
            Map<String, Integer> firstIndex)
            throws IOException, OrderException {
        CraftExpansion result = new CraftExpansion();
        boolean wantsCraft =
                exploded.lines.values().stream().anyMatch(l -> l.type().equals("craft"));
        for (var l : exploded.lines.values())
            if (!l.type().equals("craft")) result.add(l, exploded.firstIndex.get(l.ref()));
        if (!wantsCraft) return result;
        if (!physical.hasSnapshots() || !index.craftingReady(server, stash))
            throw new OrderException(409, "Crafting requires a ready agent, table and fresh physical stock", -1);

        Map<String, Long> pool = new HashMap<>(CraftingCatalog.eligiblePool(physical.available(held)));
        // Already ordered direct components must not also be used as craft inputs.
        for (Line l : result.lines.values())
            if (l.type().equals("item") && pool.containsKey(l.key()))
                pool.put(l.key(), Math.max(0, pool.get(l.key()) - l.count()));

        for (var l : exploded.lines.values()) {
            if (!l.type().equals("craft")) continue;
            int at = firstIndex.get(l.ref());
            if (l.count() > CraftingCatalog.MAX_PREVIEW)
                throw new OrderException(400, "Too many craftable items in one order", at);
            var preview = CraftingCatalog.plan(l.key(), (int) l.count(), pool);
            if (!preview.ok() || preview.plan().count() < l.count())
                throw new OrderException(409, "Not enough verified ingredients to craft " + l.key(), at);

            var step = new JsonObject();
            step.addProperty("item", l.key());
            step.addProperty("count", l.count());
            step.addProperty("recipeVersion", CraftingCatalog.VERSION);
            step.add("materials", Json.GSON.toJsonTree(preview.plan().take()));
            var recipeSteps = new JsonArray();
            for (var one : preview.plan().steps()) {
                var rs = new JsonObject();
                rs.addProperty("recipe", one.recipe().id());
                rs.addProperty("crafts", one.crafts());
                recipeSteps.add(rs);
            }
            step.add("steps", recipeSteps);
            result.plans.add(step);
            try {
                CraftingCatalog.consume(pool, preview.plan().take());
            } catch (IllegalArgumentException invalid) {
                throw new OrderException(409, "Crafting material reservation changed", at);
            }
            for (var need : preview.plan().take().entrySet()) {
                result.materials.merge(need.getKey(), (long) need.getValue(), Math::addExact);
                result.add(new Line("item", need.getKey(), need.getValue()), at);
            }
        }
        return result;
    }

    private Order commitOrder(
            String server,
            String stash,
            Map<String, Line> merged,
            Map<String, Integer> firstIndex,
            KitExplosion exploded,
            CraftExpansion crafting,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String receiptKey,
            String fingerprint,
            Order retryOf,
            boolean needsApproval,
            String recipient,
            IndexStore.OrderCounts counts,
            PhysicalReservations physical,
            Set<PhysicalReservations.Root> held,
            boolean allowStale,
            boolean deferReservation)
            throws OrderException, IOException {
        var allocations = !deferReservation && physical.hasSnapshots()
                ? physical.allocate(crafting.lines.values(), held, crafting.firstIndex, allowStale)
                : null;
        Book b = book(server, stash);
        synchronized (b) {
            long open = b.orders.stream().filter(o -> OPEN.contains(o.status)).count();
            if (open >= MAX_OPEN)
                throw new OrderException(400, "too many open orders for this stash (limit " + MAX_OPEN + ")", -1);
            Map<String, Long> reserved = reserved(b);
            for (Line l : crafting.lines.values()) {
                int at = crafting.firstIndex.get(l.ref());
                long total = counts.total(l.type, l.key);
                if (deferReservation) continue;
                if (total < 0 && allocations == null) throw new OrderException(400, "not in the stash: " + l.key, at);
                long available = Math.max(0, total - reserved.getOrDefault(l.ref(), 0L));
                if (allocations == null && l.count > available) {
                    throw new OrderException(
                            400, "only " + available + " available for " + l.key + " (asked for " + l.count + ")", at);
                }
            }
            long shulkers = shulkersNeeded(merged.values(), counts);
            if (shulkers > MAX_SHULKERS) {
                throw new OrderException(
                        400,
                        "too big: this order needs " + shulkers + " shulkers, and one order can fill at most "
                                + MAX_SHULKERS + " (one double chest)",
                        -1);
            }
            long now = clock.getAsLong();
            Order o = new Order();
            o.id = newId();
            o.server = server;
            o.stash = stash;
            o.organizationId = organizationId;
            o.intent = intent;
            o.approval = needsApproval ? Approval.PENDING : Approval.DIRECT;
            o.verifiedRecipient = recipient;
            o.lines = new ArrayList<>(merged.values());
            o.craftPlans = crafting.plans.isEmpty() ? null : crafting.plans.deepCopy();
            o.craftMaterials = crafting.materials.isEmpty() ? null : Map.copyOf(crafting.materials);
            o.names = names == null ? null : names.deepCopy();
            o.packageLabel = packageLabel == null ? null : packageLabel.deepCopy();
            o.packageTotal = (int) shulkers;
            o.allocations = allocations;
            // Pinned kit layouts: the bot packs exactly these slots even if the
            // template changes or is deleted after submit.
            if (!exploded.snapshots.isEmpty()) o.kits = new LinkedHashMap<>(exploded.snapshots);
            // A stale-backed reservation is an explicitly queued offline order, never a
            // verified reservation: visibly marked in every API response, and unclaimable
            // until a fresh observation re-verifies it (claim path stays fresh-only).
            o.queuedOffline = allowStale;
            o.reservationDeferred = deferReservation;
            o.sourceGuards = deferReservation ? null : sourceGuards(server, stash);
            o.createdAt = now;
            o.updatedAt = now;
            o.status = "pending";
            o.stage = OrderStage.CREATED;
            if (!deferReservation) o.advance(OrderStage.RESERVED);
            else o.message = "Queued after an active physical order; stock is NOT reserved until verified at claim.";
            o.placedById = byId;
            o.placedByName = byId == null ? null : byName;
            o.record(
                    deferReservation ? "order.queue.wait" : retryOf == null ? "order.reserve" : "order.retry.reserve",
                    byId == null ? Users.OWNER_ID : byId,
                    now);
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

    Order retry(
            String id,
            String byId,
            String byName,
            boolean mayOrderForGuild,
            String organizationId,
            GuildProfile.ApprovalPolicy policy,
            RecipientResolver recipient)
            throws OrderException, IOException {
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
                    throw new OrderException(
                            403, "Your role cannot retry an order for the guild or another recipient", -1);
                }
                if (old.retriedAs != null) {
                    throw new OrderException(409, "This order was already placed again as " + old.retriedAs, -1);
                }
                if (holdsPhysical(old)) {
                    var holders = ordersOnServer(book.server).stream()
                            .filter(OrderStore::holdsPhysical)
                            .map(order -> order.id)
                            .sorted()
                            .limit(6)
                            .toList();
                    throw new OrderException(
                            409,
                            "Wait for verified release of the previous reservation (still held by: "
                                    + String.join(", ", holders)
                                    + ")",
                            -1);
                }
                return placeWithObservationLock(
                        book.server,
                        book.stash,
                        old.lines,
                        old.names,
                        old.packageLabel,
                        byId,
                        byName,
                        old.organizationId == null ? organizationId : old.organizationId,
                        old.intent,
                        null,
                        null,
                        old,
                        policy.requiresApproval(old.intent),
                        recipient.resolve(old.intent),
                        false);
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
        String m = mode != null
                        && mode.isJsonPrimitive()
                        && mode.getAsJsonPrimitive().isString()
                ? mode.getAsString()
                : null;
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
            if (a.size() > MAX_SHULKERS)
                throw new OrderException(400, "names.names has more than " + MAX_SHULKERS + " entries", -1);
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

    /**
     * Validates and normalizes a W-064 package label: the user-chosen text part of the automatic
     * {@code "WeSort (X/Y) - <text>"} name. Returns null for an absent label (the caller then uses the
     * default "Beställning"), otherwise {@code {mode:"all", text}} or {@code {mode:"each", texts:[...]}}.
     */
    static JsonObject normalizePackageLabel(JsonElement e) throws OrderException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new OrderException(400, "packageLabel must be an object or null", -1);
        try {
            return PackageLabel.normalize(e.getAsJsonObject());
        } catch (IllegalArgumentException invalid) {
            throw new OrderException(400, invalid.getMessage(), -1);
        }
    }

    /** The trimmed name ("" when blank), or 400 when it is too long or has § or a control character. */
    private static String checkName(String raw, String field) throws OrderException {
        String name = raw.strip();
        if (name.length() > MAX_NAME)
            throw new OrderException(400, field + " is longer than " + MAX_NAME + " characters", -1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') throw new OrderException(400, field + " can't contain §", -1);
            if (Character.isISOControl(c))
                throw new OrderException(400, field + " can't contain control characters", -1);
            // Invisible or direction-changing characters (bidi overrides, zero-width, line separators) and broken
            // surrogates
            int type = Character.getType(c);
            if (type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || (type == Character.SURROGATE
                            && !(Character.isHighSurrogate(c)
                                    && i + 1 < name.length()
                                    && Character.isLowSurrogate(name.charAt(i + 1)))
                            && !(Character.isLowSurrogate(c)
                                    && i > 0
                                    && Character.isHighSurrogate(name.charAt(i - 1))))) {
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
            // One kit is exactly one box, however its slots are filled.
            if (l.type.equals("kit")) {
                units += l.count;
            } else if (l.type.equals("shulker") || counts.isShulkerBoxKey(l.key)) {
                units += l.count;
            } else {
                // Craft-only results can be absent from the index. Without a
                // verified stack-size sample assume 1, never undercount boxes.
                long max = l.type.equals("craft") ? 1 : counts.maxStack(l.key);
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

    /** No positive units: nothing verified (or nothing at all). Ported from PR #89. */
    private static boolean isEmpty(IndexStore.OrderCounts counts) {
        if (counts == null) return true;
        return counts.items().values().stream().noneMatch(n -> n > 0)
                && counts.shulkers().values().stream().noneMatch(n -> n > 0);
    }

    /** { orders: [newest first], available: { items: {key: n}, shulkers: {key: n} } } */
    JsonObject listJson(String server, String stash) {
        // Repair stale cancellations during normal dashboard polling as well as
        // startup. Physical holds are *not* released by this status transition.
        synchronized (index) {
            try {
                autoHeal(server);
            } catch (IOException ignored) {
                // Keep the previous durable state when disk/source verification fails.
            }
        }
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        Book b = books.get(new IndexStore.StashKey(server, stash));
        Map<String, Long> reserved = Map.of();
        if (b != null) {
            synchronized (b) {
                for (int i = b.orders.size() - 1; i >= 0; i--)
                    list.add(b.orders.get(i).toJson());
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
                    // No bot online: verified counts read zero, but the stash is still orderable
                    // (queued) against the last observation. Report that, so the shop and the
                    // submit check agree; claim still requires a fresh observation.
                    if (isEmpty(counts) && (physicalAvailable == null || physicalAvailable.isEmpty())) {
                        var stale = index.orderCountsIncludingStale(server, stash);
                        if (!isEmpty(stale)) {
                            counts = stale;
                            if (physical.hasSnapshots())
                                physicalAvailable = physical.available(heldRoots(server), null, true);
                        }
                    }
                }
            } catch (IOException ignored) {
                counts = null;
            }
        }
        JsonObject items = new JsonObject();
        JsonObject shulkers = new JsonObject();
        JsonObject kitsOut = new JsonObject();
        if (physicalAvailable != null) {
            physicalAvailable.forEach((ref, count) -> {
                int split = ref.indexOf(':');
                (ref.startsWith("item:") ? items : shulkers).addProperty(ref.substring(split + 1), count);
            });
            kitsAvailableFromRefs(physicalAvailable, reserved, kitsOut);
        } else if (counts != null) {
            for (String key : counts.items().keySet()) {
                items.addProperty(
                        key, Math.max(0, counts.total("item", key) - reserved.getOrDefault("item:" + key, 0L)));
            }
            for (String key : counts.shulkers().keySet()) {
                shulkers.addProperty(
                        key, Math.max(0, counts.total("shulker", key) - reserved.getOrDefault("shulker:" + key, 0L)));
            }
            kitsAvailableFromCounts(counts, reserved, kitsOut);
        }
        available.add("items", items);
        available.add("shulkers", shulkers);
        available.add("kits", kitsOut);
        available.add(
                "craftables",
                CraftingCatalog.available(
                        physicalAvailable == null ? Map.of() : physicalAvailable,
                        index.craftingReady(server, stash) && physicalAvailable != null));
        out.add("available", available);
        return out;
    }

    /**
     * Whole kits buildable from unreserved stock: the scarcest component decides.
     * A kit whose components are unknown (or which has none) is omitted, never
     * presented as buildable or as empty.
     */
    private void kitsAvailableFromCounts(IndexStore.OrderCounts counts, Map<String, Long> reserved, JsonObject out) {
        for (var t : allKits()) {
            var need = t.components();
            if (need.isEmpty()) continue;
            long buildable = Long.MAX_VALUE;
            for (var e : need.entrySet()) {
                long total = counts.total("item", e.getKey());
                if (total < 0) {
                    buildable = -1;
                    break;
                }
                long avail = Math.max(0, total - reserved.getOrDefault("item:" + e.getKey(), 0L));
                buildable = Math.min(buildable, avail / e.getValue());
            }
            if (buildable >= 0) out.addProperty(t.name(), buildable);
        }
    }

    private void kitsAvailableFromRefs(
            Map<String, Long> physicalAvailable, Map<String, Long> reserved, JsonObject out) {
        for (var t : allKits()) {
            var need = t.components();
            if (need.isEmpty()) continue;
            long buildable = Long.MAX_VALUE;
            for (var e : need.entrySet()) {
                String ref = "item:" + e.getKey();
                Long total = physicalAvailable.get(ref);
                if (total == null) {
                    buildable = -1;
                    break;
                }
                long avail = Math.max(0, total - reserved.getOrDefault(ref, 0L));
                buildable = Math.min(buildable, avail / e.getValue());
            }
            if (buildable >= 0) out.addProperty(t.name(), buildable);
        }
    }

    private List<KitTemplates.Template> allKits() {
        try {
            return kits().all();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    /**
     * Removes every finished order (delivered, failed, cancelled) of a stash and returns how many. Open orders are
     * never touched: they hold reservations and the bot may be working on them.
     */
    int clearFinished(String server, String stash) throws IOException {
        synchronized (index) {
            return clearFinishedWithObservationLock(server, stash);
        }
    }

    private int clearFinishedWithObservationLock(String server, String stash) throws IOException {
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return 0;
        synchronized (b) {
            var removable = b.orders.stream()
                    .filter(o -> o.stage().archiveable() && !holdsPhysical(o))
                    .toList();
            if (removable.isEmpty()) return 0;
            var before = List.copyOf(b.orders);
            var archiveBefore = List.copyOf(b.archived);
            b.orders.removeAll(removable);
            b.archived.addAll(removable);
            trimArchive(b);
            try {
                save(b);
            } catch (IOException failure) {
                b.orders.clear();
                b.orders.addAll(before);
                b.archived.clear();
                b.archived.addAll(archiveBefore);
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
                Order next = Order.fromJson(before.snapshot());
                next.approval = decision;
                next.decidedBy = actor;
                next.decidedAt = next.updatedAt = clock.getAsLong();
                if (!approve) {
                    next.advance(OrderStage.CANCELLED);
                    next.status = "cancelled";
                }
                next.record(approve ? "order.approve" : "order.reject", actor, next.updatedAt);
                update(book, before, next);
                return new ApprovalDecision(next, true);
            }
        }
    }

    /** pending -> cancelled now; accepted/in_progress -> cancelRequested; finished -> 409. */
    Order cancel(String id) throws OrderException, IOException {
        return cancel(id, Users.OWNER_ID);
    }

    Order cancel(String id, String actor) throws OrderException, IOException {
        synchronized (index) {
            return cancelWithObservationLock(id, actor);
        }
    }

    private Order cancelWithObservationLock(String id, String actor) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        // Every touch heals: a legacy needs_reconciliation order migrates to the
        // automatic rollback protocol here, not only on dashboard polls.
        autoHeal(b.server);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.snapshot());
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.status.equals("pending")) {
                o.advance(OrderStage.CANCELLED);
                o.status = "cancelled";
                o.message = "Cancelled before the bot started.";
            } else if (!o.cancelRequested) {
                o.cancelRequested = true;
                o.cancelRequestedAt = clock.getAsLong();
            } else return previous;
            o.updatedAt = clock.getAsLong();
            o.record(o.cancelRequested ? "order.cancel.request" : "order.cancel", actor, o.updatedAt);
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
        return forceCancel(id, Users.OWNER_ID);
    }

    Order forceCancel(String id, String actor) throws OrderException, IOException {
        synchronized (index) {
            return forceCancelWithObservationLock(id, actor);
        }
    }

    private Order forceCancelWithObservationLock(String id, String actor) throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        // Every touch heals, so a legacy needs_reconciliation order is already
        // migrating to RECOVERING by the time the checks below run.
        autoHeal(b.server);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.snapshot());
            if (FINISHED.contains(o.status)) throw new OrderException(409, "order is already " + o.status, -1);
            if (o.allocations != null) {
                throw new OrderException(
                        409,
                        "The agent must finish automatic physical rollback first;"
                                + " the owner can release it unconditionally via owner-force",
                        -1);
            }
            if (!o.cancelRequested || o.status.equals("pending")) {
                throw new OrderException(
                        409, "request a cancel first; force cancel is for orders the bot hasn't confirmed", -1);
            }
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled from the website (bot didn't confirm)";
            o.updatedAt = clock.getAsLong();
            o.record("order.force-cancel", actor, o.updatedAt);
            update(b, previous, o);
            return o;
        }
    }

    /**
     * The escape hatch for every stuck shape: the owner releases an order unconditionally,
     * setting a terminal state and the release timestamp despite remaining allocations.
     * The full trail (including the abandoned allocation roots) stays in the audit history.
     * Only exposed through the owner-only route; nothing else in the codebase calls this.
     */
    Order ownerForceCancel(String id, String actor) throws OrderException, IOException {
        synchronized (index) {
            Book b = byOrderId.get(id);
            if (b == null) throw new OrderException(404, "no such order", -1);
            synchronized (b) {
                Order previous = find(b, id);
                if (previous == null) throw new OrderException(404, "no such order", -1);
                if (previous.stage().archiveable())
                    throw new OrderException(409, "order is already " + previous.status, -1);
                Order o = Order.fromJson(previous.snapshot());
                // Deliberately bypasses advance(): PICKUP_READY has no FAILED edge, and this
                // path must terminate every non-terminal stage, allocations or not.
                o.stage = OrderStage.FAILED;
                o.status = "failed";
                o.releasedAt = o.updatedAt = clock.getAsLong();
                o.message = "Released by the owner without bot confirmation; verify the stash by hand.";
                o.record("order.owner-force", actor, o.updatedAt);
                update(b, previous, o);
                return o;
            }
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
                    if (active == null
                            && !o.status.equals("pending")
                            && (requester == null || requester.equals(placedBy))) {
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
                    else if (o.cancelRequested && (o.status.equals("accepted") || o.status.equals("in_progress")))
                        cancel.add(o.id);
                }
            }
        }
        out.addProperty("pending", pending);
        out.add("cancel", cancel);
        return out;
    }

    private JsonObject sourceGuards(String server, String stash) throws IOException {
        var out = new JsonObject();
        for (var source : index.stock().sources())
            if (source.server().equals(server)
                    && source.stash().equals(stash)
                    && source.status().equals("FRESH")
                    && source.value().has("slotFingerprint"))
                out.add(source.value().get("id").getAsString(), source.value().get("slotFingerprint"));
        return out.isEmpty() ? null : out;
    }

    private boolean matchesSources(Order order) throws IOException {
        if (order.allocations != null) {
            return PhysicalReservations.read(index.stock(), order.server, order.stash)
                    .matches(order.allocations);
        }
        if (order.sourceGuards == null) return true; // Earlier protocol orders retain their existing aggregate checks.
        var now = sourceGuards(order.server, order.stash);
        if (now == null) return false;
        for (var expected : order.sourceGuards.entrySet())
            if (!expected.getValue().equals(now.get(expected.getKey()))) return false;
        return true;
    }

    private static boolean isExecuting(Order order) {
        return order.status.equals("accepted") || order.status.equals("in_progress");
    }

    boolean hasActiveWork(String server, String agent) {
        return agent != null
                && ordersOnServer(server).stream().anyMatch(order -> agent.equals(order.agent) && isExecuting(order));
    }

    Order claim(String server, String stash) throws IOException {
        synchronized (index) {
            return claimWithObservationLock(server, stash, "local");
        }
    }

    /**
     * Resume only this agent's exact durable checkpoint. A restart interrupts
     * packing: never replay the original allocation against changed stock.
     * This path cannot claim an unrelated queued order.
     */
    JsonObject resumeClaim(String server, String stash, String agent, String id) throws IOException {
        if (agent == null) return null;
        synchronized (index) {
            Book book = books.get(new IndexStore.StashKey(server, stash));
            if (book == null) return null;
            synchronized (book) {
                Order order = find(book, id);
                if (order == null)
                    order = book.archived.stream()
                            .filter(row -> id.equals(row.id))
                            .findFirst()
                            .orElse(null);
                if (order == null) {
                    var receipt = repository.resumeReceipt(server, stash, agent, id);
                    if (receipt != null) order = Order.fromJson(receipt);
                }
                if (order == null || !agent.equals(order.agent)) return null;
                if (FINISHED.contains(order.status)) {
                    if (index.stock().unsettledPhysicalOrder(id)) return null;
                    if (order.stage() == OrderStage.PICKUP_READY && order.manifest == null) return null;
                    var result = order.toJson();
                    result.addProperty("resumeComplete", true);
                    return result;
                }
                if (!isExecuting(order)) return null;
                if (order.stage() != OrderStage.RECOVERING) {
                    var updated = Order.fromJson(order.snapshot());
                    try {
                        updated.advance(OrderStage.RECOVERING);
                    } catch (OrderException invalid) {
                        return null;
                    }
                    updated.status = "in_progress";
                    updated.cancelRequested = true;
                    updated.cancelRequestedAt = updated.updatedAt = clock.getAsLong();
                    updated.phase = "returning items to stash";
                    updated.message = "Agent restarted; automatically returning interrupted order";
                    updated.record("order.resume.rollback", agent, updated.updatedAt);
                    update(book, order, updated);
                    order = updated;
                }
                return claimView(server, order);
            }
        }
    }

    JsonObject claimPayload(String server, String stash, String agent) throws IOException {
        synchronized (index) {
            Order order = claimWithObservationLock(server, stash, agent);
            return order == null ? null : claimView(server, order);
        }
    }

    private JsonObject claimView(String server, Order order) {
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

    /**
     * Promote one request from the unreserved wait queue into an exact physical
     * reservation. Runs under the same index + book lock as normal claims.
     *
     * A shortage, stale source, unresolved old hold or changed recipe leaves the
     * request visibly pending; it must never be dispatched with invented slots.
     */
    private Order reserveDeferredAtClaim(Book book, Order queued, IndexStore.OrderCounts fresh) throws IOException {
        try {
            PhysicalReservations physical = PhysicalReservations.read(index.stock(), book.server, book.stash);
            Set<PhysicalReservations.Root> held = heldRoots(book.server);
            var exploded = new KitExplosion();
            // Components come from the order's pinned kit templates, not today's
            // editable kit definitions. Craft output lines are re-planned from
            // newly verified ingredients only after the current work has ended.
            for (Line line : explodeOrderLines(queued)) {
                Line previous = exploded.lines.get(line.ref());
                exploded.lines.put(
                        line.ref(),
                        previous == null
                                ? line
                                : new Line(line.type(), line.key(), Math.addExact(previous.count(), line.count())));
                exploded.firstIndex.putIfAbsent(line.ref(), 0);
            }
            for (Line line : queued.lines) {
                if (!line.type().equals("craft")) continue;
                exploded.lines.put(line.ref(), line);
                exploded.firstIndex.put(line.ref(), 0);
            }
            CraftExpansion crafting =
                    expandCrafts(book.server, book.stash, exploded, physical, held, exploded.firstIndex);
            List<PhysicalReservations.Allocation> allocations = null;
            if (physical.hasSnapshots()) {
                // Physical and old aggregate reservations may not be mixed.
                if (ordersOnServer(book.server).stream()
                        .anyMatch(o -> o != queued
                                && o.allocations == null
                                && !o.reservationDeferred
                                && OPEN.contains(o.status))) return null;
                allocations = physical.allocate(crafting.lines.values(), held, crafting.firstIndex);
            } else {
                if (!held.isEmpty()
                        || crafting.lines.values().stream()
                                .anyMatch(l -> l.type().equals("shulker"))) return null;
                Map<String, Long> reserved = reserved(book);
                for (Line line : crafting.lines.values()) {
                    long total = fresh.total(line.type(), line.key());
                    if (total < 0 || line.count() > Math.max(0, total - reserved.getOrDefault(line.ref(), 0L)))
                        return null;
                }
            }
            Order ready = Order.fromJson(queued.snapshot());
            ready.allocations = allocations;
            ready.craftPlans = crafting.plans.isEmpty() ? null : crafting.plans.deepCopy();
            ready.craftMaterials = crafting.materials.isEmpty() ? null : Map.copyOf(crafting.materials);
            ready.sourceGuards = sourceGuards(book.server, book.stash);
            ready.reservationDeferred = false;
            ready.deferredBlockedAt = 0;
            ready.advance(OrderStage.RESERVED);
            ready.message = null;
            ready.updatedAt = clock.getAsLong();
            ready.record("order.reserve.from-queue", Users.OWNER_ID, ready.updatedAt);
            update(book, queued, ready);
            return ready;
        } catch (OrderException | ArithmeticException notAvailable) {
            // Never convert an unverified queue entry into a physical claim.
            return null;
        }
    }

    /**
     * Only unallocated, never-claimed work may be failed without an agent receipt.
     * An explicitly queued offline order retains its original wait semantics.
     * Retry before applying the deadline so replenished stock can still fulfill it.
     */
    private void deferUnavailable(Book book, Order queued) throws IOException {
        if (!queued.reservationDeferred
                || queued.allocations != null
                || queued.claimedAt != 0
                || queued.agent != null
                || queued.stage() != OrderStage.CREATED
                || index.stock().unsettledPhysicalOrder(queued.id)) return;
        long now = clock.getAsLong();
        if (queued.deferredBlockedAt > 0
                && (queued.queuedOffline || now - queued.deferredBlockedAt < DEFERRED_RETRY_AFTER_MS)) return;
        Order next = Order.fromJson(queued.snapshot());
        next.updatedAt = now;
        if (next.deferredBlockedAt == 0) {
            next.deferredBlockedAt = now;
            next.message = "Stock unavailable for this order; trying other queued orders.";
            next.record("order.queue.defer", Users.OWNER_ID, now);
        } else {
            // CREATED -> FAILED is legal and no physical transaction has started.
            next.stage = OrderStage.FAILED;
            next.status = "failed";
            next.phase = "stock unavailable";
            next.message =
                    "Automatically failed: stock could not be reserved after repeated attempts. No items were taken.";
            next.record("order.queue.auto-fail", Users.OWNER_ID, now);
        }
        update(book, queued, next);
    }

    private Order claimWithObservationLock(String server, String stash, String agent) throws IOException {
        // Every touch heals: timed-out rollbacks, silent packed orders and never-claimed
        // reservations resolve here too, not only on dashboard polls.
        autoHeal(server);
        var counts = index.orderCounts(server, stash);
        if (counts == null || index.automation().current(server) != null) return null;
        for (var running : ordersOnServer(server)) {
            if (!isExecuting(running)) continue;
            boolean sameAgent = agent != null && agent.equals(running.agent);
            if ((running.allocations != null || sameAgent) && (!sameAgent || !stash.equals(running.stash))) return null;
        }
        Book b = books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return null;
        synchronized (b) {
            for (Order order : b.orders) {
                if (isExecuting(order)) return orderSourcesReady(order, counts, agent) ? order : null;
            }
            for (Order original : List.copyOf(b.orders)) {
                if (!original.status.equals("pending") || original.approval == Approval.PENDING) continue;
                Order order = original;
                if (order.reservationDeferred) {
                    order = reserveDeferredAtClaim(b, order, counts);
                    // Preserve FIFO among claimable orders, not among requests
                    // that cannot reserve any stock. No physical work started.
                    if (order == null) {
                        deferUnavailable(b, original);
                        continue;
                    }
                }
                if (!orderSourcesReady(order, counts, agent)) {
                    // A RESERVED aggregate order whose source fingerprints drifted was
                    // skipped silently forever: no stamp, no re-reservation, no expiry.
                    // No human can intervene, so the server heals itself: fresh stock
                    // that still covers every line re-reserves atomically under the
                    // index lock; genuine shortage is stamped once and auto-cancelled
                    // below after a bounded wait.
                    boolean recovered = false;
                    if (order.allocations == null
                            && order.claimedAt == 0
                            && order.sourceGuards != null
                            && order.lines.stream().allMatch(line -> counts.total(line.type, line.key) >= line.count)) {
                        JsonObject prev = order.sourceGuards;
                        order.sourceGuards = sourceGuards(server, stash);
                        if (orderSourcesReady(order, counts, agent)) {
                            long now = clock.getAsLong();
                            order.updatedAt = now;
                            order.message = "Re-reserved automatically: sources changed since reservation.";
                            order.record("order.reserve.refresh", agent, now);
                            save(b);
                            recovered = true;
                        } else {
                            order.sourceGuards = prev;
                        }
                    }
                    if (!recovered) {
                        if (order.allocations == null
                                && order.claimedAt == 0
                                && order.deferredBlockedAt == 0
                                && order.stage() != OrderStage.RECOVERING) {
                            long now = clock.getAsLong();
                            order.deferredBlockedAt = now;
                            order.updatedAt = now;
                            order.message = "Sources changed since reservation; awaiting automatic re-reservation.";
                            order.record("order.reserve.drift", agent, now);
                            save(b);
                        }
                        continue;
                    }
                }
                long before = order.updatedAt;
                if (!order.stage().allows(OrderStage.PACKING)) continue;
                order.stage = OrderStage.PACKING;
                order.status = "accepted";
                order.claimedAt = order.updatedAt = clock.getAsLong();
                // Legacy aggregate claims may omit identity. An authenticated agent keeps one claim across its stashes.
                order.agent = order.allocations == null && "local".equals(agent) ? null : agent;
                order.record("order.claim", agent, order.claimedAt);
                try {
                    save(b);
                } catch (IOException failure) {
                    order.stage = OrderStage.RESERVED;
                    order.status = "pending";
                    order.claimedAt = 0;
                    order.updatedAt = before;
                    order.agent = null;
                    order.history.remove(order.history.size() - 1);
                    throw failure;
                }
                return order;
            }
            return null;
        }
    }

    private boolean orderSourcesReady(Order order, IndexStore.OrderCounts counts, String agent) throws IOException {
        if (order.agent != null && !order.agent.equals(agent)) return false;
        // A recovering agent must be allowed to retrieve its existing items
        // even after source fingerprints changed during the interruption.
        if (order.stage() == OrderStage.RECOVERING) return order.agent != null && order.agent.equals(agent);
        if (order.allocations == null) {
            return matchesSources(order)
                    && order.lines.stream().allMatch(line -> counts.total(line.type, line.key) >= line.count);
        }
        return agent != null && matchesSources(order);
    }

    /** A status report from the bot. Fields are validated by the route; the move is checked here. */
    Order status(String id, String status, String phase, JsonObject progress, String message, JsonObject result)
            throws OrderException, IOException {
        return status(id, status, phase, progress, message, result, "local");
    }

    Order status(
            String id,
            String status,
            String phase,
            JsonObject progress,
            String message,
            JsonObject result,
            String agent)
            throws OrderException, IOException {
        return status(id, status, phase, progress, message, result, agent, null);
    }

    Order status(
            String id,
            String status,
            String phase,
            JsonObject progress,
            String message,
            JsonObject result,
            String agent,
            String reportId)
            throws OrderException, IOException {
        return status(id, status, phase, progress, message, new JsonArray(), result, agent, reportId);
    }

    Order status(
            String id,
            String status,
            String phase,
            JsonObject progress,
            String message,
            JsonArray deliveredUuids,
            JsonObject result,
            String agent,
            String reportId)
            throws OrderException, IOException {
        synchronized (index) {
            var report = new JsonObject();
            report.addProperty("status", status);
            report.addProperty("phase", phase);
            report.add("progress", progress == null ? JsonNull.INSTANCE : progress);
            report.addProperty("message", message);
            report.add("result", result == null ? JsonNull.INSTANCE : result);
            // These UUIDs are a physical side effect of the report and MUST be bound to its receipt.
            report.add("deliveredUuids", deliveredUuids == null ? new JsonArray() : deliveredUuids.deepCopy());
            String fingerprint = hash(report);
            String key =
                    receiptKey("report:" + id, agent, reportId == null ? "content:" + fingerprint : "id:" + reportId);
            var saved = previouslyReceived(key);
            if (saved != null) return replay(saved, fingerprint);
            return statusWithObservationLock(
                    id, status, phase, progress, message, deliveredUuids, result, agent, key, fingerprint);
        }
    }

    private Order statusWithObservationLock(
            String id,
            String status,
            String phase,
            JsonObject progress,
            String message,
            JsonArray deliveredUuids,
            JsonObject result,
            String agent,
            String key,
            String fingerprint)
            throws OrderException, IOException {
        Book b = byOrderId.get(id);
        if (b == null) throw new OrderException(404, "no such order", -1);
        synchronized (b) {
            Order previous = find(b, id);
            if (previous == null) throw new OrderException(404, "no such order", -1);
            Order o = Order.fromJson(previous.snapshot());
            if (o.agent != null && !o.agent.equals(agent)) {
                throw new OrderException(403, "Order belongs to another agent", -1);
            }
            if (!allowed(o.status, status)) {
                throw new OrderException(409, "can't go from " + o.status + " to " + status, -1);
            }
            OrderStage next = reportedStage(o, status, result);
            o.advance(next);
            o.status = switch (next) {
                case PICKUP_READY -> "packed";
                case RECOVERING -> "in_progress";
                case FAILED -> "failed";
                default -> status;
            };
            if (phase != null) o.phase = phase;
            if (progress != null) o.progress = progress;
            if (message != null) o.message = message;
            if (next == OrderStage.RECOVERING && status.equals("delivered")) {
                // #68: keep the frozen-Y reason visible. A partial report with covered lines
                // fails closed here; without this the dashboard only shows a generic recovery.
                long reported = exactPositiveCount(result == null ? null : result.get("shulkers"));
                if (o.packageTotal > 0 && reported > 0 && reported != o.packageTotal)
                    o.message = "delivered " + reported + " of frozen " + o.packageTotal
                            + " packages; returning items to stash"
                            + (message == null ? "" : " | agent: " + message);
            }
            if (next == OrderStage.RECOVERING) {
                o.result = null;
                o.cancelRequested = true;
                if (o.cancelRequestedAt == 0) o.cancelRequestedAt = clock.getAsLong();
                o.phase = "returning items to stash";
            } else if (result != null) o.result = result;
            o.updatedAt = clock.getAsLong();
            if (next != previous.stage()) o.record("order.transition", agent, o.updatedAt);
            // Warehouse staging is not external export: do not retire physical identities here.
            StockLedger.BoxRelease release = null;
            b.receipts.put(key, new Receipt(fingerprint, o.toJson()));
            try {
                update(b, previous, o, release);
            } catch (IOException failure) {
                b.receipts.remove(key);
                if (failure instanceof StockLedger.BoxReleaseConflict)
                    throw new OrderException(409, failure.getMessage(), -1);
                throw failure;
            }
            return find(b, id);
        }
    }

    /**
     * A client-supplied UUID is never proof of ownership. A registered box may leave the
     * registry only when its exact physical root was reserved for this order. Unregistered
     * (newly assembled) boxes need no registry mutation.
     */
    static StockLedger.BoxRelease boxRelease(Order order, JsonArray reported) {
        if (reported == null || reported.isEmpty()) return null;
        var uuids = new java.util.LinkedHashSet<String>();
        for (var element : reported)
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) uuids.add(element.getAsString());
        var roots = new HashSet<PhysicalReservations.Root>();
        if (order.allocations != null)
            for (var allocation : order.allocations)
                if (allocation.outer() && allocation.ref().startsWith("shulker:")) roots.add(allocation.root());
        return new StockLedger.BoxRelease(roots, uuids);
    }

    /** Crash-safe replay of registry removals committed to a saved order snapshot. */
    private void finishPendingUuidDeregistrations(Book book, Order order) throws IOException {
        if (order.pendingUuidDeregistrations == null || order.pendingUuidDeregistrations.isEmpty()) return;
        Order cleared = Order.fromJson(order.snapshot());
        cleared.pendingUuidDeregistrations = null;
        update(book, order, cleared, boxRelease(order, order.pendingUuidDeregistrations));
    }

    private OrderStage reportedStage(Order order, String status, JsonObject result) throws IOException {
        if (status.equals("accepted") || status.equals("in_progress"))
            return order.stage() == OrderStage.RECOVERING ? OrderStage.RECOVERING : OrderStage.PACKING;
        // Legacy agents may still report this outcome. It is an automatic
        // failure, not a human-facing reconciliation workflow.
        if (status.equals("needs_reconciliation")) return OrderStage.FAILED;
        if (status.equals("delivered")) {
            order.manifest = null;
            // A late completion cannot undo a cancellation or an already started rollback.
            if (order.cancelRequested || order.stage() == OrderStage.RECOVERING) return OrderStage.RECOVERING;
            long packages = exactPositiveCount(result == null ? null : result.get("shulkers"));
            // #68: the frozen box count Y is authoritative. A partial report with covered
            // lines must never become PICKUP_READY with a smaller Y: fail closed to
            // RECOVERING so the missing boxes are packed against the same frozen total.
            boolean countMatches = order.packageTotal <= 0 || packages == order.packageTotal;
            if (order.agent != null
                    && packages > 0
                    && packages <= MAX_SHULKERS
                    && countMatches
                    && allLinesStaged(order.lines, result)
                    && !index.stock().unsettledPhysicalOrder(order.id)) {
                order.manifest =
                        index.stock().stagingManifest(order.server, order.stash, order.id, order.agent, (int) packages);
            }
            // A terminal report without a native staging receipt is *not*
            // terminal: order the same agent to reclaim staged packages first.
            // Returning FAILED here would let the agent delete its recovery
            // checkpoint after a successful HTTP response.
            return order.manifest != null ? OrderStage.PICKUP_READY : OrderStage.RECOVERING;
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
                hint.addProperty(
                        "packageCount", manifest.getAsJsonArray("packages").size());
                return hint;
            }
            return null;
        }
    }

    ManifestReceipt acknowledgeManifest(String id, BotHub.ManifestScope scope, String hash)
            throws IOException, OrderException {
        var book = byOrderId.get(id);
        if (book == null) throw new OrderException(404, "No manifest in this scope", -1);
        synchronized (book) {
            var order = find(book, id);
            var manifest = scopedManifest(order, scope);
            if (manifest == null) throw new OrderException(404, "No manifest in this scope", -1);
            if (!hash.equals(manifest.get("hash").getAsString()))
                throw new OrderException(409, "Manifest hash differs", -1);
            if (order.manifestAcknowledgedAt > 0) return new ManifestReceipt(order.manifestAcknowledgedAt, false);
            var updated = Order.fromJson(order.snapshot());
            updated.manifestAcknowledgedAt = clock.getAsLong();
            updated.record("order.manifest.confirm", scope.agent(), updated.manifestAcknowledgedAt);
            update(book, order, updated);
            return new ManifestReceipt(updated.manifestAcknowledgedAt, true);
        }
    }

    private static JsonObject scopedManifest(Order order, BotHub.ManifestScope scope) {
        if (order == null
                || order.stage() != OrderStage.PICKUP_READY
                || order.manifest == null
                || !scope.server().equals(order.server)
                || !scope.stash().equals(order.stash)
                || !scope.agent().equals(order.agent)) return null;
        return PackageManifest.forAgent(order.manifest, scope.dimension());
    }

    static boolean allLinesStaged(List<Line> lines, JsonObject result) {
        if (result == null || !result.has("lines") || !result.get("lines").isJsonArray()) return false;
        var outstanding = new HashMap<String, Long>();
        for (var line : lines) outstanding.merge(line.key(), line.count(), Long::sum);
        for (var entry : result.getAsJsonArray("lines")) {
            if (!entry.isJsonObject()) return false;
            var line = entry.getAsJsonObject();
            var key = line.get("key");
            if (key == null
                    || !key.isJsonPrimitive()
                    || !key.getAsJsonPrimitive().isString()) return false;
            var count = outstanding.remove(key.getAsString());
            if (count == null || count.longValue() != exactPositiveCount(line.get("delivered"))) return false;
        }
        return outstanding.isEmpty();
    }

    /** Never let Gson's narrowing conversions truncate fractions or wrap overflowing quantities. */
    static long exactPositiveCount(JsonElement value) {
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) return -1;
        try {
            long count = value.getAsBigDecimal().longValueExact();
            return count > 0 ? count : -1;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return -1;
        }
    }

    /**
     * Forward moves only: accepted -> accepted/in_progress/finished, in_progress -> in_progress/finished.
     * (Repeating the current status is how the bot reports phase and progress.) Pending and finished orders
     * can't be changed by the bot.
     */
    static boolean allowed(String from, String to) {
        if (from.equals("accepted"))
            return to.equals("accepted") || to.equals("in_progress") || REPORTED_TERMINAL.contains(to);
        if (from.equals("in_progress")) return to.equals("in_progress") || REPORTED_TERMINAL.contains(to);
        return false;
    }
}
