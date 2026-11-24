package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Website accounts. The owner is the existing dashboard password (config.properties dashboardPassword=) and can do
 * everything; its name lives here (default "owner"). Sub-users, each with a password hash (see Auth) and permissions,
 * are kept in users.json (owner-only file permissions). Users answer the questions the routes ask: who is this session,
 * and may they do this.
 */
final class Users {
    static final String OWNER_ID = "owner";
    static final String DEFAULT_OWNER_NAME = "owner";
    static final int MAX_USERS = 50;
    static final int MAX_NAME = 32;
    static final int MAX_SCOPE = 200;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1," + MAX_NAME + "}");
    static final Pattern ID = Pattern.compile("u-[0-9a-f]{12}");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Characters a console might read as chaining or substitution; refused in "list" mode. */
    /** In "list" mode a whole command may only use these (plain ASCII words, names, numbers, coordinates). */

    /** A stash a scoped user may see and use. Single-server: the stash name alone is the scope. */
    record StashRef(String stash) {}

    record Perms(
            boolean view,
            boolean order,
            boolean ordersAll,
            boolean activity,
            boolean settings,
            boolean rescan,
            boolean link,
            List<StashRef> stashes,
            List<String> bots,
            boolean guildOrders,
            boolean positions) {
        static final Perms NONE = of(false, false, false, false, false, false, false, null, null);
        static final Perms ALL =
                of(true, true, true, true, true, true, true, null, null).withGuildOrders(true);

        static Perms of(
                boolean view,
                boolean order,
                boolean ordersAll,
                boolean activity,
                boolean settings,
                boolean rescan,
                boolean link,
                List<StashRef> stashes,
                List<String> bots) {
            return new Perms(
                    view || order || ordersAll,
                    order,
                    ordersAll,
                    activity || settings,
                    settings,
                    rescan,
                    link,
                    stashes == null ? null : List.copyOf(stashes),
                    bots == null ? null : List.copyOf(bots),
                    false,
                    settings);
        }

        Perms withGuildOrders(boolean allowed) {
            return new Perms(
                    view, order, ordersAll, activity, settings, rescan, link, stashes, bots, allowed, positions);
        }

        Perms withPositions(boolean allowed) {
            return new Perms(
                    view, order, ordersAll, activity, settings, rescan, link, stashes, bots, guildOrders, allowed);
        }

        /**
         * Scoped update helpers: absent scope keys in an update body keep the
         * existing scope (PATCH-like merge) instead of widening to "all".
         * Explicit null still means unlimited, [] still means no access.
         */
        Perms withStashes(List<StashRef> scopes) {
            return new Perms(
                    view,
                    order,
                    ordersAll,
                    activity,
                    settings,
                    rescan,
                    link,
                    scopes == null ? null : List.copyOf(scopes),
                    bots,
                    guildOrders,
                    positions);
        }

        Perms withBots(List<String> scopes) {
            return new Perms(
                    view,
                    order,
                    ordersAll,
                    activity,
                    settings,
                    rescan,
                    link,
                    stashes,
                    scopes == null ? null : List.copyOf(scopes),
                    guildOrders,
                    positions);
        }

        boolean seesStash(String stash) {
            return stashes == null || stashes.stream().anyMatch(r -> r.stash.equals(stash));
        }

        boolean seesBot(String botId) {
            return bots == null || bots.contains(botId);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("view", view);
            o.addProperty("order", order);
            o.addProperty("ordersAll", ordersAll);
            o.addProperty("guildOrders", guildOrders);
            o.addProperty("positions", positions);
            o.addProperty("activity", activity);
            o.addProperty("settings", settings);
            o.addProperty("rescan", rescan);
            o.addProperty("link", link);

            if (stashes == null) {
                o.add("stashes", JsonNull.INSTANCE);
            } else {
                JsonArray s = new JsonArray();
                for (StashRef r : stashes) {
                    JsonObject ro = new JsonObject();
                    ro.addProperty("stash", r.stash);
                    s.add(ro);
                }
                o.add("stashes", s);
            }
            if (bots == null) {
                o.add("bots", JsonNull.INSTANCE);
            } else {
                JsonArray b = new JsonArray();
                bots.forEach(b::add);
                o.add("bots", b);
            }
            return o;
        }

        static Perms parse(JsonElement e) {
            if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("perms must be an object");
            JsonObject o = e.getAsJsonObject();
            List<StashRef> stashes = null;
            JsonElement se = o.get("stashes");
            if (se != null && !se.isJsonNull()) {
                if (!se.isJsonArray()) throw new IllegalArgumentException("stashes must be null or a list of {stash}");
                stashes = new ArrayList<>();
                for (JsonElement item : se.getAsJsonArray()) {
                    if (!item.isJsonObject())
                        throw new IllegalArgumentException("stashes must be null or a list of {stash}");
                    String stash = AddonRoutes.string(item.getAsJsonObject(), "stash");
                    if (stash == null || stash.isEmpty() || stash.length() > 256) {
                        throw new IllegalArgumentException("every stash needs a stash name");
                    }
                    StashRef r = new StashRef(stash);
                    if (!stashes.contains(r)) stashes.add(r);
                }
                if (stashes.size() > MAX_SCOPE) throw new IllegalArgumentException("at most " + MAX_SCOPE + " stashes");
            }
            List<String> bots = null;
            JsonElement be = o.get("bots");
            if (be != null && !be.isJsonNull()) {
                if (!be.isJsonArray()) throw new IllegalArgumentException("bots must be null or a list of bot ids");
                bots = new ArrayList<>();
                for (JsonElement item : be.getAsJsonArray()) {
                    if (!(item instanceof JsonPrimitive p)
                            || !p.isString()
                            || !p.getAsString().matches("[A-Za-z0-9_-]{1,64}")) {
                        throw new IllegalArgumentException("bots must be null or a list of bot ids");
                    }
                    if (!bots.contains(p.getAsString())) bots.add(p.getAsString());
                }
                if (bots.size() > MAX_SCOPE) throw new IllegalArgumentException("at most " + MAX_SCOPE + " bots");
            }
            var perms = of(
                            bool(o, "view"),
                            bool(o, "order"),
                            bool(o, "ordersAll"),
                            bool(o, "activity"),
                            bool(o, "settings"),
                            bool(o, "rescan"),
                            bool(o, "link"),
                            stashes,
                            bots)
                    .withGuildOrders(bool(o, "guildOrders"));
            return o.has("positions") ? perms.withPositions(bool(o, "positions")) : perms;
        }

        private static boolean bool(JsonObject o, String key) {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return false;
            if (!(e instanceof JsonPrimitive p) || !p.isBoolean())
                throw new IllegalArgumentException(key + " must be true or false");
            return p.getAsBoolean();
        }
    }

    /** Lowercase, trimmed, inner whitespace collapsed. */
    static final Map<String, Perms> PRESETS;

    static {
        Map<String, Perms> p = new LinkedHashMap<>();
        p.put("viewer", Perms.of(true, false, false, false, false, false, false, null, null));
        p.put("orderer", Perms.of(true, true, false, false, false, false, false, null, null));
        p.put("member", p.get("orderer"));
        p.put(
                "officer",
                Perms.of(true, true, true, true, false, false, false, null, null)
                        .withGuildOrders(true));
        p.put("operator", Perms.of(true, true, true, true, true, true, false, null, null));
        PRESETS = java.util.Collections.unmodifiableMap(p);
    }

    /** A sub-user as stored. Immutable: changes replace it. */
    record User(
            String id,
            String name,
            String hash,
            Perms perms,
            boolean disabled,
            boolean mustChange,
            long createdAt,
            long lastLoginAt,
            long passwordChangedAt) {
        /** Never the hash. */
        @Override
        public String toString() {
            return "User[" + id + " " + name + (disabled ? " disabled" : "") + "]";
        }

        JsonObject toJson(int sessions) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("disabled", disabled);
            o.addProperty("mustChange", mustChange);
            o.add("perms", perms.toJson());
            o.addProperty("createdAt", createdAt);
            o.addProperty("lastLoginAt", lastLoginAt > 0 ? lastLoginAt : null);
            o.addProperty("sessions", sessions);
            return o;
        }
    }

    /** Who a request is from: the owner or a sub-user, with what it may do. */
    record Principal(String id, String name, boolean owner, Perms perms, boolean mustChange) {
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("owner", owner);
            o.addProperty("mustChange", mustChange);
            o.add("perms", perms.toJson());
            return o;
        }
    }

    /** A refused change, with the HTTP status to answer. */
    static final class UserException extends Exception {
        final int status;

        UserException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, User> users = new LinkedHashMap<>(); // id -> user, in creation order
    private String ownerName = DEFAULT_OWNER_NAME;
    private boolean registryUnavailable;
    private final Map<String, String> minecraftAccounts = new LinkedHashMap<>();

    Users(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    Users(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("users.json");
        this.clock = clock;
    }

    /**
     * Keep the Manager and the independently authenticated owner available if the account registry
     * cannot be validated. Never restore older permissions or replace the damaged file with defaults.
     * A restart after an offline, verified restore is required to leave this mode.
     */
    synchronized void loadForService() {
        try {
            load();
            registryUnavailable = false;
        } catch (IOException failure) {
            users.clear();
            minecraftAccounts.clear();
            ownerName = DEFAULT_OWNER_NAME;
            registryUnavailable = true;
            System.err.println("Website user registry unavailable: sub-user access and registry writes disabled");
        }
    }

    synchronized boolean registryUnavailable() {
        return registryUnavailable;
    }

    /** Validate the complete persisted registry before publishing any account or permission. */
    synchronized void load() throws IOException {
        if (file == null) return;
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid user registry location");
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!users.isEmpty() || !minecraftAccounts.isEmpty() || !DEFAULT_OWNER_NAME.equals(ownerName))
                throw new IOException("Previously loaded user registry is missing");
            return;
        }
        try {
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (longOf(root, "version") != 1) throw new IllegalArgumentException("Unsupported user schema");
            String nextOwner = requiredText(root, "ownerName");
            if (!NAME.matcher(nextOwner).matches()) throw new IllegalArgumentException("Invalid Owner name");
            var loaded = new LinkedHashMap<String, User>();
            var accounts = new LinkedHashMap<String, String>();
            var names = new java.util.HashSet<String>();
            names.add(nextOwner.toLowerCase(Locale.ROOT));
            JsonArray list = root.getAsJsonArray("users");
            if (list == null || list.size() > MAX_USERS) throw new IllegalArgumentException("Invalid user list");
            for (JsonElement e : list) {
                var o = e.getAsJsonObject();
                String id = requiredText(o, "id");
                String name = requiredText(o, "name");
                if (!ID.matcher(id).matches()
                        || loaded.containsKey(id)
                        || !NAME.matcher(name).matches()
                        || !names.add(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Invalid or duplicate account");
                String hash = requiredText(o, "hash");
                if (!Auth.wellFormed(hash)) throw new IllegalArgumentException("Invalid password hash");
                var persistedPerms = o.getAsJsonObject("perms");
                // The current writer always includes these fields. Missing scope
                // fields must never silently become unlimited privileges.
                for (String key : List.of(
                        "view",
                        "order",
                        "ordersAll",
                        "activity",
                        "settings",
                        "rescan",
                        "link",
                        "guildOrders",
                        "positions")) requiredBoolean(persistedPerms, key);
                if (!persistedPerms.has("stashes") || !persistedPerms.has("bots"))
                    throw new IllegalArgumentException("Missing permission scope");
                loaded.put(
                        id,
                        new User(
                                id,
                                name,
                                hash,
                                Perms.parse(persistedPerms),
                                requiredBoolean(o, "disabled"),
                                requiredBoolean(o, "mustChange"),
                                longOf(o, "createdAt"),
                                longOf(o, "lastLoginAt"),
                                longOf(o, "passwordChangedAt")));
            }
            if (root.has("minecraftAccounts")) {
                var mappings = root.getAsJsonObject("minecraftAccounts");
                for (var entry : mappings.entrySet()) {
                    String account = requiredText(mappings, entry.getKey());
                    if (!account.matches("[A-Za-z0-9_]{1,16}")
                            || accounts.values().stream().anyMatch(account::equalsIgnoreCase)
                            || !(OWNER_ID.equals(entry.getKey()) || loaded.containsKey(entry.getKey())))
                        throw new IllegalArgumentException("Invalid Minecraft account mapping");
                    accounts.put(entry.getKey(), account);
                }
            }
            AtomicFiles.ownerOnly(file);
            ownerName = nextOwner;
            users.clear();
            users.putAll(loaded);
            minecraftAccounts.clear();
            minecraftAccounts.putAll(accounts);
        } catch (RuntimeException invalid) {
            // Do not include arbitrary registry values or password hashes in startup logs.
            throw new IOException("Cannot validate users.json; refusing to discard or widen account permissions");
        }
    }

    private static String requiredText(JsonObject o, String key) {
        var e = o.get(key);
        if (!(e instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("Invalid user field");
        return p.getAsString();
    }

    private static boolean requiredBoolean(JsonObject o, String key) {
        var e = o.get(key);
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) throw new IllegalArgumentException("Invalid user flag");
        return p.getAsBoolean();
    }

    private static long longOf(JsonObject o, String key) {
        var e = o.get(key);
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) throw new IllegalArgumentException("Invalid user number");
        long n = p.getAsBigDecimal().longValueExact();
        if (n < 0) throw new IllegalArgumentException("Negative user number");
        return n;
    }

    private void save() throws IOException {
        if (registryUnavailable) throw new IOException("User registry requires verified offline recovery");
        if (file == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("ownerName", ownerName);
        JsonArray list = new JsonArray();
        for (User u : users.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", u.id);
            o.addProperty("name", u.name);
            o.addProperty("hash", u.hash);
            o.add("perms", u.perms.toJson());
            o.addProperty("disabled", u.disabled);
            o.addProperty("mustChange", u.mustChange);
            o.addProperty("createdAt", u.createdAt);
            o.addProperty("lastLoginAt", u.lastLoginAt);
            o.addProperty("passwordChangedAt", u.passwordChangedAt);
            list.add(o);
        }
        root.add("users", list);
        if (!minecraftAccounts.isEmpty()) {
            var accounts = new JsonObject();
            minecraftAccounts.forEach(accounts::addProperty);
            root.add("minecraftAccounts", accounts);
        }
        AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
    }

    synchronized String minecraftAccount(String id) {
        return minecraftAccounts.get(id);
    }

    synchronized String activeMinecraftAccount(String id) {
        User member = users.get(id);
        return OWNER_ID.equals(id) || member != null && !member.disabled ? minecraftAccounts.get(id) : null;
    }

    synchronized String approvedRecipient(String account) {
        for (var entry : minecraftAccounts.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(account)) return activeMinecraftAccount(entry.getKey());
        }
        return null;
    }

    synchronized void linkMinecraftAccount(String id, String account) throws UserException, IOException {
        if (!OWNER_ID.equals(id)) need(id);
        String before = minecraftAccounts.get(id);
        if (account == null || account.isBlank()) {
            minecraftAccounts.remove(id);
        } else {
            account = account.strip();
            if (!account.matches("[A-Za-z0-9_]{1,16}")) {
                throw new UserException(400, "Minecraft names must contain 1–16 letters, digits or underscores");
            }
            for (var entry : minecraftAccounts.entrySet()) {
                if (!id.equals(entry.getKey()) && entry.getValue().equalsIgnoreCase(account)) {
                    throw new UserException(409, "This Minecraft account is already linked to another member");
                }
            }
            minecraftAccounts.put(id, account);
        }
        saveOrUndo(() -> {
            if (before == null) minecraftAccounts.remove(id);
            else minecraftAccounts.put(id, before);
        });
    }

    synchronized String ownerName() {
        return ownerName;
    }

    synchronized List<User> list() {
        return List.copyOf(users.values());
    }

    synchronized User byId(String id) {
        return id == null ? null : users.get(id);
    }

    synchronized User byName(String name) {
        return byNameLocked(name);
    }

    /** Names compare ignoring ASCII case only (no Unicode folding: the Kelvin sign is not a k). */
    static boolean sameName(String a, String b) {
        return a != null
                && b != null
                && NAME.matcher(a).matches()
                && NAME.matcher(b).matches()
                && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }

    private User byNameLocked(String name) {
        if (name == null) return null;
        for (User u : users.values()) if (sameName(u.name, name)) return u;
        return null;
    }

    /** True when a login name means the owner: blank, or the owner's name. */
    synchronized boolean isOwnerName(String name) {
        return name == null || name.isBlank() || sameName(name.trim(), ownerName);
    }

    /** The credential tag a session of this user must carry (see Auth.tag), or null for an unknown user. */
    synchronized String credTag(String userId, String ownerHash) {
        if (OWNER_ID.equals(userId)) return Auth.tag(ownerHash);
        User u = users.get(userId);
        return u == null ? null : Auth.tag(u.hash);
    }

    /**
     * Who a session's user id is now, or null when that user is gone or disabled (the session must end). Read on every
     * request, so permission changes apply at once.
     */
    synchronized Principal principal(String userId) {
        if (OWNER_ID.equals(userId)) return new Principal(OWNER_ID, ownerName, true, Perms.ALL, false);
        User u = byId(userId);
        if (u == null || u.disabled) return null;
        return new Principal(u.id, u.name, false, u.perms, u.mustChange);
    }

    private String checkName(String name, String exceptId) throws UserException {
        if (name == null) throw new UserException(400, "name must be text");
        String n = name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new UserException(400, "names are 1-" + MAX_NAME + " characters: letters, digits, _ . -");
        }
        User other = byNameLocked(n);
        if ((other != null && !other.id.equals(exceptId)) || (!OWNER_ID.equals(exceptId) && sameName(n, ownerName))) {
            throw new UserException(409, "that name is taken");
        }
        return n;
    }

    synchronized User create(String name, String hash, Perms perms, boolean mustChange)
            throws UserException, IOException {
        if (users.size() >= MAX_USERS) throw new UserException(400, "at most " + MAX_USERS + " users");
        String n = checkName(name, null);
        String id;
        do {
            byte[] b = new byte[6];
            RANDOM.nextBytes(b);
            id = "u-" + HexFormat.of().formatHex(b);
        } while (users.containsKey(id));
        long now = clock.getAsLong();
        User u = new User(id, n, hash, perms, false, mustChange, now, 0, now);
        users.put(id, u);
        saveOrUndo(() -> users.remove(u.id));
        return u;
    }

    /** Changes name, permissions and/or disabled (each null = unchanged). */
    synchronized User update(String id, String name, Perms perms, Boolean disabled) throws UserException, IOException {
        User u = need(id);
        String n = name == null ? u.name : checkName(name, id);
        User next = new User(
                u.id,
                n,
                u.hash,
                perms == null ? u.perms : perms,
                disabled == null ? u.disabled : disabled,
                u.mustChange,
                u.createdAt,
                u.lastLoginAt,
                u.passwordChangedAt);
        users.put(id, next);
        saveOrUndo(() -> users.put(id, u));
        return next;
    }

    synchronized void setPassword(String id, String hash, boolean mustChange) throws UserException, IOException {
        User u = need(id);
        users.put(
                id,
                new User(
                        u.id,
                        u.name,
                        hash,
                        u.perms,
                        u.disabled,
                        mustChange,
                        u.createdAt,
                        u.lastLoginAt,
                        clock.getAsLong()));
        saveOrUndo(() -> users.put(id, u));
    }

    synchronized void delete(String id) throws UserException, IOException {
        need(id);
        Map<String, User> before = new LinkedHashMap<>(users);
        users.remove(id);
        String account = minecraftAccounts.remove(id);
        saveOrUndo(() -> {
            users.clear();
            users.putAll(before);
            if (account != null) minecraftAccounts.put(id, account);
        });
    }

    synchronized void renameOwner(String name) throws UserException, IOException {
        String n = checkName(name, OWNER_ID);
        String before = ownerName;
        ownerName = n;
        try {
            save();
        } catch (IOException e) {
            ownerName = before;
            throw e;
        }
    }

    /** Notes a successful login (not saved on failure: it's only shown). */
    synchronized void noteLogin(String id) {
        User u = users.get(id);
        if (u == null) return;
        users.put(
                id,
                new User(
                        u.id,
                        u.name,
                        u.hash,
                        u.perms,
                        u.disabled,
                        u.mustChange,
                        u.createdAt,
                        clock.getAsLong(),
                        u.passwordChangedAt));
        try {
            save();
        } catch (IOException e) {
            System.err.println("Could not save users.json: " + e.getMessage());
        }
    }

    private User need(String id) throws UserException {
        User u = id == null ? null : users.get(id);
        if (u == null) throw new UserException(404, "no such user");
        return u;
    }

    private void saveOrUndo(Runnable undo) throws IOException {
        try {
            save();
        } catch (IOException e) {
            undo.run();
            throw e;
        }
    }
}
