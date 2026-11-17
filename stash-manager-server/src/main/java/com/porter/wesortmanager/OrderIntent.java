package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** A member's declared intent. Recipient verification and delivery are separate steps. */
record OrderIntent(Purpose purpose, String recipient, String comment) {
    enum Purpose { SELF, GUILD }

    static final OrderIntent DEFAULT = new OrderIntent(Purpose.SELF, null, null);

    boolean requiresGuildPermission() {
        return purpose == Purpose.GUILD || recipient != null;
    }

    static OrderIntent read(JsonObject body) {
        String declaredPurpose = optionalText(body, "purpose", 8);
        Purpose purpose = declaredPurpose == null ? Purpose.SELF : Purpose.valueOf(declaredPurpose);
        String recipient = optionalText(body, "recipient", 16);
        if (recipient != null && !recipient.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Recipient must be a Minecraft player name");
        }
        return new OrderIntent(purpose, recipient, optionalText(body, "comment", 500));
    }

    private static String optionalText(JsonObject body, String key, int maximum) {
        var element = body.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(key + " must be text");
        }
        String value = primitive.getAsString().strip();
        if (value.codePointCount(0, value.length()) > maximum
            || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\t')) {
            throw new IllegalArgumentException(key + " must be at most " + maximum + " characters");
        }
        return value.isEmpty() ? null : value;
    }
}
