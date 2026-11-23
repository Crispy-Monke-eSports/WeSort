package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Website login sessions. The browser holds a random 32-byte token in the wesort_session cookie; sessions.json stores
 * only SHA-256(token) with the created, last-seen and expiry times, so a stolen data folder doesn't hold usable
 * tokens and logins survive a restart. A session lasts 30 days from login.
 */
final class SessionStore {
    static final long LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;
    static final String COOKIE = "wesort_session";
    static final long ELEVATED_MS = 15L * 60 * 1000;
    /** lastSeen is written to disk at most this often per session (it only matters for display and pruning). */
    private static final long SEEN_WRITE_MS = 10L * 60 * 1000;

    private static final int MAX_SESSIONS = 200;
    /** One account can't hold more, so it can't push everyone else's sessions out of the global cap. */
    static final int MAX_PER_USER = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Session {
        final String user; // Users.OWNER_ID or a sub-user's id
        String cred; // Auth.tag of the password hash the session was made with; null for sessions from older servers
        final long created;
        final long expires;
        long lastSeen;
        long writtenSeen;

        Session(String user, long created, long expires, long lastSeen) {
            this.user = user;
            this.created = created;
            this.expires = expires;
            this.lastSeen = lastSeen;
            this.writtenSeen = lastSeen;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new HashMap<>(); // hex SHA-256(token) -> session
    private final Map<String, Long> elevatedUntil = new HashMap<>(); // in memory only: a restart asks again

    SessionStore(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    SessionStore(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("sessions.json");
        this.clock = clock;
    }

    synchronized void load() throws IOException {
        sessions.clear();
        elevatedUntil.clear();
        if (file == null || !Files.exists(file)) return;
        var loaded = new HashMap<String, Session>();
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            for (JsonElement e : list) {
                JsonObject o = e.getAsJsonObject();
                String hash = o.get("hash").getAsString();
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid session hash");
                // A missing user is not evidence of Owner identity.
                String user = o.get("user").getAsString();
                if (!Users.OWNER_ID.equals(user) && !Users.ID.matcher(user).matches())
                    throw new IllegalArgumentException("Invalid session user");
                Session s = new Session(
                        user,
                        o.get("created").getAsLong(),
                        o.get("expires").getAsLong(),
                        o.get("lastSeen").getAsLong());
                s.cred = o.has("cred") && o.get("cred").isJsonPrimitive()
                        ? o.get("cred").getAsString()
                        : null;
                if (loaded.putIfAbsent(hash, s) != null || loaded.size() > MAX_SESSIONS)
                    throw new IllegalArgumentException("Invalid session registry");
            }
            sessions.putAll(loaded);
        } catch (RuntimeException invalid) {
            // Do not retain a valid-looking prefix of a damaged registry.
            System.err.println("Invalid " + file.getFileName() + "; all users must log in again");
        }
        AtomicFiles.ownerOnly(file);
        if (prune()) save();
    }

    /** Starts an owner session and returns its token (for the cookie). */
    synchronized String create() throws IOException {
        return create(Users.OWNER_ID);
    }

    /** Starts a session for this user and returns its token (for the cookie). */
    synchronized String create(String user) throws IOException {
        return create(user, null);
    }

    /** Starts a session for this user, made with the password whose {@link Auth#tag} is cred. */
    synchronized String create(String user, String cred) throws IOException {
        long now = clock.getAsLong();
        var next = new HashMap<>(sessions);
        next.values().removeIf(session -> now >= session.expires);
        // This user's own least recently used session goes first.
        int own = 0;
        String ownOldest = null;
        long ownSeen = Long.MAX_VALUE;
        for (Map.Entry<String, Session> e : next.entrySet()) {
            if (!e.getValue().user.equals(user)) continue;
            own++;
            if (e.getValue().lastSeen < ownSeen) {
                ownSeen = e.getValue().lastSeen;
                ownOldest = e.getKey();
            }
        }
        if (own >= MAX_PER_USER) {
            next.remove(ownOldest);
        }
        if (next.size() >= MAX_SESSIONS) {
            // Drop the least recently used session to stay bounded.
            String oldest = null;
            long seen = Long.MAX_VALUE;
            for (Map.Entry<String, Session> e : next.entrySet()) {
                if (e.getValue().lastSeen < seen) {
                    seen = e.getValue().lastSeen;
                    oldest = e.getKey();
                }
            }
            next.remove(oldest);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session s = new Session(user, now, now + LIFETIME_MS, now);
        s.cred = cred;
        next.put(hash(token), s);
        commit(next);
        return token;
    }

    /** True when the token belongs to a live session (and notes that it was used). */
    synchronized boolean valid(String token) {
        if (token == null || token.isEmpty() || token.length() > 100) return false;
        String h = hash(token);
        Session s = sessions.get(h);
        if (s == null) return false;
        long now = clock.getAsLong();
        if (now >= s.expires) {
            sessions.remove(h);
            save();
            return false;
        }
        s.lastSeen = now;
        if (now - s.writtenSeen >= SEEN_WRITE_MS) save();
        return true;
    }

    /** The user id of a live session, or null (the same checks as {@link #valid}). */
    synchronized String userOf(String token) {
        if (!valid(token)) return null;
        Session s = sessions.get(hash(token));
        return s == null ? null : s.user;
    }

    /** The credential tag a live session was made with (null when unknown or the session is gone). */
    synchronized String credOf(String token) {
        if (token == null) return null;
        Session s = sessions.get(hash(token));
        return s == null ? null : s.cred;
    }

    /** This session now belongs to the new password (after its owner changed it from this session). */
    synchronized void retag(String token, String cred) {
        if (token == null) return;
        Session s = sessions.get(hash(token));
        if (s != null) {
            s.cred = cred;
            save();
        }
    }

    /** Ends every session of this user except keepToken's (null ends them all). Returns how many ended. */
    synchronized int removeUser(String user, String keepToken) throws IOException {
        String keep = keepToken == null ? null : hash(keepToken);
        var next = new HashMap<>(sessions);
        next.entrySet()
                .removeIf(e -> e.getValue().user.equals(user) && !e.getKey().equals(keep));
        int removed = sessions.size() - next.size();
        if (removed > 0) commit(next);
        return removed;
    }

    /** How many live sessions each user has. */
    synchronized Map<String, Integer> countByUser() {
        prune();
        Map<String, Integer> out = new HashMap<>();
        for (Session s : sessions.values()) out.merge(s.user, 1, Integer::sum);
        return out;
    }

    /** Ends one session. */
    synchronized void remove(String token) throws IOException {
        if (token == null) return;
        var next = new HashMap<>(sessions);
        if (next.remove(hash(token)) != null) commit(next);
    }

    /** The password was just typed in this session: dangerous actions are allowed for 15 minutes. */
    synchronized void elevate(String token) {
        if (token != null && sessions.containsKey(hash(token)))
            elevatedUntil.put(hash(token), clock.getAsLong() + ELEVATED_MS);
    }

    /** Ends sudo mode for this session at once ("Lock now"). */
    synchronized void lock(String token) {
        if (token != null) elevatedUntil.remove(hash(token));
    }

    /** When sudo mode ends for this session (epoch ms), or 0 when it isn't on. */
    synchronized long elevatedUntil(String token) {
        if (!elevated(token)) return 0;
        return elevatedUntil.get(hash(token));
    }

    synchronized boolean elevated(String token) {
        if (token == null) return false;
        Long until = elevatedUntil.get(hash(token));
        return until != null && until > clock.getAsLong() && sessions.containsKey(hash(token));
    }

    /** Ends every session except keepToken's (null ends them all). Returns how many ended. */
    synchronized int removeAllExcept(String keepToken) throws IOException {
        String keep = keepToken == null ? null : hash(keepToken);
        var next = new HashMap<>(sessions);
        next.keySet().removeIf(h -> !h.equals(keep));
        int removed = sessions.size() - next.size();
        if (removed > 0) commit(next);
        return removed;
    }

    synchronized int size() {
        return sessions.size();
    }

    /** The token from a Cookie header ("a=b; wesort_session=..."), or null. */
    static String tokenFrom(java.util.List<String> cookieHeaders) {
        if (cookieHeaders == null) return null;
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    String v = p.substring(COOKIE.length() + 1).trim();
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    // No Secure flag: the server itself only speaks plain HTTP (an HTTPS reverse proxy forwards to it over http),
    // and a Secure cookie would never be sent back on plain-HTTP LAN access. If one is ever added, add it only when
    // the request is known to have arrived over https, never from a client-supplied header alone.
    static String cookie(String token) {
        return COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + LIFETIME_MS / 1000;
    }

    static String clearCookie() {
        return COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0";
    }

    private boolean prune() {
        long now = clock.getAsLong();
        return sessions.values().removeIf(s -> now >= s.expires);
    }

    private void commit(Map<String, Session> snapshot) throws IOException {
        persist(snapshot);
        sessions.clear();
        sessions.putAll(snapshot);
        elevatedUntil.keySet().retainAll(sessions.keySet());
    }

    private void persist(Map<String, Session> snapshot) throws IOException {
        if (file == null) return;
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Session> e : snapshot.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("hash", e.getKey());
            o.addProperty("user", e.getValue().user);
            if (e.getValue().cred != null) o.addProperty("cred", e.getValue().cred);
            o.addProperty("created", e.getValue().created);
            o.addProperty("lastSeen", e.getValue().lastSeen);
            o.addProperty("expires", e.getValue().expires);
            list.add(o);
        }
        AtomicFiles.write(file, Json.GSON.toJson(list).getBytes(StandardCharsets.UTF_8), true);
        for (Session s : snapshot.values()) s.writtenSeen = s.lastSeen;
    }

    private void save() {
        try {
            persist(sessions);
        } catch (IOException ex) {
            System.err.println("Could not save " + file.getFileName() + ": " + ex.getMessage());
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
