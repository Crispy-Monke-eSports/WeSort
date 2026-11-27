package com.porter.wesort.inventory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure fail-closed protocol state: local prediction never acknowledges a physical operation. */
public final class InventoryReadback {
    public record Expected(int menu, List<String> slots, String cursor, Map<Integer, Set<String>> alternatives) {
        public Expected(int menu, List<String> slots, String cursor) {
            this(menu, slots, cursor, Map.of());
        }

        public Expected {
            slots = List.copyOf(slots);
            Map<Integer, Set<String>> copy = new HashMap<>();
            for (var entry : alternatives.entrySet()) {
                if (entry.getKey() < 0
                        || entry.getKey() >= slots.size()
                        || entry.getValue().isEmpty()
                        || entry.getValue().size() > 2)
                    throw new IllegalArgumentException("Invalid bounded server outcome");
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            alternatives = Map.copyOf(copy);
        }

        boolean matches(Expected observed) {
            return mismatch(observed) == null;
        }

        private String mismatch(Expected observed) {
            if (menu != observed.menu || slots.size() != observed.slots.size())
                return "INSPECT_MENU: server menu or slot count differs from the recorded menu";
            if (!cursor.equals(observed.cursor))
                return "INSPECT_CURSOR: server cursor differs from the recorded cursor";
            int differences = 0;
            for (int i = 0; i < slots.size(); i++)
                if (!slots.get(i).equals(observed.slots.get(i))
                        && !alternatives.getOrDefault(i, Set.of()).contains(observed.slots.get(i))) differences++;
            return differences == 0 ? null : "INSPECT_ITEMS: differing slot count " + differences;
        }
    }

    private record Observed(Expected value, long epoch) {}

    private record Fence(Expected value, long afterEpoch, long deadline) {}

    private final ArrayDeque<Fence> fences = new ArrayDeque<>();
    private Expected batch, expected;
    private Observed observed;
    private long epoch, acknowledgedEpoch;
    private String failure = "";

    public boolean ready() {
        return failure.isEmpty() && batch == null && fences.isEmpty();
    }

    public boolean acceptingBatch() {
        return !failed() && batch != null;
    }

    public boolean failed() {
        return !failure.isEmpty();
    }

    public String reason() {
        return failure;
    }

    public Expected expected() {
        return expected;
    }

    public long observedEpoch() {
        return epoch;
    }

    public boolean observedMenuSince(int menu, long after) {
        return observed != null && observed.epoch > after && observed.value.menu == menu;
    }

    public void predict(Expected value) {
        if (failed()) throw new IllegalStateException(failure);
        batch = expected = value;
    }

    public Expected fence(long now) {
        if (batch == null || failed()) return null;
        Expected value = batch;
        batch = null;
        fences.add(new Fence(value, epoch, now + 8_000_000_000L));
        return value;
    }

    public void observe(Expected value) {
        observed = new Observed(value, ++epoch);
    }

    /** Returns the reason only when this barrier newly fails a pending read. */
    public String barrier() {
        if (failed() || fences.isEmpty()) return null;
        Fence fence = fences.remove();
        String mismatch = observed == null || observed.epoch <= Math.max(fence.afterEpoch, acknowledgedEpoch)
                ? "INSPECT_NO_FRAME: no fresh server inventory preceded the barrier"
                : fence.value.mismatch(observed.value);
        if (mismatch != null) fail(mismatch);
        else acknowledgedEpoch = observed.epoch;
        return mismatch;
    }

    public void expire(long now) {
        if (!fences.isEmpty() && now >= fences.peek().deadline)
            fail("Inventory readback timed out; do not retry the physical action");
    }

    public void fail(String message) {
        failure = "NEEDS_RECONCILIATION: " + message;
        fences.clear();
        batch = null;
    }

    public void restorePending(Expected value) {
        expected = value;
        fail("An inventory operation was not verified before the previous session ended");
    }

    /** Explicit recovery verifies the original postcondition against a new server read; it sends no real click. */
    public Expected reconcile(int freshMenu, long now) {
        if (!failed() || expected == null) return null;
        Expected value = new Expected(freshMenu, expected.slots, expected.cursor, expected.alternatives);
        failure = "";
        fences.clear();
        batch = value;
        return fence(now);
    }
}
