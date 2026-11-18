package com.porter.wesort.data;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuotaRuleTest {
    @Test void matchingPrefersMemberThenRoleThenEveryone() {
        var member = new QuotaRule("u-1", "item:minecraft:diamond", 100, 3600);
        var role = new QuotaRule("member", "item:minecraft:diamond", 50, 3600);
        var everyone = new QuotaRule("*", "item:minecraft:diamond", 10, 3600);
        assertEquals(member, QuotaRule.matching(List.of(everyone, role, member), "u-1", List.of("member"), "item:minecraft:diamond"));
        assertEquals(role, QuotaRule.matching(List.of(everyone, role, member), "u-2", List.of("member"), "item:minecraft:diamond"));
        assertEquals(everyone, QuotaRule.matching(List.of(everyone, role, member), "u-2", List.of("other"), "item:minecraft:diamond"));
    }

    @Test void keyCoverageAndNonMatchingRules() {
        var diamond = new QuotaRule("*", "item:minecraft:diamond", 10, 3600);
        assertTrue(diamond.covers("item:minecraft:diamond"));
        assertFalse(diamond.covers("item:minecraft:iron_ingot"));
        var all = new QuotaRule("*", "*", 10, 3600);
        assertTrue(all.covers("anything"));
        assertNull(QuotaRule.matching(List.of(diamond), "u-1", List.of(), "item:minecraft:iron_ingot"));
    }

    @Test void validationRejectsBadRules() {
        assertThrows(IllegalArgumentException.class, () -> new QuotaRule("", "item:x", 1, 3600));
        assertThrows(IllegalArgumentException.class, () -> new QuotaRule("*", "", 1, 3600));
        assertThrows(IllegalArgumentException.class, () -> new QuotaRule("*", "item:x", 0, 3600));
        assertThrows(IllegalArgumentException.class, () -> new QuotaRule("*", "item:x", 1, 0));
    }
}
