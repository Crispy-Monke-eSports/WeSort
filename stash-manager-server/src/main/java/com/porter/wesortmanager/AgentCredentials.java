package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Installation-local API key slots for Minecraft agents.
 *
 * <p>A slot is an unbound connection place, not an identity: it carries no Minecraft server,
 * stash or player binding. Any player may connect with a slot's key, but only one player at a
 * time. The first player to use the key claims the slot; a different player is refused while the
 * claim is live. The Manager is the source of truth for server, stash and work; the key only
 * proves the connection is allowed.
 */
final class AgentCredentials {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ACTIVE_SLOTS = 5;
    private static final int MAX_ENTRIES = 64;
    /** A live player claim holds the slot this long without any signed request. */
    static final long BIND_WINDOW_MS = 120_000;

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Slot> entries = new TreeMap<>();
    private boolean scopedOnly;

    /** The player currently holding a slot, with when it was last seen. */
    record Binding(String playerUuid, String playerName, long lastSeen) {
        Binding {
            if (playerUuid == null
                    || !playerUuid.equals(UUID.fromString(playerUuid).toString())
                    || playerName == null
                    || !playerName.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IllegalArgumentException("Invalid player binding");
            }
        }
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

        boolean bound() {
            return binding != null;
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

    /** The outcome of claiming (or re-checking) a slot for a player. */
    enum Touch {
        BOUND,
        REBOUND,
        CONFLICT
    }

    AgentCredentials(Path data) throws IOException {
        this(data, System::currentTimeMillis);
    }

    /** Test-only clock: bindings expire against it, so rebinding is testable without sleeping. */
    AgentCredentials(Path data, LongSupplier clock) throws IOException {
        file = data.resolve("agent-credentials.json");
        this.clock = clock;
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid agent credential storage");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            int version = root.get("version").getAsInt();
            if (version != 1 && version != 2 && version != 3) throw new IllegalArgumentException();
            var loaded = new TreeMap<String, Slot>();
            for (var item : root.getAsJsonArray(version == 3 ? "slots" : "agents")) {
                var row = item.getAsJsonObject();
                var slot = version == 3 ? readSlot(row) : migrate(row, version == 1);
                String key = field(row, "key");
                if (key != null && Base64.getDecoder().decode(key).length != 32
                        || loaded.putIfAbsent(slot.id(), slot.withKey(key)) != null
                        || loaded.size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException();
                }
            }
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

    /** All slots, active and revoked, oldest first. */
    synchronized List<Slot> all() {
        return List.copyOf(entries.values());
    }

    /**
     * Issues a new unbound slot. Returns {@code "<id>.<secret>"}; only the id is stored, the
     * secret is shown once.
     */
    synchronized String issue(String label) throws IOException {
        if (entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("Agent credential registry is full");
        long active = entries.values().stream().filter(Slot::active).count();
        if (active >= MAX_ACTIVE_SLOTS) throw new IllegalArgumentException("Only five agent credentials may be active");
        String id = UUID.randomUUID().toString();
        var random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var signingKey = Base64.getEncoder().encodeToString(Signer.deriveKey(secret));
        entries.put(id, new Slot(id, label, signingKey, null));
        save(scopedOnly);
        return id + "." + secret;
    }

    /**
     * Claims the slot for a player, or checks the live claim. {@link Touch#CONFLICT} when another
     * player holds the slot; the failed claim never extends the live one. A persisted bind/rebind
     * is saved; a same-player refresh only touches memory.
     */
    synchronized Touch touch(String id, String playerUuid, String playerName) throws IOException {
        var saved = entries.get(id);
        if (saved == null || !saved.active()) throw new IllegalArgumentException("No active agent credential");
        var current = saved.binding();
        long now = clock.getAsLong();
        if (current != null && !current.playerUuid().equals(playerUuid) && now - current.lastSeen() < BIND_WINDOW_MS) {
            return Touch.CONFLICT;
        }
        boolean changed = current == null || !current.playerUuid().equals(playerUuid);
        entries.put(id, saved.withBinding(new Binding(playerUuid, playerName, now)));
        if (changed) save(scopedOnly);
        return changed ? (current == null ? Touch.BOUND : Touch.REBOUND) : Touch.BOUND;
    }

    synchronized boolean revoke(String id) throws IOException {
        var current = entries.get(id);
        if (current == null || !current.active()) return false;
        entries.put(id, current.withKey(null).withBinding(null));
        save(scopedOnly);
        return true;
    }

    synchronized void requireScoped() throws IOException {
        if (entries.values().stream().noneMatch(Slot::active))
            throw new IllegalArgumentException("Issue an agent credential first");
        save(true);
    }

    private void save(boolean required) throws IOException {
        var root = new JsonObject();
        root.addProperty("version", 3);
        root.addProperty("scopedOnly", required);
        var slots = new com.google.gson.JsonArray();
        for (var entry : entries.values()) {
            var row = new JsonObject();
            row.addProperty("id", entry.id());
            row.addProperty("label", entry.label());
            if (entry.binding() != null) {
                var binding = new JsonObject();
                binding.addProperty("uuid", entry.binding().playerUuid());
                binding.addProperty("name", entry.binding().playerName());
                binding.addProperty("seen", entry.binding().lastSeen());
                row.add("binding", binding);
            }
            row.addProperty("key", entry.signingKey());
            slots.add(row);
        }
        root.add("slots", slots);
        AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8), true);
        scopedOnly = required;
    }

    /** Pre-slot file formats (1: single stash, 2: stash list): bindings are dropped, slots stay. */
    private static Slot migrate(JsonObject row, boolean singleStash) {
        String agent = field(row, "agent");
        if (agent == null || !agent.equals(UUID.fromString(agent).toString())) {
            throw new IllegalArgumentException("Invalid agent id");
        }
        String player = field(row, "player");
        if (player == null || !player.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Invalid player");
        }
        String server = field(row, "server");
        if (!validServer(server)) throw new IllegalArgumentException("Invalid server");
        if (singleStash) {
            if (!validServer(field(row, "stash"))) throw new IllegalArgumentException("Invalid stash");
        } else {
            var stashes = new HashSet<String>();
            for (var stash : row.getAsJsonArray("stashes")) {
                if (!stash.isJsonPrimitive()
                        || !stash.getAsJsonPrimitive().isString()
                        || !validServer(stash.getAsString())
                        || !stashes.add(stash.getAsString())) {
                    throw new IllegalArgumentException("Invalid stash list");
                }
            }
            if (stashes.isEmpty() || stashes.size() > IndexStore.MAX_STASHES) {
                throw new IllegalArgumentException("Invalid stash list");
            }
        }
        // The legacy row validated: the slot keeps only its id and a human label.
        String label = !player.isBlank() ? player : "agent-" + agent.substring(0, 8);
        return new Slot(agent, label, null, null);
    }

    private static Slot readSlot(JsonObject row) {
        Binding binding = null;
        if (row.has("binding") && row.get("binding").isJsonObject()) {
            var b = row.getAsJsonObject("binding");
            binding = new Binding(
                    field(b, "uuid"), field(b, "name"), b.get("seen").getAsLong());
        }
        return new Slot(field(row, "id"), field(row, "label"), null, binding);
    }

    static boolean validLabel(String label) {
        return label != null
                && !label.isBlank()
                && label.length() <= 64
                && label.chars().noneMatch(Character::isISOControl);
    }

    private static boolean validServer(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 256
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String field(JsonObject object, String field) {
        return AddonRoutes.string(object, field);
    }
}
