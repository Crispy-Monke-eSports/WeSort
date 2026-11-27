package com.porter.wesort.snbt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SnbtCodecTest {
    @Test
    void numericWidthsAreDistinct() {
        assertEquals("1b", SnbtCodec.write(SnbtCodec.parse("1b")));
        assertEquals("1s", SnbtCodec.write(SnbtCodec.parse("1s")));
        assertEquals("1", SnbtCodec.write(SnbtCodec.parse("1")));
        assertEquals("1L", SnbtCodec.write(SnbtCodec.parse("1L")));
        assertEquals("1.0f", SnbtCodec.write(SnbtCodec.parse("1.0f")));
        assertEquals("1.0d", SnbtCodec.write(SnbtCodec.parse("1.0d")));
        assertNotEquals(SnbtCodec.write(SnbtCodec.parse("1b")), SnbtCodec.write(SnbtCodec.parse("1s")));
        assertNotEquals(SnbtCodec.write(SnbtCodec.parse("1")), SnbtCodec.write(SnbtCodec.parse("1L")));
    }

    @Test
    void compoundKeysAreSortedAndWhitespaceInsensitive() {
        SnbtCodec.Tag a = SnbtCodec.parse("{b:1,z:2}");
        SnbtCodec.Tag b = SnbtCodec.parse("{\n  \"z\" : 2 , \"b\" : 1\n}");
        assertEquals(SnbtCodec.write(a), SnbtCodec.write(b));
        assertEquals("{\"b\":1,\"z\":2}", SnbtCodec.write(a));
    }

    @Test
    void typedArraysRoundTrip() {
        assertEquals("[B;1b,2b]", SnbtCodec.write(SnbtCodec.parse("[B;1b,2b]")));
        assertEquals("[I;1,2]", SnbtCodec.write(SnbtCodec.parse("[I;1,2]")));
        assertEquals("[L;1L,2L]", SnbtCodec.write(SnbtCodec.parse("[L;1L,2L]")));
        assertEquals("[B;]", SnbtCodec.write(SnbtCodec.parse("[B;]")));
    }

    @Test
    void duplicateKeysAndTrailingCommasAreRejected() {
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("{\"a\":1,\"a\":2}"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[1,2,]"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("{a:1"));
    }

    @Test
    void nonFiniteFloatsAreRejected() {
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("NaN"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("Infinityd"));
    }

    @Test
    void stringsEscapeDeterministically() {
        SnbtCodec.Tag t = new SnbtCodec.TCompound(Map.of("k", new SnbtCodec.TString("a\"b\\c\n")));
        assertEquals("{\"k\":\"a\\\"b\\\\c\\n\"}", SnbtCodec.write(t));
        assertEquals(t, SnbtCodec.parse(SnbtCodec.write(t)));
    }

    @Test
    void listOrderIsSignificant() {
        String ab = SnbtCodec.write(SnbtCodec.parse("[1,2]"));
        String ba = SnbtCodec.write(SnbtCodec.parse("[2,1]"));
        assertNotEquals(ab, ba);
    }

    @Test
    void booleansAreBytes() {
        assertEquals("1b", SnbtCodec.write(SnbtCodec.parse("true")));
        assertEquals("0b", SnbtCodec.write(SnbtCodec.parse("false")));
    }

    @Test
    void unsignedByteSuffixParses() {
        assertEquals("1b", SnbtCodec.write(SnbtCodec.parse("1ub")));
        assertEquals("-1b", SnbtCodec.write(SnbtCodec.parse("255ub")));
    }

    @Test
    void digestsAreDomainSeparated() {
        String item = "{\"id\":\"minecraft:stone\"}";
        String content = SnbtCodec.contentDigest(item);
        String stack = SnbtCodec.stackDigest(item, 1);
        String snap = SnbtCodec.snapshotDigest(item);
        assertEquals(64, content.length());
        assertNotEquals(content, stack);
        assertNotEquals(content, snap);
        assertNotEquals(stack, snap);
        assertEquals(content, SnbtCodec.contentDigest(item));
    }

    @Test
    void canonicalFormIsStable() {
        SnbtCodec.Tag t =
                SnbtCodec.parse("{id:\"minecraft:shulker_box\",Count:1b,tag:{display:{Name:'{\"text\":\"x\"}'}}}");
        String once = SnbtCodec.write(t);
        assertEquals(once, SnbtCodec.write(SnbtCodec.parse(once)));
    }

    @Test
    void trailingContentIsRejected() {
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("{a:1} trailing"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("1 2"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[1,2]]"));
    }

    @Test
    void whitespaceFramingIsAccepted() {
        SnbtCodec.Tag t = SnbtCodec.parse("  {a:1}\n\t");
        assertEquals("{\"a\":1}", SnbtCodec.write(t));
    }

    @Test
    void maxDepthIsEnforced() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) sb.append("{a:");
        sb.append("1");
        for (int i = 0; i < 70; i++) sb.append('}');
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse(sb.toString()));
    }

    @Test
    void maxNodesIsEnforced() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 70000; i++) {
            if (i > 0) sb.append(',');
            sb.append('1');
        }
        sb.append(']');
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse(sb.toString()));
    }

    @Test
    void maxBytesIsEnforced() {
        String big = "\"" + "a".repeat((int) SnbtCodec.MAX_BYTES) + "\"";
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse(big));
    }

    @Test
    void unknownAndTruncatedEscapesAreRejected() {
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\q\""));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"abc"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"abc\\"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\xZZ\""));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\x1\""));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\u12\""));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\N{}\""));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("\"\\N{SPACE}\""));
    }

    @Test
    void malformedContainersAndArraysAreRejected() {
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("{a:1"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[1,2"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[B;1b,2b"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[B;1b,foo]"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[I;1,]"));
        assertThrows(SnbtCodec.SnbtException.class, () -> SnbtCodec.parse("[L;1L,x]"));
    }

    @Test
    void compatAcceptSetIsPinned() {
        // Single quotes and bare words are accepted but canonicalize to double-quoted.
        assertEquals("{\"a\":1}", SnbtCodec.write(SnbtCodec.parse("{'a':1}")));
        assertEquals("\"foo\"", SnbtCodec.write(SnbtCodec.parse("foo")));
        // Hex and binary canonicalize to long (the int fast-path rejects the
        // prefix; the long path interprets the base). Leading-zero decimals
        // stay decimal in the int fast-path.
        assertEquals("16L", SnbtCodec.write(SnbtCodec.parse("0x10")));
        assertEquals("10", SnbtCodec.write(SnbtCodec.parse("010")));
        assertEquals("5L", SnbtCodec.write(SnbtCodec.parse("0b101")));
        assertEquals("1000", SnbtCodec.write(SnbtCodec.parse("1_000")));
        // Suffix-less doubles canonicalize with an explicit suffix.
        assertEquals("1.5d", SnbtCodec.write(SnbtCodec.parse("1.5")));
    }
}
