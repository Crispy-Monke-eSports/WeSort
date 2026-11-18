package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Installation-local credentials. A credential authorizes one Minecraft account and stash. */
final class AgentCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_AGENTS = 64;
    private final Path file;
    private Map<String, Credential> entries = Map.of();
    private boolean scopedOnly;

    record Scope(String agent, String player, String server, String stash) {
        Scope {
            if (agent == null || !agent.equals(UUID.fromString(agent).toString()) || player == null
                || !player.matches("[A-Za-z0-9_]{1,16}") || !valid(server, 256) || !valid(stash, 256)) {
                throw new IllegalArgumentException("Invalid Minecraft agent scope");
            }
        }

        private static boolean valid(String value, int limit) {
            return value != null && !value.isBlank() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl);
        }

        boolean matchesLocation(String server, String stash) { return this.server.equals(server) && this.stash.equals(stash); }

        String botId() {
            return UUID.nameUUIDFromBytes((server + "\u0000" + agent).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    record Credential(Scope scope, String signingKey) {
        byte[] key() { return Base64.getDecoder().decode(signingKey); }
        @Override public String toString() { return "Agent credential for " + scope.agent(); }
    }

    AgentCredentials(Path data) throws IOException {
        file = data.resolve("agent-credentials.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid agent credential storage");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            var loaded = new TreeMap<String, Credential>();
            for (var item : root.getAsJsonArray("agents")) {
                var row = item.getAsJsonObject();
                var scope = new Scope(field(row, "agent"), field(row, "player"), field(row, "server"), field(row, "stash"));
                String key = field(row, "key");
                if (key != null && Base64.getDecoder().decode(key).length != 32
                    || loaded.putIfAbsent(scope.agent(), new Credential(scope, key)) != null || loaded.size() > MAX_AGENTS) {
                    throw new IllegalArgumentException();
                }
            }
            entries = Map.copyOf(loaded);
            scopedOnly = root.get("scopedOnly").getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot load agent credentials");
        }
    }

    synchronized boolean scopedOnly() { return scopedOnly; }

    synchronized Credential find(String id) {
        var saved = entries.get(id);
        return saved == null || saved.signingKey() == null ? null : saved;
    }

    synchronized String issue(Scope scope) throws IOException {
        var existing = entries.get(scope.agent());
        if (existing != null && !existing.scope().equals(scope)) throw new IllegalArgumentException("Agent ID is bound to a different scope");
        if (existing == null && entries.size() >= MAX_AGENTS) throw new IllegalArgumentException("Agent credential limit reached");
        var random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var next = new TreeMap<>(entries);
        var signingKey = Base64.getEncoder().encodeToString(Signer.deriveKey(secret));
        next.put(scope.agent(), new Credential(scope, signingKey));
        save(next, scopedOnly);
        return scope.agent() + "." + secret;
    }

    synchronized boolean revoke(String id) throws IOException {
        var current = find(id);
        if (current == null) return false;
        var next = new TreeMap<>(entries);
        next.put(id, new Credential(current.scope(), null));
        save(next, scopedOnly);
        return true;
    }

    synchronized void requireScoped() throws IOException {
        if (entries.values().stream().noneMatch(c -> c.signingKey() != null)) throw new IllegalArgumentException("Issue an agent credential first");
        save(entries, true);
    }

    private void save(Map<String, Credential> next, boolean required) throws IOException {
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("scopedOnly", required);
        var agents = new com.google.gson.JsonArray();
        for (var entry : next.values()) {
            var row = new JsonObject();
            row.addProperty("agent", entry.scope().agent());
            row.addProperty("player", entry.scope().player());
            row.addProperty("server", entry.scope().server());
            row.addProperty("stash", entry.scope().stash());
            row.addProperty("key", entry.signingKey());
            agents.add(row);
        }
        root.add("agents", agents);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
        entries = Map.copyOf(next);
        scopedOnly = required;
    }

    private static String field(JsonObject object, String field) { return AddonRoutes.string(object, field); }
}
