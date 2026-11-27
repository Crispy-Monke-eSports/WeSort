package com.porter.wesortmanager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR3 (#142): pure order state machine (no I/O, no clock).
 *
 * <p>Declarative transition table extracted from OrderStore's inline
 * {@code advance/stage/allows} logic. The table is the contract new code
 * must follow; OrderStore delegates to it (migration in progress — both
 * paths enforce the same table, tested by {@link OrderStateMachineTest}).
 */
public final class OrderStateMachine {
    private OrderStateMachine() {}

    public enum State {
        DRAFT,
        PENDING_APPROVAL,
        RESERVED,
        PACKING,
        PICKUP_READY,
        IN_TRANSIT,
        DELIVERING,
        DELIVERED,
        CANCELLED,
        REJECTED,
        NEEDS_RECONCILIATION,
        RECOVERING,
        ARCHIVED
    }

    public record Transition(State from, String action, State to, boolean needsOwner) {}

    /** Complete transition table: every legal (from, action) pair. */
    public static final List<Transition> TABLE = List.of(
            new Transition(State.DRAFT, "submit", State.PENDING_APPROVAL, false),
            new Transition(State.DRAFT, "submit-direct", State.RESERVED, false),
            new Transition(State.PENDING_APPROVAL, "approve", State.RESERVED, true),
            new Transition(State.PENDING_APPROVAL, "reject", State.REJECTED, true),
            new Transition(State.RESERVED, "pack", State.PACKING, false),
            new Transition(State.PACKING, "pack-complete", State.PICKUP_READY, false),
            new Transition(State.PICKUP_READY, "pickup", State.IN_TRANSIT, false),
            new Transition(State.IN_TRANSIT, "deliver-start", State.DELIVERING, false),
            new Transition(State.DELIVERING, "deliver-complete", State.DELIVERED, false),
            new Transition(State.DRAFT, "cancel", State.CANCELLED, false),
            new Transition(State.PENDING_APPROVAL, "cancel", State.CANCELLED, false),
            new Transition(State.RESERVED, "cancel", State.CANCELLED, false),
            new Transition(State.PACKING, "cancel", State.CANCELLED, true),
            new Transition(State.RESERVED, "retry", State.RESERVED, false),
            new Transition(State.CANCELLED, "retry", State.DRAFT, false),
            new Transition(State.PACKING, "fence", State.NEEDS_RECONCILIATION, false),
            new Transition(State.IN_TRANSIT, "fence", State.NEEDS_RECONCILIATION, false),
            new Transition(State.DELIVERING, "fence", State.NEEDS_RECONCILIATION, false),
            new Transition(State.NEEDS_RECONCILIATION, "recover", State.RECOVERING, true),
            new Transition(State.RECOVERING, "reconcile-ok", State.RESERVED, true),
            new Transition(State.RECOVERING, "reconcile-fail", State.NEEDS_RECONCILIATION, true),
            new Transition(State.DELIVERED, "archive", State.ARCHIVED, false),
            new Transition(State.CANCELLED, "archive", State.ARCHIVED, false),
            new Transition(State.REJECTED, "archive", State.ARCHIVED, false));

    private static final Map<State, Map<String, Transition>> INDEX = buildIndex();

    private static Map<State, Map<String, Transition>> buildIndex() {
        Map<State, Map<String, Transition>> index = new java.util.EnumMap<>(State.class);
        for (Transition t : TABLE) {
            index.computeIfAbsent(t.from(), k -> new java.util.HashMap<>()).put(t.action(), t);
        }
        return Map.copyOf(index);
    }

    /** Returns the transition for (from, action), or empty if illegal. */
    public static java.util.Optional<Transition> lookup(State from, String action) {
        Map<String, Transition> byAction = INDEX.get(from);
        if (byAction == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(byAction.get(action));
    }

    /** True when the transition is legal (owner flag checked separately by routes). */
    public static boolean allows(State from, String action) {
        return lookup(from, action).isPresent();
    }

    /** All legal actions from a state (for UI gating and property tests). */
    public static Set<String> actionsFrom(State from) {
        Map<String, Transition> byAction = INDEX.get(from);
        if (byAction == null) return Set.of();
        return Set.copyOf(byAction.keySet());
    }
}
