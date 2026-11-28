package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.wesort.data.PackageLabel;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR3 Step 5: order placement (validation, kit/craft expansion, idempotency
 * receipts and the submit path), moved verbatim out of OrderStore (no
 * behavior change, Refs #142). Runs under the OrderStore locks; the host owns
 * the books, this service owns the path.
 */
final class OrderPlacementService {
    private final OrderStore host;
    private final ReservationPolicy policy;

    OrderPlacementService(OrderStore host, ReservationPolicy policy) {
        this.host = host;
        this.policy = policy;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Kit lines exploded to their component item lines: one kit is exactly one
     * box, so ordering N kits of a template reserves N times every slot.
     * Errors point at the original kit line, naming the missing component.
     */
    static final class KitExplosion {
        /** Component item lines merged with any real item lines (ref to line). */
        final Map<String, OrderStore.Line> lines = new LinkedHashMap<>();
        /** Every line ref (exploded or plain) to the request index for error reporting. */
        final Map<String, Integer> firstIndex = new HashMap<>();
        /** Template snapshots to pin on the order (name to template JSON). */
        final Map<String, JsonObject> snapshots = new LinkedHashMap<>();
    }

    /**
     * Explodes merged kit lines using the current templates. Unknown kits are
     * 404 (like unknown stashes); shortages are reported against components.
     */
    static KitExplosion explodeKits(
            Map<String, OrderStore.Line> merged, Map<String, Integer> firstIndex, KitTemplates kits)
            throws OrderStore.OrderException, IOException {
        var out = new KitExplosion();
        for (var l : merged.values()) {
            if (!l.type().equals("kit")) {
                var prev = out.lines.get(l.ref());
                out.lines.put(
                        l.ref(), prev == null ? l : new OrderStore.Line(l.type(), l.key(), prev.count() + l.count()));
                out.firstIndex.putIfAbsent(l.ref(), firstIndex.get(l.ref()));
                continue;
            }
            var template = kits.get(l.key());
            if (template == null)
                throw new OrderStore.OrderException(404, "no such kit: " + l.key(), firstIndex.get(l.ref()));
            out.snapshots.put(l.key(), template.json());
            for (var entry : template.components().entrySet()) {
                var need = new OrderStore.Line("item", entry.getKey(), entry.getValue() * l.count());
                var prev = out.lines.get(need.ref());
                out.lines.put(
                        need.ref(),
                        prev == null ? need : new OrderStore.Line("item", need.key(), prev.count() + need.count()));
                // A component shared with a real item line reports the earliest line.
                out.firstIndex.merge(need.ref(), firstIndex.get(l.ref()), Math::min);
            }
            if (template.components().isEmpty())
                throw new OrderStore.OrderException(400, "kit '" + l.key() + "' is empty", firstIndex.get(l.ref()));
        }
        return out;
    }
    /** Explodes an order's kit lines from its pinned snapshots (no live templates needed). */
    static List<OrderStore.Line> explodeOrderLines(OrderStore.Order o) {
        var out = new ArrayList<OrderStore.Line>();
        for (OrderStore.Line l : o.lines) {
            if (l.type().equals("craft")) continue;
            if (!l.type().equals("kit") || o.kits == null || !o.kits.containsKey(l.key())) {
                out.add(l);
                continue;
            }
            var slots = o.kits.get(l.key()).getAsJsonArray("slots");
            var perKit = new LinkedHashMap<String, Long>();
            for (var e : slots) {
                var s = e.getAsJsonObject();
                perKit.merge(s.get("key").getAsString(), s.get("count").getAsLong(), Long::sum);
            }
            for (var e : perKit.entrySet()) out.add(new OrderStore.Line("item", e.getKey(), e.getValue() * l.count()));
        }
        if (o.craftMaterials != null)
            o.craftMaterials.forEach((key, count) -> out.add(new OrderStore.Line("item", key, count)));
        return out;
    }

    static String receiptKey(String action, String actor, String requestId) {
        var parts = new JsonArray();
        parts.add(action);
        parts.add(actor);
        parts.add(requestId);
        return hash(parts);
    }

    static String hash(JsonElement value) {
        return com.porter.wesort.data.ContainerIdentity.hash(com.porter.wesort.data.CanonicalJson.stringify(value));
    }

    OrderStore.Receipt previouslyReceived(String key) {
        for (OrderStore.Book book : host.books.values()) {
            synchronized (book) {
                var saved = book.receipts.get(key);
                if (saved != null) return saved;
            }
        }
        return null;
    }

    static OrderStore.Order replay(OrderStore.Receipt saved, String fingerprint) throws OrderStore.OrderException {
        if (!saved.fingerprint().equals(fingerprint)) {
            throw new OrderStore.OrderException(
                    409, "This idempotency key has already been used for different content", -1);
        }
        return OrderStore.Order.fromJson(saved.order());
    }
    /** Validates (against the current index and open orders), merges duplicate lines, stores and returns the order. */
    OrderStore.Order place(String server, String stash, List<OrderStore.Line> requested, JsonObject names)
            throws OrderStore.OrderException, IOException {
        return place(server, stash, requested, names, null, null);
    }

    /** The same, recording who placed it (byId null: unknown, counted as the owner's). */
    OrderStore.Order place(
            String server, String stash, List<OrderStore.Line> requested, JsonObject names, String byId, String byName)
            throws OrderStore.OrderException, IOException {
        return place(server, stash, requested, names, byId, byName, null, OrderIntent.DEFAULT);
    }

    /**
     * The same with an explicit offline-queue request: when verified counts fall short, the order
     * may be queued as pending against last-verified counts instead of failing. Without the flag
     * an expired source keeps its shortage (400/409, no order row).
     */
    OrderStore.Order place(
            String server, String stash, List<OrderStore.Line> requested, JsonObject names, boolean queueWhenStale)
            throws OrderStore.OrderException, IOException {
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

    OrderStore.Order place(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent)
            throws OrderStore.OrderException, IOException {
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

    OrderStore.Submission submit(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            OrderStore.RecipientResolver recipient)
            throws OrderStore.OrderException, IOException {
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

    OrderStore.Submission submit(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            OrderStore.RecipientResolver recipient)
            throws OrderStore.OrderException, IOException {
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

    OrderStore.Submission submit(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            OrderStore.RecipientResolver recipient,
            boolean queueWhenStale)
            throws OrderStore.OrderException, IOException {
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
                queueWhenStale,
                null);
    }

    OrderStore.Submission submit(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String idempotencyKey,
            boolean needsApproval,
            OrderStore.RecipientResolver recipient,
            boolean queueWhenStale,
            AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
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
                if (saved != null) return new OrderStore.Submission(replay(saved, fingerprint), false);
            }
            return new OrderStore.Submission(
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
                            queueWhenStale,
                            audit),
                    true);
        }
    }

    OrderStore.Order placeWithObservationLock(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String receiptKey,
            String fingerprint,
            OrderStore.Order retryOf,
            boolean needsApproval,
            String recipient,
            boolean queueWhenStale)
            throws OrderStore.OrderException, IOException {
        return placeWithObservationLock(
                server,
                stash,
                requested,
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
                queueWhenStale,
                null);
    }

    OrderStore.Order placeWithObservationLock(
            String server,
            String stash,
            List<OrderStore.Line> requested,
            JsonObject names,
            JsonObject packageLabel,
            String byId,
            String byName,
            String organizationId,
            OrderIntent intent,
            String receiptKey,
            String fingerprint,
            OrderStore.Order retryOf,
            boolean needsApproval,
            String recipient,
            boolean queueWhenStale,
            AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        // A sorting lease cannot coexist with a new physical reservation.
        // Accept a transparently UNRESERVED request and let the sorter finish
        // or reconcile before any later claim. Never reject the customer's order
        // just because an autonomous tier-optimization pass is in progress.
        boolean sortingLeaseActive = host.index.automation().current(server) != null;
        if (requested.isEmpty()) throw new OrderStore.OrderException(400, "an order needs at least one line", -1);
        if (requested.size() > OrderStore.MAX_LINES)
            throw new OrderStore.OrderException(400, "at most " + OrderStore.MAX_LINES + " lines", -1);
        IndexStore.OrderCounts fresh = host.index.orderCounts(server, stash);
        if (fresh == null) throw new OrderStore.OrderException(400, "no index for that stash", -1);

        // Merge lines with the same type and key, remembering where each first appeared (for error reporting).
        Map<String, OrderStore.Line> merged = new LinkedHashMap<>();
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            OrderStore.Line l = requested.get(i);
            OrderStore.Line prev = merged.get(l.ref());
            if (prev == null) {
                merged.put(l.ref(), l);
                firstIndex.put(l.ref(), i);
            } else {
                merged.put(l.ref(), new OrderStore.Line(l.type(), l.key(), prev.count() + l.count()));
            }
        }

        for (var line : merged.values()) {
            if (line.count() < 1 || line.count() > Integer.MAX_VALUE) {
                throw new OrderStore.OrderException(400, "invalid item count", firstIndex.get(line.ref()));
            }
        }
        var physical = PhysicalReservations.read(host.index.stock(), server, stash);
        var existing = host.ordersOnServer(server);
        var held = host.heldRoots(server);
        boolean executingPhysical =
                existing.stream().anyMatch(order -> order.allocations != null && policy.isExecuting(order));
        // Never extend an agent's already issued protected-root list mid-order.
        // Persist the request instead, explicitly unreserved until the next
        // claim revalidates the actual physical slots against fresh observations.
        if (executingPhysical || sortingLeaseActive) {
            KitExplosion pinned = explodeKits(merged, firstIndex, host.kits());
            for (OrderStore.Line line : merged.values())
                if (line.type().equals("craft")
                        && (line.count() > CraftingCatalog.MAX_PREVIEW
                                || CraftingCatalog.RECIPES == null
                                || CraftingCatalog.RECIPES.producing(line.key()).isEmpty()))
                    throw new OrderStore.OrderException(
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
                    true,
                    audit);
        }
        if (!held.isEmpty()
                && host.index.stock().sources().stream()
                        .anyMatch(source -> source.server().equals(server)
                                && source.status().equals("SUPERSEDED")
                                && held.stream()
                                        .anyMatch(root -> root.source()
                                                .equals(source.value().get("id").getAsString())))) {
            throw new OrderStore.OrderException(
                    409, "A reserved physical source changed topology and requires reconciliation", -1);
        }
        boolean legacyHold = existing.stream()
                .anyMatch(order -> order.allocations == null && OrderStore.OPEN.contains(order.status));
        if (physical.hasSnapshots() && legacyHold
                || !physical.hasSnapshots()
                        && (!held.isEmpty()
                                || merged.values().stream()
                                        .anyMatch(line -> line.type().equals("shulker")))) {
            throw new OrderStore.OrderException(
                    409, "Resolve existing reservations before mixing source observation formats", -1);
        }
        // Kit lines reserve their components, never a "kit:" ref: explode against
        // the current templates and pin the snapshots on the order, so the bot
        // packs the exact layout even if the template changes later.
        KitExplosion exploded = explodeKits(merged, firstIndex, host.kits());
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
                    false,
                    audit);
        } catch (OrderStore.OrderException shortfall) {
            // The offline queue is explicit, never silent: only a caller that opts in with
            // queueWhenStale may fall back to last-verified counts. An expired source on the
            // default path keeps the original shortage (400/409, no order row), so stale stock
            // is never reserved behind the requester's back. Claim still requires a fresh
            // observation, so the bot re-verifies before it touches anything. Ported from PR #89.
            if (!queueWhenStale || !isShortage(shortfall)) throw shortfall;
            IndexStore.OrderCounts stale = host.index.orderCountsIncludingStale(server, stash);
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
                        false,
                        audit);
            } catch (OrderStore.OrderException retryFailed) {
                throw isShortage(retryFailed) ? shortfall : retryFailed;
            }
        }
    }
    /** A 400/409 against one order line: a real shortage, not a policy refusal. */
    private static boolean isShortage(OrderStore.OrderException e) {
        return e.line >= 0 && (e.status == 400 || e.status == 409);
    }
    /** Craft outputs remain order lines; only their reserved materials become physical lines. */
    static final class CraftExpansion {
        final Map<String, OrderStore.Line> lines = new LinkedHashMap<>();
        final Map<String, Integer> firstIndex = new HashMap<>();
        final Map<String, Long> materials = new LinkedHashMap<>();
        final JsonArray plans = new JsonArray();

        void add(OrderStore.Line line, int at) {
            var existing = lines.get(line.ref());
            lines.put(
                    line.ref(),
                    existing == null
                            ? line
                            : new OrderStore.Line(
                                    line.type(), line.key(), Math.addExact(existing.count(), line.count())));
            firstIndex.merge(line.ref(), at, Math::min);
        }
    }
    /**
     * Resolve recipe alternatives before reserving any stock. Direct lines and
     * kits take precedence; multiple craft requests share one diminishing pool.
     * Only fresh, physical, base-ID ingredient slots can back a craft.
     */
    CraftExpansion expandCrafts(
            String server,
            String stash,
            KitExplosion exploded,
            PhysicalReservations physical,
            Set<PhysicalReservations.Root> held,
            Map<String, Integer> firstIndex)
            throws IOException, OrderStore.OrderException {
        CraftExpansion result = new CraftExpansion();
        boolean wantsCraft =
                exploded.lines.values().stream().anyMatch(l -> l.type().equals("craft"));
        for (var l : exploded.lines.values())
            if (!l.type().equals("craft")) result.add(l, exploded.firstIndex.get(l.ref()));
        if (!wantsCraft) return result;
        if (!physical.hasSnapshots() || !host.index.craftingReady(server, stash))
            throw new OrderStore.OrderException(
                    409, "Crafting requires a ready agent, table and fresh physical stock", -1);

        Map<String, Long> pool = new HashMap<>(CraftingCatalog.eligiblePool(physical.available(held)));
        // Already ordered direct components must not also be used as craft inputs.
        for (OrderStore.Line l : result.lines.values())
            if (l.type().equals("item") && pool.containsKey(l.key()))
                pool.put(l.key(), Math.max(0, pool.get(l.key()) - l.count()));

        for (var l : exploded.lines.values()) {
            if (!l.type().equals("craft")) continue;
            int at = firstIndex.get(l.ref());
            if (l.count() > CraftingCatalog.MAX_PREVIEW)
                throw new OrderStore.OrderException(400, "Too many craftable items in one order", at);
            var preview = CraftingCatalog.plan(l.key(), (int) l.count(), pool);
            if (!preview.ok() || preview.plan().count() < l.count())
                throw new OrderStore.OrderException(409, "Not enough verified ingredients to craft " + l.key(), at);

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
                throw new OrderStore.OrderException(409, "Crafting material reservation changed", at);
            }
            for (var need : preview.plan().take().entrySet()) {
                result.materials.merge(need.getKey(), (long) need.getValue(), Math::addExact);
                result.add(new OrderStore.Line("item", need.getKey(), need.getValue()), at);
            }
        }
        return result;
    }

    private OrderStore.Order commitOrder(
            String server,
            String stash,
            Map<String, OrderStore.Line> merged,
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
            OrderStore.Order retryOf,
            boolean needsApproval,
            String recipient,
            IndexStore.OrderCounts counts,
            PhysicalReservations physical,
            Set<PhysicalReservations.Root> held,
            boolean allowStale,
            boolean deferReservation,
            AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        var allocations = !deferReservation && physical.hasSnapshots()
                ? physical.allocate(crafting.lines.values(), held, crafting.firstIndex, allowStale)
                : null;
        OrderStore.Book b = host.book(server, stash);
        synchronized (b) {
            long open = b.orders.stream()
                    .filter(o -> OrderStore.OPEN.contains(o.status))
                    .count();
            if (open >= OrderStore.MAX_OPEN)
                throw new OrderStore.OrderException(
                        400, "too many open orders for this stash (limit " + OrderStore.MAX_OPEN + ")", -1);
            Map<String, Long> reserved = policy.reserved(b);
            for (OrderStore.Line l : crafting.lines.values()) {
                int at = crafting.firstIndex.get(l.ref());
                long total = counts.total(l.type(), l.key());
                if (deferReservation) continue;
                if (total < 0 && allocations == null)
                    throw new OrderStore.OrderException(400, "not in the stash: " + l.key(), at);
                long available = Math.max(0, total - reserved.getOrDefault(l.ref(), 0L));
                if (allocations == null && l.count() > available) {
                    throw new OrderStore.OrderException(
                            400,
                            "only " + available + " available for " + l.key() + " (asked for " + l.count() + ")",
                            at);
                }
            }
            long shulkers = shulkersNeeded(merged.values(), counts);
            if (shulkers > OrderStore.MAX_SHULKERS) {
                throw new OrderStore.OrderException(
                        400,
                        "too big: this order needs " + shulkers + " shulkers, and one order can fill at most "
                                + OrderStore.MAX_SHULKERS + " (one double chest)",
                        -1);
            }
            long now = host.clock.millis();
            OrderStore.Order o = new OrderStore.Order();
            o.id = newId();
            o.server = server;
            o.stash = stash;
            o.organizationId = organizationId;
            o.intent = intent;
            o.approval = needsApproval ? OrderStore.Approval.PENDING : OrderStore.Approval.DIRECT;
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
            o.sourceGuards = deferReservation ? null : policy.sourceGuards(server, stash);
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
            if (receiptKey != null) b.receipts.put(receiptKey, new OrderStore.Receipt(fingerprint, o.toJson()));
            if (audit != null) {
                if (retryOf != null) audit.say("order.retry", retryOf.id + " as " + o.id);
                else audit.say("order.place", o.id + " " + intent.purpose().name());
            }
            try {
                host.save(b, null, audit);
            } catch (IOException failure) {
                b.orders.remove(o);
                if (retryOf != null) retryOf.retriedAs = null;
                if (receiptKey != null) b.receipts.remove(receiptKey);
                throw failure;
            }
            host.byOrderId.put(o.id, b);
            return o;
        }
    }
    /**
     * Validates and normalizes an order's names (PROTOCOL.md, "Additions: shulker names"). Returns null for no naming
     * (absent, null, a blank "all" name, or an "each" list that is all blank), otherwise {mode: "all", name} or
     * {mode: "each", names: [...]} with every name trimmed, blank entries as "", and trailing blank entries dropped.
     * Throws 400 for a wrong type, an unknown mode, a name over 50 characters or with § or a control character, or
     * an "each" list of more than 54 entries.
     */
    static JsonObject normalizeNames(JsonElement e) throws OrderStore.OrderException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new OrderStore.OrderException(400, "names must be an object or null", -1);
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
                throw new OrderStore.OrderException(400, "names.name must be a string", -1);
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
            if (list == null || !list.isJsonArray())
                throw new OrderStore.OrderException(400, "names.names must be an array", -1);
            JsonArray a = list.getAsJsonArray();
            if (a.size() > OrderStore.MAX_SHULKERS)
                throw new OrderStore.OrderException(
                        400, "names.names has more than " + OrderStore.MAX_SHULKERS + " entries", -1);
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
                    throw new OrderStore.OrderException(400, "names.names[" + i + "] must be a string", -1);
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
        throw new OrderStore.OrderException(400, "names.mode must be \"all\" or \"each\"", -1);
    }
    /**
     * Validates and normalizes a W-064 package label: the user-chosen text part of the automatic
     * {@code "WeSort (X/Y) - <text>"} name. Returns null for an absent label (the caller then uses the
     * default "Beställning"), otherwise {@code {mode:"all", text}} or {@code {mode:"each", texts:[...]}}.
     */
    static JsonObject normalizePackageLabel(JsonElement e) throws OrderStore.OrderException {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonObject()) throw new OrderStore.OrderException(400, "packageLabel must be an object or null", -1);
        try {
            return PackageLabel.normalize(e.getAsJsonObject());
        } catch (IllegalArgumentException invalid) {
            throw new OrderStore.OrderException(400, invalid.getMessage(), -1);
        }
    }
    /** The trimmed name ("" when blank), or 400 when it is too long or has § or a control character. */
    private static String checkName(String raw, String field) throws OrderStore.OrderException {
        String name = raw.strip();
        if (name.length() > OrderStore.MAX_NAME)
            throw new OrderStore.OrderException(
                    400, field + " is longer than " + OrderStore.MAX_NAME + " characters", -1);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§') throw new OrderStore.OrderException(400, field + " can't contain §", -1);
            if (Character.isISOControl(c))
                throw new OrderStore.OrderException(400, field + " can't contain control characters", -1);
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
                throw new OrderStore.OrderException(
                        400, field + " can't contain invisible or direction-changing characters", -1);
            }
        }
        return name;
    }
    /**
     * How many shulkers the addon's packer (OrderStore.Order.pack) needs for these lines: every item line takes
     * ceil(count / maxStack) slots, the slots of all lines fill 27-slot shulkers in sequence, and every shulker line
     * and empty-shulker line (an item line for a shulker box) adds one shulker per unit.
     */
    static long shulkersNeeded(Iterable<OrderStore.Line> lines, IndexStore.OrderCounts counts) {
        long slots = 0;
        long units = 0;
        for (OrderStore.Line l : lines) {
            // One kit is exactly one box, however its slots are filled.
            if (l.type().equals("kit")) {
                units += l.count();
            } else if (l.type().equals("shulker") || counts.isShulkerBoxKey(l.key())) {
                units += l.count();
            } else {
                // Craft-only results can be absent from the host.index. Without a
                // verified stack-size sample assume 1, never undercount boxes.
                long max = l.type().equals("craft") ? 1 : counts.maxStack(l.key());
                slots += (l.count() + max - 1) / max;
            }
        }
        return units + (slots + OrderStore.SHULKER_SLOTS - 1) / OrderStore.SHULKER_SLOTS;
    }

    private String newId() {
        while (true) {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            String id = "o-" + HexFormat.of().formatHex(bytes);
            if (!host.byOrderId.containsKey(id)) return id;
        }
    }
}
