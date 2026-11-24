package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class StashYieldTest {
    @Test
    void agentOwnershipClearsCourtesyBackoffWithoutInspectingClient() {
        StashYield.Owner owner = (StashYield.Owner) Proxy.newProxyInstance(
                StashYield.Owner.class.getClassLoader(),
                new Class<?>[] {StashYield.Owner.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("quietSeconds")) return 30;
                    if (method.getName().equals("say") || method.getName().equals("step")) return null;
                    throw new AssertionError("Exclusive Agent inspected foreign activity: " + method.getName());
                });
        StashYield yield = new StashYield(owner);
        yield.interrupted();
        yield.interrupted();
        yield.startWaiting(StashYield.Reason.Screen);
        assertEquals(1200, yield.quietTicks());
        assertTrue(yield.waiting());

        assertNull(yield.check(true));
        assertFalse(yield.waiting());
        assertTrue(yield.quietElapsed());
        assertFalse(yield.foreignRecently(1200));
        assertEquals(600, yield.quietTicks());
        assertNull(yield.lastReason());
    }

    @Test
    void waitingElapsedAndQuietRemainingAreExposed() throws Exception {
        java.util.List<String> said = new java.util.ArrayList<>();
        StashYield.Owner owner = (StashYield.Owner) Proxy.newProxyInstance(
                StashYield.Owner.class.getClassLoader(),
                new Class<?>[] {StashYield.Owner.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("quietSeconds")) return 30;
                    if (method.getName().equals("say") || method.getName().equals("step")) {
                        said.add(String.valueOf(args[0]));
                        return null;
                    }
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
        StashYield yield = new StashYield(owner);
        assertEquals(-1, yield.waitingTicks());
        yield.startWaiting(StashYield.Reason.Screen);
        assertEquals(0, yield.waitingTicks());
        // Advance the internal tick counter without inspecting the client.
        setNow(yield, 5);
        assertEquals(5, yield.waitingTicks());
        // A minute of quiet with no foreign activity: the wait is already elapsed.
        setNow(yield, 10_000);
        assertTrue(yield.quietElapsed());
        assertEquals(0, yield.quietRemainingTicks());
        yield.endWaiting();
        assertEquals(-1, yield.waitingTicks());
        assertFalse(said.isEmpty());
    }

    @Test
    void quietRemainingCountsDownAfterForeignActivity() throws Exception {
        StashYield.Owner owner = (StashYield.Owner) Proxy.newProxyInstance(
                StashYield.Owner.class.getClassLoader(),
                new Class<?>[] {StashYield.Owner.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("quietSeconds")) return 30;
                    if (method.getName().equals("say") || method.getName().equals("step")) return null;
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
        StashYield yield = new StashYield(owner);
        // Simulate foreign activity one tick ago: 600 ticks of quiet required, 10 elapsed.
        setNow(yield, 1000);
        setLastForeign(yield, 990);
        // 600 ticks of quiet required, 10 elapsed.
        assertEquals(590, yield.quietRemainingTicks());
        assertFalse(yield.quietElapsed());
        setLastForeign(yield, 400);
        assertEquals(0, yield.quietRemainingTicks());
        assertTrue(yield.quietElapsed());
    }

    private static void setNow(StashYield yield, long now) throws Exception {
        var field = StashYield.class.getDeclaredField("now");
        field.setAccessible(true);
        field.setLong(yield, now);
    }

    private static void setLastForeign(StashYield yield, long lastForeign) throws Exception {
        var field = StashYield.class.getDeclaredField("lastForeign");
        field.setAccessible(true);
        field.setLong(yield, lastForeign);
    }
}
