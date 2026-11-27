package com.porter.wesortmanager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR3 (#265): typed order transition changeset (additive).
 *
 * <p>Declares the contract future diff-writes must follow: instead of
 * serializing the whole order book per mutation, a commit carries only the
 * changed order/package/lease/reservation/custody rows plus new immutable
 * events, the idempotency receipt, and an optional terminal resume receipt —
 * all inside the single SQLite transaction (#140). Not yet wired into
 * {@link OrderStore}; {@code OrderBookRepository.commit} still takes the full
 * snapshot until the cutover lands.
 */
public record OrderChangeSet(
        String orderId,
        long expectedRevision,
        long nextRevision,
        List<Map<String, String>> changedRows,
        List<Map<String, String>> newEvents,
        Map<String, String> idempotencyReceipt,
        Map<String, String> terminalResumeReceipt) {
    public OrderChangeSet {
        if (orderId == null || orderId.isEmpty()) throw new IllegalArgumentException("orderId required");
        if (nextRevision != expectedRevision + 1)
            throw new IllegalArgumentException("nextRevision must be expectedRevision + 1");
        changedRows = changedRows == null ? List.of() : List.copyOf(changedRows);
        newEvents = newEvents == null ? List.of() : List.copyOf(newEvents);
        if (changedRows.isEmpty() && newEvents.isEmpty() && terminalResumeReceipt == null)
            throw new IllegalArgumentException("empty changeset commits nothing");
    }

    /**
     * PR3 steg-1 (#265): derives one validated changeset per changed order from
     * a full book commit. Histories must only append (rewrites fail closed);
     * storage still writes the full snapshot until the diff cutover lands, but
     * every commit now proves it carries a non-empty, revision-linked changeset.
     */
    static List<OrderChangeSet> forBook(
            com.google.gson.JsonObject prevRoot, com.google.gson.JsonObject nextRoot, long expectedRevision) {
        long nextRevision = expectedRevision + 1;
        java.util.Map<String, LocatedOrder> prevOrders = bookOrders(prevRoot);
        java.util.Map<String, LocatedOrder> nextOrders = bookOrders(nextRoot);
        java.util.Map<String, String> prevReceipts = bookReceipts(prevRoot);
        java.util.Map<String, String> nextReceipts = bookReceipts(nextRoot);
        java.util.Map<String, String> newReceipts = new java.util.LinkedHashMap<>();
        for (var r : nextReceipts.entrySet()) {
            if (!r.getValue().equals(prevReceipts.get(r.getKey()))) newReceipts.put(r.getKey(), r.getValue());
        }
        var out = new java.util.ArrayList<OrderChangeSet>();
        var attributedReceipts = new java.util.HashSet<String>();
        for (var entry : nextOrders.entrySet()) {
            String id = entry.getKey();
            LocatedOrder next = entry.getValue();
            String nextJson = com.porter.wesort.data.CanonicalJson.stringify(next.order());
            LocatedOrder prev = prevOrders.get(id);
            boolean changed = prev == null
                    || !prev.location().equals(next.location())
                    || !nextJson.equals(com.porter.wesort.data.CanonicalJson.stringify(prev.order()));
            java.util.List<Map<String, String>> newEvents =
                    newOrderEvents(prev == null ? null : prev.order(), next.order());
            // Receipts are durable book state too (idempotency): a late or duplicate
            // report that changes no order row still commits its receipt key.
            var myReceipts = new java.util.LinkedHashMap<String, String>();
            for (var r : newReceipts.entrySet()) {
                if (r.getKey().contains("report:" + id)) {
                    myReceipts.put(r.getKey(), r.getValue());
                    attributedReceipts.add(r.getKey());
                }
            }
            var rows = new java.util.ArrayList<Map<String, String>>();
            if (changed) rows.add(Map.of("order", nextJson));
            for (var r : myReceipts.entrySet()) rows.add(Map.of("receipt:" + r.getKey(), r.getValue()));
            java.util.Map<String, String> terminal = null;
            var order = next.order();
            if (order.has("agent")
                    && !order.get("agent").isJsonNull()
                    && order.has("status")
                    && OrderStore.FINISHED.contains(order.get("status").getAsString())) {
                terminal = Map.of(id, nextJson);
            }
            if (!rows.isEmpty() || !newEvents.isEmpty() || terminal != null) {
                out.add(new OrderChangeSet(
                        id, expectedRevision, nextRevision, rows, newEvents, Map.copyOf(myReceipts), terminal));
            }
        }
        if (out.isEmpty() && newReceipts.size() != attributedReceipts.size() && !nextOrders.isEmpty()) {
            // Receipt keys naming no listed order (or listed orders with no other
            // change): attribute the leftovers to the first order so the commit
            // persisting them still carries a changeset.
            String first = nextOrders.keySet().iterator().next();
            var rows = new java.util.ArrayList<Map<String, String>>();
            var leftover = new java.util.LinkedHashMap<String, String>();
            for (var r : newReceipts.entrySet()) {
                if (!attributedReceipts.contains(r.getKey())) {
                    rows.add(Map.of("receipt:" + r.getKey(), r.getValue()));
                    leftover.put(r.getKey(), r.getValue());
                }
            }
            if (!rows.isEmpty()) {
                out.add(new OrderChangeSet(
                        first, expectedRevision, nextRevision, rows, List.of(), Map.copyOf(leftover), null));
            }
        }
        return List.copyOf(out);
    }

    /**
     * PR3 (#265 cutover): row-level sync plan for one book commit. Upserts carry
     * each order's array location so moves between orders/archived are changes;
     * deletes list ids that left the book (archive eviction); receipts carry
     * every new idempotency receipt. Empty overall plan means nothing changed.
     */
    record Upsert(String orderId, String location, String json, int seq) {}

    record SyncPlan(
            java.util.List<Upsert> upserts, java.util.Set<String> deletes, java.util.Map<String, String> receipts) {
        SyncPlan {
            upserts = upserts == null ? List.of() : List.copyOf(upserts);
            deletes = deletes == null ? Set.of() : Set.copyOf(deletes);
            receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
        }

        boolean isEmpty() {
            return upserts.isEmpty() && deletes.isEmpty() && receipts.isEmpty();
        }
    }

    static SyncPlan plan(com.google.gson.JsonObject prevRoot, com.google.gson.JsonObject nextRoot) {
        java.util.Map<String, LocatedOrder> prevOrders = bookOrders(prevRoot);
        java.util.Map<String, LocatedOrder> nextOrders = bookOrders(nextRoot);
        java.util.Map<String, String> prevReceipts = bookReceipts(prevRoot);
        java.util.Map<String, String> nextReceipts = bookReceipts(nextRoot);
        var upserts = new java.util.ArrayList<Upsert>();
        var seqByLocation = new java.util.HashMap<String, Integer>();
        for (var entry : nextOrders.entrySet()) {
            String id = entry.getKey();
            LocatedOrder next = entry.getValue();
            String nextJson = com.porter.wesort.data.CanonicalJson.stringify(next.order());
            LocatedOrder prev = prevOrders.get(id);
            // History rewrites fail closed even when the row is otherwise identical.
            newOrderEvents(prev == null ? null : prev.order(), next.order());
            if (prev == null
                    || !prev.location().equals(next.location())
                    || !nextJson.equals(com.porter.wesort.data.CanonicalJson.stringify(prev.order()))) {
                int seq = seqByLocation.merge(next.location(), 0, (a, b) -> a + 1);
                upserts.add(new Upsert(id, next.location(), nextJson, seq));
            } else {
                // Unchanged rows keep their stored sequence: still count the slot
                // so later upserts in the same array keep stable positions.
                seqByLocation.merge(next.location(), 0, (a, b) -> a + 1);
            }
        }
        var deletes = new java.util.HashSet<String>();
        for (String id : prevOrders.keySet()) {
            if (!nextOrders.containsKey(id)) deletes.add(id);
        }
        var receipts = new java.util.LinkedHashMap<String, String>();
        for (var r : nextReceipts.entrySet()) {
            if (!r.getValue().equals(prevReceipts.get(r.getKey()))) receipts.put(r.getKey(), r.getValue());
        }
        return new SyncPlan(upserts, deletes, receipts);
    }

    /** An order plus which book array holds it: moves between arrays are changes. */
    private record LocatedOrder(String location, com.google.gson.JsonObject order) {}

    private static java.util.Map<String, LocatedOrder> bookOrders(com.google.gson.JsonObject root) {
        var out = new java.util.LinkedHashMap<String, LocatedOrder>();
        if (root == null) return out;
        for (String key : List.of("orders", "archived")) {
            if (!root.has(key) || !root.get(key).isJsonArray()) continue;
            for (var element : root.getAsJsonArray(key)) {
                var order = element.getAsJsonObject();
                out.putIfAbsent(order.get("id").getAsString(), new LocatedOrder(key, order));
            }
        }
        return out;
    }

    private static java.util.Map<String, String> bookReceipts(com.google.gson.JsonObject root) {
        var out = new java.util.LinkedHashMap<String, String>();
        if (root == null || !root.has("receipts") || !root.get("receipts").isJsonObject()) return out;
        for (var entry : root.getAsJsonObject("receipts").entrySet()) {
            out.put(entry.getKey(), com.porter.wesort.data.CanonicalJson.stringify(entry.getValue()));
        }
        return out;
    }

    private static java.util.List<Map<String, String>> newOrderEvents(
            com.google.gson.JsonObject prev, com.google.gson.JsonObject next) {
        var prevHistory =
                prev != null && prev.has("history") && prev.get("history").isJsonArray()
                        ? prev.getAsJsonArray("history")
                        : new com.google.gson.JsonArray();
        if (!next.has("history") || !next.get("history").isJsonArray()) return List.of();
        var nextHistory = next.getAsJsonArray("history");
        for (int i = 0; i < prevHistory.size() && i < nextHistory.size(); i++) {
            if (!com.porter.wesort.data.CanonicalJson.stringify(prevHistory.get(i))
                    .equals(com.porter.wesort.data.CanonicalJson.stringify(nextHistory.get(i)))) {
                throw new IllegalArgumentException("Order audit history cannot be rewritten");
            }
        }
        if (nextHistory.size() < prevHistory.size())
            throw new IllegalArgumentException("Order audit history cannot shrink");
        var out = new java.util.ArrayList<Map<String, String>>();
        for (int i = prevHistory.size(); i < nextHistory.size(); i++) {
            out.add(Map.of(
                    "seq", Integer.toString(i + 1),
                    "event", com.porter.wesort.data.CanonicalJson.stringify(nextHistory.get(i))));
        }
        return List.copyOf(out);
    }
}
