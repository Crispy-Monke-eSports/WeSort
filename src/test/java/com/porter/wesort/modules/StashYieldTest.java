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
}
