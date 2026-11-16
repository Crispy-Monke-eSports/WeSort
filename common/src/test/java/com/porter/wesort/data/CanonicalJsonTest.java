package com.porter.wesort.data;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalJsonTest {
    private String canonical(String value) { return CanonicalJson.stringify(JsonParser.parseString(value)); }
    @Test void nestedObjectInsertionOrderDoesNotChangeIdentity() {
        assertEquals(canonical("{\"b\":[{\"z\":1,\"a\":2}],\"a\":true}"), canonical("{\"a\":true,\"b\":[{\"a\":2,\"z\":1}]}"));
    }
    @Test void slotOrderIsSignificant() { assertNotEquals(canonical("[1,2]"), canonical("[2,1]")); }
    @Test void primitiveTypesAndEscapedNamesCannotCollide() {
        assertNotEquals(canonical("1"), canonical("\"1\""));
        assertNotEquals(canonical("{\"a:b\":\"c\"}"), canonical("{\"a\":\"b:c\"}"));
    }
}
