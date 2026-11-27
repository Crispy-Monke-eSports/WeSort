package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR3 Step 5: the order lifecycle (self-healing, clear/review/cancel/retry
 * and the force-cancel escape hatches), moved verbatim out of OrderStore (no
 * behavior change, Refs #142). Runs under the OrderStore locks; the host owns
 * the books, this service owns the transitions.
 */
final class OrderLifecycleService {
    private final OrderStore host;
    private final OrderPlacementService placement;
    private final ReservationPolicy policy;

    OrderLifecycleService(OrderStore host, OrderPlacementService placement, ReservationPolicy policy) {
        this.host = host;
        this.placement = placement;
        this.policy = policy;
    }

    /** UI-independent sweep; one indexed server is reconciled under its normal lock. */
    void selfHealAll() throws IOException {
        for (String server : host.books.keySet().stream()
                .map(IndexStore.StashKey::server)
                .distinct()
                .toList()) releaseFromFreshObservation(server);
    }
    void releaseFromFreshObservation(String server) throws IOException {
        synchronized (host.index) {
            // Expire silent operations first: without this, only opening the operations view
            // (or an agent heartbeat) ever flips them to NEEDS_RECONCILIATION, and the 15-second
            // scheduler below would keep seeing a healthy IN_PROGRESS forever.
            new PhysicalOperations(host.index.stock()).sweepStale();
            PhysicalReservations observations = null;
            for (var entry : host.books.entrySet()) {
                if (!entry.getKey().server().equals(server)) continue;
                var book = entry.getValue();
                synchronized (book) {
                    var released = new ArrayList<OrderStore.Order>();
                    for (var order : book.orders) {
                        if (!policy.holdsPhysical(order)
                                || !OrderStore.FINISHED.contains(order.status)
                                || order.stage() == OrderStage.NEEDS_RECONCILIATION) continue;
                        if (observations == null) observations = PhysicalReservations.read(host.index.stock(), server, null);
                        if (observations.refreshedAfter(order.allocations, order.updatedAt)
                                && !host.index.stock().unsettledPhysicalOrder(order.id)) {
                            released.add(order);
                            order.releasedAt = host.clock.getAsLong();
                        }
                    }
                    if (!released.isEmpty()) {
                        try {
                            host.save(book);
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
    /**
     * Self-healing on fresh indexes and periodic sweeps, without operator actions. A
     * matching original source slot is not proof that packing or staging succeeded.
     * Uncertain orders terminate only after their physical journals settle and reserved
     * sources are freshly observed; otherwise all holds remain protected.
     */
    void autoHeal(String server) throws IOException {
        long now = host.clock.getAsLong();
        for (var entry : host.books.entrySet()) {
            if (!entry.getKey().server().equals(server)) continue;
            var book = entry.getValue();
            synchronized (book) {
                // Copy-on-write through host.update(): a failed save rolls back that order only,
                // exactly like the release sweep above.
                for (OrderStore.Order previous : List.copyOf(book.orders)) {
                    OrderStore.Order next;
                    try {
                        next = healed(book, previous, now);
                    } catch (OrderStore.OrderException conflict) {
                        continue;
                    }
                    if (next != null) host.update(book, previous, next);
                }
            }
        }
    }
    /** One order's automatic step, or null when it stays as it is. Copy-on-write: the caller saves. */
    private OrderStore.Order healed(OrderStore.Book book, OrderStore.Order previous, long now) throws IOException, OrderStore.OrderException {
        if (previous.stage() == OrderStage.NEEDS_RECONCILIATION) {
            // Migrate a pre-upgrade order to the same automatic rollback
            // protocol as new failures. Never expose the old operator state.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
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
                && host.index.stock().reconciliationRequiredPhysicalOrder(previous.id)) {
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
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
                && policy.isExecuting(previous)
                && previous.stage() != OrderStage.RECOVERING
                && previous.cancelRequestedAt > 0
                && now - previous.cancelRequestedAt > OrderStore.AUTO_CANCEL_AFTER_MS) {
            // The deadline runs on the cancel request alone: repeated bot progress reports
            // (which touch updatedAt) must not defer the rollback forever.
            // Keep the allocation and original agent, rather than treating a
            // missing bot acknowledgement as permission to abandon items.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
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
                && now - previous.cancelRequestedAt > OrderStore.AUTO_CANCEL_AFTER_MS
                && now - previous.updatedAt > OrderStore.AUTO_CANCEL_AFTER_MS) {
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled automatically: requested, bot silent, nothing held";
            o.updatedAt = now;
            o.record("order.auto-force-cancel", Users.OWNER_ID, now);
            return o;
        }
        if (previous.stage() == OrderStage.RECOVERING && now - previous.updatedAt > OrderStore.RECOVERING_AFTER_MS) {
            // The exit a silent bot never takes: fail the rollback so its holds release
            // through the normal sweep. A reporting bot still finishes first via the
            // cancelled/failed fast path, which this never pre-empts.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
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
                && now - previous.updatedAt > OrderStore.pickupReadyReleaseAfterMs()) {
            // A packed order whose bot went silent: the goods are staged and waiting,
            // so the hold may lapse while the order itself stays collectible.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            o.releasedAt = now;
            o.message = "Released automatically: packed, bot silent, goods await pickup";
            o.updatedAt = now;
            o.record("order.pickup-release", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval != OrderStore.Approval.PENDING
                && previous.allocations != null
                && previous.claimedAt == 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.updatedAt > OrderStore.AUTO_CANCEL_AFTER_MS) {
            // A reservation the bot never picked up must not gate the sorting lease
            // forever. Nothing was ever touched, so cancelling releases cleanly.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled automatically: reserved but never claimed by a bot";
            o.updatedAt = now;
            o.record("order.auto-cancel-unclaimed", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval != OrderStore.Approval.PENDING
                && previous.allocations == null
                && previous.claimedAt == 0
                && previous.deferredBlockedAt > 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.deferredBlockedAt > OrderStore.AUTO_CANCEL_AFTER_MS
                && !host.index.stock().unsettledPhysicalOrder(previous.id)) {
            // Scout runda 3: a drift-stamped aggregate reservation that could never
            // be re-reserved. Nothing was ever touched, so cancelling releases
            // cleanly instead of parking the queue forever with no human to intervene.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message =
                    "cancelled automatically: sources changed and stock could not be re-reserved; nothing was taken";
            o.updatedAt = now;
            o.record("order.auto-cancel-drift", Users.OWNER_ID, now);
            return o;
        }
        if (previous.status.equals("pending")
                && previous.approval == OrderStore.Approval.PENDING
                && previous.claimedAt == 0
                && previous.stage().allows(OrderStage.CANCELLED)
                && now - previous.updatedAt > OrderStore.AUTO_CANCEL_AFTER_MS) {
            // #59: no human can approve. A pending approval that waits forever gates
            // the shared stock and the single agent's queue, so default-deny releases
            // cleanly after a bounded wait. Claims always skip PENDING approvals, so
            // the bot never touched anything; the order must simply be placed again.
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            o.approval = OrderStore.Approval.REJECTED;
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
    /**
     * POST /api/v1/orders/{id}/retry: a new pending order with the old one's server, stash, lines and names, checked
     * like any new order. Only failed and cancelled orders (409 otherwise, 404 for an unknown id). The old one stays.
     */
    OrderStore.Order retry(String id) throws OrderStore.OrderException, IOException {
        return retry(id, null, null);
    }

    /** The same; the new order is recorded as placed by byId (who pressed Retry). */
    OrderStore.Order retry(String id, String byId, String byName) throws OrderStore.OrderException, IOException {
        return retry(id, byId, byName, true, null, GuildProfile.ApprovalPolicy.DIRECT, ignored -> null);
    }

    OrderStore.Order retry(
            String id,
            String byId,
            String byName,
            boolean mayOrderForGuild,
            String organizationId,
            GuildProfile.ApprovalPolicy policy,
            OrderStore.RecipientResolver recipient)
            throws OrderStore.OrderException, IOException {
        return retry(id, byId, byName, mayOrderForGuild, organizationId, policy, recipient, null);
    }

    OrderStore.Order retry(
            String id,
            String byId,
            String byName,
            boolean mayOrderForGuild,
            String organizationId,
            GuildProfile.ApprovalPolicy policy,
            OrderStore.RecipientResolver recipient,
            AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
            OrderStore.Book book = host.byOrderId.get(id);
            if (book == null) throw new OrderStore.OrderException(404, "no such order", -1);
            synchronized (book) {
                OrderStore.Order old = OrderStore.find(book, id);
                if (old == null) throw new OrderStore.OrderException(404, "no such order", -1);
                if (old.stage() != OrderStage.FAILED && old.stage() != OrderStage.CANCELLED) {
                    throw new OrderStore.OrderException(409, "Only failed or cancelled orders can be retried", -1);
                }
                if (!mayOrderForGuild && old.intent.requiresGuildPermission()) {
                    throw new OrderStore.OrderException(
                            403, "Your role cannot retry an order for the guild or another recipient", -1);
                }
                if (old.retriedAs != null) {
                    throw new OrderStore.OrderException(409, "This order was already placed again as " + old.retriedAs, -1);
                }
                if (this.policy.holdsPhysical(old)) {
                    var holders = host.ordersOnServer(book.server).stream()
                            .filter(order -> this.policy.holdsPhysical(order))
                            .map(order -> order.id)
                            .sorted()
                            .limit(6)
                            .toList();
                    throw new OrderStore.OrderException(
                            409,
                            "Wait for verified release of the previous reservation (still held by: "
                                    + String.join(", ", holders)
                                    + ")",
                            -1);
                }
                return placement.placeWithObservationLock(
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
                        false,
                        audit);
            }
        }
    }
    /**
     * Removes every finished order (delivered, failed, cancelled) of a stash and returns how many. Open orders are
     * never touched: they hold reservations and the bot may be working on them.
     */
    int clearFinished(String server, String stash) throws IOException {
        return clearFinished(server, stash, null);
    }

    int clearFinished(String server, String stash, AuditLog.Batch audit) throws IOException {
        synchronized (host.index) {
            return clearFinishedWithObservationLock(server, stash, audit);
        }
    }

    int clearFinishedWithObservationLock(String server, String stash) throws IOException {
        return clearFinishedWithObservationLock(server, stash, null);
    }

    int clearFinishedWithObservationLock(String server, String stash, AuditLog.Batch audit) throws IOException {
        OrderStore.Book b = host.books.get(new IndexStore.StashKey(server, stash));
        if (b == null) return 0;
        synchronized (b) {
            var removable = b.orders.stream()
                    .filter(o -> o.stage().archiveable() && !policy.holdsPhysical(o))
                    .toList();
            if (removable.isEmpty()) return 0;
            var before = List.copyOf(b.orders);
            var archiveBefore = List.copyOf(b.archived);
            b.orders.removeAll(removable);
            b.archived.addAll(removable);
            OrderStore.trimArchive(b);
            if (audit != null) audit.say("orders.clear", stash + ": " + removable.size());
            try {
                host.save(b, null, audit);
            } catch (IOException failure) {
                b.orders.clear();
                b.orders.addAll(before);
                b.archived.clear();
                b.archived.addAll(archiveBefore);
                throw failure;
            }
            removable.forEach(o -> host.byOrderId.remove(o.id, b));
            return removable.size();
        }
    }
    OrderStore.ApprovalDecision review(String id, boolean approve, String actor) throws OrderStore.OrderException, IOException {
        return review(id, approve, actor, null);
    }

    OrderStore.ApprovalDecision review(String id, boolean approve, String actor, AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
            OrderStore.Book book = host.byOrderId.get(id);
            if (book == null) throw new OrderStore.OrderException(404, "no such order", -1);
            synchronized (book) {
                OrderStore.Order before = OrderStore.find(book, id);
                if (before == null) throw new OrderStore.OrderException(404, "no such order", -1);
                OrderStore.Approval decision = approve ? OrderStore.Approval.APPROVED : OrderStore.Approval.REJECTED;
                if (before.approval == decision) return new OrderStore.ApprovalDecision(before, false);
                if (!before.status.equals("pending") || before.approval != OrderStore.Approval.PENDING) {
                    throw new OrderStore.OrderException(409, "This order is no longer awaiting approval", -1);
                }
                OrderStore.Order next = OrderStore.Order.fromJson(before.snapshot());
                next.approval = decision;
                next.decidedBy = actor;
                next.decidedAt = next.updatedAt = host.clock.getAsLong();
                if (!approve) {
                    next.advance(OrderStage.CANCELLED);
                    next.status = "cancelled";
                }
                next.record(approve ? "order.approve" : "order.reject", actor, next.updatedAt);
                if (audit != null) audit.say("order." + (approve ? "approve" : "reject"), id);
                host.update(book, before, next, null, audit);
                return new OrderStore.ApprovalDecision(next, true);
            }
        }
    }
    /** pending -> cancelled now; accepted/in_progress -> cancelRequested; finished -> 409. */
    OrderStore.Order cancel(String id) throws OrderStore.OrderException, IOException {
        return cancel(id, Users.OWNER_ID);
    }

    OrderStore.Order cancel(String id, String actor) throws OrderStore.OrderException, IOException {
        return cancel(id, actor, null);
    }

    OrderStore.Order cancel(String id, String actor, AuditLog.Batch audit) throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
            return cancelWithObservationLock(id, actor, audit);
        }
    }

    private OrderStore.Order cancelWithObservationLock(String id, String actor) throws OrderStore.OrderException, IOException {
        return cancelWithObservationLock(id, actor, null);
    }

    private OrderStore.Order cancelWithObservationLock(String id, String actor, AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        OrderStore.Book b = host.byOrderId.get(id);
        if (b == null) throw new OrderStore.OrderException(404, "no such order", -1);
        // Every touch heals: a legacy needs_reconciliation order migrates to the
        // automatic rollback protocol here, not only on dashboard polls.
        autoHeal(b.server);
        synchronized (b) {
            OrderStore.Order previous = OrderStore.find(b, id);
            if (previous == null) throw new OrderStore.OrderException(404, "no such order", -1);
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            if (OrderStore.FINISHED.contains(o.status)) throw new OrderStore.OrderException(409, "order is already " + o.status, -1);
            if (o.status.equals("pending")) {
                o.advance(OrderStage.CANCELLED);
                o.status = "cancelled";
                o.message = "Cancelled before the bot started.";
            } else if (!o.cancelRequested) {
                o.cancelRequested = true;
                o.cancelRequestedAt = host.clock.getAsLong();
            } else return previous;
            o.updatedAt = host.clock.getAsLong();
            o.record(o.cancelRequested ? "order.cancel.request" : "order.cancel", actor, o.updatedAt);
            if (audit != null) audit.say("order.cancel", o.id);
            host.update(b, previous, o, null, audit);
            return o;
        }
    }
    /**
     * Marks an accepted/in_progress order whose cancel was requested as cancelled without the bot's confirmation,
     * which releases its reservations. The bot's later status reports for it get 409 (finished orders can't change).
     * 409 when no cancel was requested or the order isn't held by the bot.
     */
    OrderStore.Order forceCancel(String id) throws OrderStore.OrderException, IOException {
        return forceCancel(id, Users.OWNER_ID);
    }

    OrderStore.Order forceCancel(String id, String actor) throws OrderStore.OrderException, IOException {
        return forceCancel(id, actor, null);
    }

    OrderStore.Order forceCancel(String id, String actor, AuditLog.Batch audit) throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
            return forceCancelWithObservationLock(id, actor, audit);
        }
    }

    private OrderStore.Order forceCancelWithObservationLock(String id, String actor) throws OrderStore.OrderException, IOException {
        return forceCancelWithObservationLock(id, actor, null);
    }

    private OrderStore.Order forceCancelWithObservationLock(String id, String actor, AuditLog.Batch audit)
            throws OrderStore.OrderException, IOException {
        OrderStore.Book b = host.byOrderId.get(id);
        if (b == null) throw new OrderStore.OrderException(404, "no such order", -1);
        // Every touch heals, so a legacy needs_reconciliation order is already
        // migrating to RECOVERING by the time the checks below run.
        autoHeal(b.server);
        synchronized (b) {
            OrderStore.Order previous = OrderStore.find(b, id);
            if (previous == null) throw new OrderStore.OrderException(404, "no such order", -1);
            OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
            if (OrderStore.FINISHED.contains(o.status)) throw new OrderStore.OrderException(409, "order is already " + o.status, -1);
            if (o.allocations != null) {
                throw new OrderStore.OrderException(
                        409,
                        "The agent must finish automatic physical rollback first;"
                                + " the owner can release it unconditionally via owner-force",
                        -1);
            }
            if (!o.cancelRequested || o.status.equals("pending")) {
                throw new OrderStore.OrderException(
                        409, "request a cancel first; force cancel is for orders the bot hasn't confirmed", -1);
            }
            o.advance(OrderStage.CANCELLED);
            o.status = "cancelled";
            o.message = "cancelled from the website (bot didn't confirm)";
            o.updatedAt = host.clock.getAsLong();
            o.record("order.force-cancel", actor, o.updatedAt);
            if (audit != null) audit.say("order.force-cancel", o.id);
            host.update(b, previous, o, null, audit);
            return o;
        }
    }
    /**
     * The escape hatch for every stuck shape: the owner releases an order unconditionally,
     * setting a terminal state and the release timestamp despite remaining allocations.
     * The full trail (including the abandoned allocation roots) stays in the audit history.
     * Only exposed through the owner-only route; nothing else in the codebase calls this.
     */
    OrderStore.Order ownerForceCancel(String id, String actor) throws OrderStore.OrderException, IOException {
        return ownerForceCancel(id, actor, null);
    }

    OrderStore.Order ownerForceCancel(String id, String actor, AuditLog.Batch audit) throws OrderStore.OrderException, IOException {
        synchronized (host.index) {
            OrderStore.Book b = host.byOrderId.get(id);
            if (b == null) throw new OrderStore.OrderException(404, "no such order", -1);
            synchronized (b) {
                OrderStore.Order previous = OrderStore.find(b, id);
                if (previous == null) throw new OrderStore.OrderException(404, "no such order", -1);
                if (previous.stage().archiveable())
                    throw new OrderStore.OrderException(409, "order is already " + previous.status, -1);
                OrderStore.Order o = OrderStore.Order.fromJson(previous.snapshot());
                // Deliberately bypasses advance(): PICKUP_READY has no FAILED edge, and this
                // path must terminate every non-terminal stage, allocations or not.
                o.stage = OrderStage.FAILED;
                o.status = "failed";
                o.releasedAt = o.updatedAt = host.clock.getAsLong();
                o.message = "Released by the owner without bot confirmation; verify the stash by hand.";
                o.record("order.owner-force", actor, o.updatedAt);
                if (audit != null) audit.say("order.owner-force", o.id);
                host.update(b, previous, o, null, audit);
                return o;
            }
        }
    }
    /** Crash-safe replay of registry removals committed to a saved order snapshot. */
    void finishPendingUuidDeregistrations(OrderStore.Book book, OrderStore.Order order) throws IOException {
        if (order.pendingUuidDeregistrations == null || order.pendingUuidDeregistrations.isEmpty()) return;
        OrderStore.Order cleared = OrderStore.Order.fromJson(order.snapshot());
        cleared.pendingUuidDeregistrations = null;
        host.update(book, order, cleared, OrderStore.boxRelease(order, order.pendingUuidDeregistrations));
    }
}
