package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** The single organisation in this Manager volume. Names, members and Minecraft routing have separate identities. */
record GuildProfile(UUID id, String name, String description, long revision) {
    private static final int SCHEMA = 1;

    GuildProfile {
        name = validateText(name, 64, false);
        description = validateText(description, 512, true);
        if (revision < 1) throw new IllegalArgumentException("Invalid guild revision");
    }

    static GuildProfile open(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid guild profile location");
        if (!Files.exists(file)) {
            var initial = new GuildProfile(UUID.randomUUID(), "WeSort Guild", "", 1);
            initial.save(file);
            return initial;
        }
        try {
            var data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (data.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            return new GuildProfile(UUID.fromString(data.get("id").getAsString()), string(data, "name"),
                string(data, "description"), data.get("revision").getAsBigDecimal().longValueExact());
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read the guild profile", invalid);
        }
    }

    JsonObject json() {
        var out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.addProperty("id", id.toString());
        out.addProperty("name", name);
        out.addProperty("description", description);
        out.addProperty("revision", revision);
        return out;
    }

    void save(Path file) throws IOException {
        AtomicFiles.write(file, json().toString().getBytes(StandardCharsets.UTF_8), true);
    }

    GuildProfile updated(JsonObject request) {
        return new GuildProfile(id, string(request, "name"), string(request, "description"), Math.addExact(revision, 1));
    }

    private static String string(JsonObject value, String field) {
        var element = value.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Guild " + field + " must be text");
        }
        return element.getAsString();
    }

    private static String validateText(String raw, int limit, boolean optional) {
        String value = raw.strip();
        if (value.length() > limit || !optional && value.isEmpty()
            || value.codePoints().anyMatch(cp -> Character.getType(cp) == Character.FORMAT
                || Character.isISOControl(cp) && !(optional && cp == '\n'))) {
            throw new IllegalArgumentException(optional ? "Guild description must be at most 512 characters"
                : "Guild name must contain 1–64 printable characters");
        }
        return value;
    }
}
