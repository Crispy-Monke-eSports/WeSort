package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * W-061 driven-work regression: the live level reports the raw ResourceKey dimension form while
 * directives and records carry the normalized id. Comparing raw strings silently mismatches, and
 * notInStashWorld then returns "wrong dimension" forever — permanently disabling driven
 * re-reads, so the Manager only ever sees stale-from-disk observations.
 */
class StashDimensionTest {
    @Test
    void canonicalizesRawResourceKeyForm() {
        assertEquals(
                "minecraft:overworld",
                Stash.canonicalDimension("ResourceKey[minecraft:dimension / minecraft:overworld]"));
        assertEquals("minecraft:the_nether", Stash.canonicalDimension("minecraft:the_nether"));
        assertEquals("", Stash.canonicalDimension(null));
        assertEquals("", Stash.canonicalDimension(""));
    }

    @Test
    void sameDimensionAcrossRawAndNormalizedForms() {
        String raw = "ResourceKey[minecraft:dimension / minecraft:overworld]";
        assertTrue(Stash.sameDimension(raw, "minecraft:overworld"));
        assertTrue(Stash.sameDimension("minecraft:overworld", raw));
        assertTrue(Stash.sameDimension(raw, raw));
        assertTrue(Stash.sameDimension("minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void differentDimensionsStayDifferent() {
        assertFalse(Stash.sameDimension("minecraft:overworld", "minecraft:the_nether"));
        assertFalse(
                Stash.sameDimension("ResourceKey[minecraft:dimension / minecraft:overworld]", "minecraft:the_nether"));
    }

    @Test
    void emptyMeansUnknownNeverEqual() {
        assertFalse(Stash.sameDimension("", "minecraft:overworld"));
        assertFalse(Stash.sameDimension("minecraft:overworld", ""));
        assertFalse(Stash.sameDimension(null, "minecraft:overworld"));
    }
}
