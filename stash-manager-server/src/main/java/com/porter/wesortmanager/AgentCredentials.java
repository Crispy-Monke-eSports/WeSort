package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Installation-local API key slots for Minecraft agents.
 *
 * <p>A slot is an unbound connection place, not an identity: it carries no Minecraft server,
 * stash or player restriction. A valid signing key authorizes any player, independent
 * of username and UUID. The last reported username is non-authoritative display data.
 * Manager still enforces server, stash, session, revocation and physical safety guards.
 */
final class AgentCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ACTIVE_SLOTS = 1;
    private static final int MAX_ENTRIES = 64;
    /** Revoked slots carry no secret; keep the registry small so rotation never fills it with tombstones. */
    static final int MAX_REVOKED_SLOTS = 16;

    private final Path file;
    private final Clock clock;
    private final Map<String, Slot> entries = new TreeMap<>();
    private boolean scopedOnly;
    private java.util.function.Consumer<String> revocationListener = id -> {};
    /** Last rejected signed request per key id (best-effort presence: auth-failure vs silence). */
    private final Map<String, Long> authFailures = new HashMap<>();

    /** Retire the single active session after its credential is durably revoked. */
    synchronized void onRevocation(java.util.function.Consumer<String> listener) {
        revocationListener = java.util.Objects.requireNonNull(listener);
    }

    /** Latest signed request's reported player; not a security identity. */
    record Binding(String playerName, long lastSeen) {}

    /**
     * Proxy/Floodgate/offline names can contain dots, spaces, stars or Unicode. Treat
     * these as untrusted display data, not as Java-account names or credentials.
     */
    static String displayName(String reported) {
        if (reported == null) return null;
        String name = reported.strip();
        if (name.isEmpty()
                || name.length() > 64
                || name.codePoints()
                        .anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT))
            return null;
        return name;
    }

    record Slot(String id, String label, String signingKey, Binding binding) {
        Slot {
            if (id == null || !id.equals(UUID.fromString(id).toString())) {
                throw new IllegalArgumentException("Invalid agent key id");
            }
            if (!validLabel(label)) throw new IllegalArgumentException("Invalid agent key label");
        }

        boolean active() {
            return signingKey != null;
        }

        Slot withKey(String key) {
            return new Slot(id, label, key, binding);
        }

        Slot withBinding(Binding next) {
            return new Slot(id, label, signingKey, next);
        }

        /** The bot id the agent must send: the slot id, nothing derived. */
        String botId() {
            return id;
        }
    }

    AgentCredentials(Path data) throws IOException {
        this(data, Clock.system());
    }

    /** Test-only clock for display-only presence timestamps. */
    AgentCredentials(Path data, Clock clock) throws IOException {
        file = data.resolve("agent-credentials.json");
        this.clock = clock;
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid agent credential storage");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("version").getAsBigDecimal().intValueExact() != 3) throw new IllegalArgumentException();
            var loaded = new TreeMap<String, Slot>();
            for (var item : root.getAsJsonArray("slots")) {
                var row = item.getAsJsonObject();
                var slot = readSlot(row);
                String key = field(row, "key");
                if (key != null && Base64.getDecoder().decode(key).length != 32
                        || loaded.putIfAbsent(slot.id(), slot.withKey(key)) != null
                        || loaded.size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException();
                }
            }
            if (loaded.values().stream().filter(Slot::active).count() > MAX_ACTIVE_SLOTS)
                throw new IllegalArgumentException("Only one active agent credential is supported");
            entries.putAll(loaded);
            scopedOnly = root.get("scopedOnly").getAsBoolean();
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot load agent credentials");
        }
    }

    synchronized boolean scopedOnly() {
        return scopedOnly;
    }

    synchronized Slot find(String id) {
        var saved = entries.get(id);
        return saved == null || !saved.active() ? null : saved;
    }

    /** Retained slots, active and revoked, sorted by id. */
    synchronized List<Slot> all() {
        return List.copyOf(entries.values());
    }

    /**
     * Issues a new unbound slot. Returns {@code "<id>.<secret>"}; only the id is stored, the
     * secret is shown once.
     */
    synchronized String issue(String label) throws IOException {
        long active = entries.values().stream().filter(Slot::active).count();
        if (active >= MAX_ACTIVE_SLOTS) throw new IllegalArgumentException("Only one agent credential may be active");
        String id = UUID.randomUUID().toString();
        var random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var signingKey = Base64.getEncoder().encodeToString(Signer.deriveKey(secret));
        var updated = new TreeMap<>(entries);
        // Revoked slots no longer authenticate. Keep a bounded registry without
        // making the installation permanently unable to rotate its only agent key.
        if (updated.size() >= MAX_ENTRIES) {
            String retired = updated.values().stream()
                    .filter(slot -> !slot.active())
                    .findFirst()
                    .orElseThrow()
                    .id();
            updated.remove(retired);
        }
        updated.put(id, new Slot(id, label, signingKey, null));
        save(updated, scopedOnly);
        entries.clear();
        entries.putAll(updated);
        return id + "." + secret;
    }

    /**
     * Remember only the last reported username for Manager's UI. An invalid or absent
     * name is ignored, never denied. This is transient presence, not a key claim.
     */
    synchronized void notePlayer(String id, String name) {
        name = displayName(name);
        if (name == null) return;
        var slot = entries.get(id);
        if (slot == null || !slot.active()) return;
        entries.put(id, slot.withBinding(new Binding(name, clock.millis())));
    }

    /**
     * Remember a rejected signed request for one key id (unknown, revoked or bad
     * signature): the dashboard tells "auth-failure" apart from "silent". Never
     * persisted; bounded so junk floods cannot grow it.
     */
    synchronized void noteAuthFailure(String id) {
        if (id == null) return;
        if (authFailures.size() >= 128) authFailures.clear();
        authFailures.put(id, clock.millis());
    }

    /** The last rejected signed-request time for one key id, or -1. */
    synchronized long lastAuthFailure(String id) {
        return authFailures.getOrDefault(id, -1L);
    }

    /** How long a rejected key still reads as "auth-failure" instead of "silent". */
    static final long AUTH_FAILURE_WINDOW_MS = 5 * 60 * 1000;

    /** Whether the key saw a rejected signed request recently (vs a bot that just went quiet). */
    synchronized boolean recentAuthFailure(String id, long now) {
        long at = authFailures.getOrDefault(id, -1L);
        return at > 0 && now - at < AUTH_FAILURE_WINDOW_MS;
    }

    synchronized boolean revoke(String id) throws IOException {
        var current = entries.get(id);
        if (current == null || !current.active()) return false;
        var updated = new TreeMap<>(entries);
        updated.put(id, current.withKey(null).withBinding(null));
        save(updated, scopedOnly);
        entries.clear();
        entries.putAll(updated);
        // A valid key no longer authorizes this bot. Remove its cached online
        // presence immediately, not after the long-poll online grace period.
        revocationListener.accept(id);
        return true;
    }

    synchronized void requireScoped() throws IOException {
        if (entries.values().stream().noneMatch(Slot::active))
            throw new IllegalArgumentException("Issue an agent credential first");
        // save prunes revoked entries, so never pass the live map to a fallible write.
        var updated = new TreeMap<>(entries);
        save(updated, true);
        entries.clear();
        entries.putAll(updated);
    }

    private void save(Map<String, Slot> updated, boolean required) throws IOException {
        pruneRevoked(updated);
        var root = new JsonObject();
        root.addProperty("version", 3);
        root.addProperty("scopedOnly", required);
        var slots = new com.google.gson.JsonArray();
        for (var entry : updated.values()) {
            var row = new JsonObject();
            row.addProperty("id", entry.id());
            row.addProperty("label", entry.label());
            row.addProperty("key", entry.signingKey());
            slots.add(row);
        }
        root.add("slots", slots);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
        scopedOnly = required;
    }

    private static Slot readSlot(JsonObject row) {
        // Discard old persisted UUID/player claims; only live, signed requests set presence.
        return new Slot(field(row, "id"), field(row, "label"), null, null);
    }

    /** Drop revoked tombstones beyond the cap; active slots are never pruned. */
    private static void pruneRevoked(Map<String, Slot> updated) {
        long revoked = updated.values().stream().filter(slot -> !slot.active()).count();
        var it = updated.entrySet().iterator();
        while (revoked > MAX_REVOKED_SLOTS && it.hasNext()) {
            if (!it.next().getValue().active()) {
                it.remove();
                revoked--;
            }
        }
    }

    static boolean validLabel(String label) {
        return label != null
                && !label.isBlank()
                && label.length() <= 64
                && label.chars().noneMatch(Character::isISOControl);
    }

    private static String field(JsonObject object, String field) {
        return AddonRoutes.string(object, field);
    }
}
