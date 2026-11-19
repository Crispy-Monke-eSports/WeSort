package com.porter.wesort.data;

import com.google.gson.JsonObject;
import java.util.UUID;

/**
 * W-062: the closed allowlist of typed transport operations. A server may opt in to {@code /tpa} and
 * {@code /home} per verified profile, but only these exact operations exist — never arbitrary {@code /command}
 * strings, forwarded player messages or unsanitized fields. Both are default OFF; support is only advertised
 * after explicit, verified testing of the actual server.
 */
public final class TransportCommands {
    public enum Kind {
        TPA_REQUEST,
        HOME_GO,
        TPA_ACCEPT
    }

    private TransportCommands() {}

    /** A Minecraft player name target: printable, 1–16 characters, no spaces, control, section or format characters. */
    public static String player(String raw, String field) {
        return text(raw, field, 16);
    }

    /** A registered home name: printable, 1–32 characters, no spaces, control, section or format characters. */
    public static String home(String raw, String field) {
        return text(raw, field, 32);
    }

    /** A correlation id for a TPA request/accept: a canonical UUID. */
    public static String requestId(String raw, String field) {
        try {
            return UUID.fromString(raw).toString();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    /**
     * Validates and normalizes one typed operation. {@code {"kind":"TPA_REQUEST","target":"..."}},
     * {@code {"kind":"HOME_GO","home":"..."}} or {@code {"kind":"TPA_ACCEPT","requestId":"..."}}. Any other
     * kind or field is rejected before any chat transmission.
     */
    public static JsonObject parse(JsonObject operation) {
        String kind;
        try {
            kind = operation.get("kind").getAsString();
        } catch (RuntimeException missing) {
            throw new IllegalArgumentException("A typed transport operation requires a kind");
        }
        var out = new JsonObject();
        switch (Kind.valueOf(kind)) {
            case TPA_REQUEST -> {
                out.addProperty("kind", "TPA_REQUEST");
                out.addProperty("target", player(string(operation, "target"), "target"));
            }
            case HOME_GO -> {
                out.addProperty("kind", "HOME_GO");
                out.addProperty("home", home(string(operation, "home"), "home"));
            }
            case TPA_ACCEPT -> {
                out.addProperty("kind", "TPA_ACCEPT");
                out.addProperty("requestId", requestId(string(operation, "requestId"), "requestId"));
            }
        }
        return out;
    }

    private static String string(JsonObject operation, String field) {
        var value = operation.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "A " + operation.get("kind").getAsString() + " operation needs " + field);
        }
        return value.getAsString();
    }

    private static String text(String raw, String field, int limit) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > limit)
            throw new IllegalArgumentException(field + " must be 1-" + limit + " characters");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '§'
                    || c == ' '
                    || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(field + " contains a forbidden character");
            }
        }
        return value;
    }
}
