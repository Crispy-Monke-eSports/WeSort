package com.porter.wesort.data;

import com.google.gson.*;
import java.util.TreeMap;

/** Stable object ordering; arrays, primitive types and string escaping retain their meaning. */
public final class CanonicalJson {
    private CanonicalJson() {}
    public static String stringify(JsonElement value) { return sorted(value).toString(); }
    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, item) -> out.add(key, sorted(item)));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray(); value.getAsJsonArray().forEach(item -> out.add(sorted(item))); return out;
        }
        return value;
    }
}
