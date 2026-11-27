package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.component.DataComponentPatch;
import org.junit.jupiter.api.Test;

/** #266: an unencodable component patch quarantines instead of falling back to the bare id. */
class IndexQuarantineTest {
    @Test
    void emptyComponentsKeepBareId() {
        assertEquals("minecraft:stone", StashIndex.key(null, "minecraft:stone", DataComponentPatch.EMPTY));
    }

    @Test
    void quarantineSentinelNeverCollidesWithBareIdOrVariantKey() {
        String q = StashIndex.quarantineKey("minecraft:stone");
        assertNotEquals("minecraft:stone", q);
        assertTrue(q.startsWith("minecraft:stone#"));
        // A valid variant key ends in id + '#' + 8 hex chars; the sentinel must never match that shape.
        assertFalse(q.matches(".*#[0-9a-f]{8}$"));
    }
}
