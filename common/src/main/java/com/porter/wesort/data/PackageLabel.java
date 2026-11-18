package com.porter.wesort.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * W-064: the user chooses the text part of an order's shulker-box names in the Manager web UI; the Manager
 * always generates the {@code "WeSort (X/Y) - <text>"} prefix. The anvil limit is 50 UTF-16 code units and
 * the longest prefix {@code "WeSort (54/54) - "} is 17, so the user text is capped at 33 code units for every
 * order size. {@code Y} is the frozen, authoritative packing-plan total, never 99 or a raised limit; the box
 * count may not exceed 54 (matching {@code Order.MAX_BINS}).
 */
public final class PackageLabel {
    public static final String PREFIX = "WeSort (";
    public static final String SEPARATOR = ") - ";
    public static final String DEFAULT_TEXT = "Beställning";
    /** Longest prefix "WeSort (54/54) - " in UTF-16 code units. */
    public static final int PREFIX_LENGTH = 17;
    /** User text budget: 50 (anvil) minus the longest prefix. */
    public static final int MAX_TEXT = 33;
    public static final int MAX_BOXES = 54;

    private PackageLabel() {}

    /** The full anvil name for box X of a frozen total Y, e.g. {@code "WeSort (3/4) - Byggmaterial"}. */
    public static String name(int x, int y, String text) {
        if (x < 1 || y < 1 || x > y || y > MAX_BOXES) throw new IllegalArgumentException("Box number out of range");
        return PREFIX + x + "/" + y + SEPARATOR + text;
    }

    /**
     * Normalizes and validates one user text: trimmed, empty becomes {@link #DEFAULT_TEXT}, rejected when longer
     * than 33 UTF-16 code units or when it carries {@code §}, control characters, bidi/invisible format characters
     * or a broken surrogate. No whitespace is folded.
     */
    public static String text(String raw, String field) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) return DEFAULT_TEXT;
        if (value.length() > MAX_TEXT) throw new IllegalArgumentException(field + " is longer than " + MAX_TEXT + " characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '§') throw new IllegalArgumentException(field + " can't contain §");
            if (Character.isISOControl(c)) throw new IllegalArgumentException(field + " can't contain control characters");
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || (type == Character.SURROGATE && !(Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)))
                    && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(value.charAt(i - 1))))) {
                throw new IllegalArgumentException(field + " can't contain invisible or direction-changing characters");
            }
        }
        return value;
    }

    /**
     * Normalizes a submitted package label: either {@code {"text":"..."}} (common suffix) or
     * {@code {"texts":["...", ...]}} (a suffix per box, trailing blanks dropped, at most 54). Returns
     * {@code {mode:"all", text}} or {@code {mode:"each", texts:[...]}}, or {@code null} for an absent label
     * (the caller then applies the default "Beställning" for every box).
     */
    public static JsonObject normalize(JsonObject label) {
        if (label == null) return null;
        if (label.has("text")) {
            if (!label.get("text").isJsonPrimitive() || !label.get("text").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("packageLabel.text must be a string");
            String t = text(label.get("text").getAsString(), "packageLabel.text");
            var out = new JsonObject();
            out.addProperty("mode", "all");
            out.addProperty("text", t);
            return out;
        }
        if (label.has("texts")) {
            if (!label.get("texts").isJsonArray()) throw new IllegalArgumentException("packageLabel.texts must be an array");
            var raw = label.getAsJsonArray("texts");
            if (raw.size() > MAX_BOXES) throw new IllegalArgumentException("packageLabel.texts has more than " + MAX_BOXES + " entries");
            int last = -1;
            var texts = new JsonArray();
            for (int i = 0; i < raw.size(); i++) {
                if (!raw.get(i).isJsonPrimitive() || !raw.get(i).getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("packageLabel.texts[" + i + "] must be a string");
                String t = text(raw.get(i).getAsString(), "packageLabel.texts[" + i + "]");
                texts.add(t);
                if (!t.equals(DEFAULT_TEXT)) last = i;
            }
            if (last < 0) {
                var out = new JsonObject();
                out.addProperty("mode", "all");
                out.addProperty("text", DEFAULT_TEXT);
                return out;
            }
            var trimmed = new JsonArray();
            for (int i = 0; i <= last; i++) trimmed.add(texts.get(i));
            var out = new JsonObject();
            out.addProperty("mode", "each");
            out.add("texts", trimmed);
            return out;
        }
        throw new IllegalArgumentException("packageLabel must have text or texts");
    }

    /** The generated full names for a frozen package total, as the agent's {@code names.each} shape. */
    public static JsonArray generate(JsonObject label, int packageTotal) {
        if (packageTotal < 1 || packageTotal > MAX_BOXES) throw new IllegalArgumentException("packageTotal out of range");
        var names = new JsonArray();
        if (label == null || "all".equals(label.get("mode").getAsString())) {
            String text = label == null ? DEFAULT_TEXT : label.get("text").getAsString();
            for (int x = 1; x <= packageTotal; x++) names.add(name(x, packageTotal, text));
        } else {
            var texts = label.getAsJsonArray("texts");
            for (int x = 1; x <= packageTotal; x++) {
                String text = x - 1 < texts.size() ? texts.get(x - 1).getAsString() : DEFAULT_TEXT;
                names.add(name(x, packageTotal, text));
            }
        }
        return names;
    }
}
