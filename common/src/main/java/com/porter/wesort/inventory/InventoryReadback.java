package com.porter.wesort.inventory;

import java.util.ArrayDeque;
import java.util.List;

/** Pure fail-closed protocol state: local prediction never acknowledges a physical operation. */
public final class InventoryReadback {
    public record Expected(int menu, List<String> slots, String cursor) {
        public Expected { slots = List.copyOf(slots); }
    }
    private record Observed(Expected value, long epoch) {}
    private record Fence(Expected value, long afterEpoch, long deadline) {}
    private final ArrayDeque<Fence> fences = new ArrayDeque<>();
    private Expected batch, expected;
    private Observed observed;
    private long epoch, acknowledgedEpoch;
    private String failure = "";

    public boolean ready() { return failure.isEmpty() && batch == null && fences.isEmpty(); }
    public boolean acceptingBatch() { return !failed() && batch != null; }
    public boolean failed() { return !failure.isEmpty(); }
    public String reason() { return failure; }
    public Expected expected() { return expected; }
    public long observedEpoch() { return epoch; }
    public boolean observedMenuSince(int menu, long after) { return observed != null && observed.epoch > after && observed.value.menu == menu; }

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

    public void observe(Expected value) { observed = new Observed(value, ++epoch); }

    public void barrier() {
        if (failed() || fences.isEmpty()) return;
        Fence fence = fences.remove();
        if (observed == null || observed.epoch <= Math.max(fence.afterEpoch, acknowledgedEpoch) || !observed.value.equals(fence.value)) {
            fail("Server inventory differs from prediction or no fresh snapshot preceded the barrier");
        } else acknowledgedEpoch = observed.epoch;
    }

    public void expire(long now) {
        if (!fences.isEmpty() && now >= fences.peek().deadline) fail("Inventory readback timed out; do not retry the physical action");
    }

    public void fail(String message) {
        failure = "NEEDS_RECONCILIATION: " + message;
        fences.clear(); batch = null;
    }

    public void restorePending(Expected value) {
        expected = value;
        fail("An inventory operation was not verified before the previous session ended");
    }

    /** Explicit recovery verifies the original postcondition against a new server read; it sends no real click. */
    public Expected reconcile(int freshMenu, long now) {
        if (!failed() || expected == null) return null;
        Expected value = new Expected(freshMenu, expected.slots, expected.cursor);
        failure = ""; fences.clear(); batch = value;
        return fence(now);
    }
}
