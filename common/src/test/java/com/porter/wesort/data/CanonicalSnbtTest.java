package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalSnbtTest {
    @Test
    void preservesTypesAndExactLongs() {
        var a = CanonicalSnbt.compound("{l:9007199254740993L,b:1b,s:1s,i:1,f:1f,d:1d}");
        assertEquals(
                "{\"b\":1b,\"d\":1.0d,\"f\":1.0f,\"i\":1,\"l\":9007199254740993L,\"s\":1s}", CanonicalSnbt.write(a));
        assertEquals(a, CanonicalSnbt.parse(CanonicalSnbt.write(a)));
        assertNotEquals(CanonicalSnbt.parse("1b"), CanonicalSnbt.parse("1"));
        assertNotEquals(CanonicalSnbt.parse("0.0f"), CanonicalSnbt.parse("-0.0f"));
    }

    @Test
    void normalizesEquivalentInputButNotListOrder() {
        assertEquals(
                CanonicalSnbt.canonicalize("{b:[I;1b,2s,3],a:240ub}"),
                CanonicalSnbt.canonicalize("{a:-16b,b:[I;1,2,3],}"));
        assertNotEquals(CanonicalSnbt.canonicalize("[1,2]"), CanonicalSnbt.canonicalize("[2,1]"));
        assertNotEquals(CanonicalSnbt.canonicalize("[1]"), CanonicalSnbt.canonicalize("[I;1]"));
        assertEquals("{\"a\":[1,\"two\",{}]}", CanonicalSnbt.canonicalize("{a:[1,two,{}]}"));
        assertEquals("[B;-128b,127b]", CanonicalSnbt.canonicalize("[B;-128,127]"));
    }

    @Test
    void stringsAndOperations() {
        assertEquals("\"Snowman ☃\"", CanonicalSnbt.canonicalize("\"Snowman \\N{Snowman}\""));
        assertEquals("\"a\\n\\\"b\"", CanonicalSnbt.canonicalize("'a\\n\"b'"));
        assertEquals("1b", CanonicalSnbt.canonicalize("bool(2)"));
        assertEquals("0b", CanonicalSnbt.canonicalize("bool(-0.0d)"));
        assertEquals(
                "[I;-132296786,2112623056,-1486552928,-920753162]",
                CanonicalSnbt.canonicalize("uuid(\"f81d4fae-7dec-11d0-a765-00a0c91e6bf6\")"));
    }

    @Test
    void hashDomainsAreSeparated() {
        var a = CanonicalSnbt.parse("{x:1,y:2}");
        var b = CanonicalSnbt.parse("{y:2,x:1}");
        assertEquals(CanonicalSnbt.fingerprint("stack", a), CanonicalSnbt.fingerprint("stack", b));
        assertNotEquals(CanonicalSnbt.fingerprint("stack", a), CanonicalSnbt.fingerprint("snapshot", a));
    }

    @Test
    void rejectsInvalidOrOversizedValues() {
        for (String invalid : List.of(
                "{x:1,x:2}",
                "{x:}",
                "[]junk",
                "[B;128]",
                "[B;true]",
                "[I;1L]",
                "9223372036854775808L",
                "1e999d",
                "01",
                "1_",
                "82u",
                "-87uI",
                "\"\\q\"",
                "\"\\U00110000\"",
                "[,]",
                "{,}")) assertThrows(IllegalArgumentException.class, () -> CanonicalSnbt.parse(invalid), invalid);
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalSnbt.parse(
                        "[".repeat(CanonicalSnbt.MAX_DEPTH + 2) + "0" + "]".repeat(CanonicalSnbt.MAX_DEPTH + 2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalSnbt.parse("\"" + "a".repeat(CanonicalSnbt.MAX_BYTES) + "\""));
    }
}
