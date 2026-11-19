package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W-062: per-server opt-in transport capabilities. {@code /tpa} and {@code /home} are two independent,
 * default-OFF flags bound to a verified Minecraft server profile; "configured" is kept separate from
 * "verified supported", so nothing is ever advertised as working without actual server testing. A revision
 * gives optimistic concurrency for Owner updates.
 */
final class TransportProfiles {
    record Profile(
            String server,
            boolean allowTpa,
            boolean allowHome,
            boolean tpaVerified,
            boolean homeVerified,
            long revision) {
        Profile {
            if (server == null || server.isBlank() || server.length() > 256)
                throw new IllegalArgumentException("A server profile needs a server");
            if (revision < 1) throw new IllegalArgumentException("Invalid transport profile revision");
        }

        JsonObject json() {
            var out = new JsonObject();
            out.addProperty("server", server);
            out.addProperty("allowTpa", allowTpa);
            out.addProperty("allowHome", allowHome);
            out.addProperty("tpaVerified", tpaVerified);
            out.addProperty("homeVerified", homeVerified);
            out.addProperty("revision", revision);
            return out;
        }

        static Profile read(JsonObject o) {
            return new Profile(
                    o.get("server").getAsString(),
                    o.get("allowTpa").getAsBoolean(),
                    o.get("allowHome").getAsBoolean(),
                    o.get("tpaVerified").getAsBoolean(),
                    o.get("homeVerified").getAsBoolean(),
                    o.get("revision").getAsLong());
        }

        static Profile initial(String server) {
            return new Profile(server, false, false, false, false, 1);
        }

        Profile updated(JsonObject request) {
            return new Profile(
                    server,
                    flag(request, "allowTpa", allowTpa),
                    flag(request, "allowHome", allowHome),
                    flag(request, "tpaVerified", tpaVerified),
                    flag(request, "homeVerified", homeVerified),
                    Math.addExact(revision, 1));
        }

        private static boolean flag(JsonObject o, String field, boolean current) {
            var value = o.get(field);
            if (value == null) return current;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException(field + " must be a boolean");
            return value.getAsBoolean();
        }
    }

    static final class Conflict extends IOException {
        Conflict() {
            super("Transport profile revision changed; reload before updating");
        }
    }

    private static final int SCHEMA = 1;
    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    TransportProfiles(Path data) throws IOException {
        this.file = data.resolve("transport.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid transport profile location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var entry : root.getAsJsonArray("profiles"))
                profiles.put(
                        entry.getAsJsonObject().get("server").getAsString(), Profile.read(entry.getAsJsonObject()));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read transport profiles", invalid);
        }
    }

    synchronized Profile get(String server) {
        if (server == null || server.isBlank() || server.length() > 256)
            throw new IllegalArgumentException("A server is required");
        return profiles.getOrDefault(server, Profile.initial(server));
    }

    synchronized Profile update(String server, JsonObject request, long expectedRevision) throws IOException {
        Profile before = get(server);
        if (before.revision() != expectedRevision) throw new Conflict();
        Profile after = before.updated(request);
        profiles.put(server, after);
        save();
        return after;
    }

    synchronized JsonArray list() {
        var out = new JsonArray();
        profiles.values().forEach(profile -> out.add(profile.json()));
        return out;
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var list = new JsonArray();
        profiles.values().forEach(profile -> list.add(profile.json()));
        root.add("profiles", list);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
    }
}
