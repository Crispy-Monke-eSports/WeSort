package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerIdentityTest {
    private final ContainerIdentity.Position a = new ContainerIdentity.Position(0, 64, 3),
            b = new ContainerIdentity.Position(1, 64, 3);

    private ContainerIdentity identity(String server, String dimension, ContainerIdentity.Position... positions) {
        return new ContainerIdentity(server, dimension, List.of(positions));
    }

    @Test
    void doubleChestOrderDoesNotChangeIdentity() {
        assertEquals(
                identity("s", "minecraft:overworld", a, b).id(),
                identity("s", "minecraft:overworld", b, a).id());
    }

    @Test
    void halvesWorldsAndDimensionsAreDistinct() {
        var id = identity("s", "minecraft:overworld", a).id();
        assertNotEquals(id, identity("s", "minecraft:overworld", a, b).id());
        assertNotEquals(id, identity("s", "minecraft:the_nether", a).id());
        assertNotEquals(id, identity("other", "minecraft:overworld", a).id());
    }

    @Test
    void malformedTopologyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> identity("s", "minecraft:overworld", a, a));
        assertThrows(
                IllegalArgumentException.class,
                () -> identity("s", "minecraft:overworld", a, new ContainerIdentity.Position(4, 64, 3)));
    }
}
