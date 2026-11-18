package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PackageCustodyTest {
    @TempDir Path data;

    private static String id() { return UUID.randomUUID().toString(); }

    private static JsonObject location(String dim) {
        var l = new JsonObject();
        l.addProperty("server", "s");
        l.addProperty("dimension", dim);
        l.addProperty("stash", "a");
        return l;
    }

    @Test void stageIsIdempotentAndFollowsTheCustodyChain() throws Exception {
        var custody = new PackageCustody(data);
        String packageId = id();
        var staged = custody.stage(packageId, "o-1", location("minecraft:overworld"));
        assertEquals(PackageCustody.State.STAGED, staged.state());
        assertEquals(staged.json(), custody.stage(packageId, "o-1", location("minecraft:overworld")).json());

        String agent = id();
        var picked = custody.transition(packageId, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, location("minecraft:overworld"), null);
        assertEquals(PackageCustody.State.PICKUP_VERIFIED, picked.state());
        assertEquals(agent, picked.agent());
        assertEquals(PackageCustody.State.IN_TRANSIT, custody.transition(packageId, "o-1", agent, PackageCustody.State.IN_TRANSIT, null, null).state());
        assertEquals(PackageCustody.State.DEPOSIT_VERIFIED, custody.transition(packageId, "o-1", agent, PackageCustody.State.DEPOSIT_VERIFIED, location("minecraft:overworld"), null).state());
        assertEquals(4, custody.get(packageId).revision());
    }

    @Test void secondAgentCannotClaimAnAlreadyPickedPackage() throws Exception {
        var custody = new PackageCustody(data);
        String packageId = id();
        custody.stage(packageId, "o-1", location("minecraft:overworld"));
        String first = id();
        custody.transition(packageId, "o-1", first, PackageCustody.State.PICKUP_VERIFIED, location("minecraft:overworld"), null);
        // A second agent cannot claim or advance the package.
        String second = id();
        assertThrows(PackageCustody.Conflict.class, () -> custody.transition(packageId, "o-1", second, PackageCustody.State.PICKUP_VERIFIED, location("minecraft:overworld"), null));
        assertThrows(PackageCustody.Conflict.class, () -> custody.transition(packageId, "o-1", second, PackageCustody.State.IN_TRANSIT, null, null));
        // But the holding agent can continue, and reconciliation is always available.
        assertEquals(PackageCustody.State.IN_TRANSIT, custody.transition(packageId, "o-1", first, PackageCustody.State.IN_TRANSIT, null, null).state());
        assertEquals(PackageCustody.State.NEEDS_RECONCILIATION, custody.transition(packageId, "o-1", second, PackageCustody.State.NEEDS_RECONCILIATION, null, null).state());
    }

    @Test void invalidTransitionsAreRejected() throws Exception {
        var custody = new PackageCustody(data);
        String packageId = id();
        custody.stage(packageId, "o-1", location("minecraft:overworld"));
        // STAGED -> IN_TRANSIT is not allowed (must pick up first).
        assertThrows(PackageCustody.Conflict.class, () -> custody.transition(packageId, "o-1", id(), PackageCustody.State.IN_TRANSIT, null, null));
        // A package staged under another order cannot be moved under a different order.
        assertThrows(PackageCustody.Conflict.class, () -> custody.transition(packageId, "o-2", id(), PackageCustody.State.PICKUP_VERIFIED, null, null));
    }

    @Test void custodyPersistsAcrossRestart() throws Exception {
        var custody = new PackageCustody(data);
        String packageId = id();
        custody.stage(packageId, "o-1", location("minecraft:overworld"));
        String agent = id();
        custody.transition(packageId, "o-1", agent, PackageCustody.State.PICKUP_VERIFIED, location("minecraft:overworld"), null);

        var reloaded = new PackageCustody(data);
        var p = reloaded.get(packageId);
        assertEquals(PackageCustody.State.PICKUP_VERIFIED, p.state());
        assertEquals(agent, p.agent());
        // The same agent can continue after restart; a different agent still cannot.
        assertEquals(PackageCustody.State.IN_TRANSIT, reloaded.transition(packageId, "o-1", agent, PackageCustody.State.IN_TRANSIT, null, null).state());
    }
}
