package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single HttpServer context. Runs the access check on every request, then dispatches by method and path,
 * then applies the route's guard. New endpoints (orders later on) are added with {@link #exact} or {@link #prefix}.
 */
final class Router implements HttpHandler {
    /** Exchange attribute holding the API key generation that an ADDON_KEY request was made with (a Long). */
    static final String KEY_GENERATION = "wesortmanager.keyGeneration";

    static final String SCOPED_AGENT = "wesortmanager.scopedAgent";
    /** Exchange attribute holding the session token of a SESSION / BROWSER_WRITE request (a String). */
    static final String SESSION_TOKEN = "wesortmanager.session";
    /** Exchange attribute holding who a SESSION / BROWSER_WRITE request is from (a Users.Principal). */
    static final String USER = "wesortmanager.user";

    static final Set<String> GET = Set.of("GET", "HEAD");
    static final Set<String> POST = Set.of("POST");

    enum Guard {
        /** Only the access check (LAN address + Host allowlist): static files, auth state. */
        OPEN,
        /** A bot: Authorization: Bearer <apiKey>, otherwise 401. Never needs a website login. */
        ADDON_KEY,
        /** A logged-in browser (wesort_session cookie), otherwise 401 {"error":"login required"}. Every browser GET. */
        SESSION,
        /** The CSRF checks in PROTOCOL.md without a session (login and setup), otherwise 403. */
        CSRF,
        /** A state-changing request from a logged-in browser: SESSION plus CSRF. */
        BROWSER_WRITE
    }

    /**
     * The permission a logged-in route needs before its handler runs. Handlers check the finer rules themselves
     * (own orders, stash and bot scope, proxy and console levels, Admin unlocked).
     */
    enum Perm {
        /** Any logged-in user, even one that must change the password first (only the auth routes). */
        ANY("any", "do that"),
        VIEW("view", "view stashes"),
        ICONS("view", "see item icons"),
        ORDER("order", "place orders"),
        /** order, ordersAll or plannerOrder (cancel and retry: own orders, or any with ordersAll). */
        ORDER_OWN_OR_ALL("order", "change orders"),
        ORDERS_ALL("ordersAll", "change other users' orders"),
        ACTIVITY("activity", "view bots"),
        RESCAN("rescan", "rescan stashes"),
        LINK("link", "link bots"),
        /** The owner only: user management and the audit log. */
        OWNER("owner", "manage users (owner only)");

        final String key;
        final String what;

        Perm(String key, String what) {
            this.key = key;
            this.what = what;
        }

        boolean allows(Users.Principal p) {
            Users.Perms x = p.perms();
            return switch (this) {
                case ANY -> true;
                case VIEW -> x.view();
                case ICONS -> x.view();
                case ORDER -> x.order();
                case ORDER_OWN_OR_ALL -> x.order() || x.ordersAll();
                case ORDERS_ALL -> x.ordersAll();
                case ACTIVITY -> x.activity();
                case RESCAN -> x.rescan();
                case LINK -> x.link();
                case OWNER -> p.owner();
            };
        }
    }

    interface Route {
        void handle(HttpExchange ex) throws IOException;
    }

    private record Entry(
            Set<String> methods,
            String path,
            boolean prefix,
            boolean fallback,
            Guard guard,
            Perm perm,
            Route route,
            Pattern pattern) {
        boolean matches(String p) {
            if (pattern != null) return pattern.matcher(p).matches();
            return prefix ? p.startsWith(path) : p.equals(path);
        }
    }

    private final AccessControl access;
    private final Signer signer = new Signer();
    private final SessionStore sessions;
    private final Config config;
    private final Users users;
    private final List<Entry> entries = new ArrayList<>();

    /**
     * A server address from a request, as given. Null when it isn't one: answer 404.
     */
    static String serverParam(HttpExchange ex, String value) {
        if (value == null) return null;
        return value;
    }

    /** The same for a bot id. */
    static String botParam(HttpExchange ex, String value) {
        return value;
    }

    private final AgentCredentials agentCredentials;
    private final OrderStore orderStore;

    Router(
            AccessControl access,
            SessionStore sessions,
            Config config,
            Users users,
            AgentCredentials agentCredentials,
            OrderStore orderStore) {
        this.access = access;
        this.sessions = sessions;
        this.config = config;
        this.users = users;
        this.agentCredentials = agentCredentials;
        this.orderStore = orderStore;
    }

    /** The access rules (the same-origin checks of a long-lived browser GET such as the viewer stream). */
    AccessControl access() {
        return access;
    }

    /**
     * Whether a session may still use something it was let in for: the session is live (its user not deleted or
     * disabled, its password unchanged), no password change is pending, the permission is still granted and, with a
     * botId, the bot is still in the user's scope. For long-lived responses (the viewer stream) that re-check while
     * they run, with the same resolve every request goes through.
     */
    /** Who a session token belongs to now (the same resolve as every request), or null. */
    Users.Principal current(String token) {
        return resolve(sessions, users, config, token);
    }

    boolean stillAllowed(String token, Perm perm, String botId) {
        Users.Principal p = resolve(sessions, users, config, token);
        if (p == null || (p.mustChange() && perm != Perm.ANY) || !perm.allows(p)) return false;
        return botId == null || p.perms().seesBot(botId);
    }

    /** Who made this logged-in request (set before every SESSION / BROWSER_WRITE handler runs). */
    static Users.Principal user(HttpExchange ex) {
        return (Users.Principal) Http.attr(ex, USER);
    }

    /** Answers 403 for a missing permission. */
    static void forbid(HttpExchange ex, String what, String key) throws IOException {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", "you don't have permission to " + what);
        o.addProperty("permission", key);
        Http.json(ex, 403, o);
    }

    private record LoggedIn(String token, Users.Principal user) {}

    /**
     * The session token and its user when the request carries a live session (and a password is set), else null.
     * A session whose user was deleted or disabled is ended here.
     */
    private LoggedIn session(HttpExchange ex) {
        String token = SessionStore.tokenFrom(ex.getRequestHeaders().get("Cookie"));
        Users.Principal p = resolve(sessions, users, config, token);
        return p == null ? null : new LoggedIn(token, p);
    }

    /**
     * Who a session token belongs to now, or null. Ends the session when its user is gone or disabled, or when it
     * was made with a password that has changed since. Sessions from before sub-users (no tag) are the owner's only.
     */
    static Users.Principal resolve(SessionStore sessions, Users users, Config config, String token) {
        if (config.dashboardPassword() == null) return null;
        String userId = sessions.userOf(token);
        if (userId == null) return null;
        Users.Principal p = users.principal(userId);
        String cred = sessions.credOf(token);
        boolean credOk =
                cred == null ? p != null && p.owner() : cred.equals(users.credTag(userId, config.dashboardPassword()));
        if (p == null || !credOk) {
            sessions.remove(token);
            return null;
        }
        return p;
    }

    private static void checkPerm(Guard guard, Perm perm) {
        boolean loggedIn = guard == Guard.SESSION || guard == Guard.BROWSER_WRITE;
        if (loggedIn != (perm != null)) {
            throw new IllegalArgumentException(
                    "logged-in routes need a permission, other routes none: " + guard + " " + perm);
        }
    }

    Router exact(Set<String> methods, String path, Guard guard, Route route) {
        return exact(methods, path, guard, null, route);
    }

    Router exact(Set<String> methods, String path, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, path, false, false, guard, perm, route, null));
        return this;
    }

    Router prefix(Set<String> methods, String path, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, path, true, false, guard, perm, route, null));
        return this;
    }

    /** A path matching a regex over the raw path, e.g. /api/v1/orders/(o-[0-9a-f]{12})/cancel. */
    Router pattern(Set<String> methods, Pattern pattern, Guard guard, Route route) {
        return pattern(methods, pattern, guard, null, route);
    }

    Router pattern(Set<String> methods, Pattern pattern, Guard guard, Perm perm, Route route) {
        checkPerm(guard, perm);
        entries.add(new Entry(methods, null, false, false, guard, perm, route, pattern));
        return this;
    }

    /** A catch-all (the static files). Unlike other routes it never turns a wrong method into 405. */
    Router fallback(Set<String> methods, String path, Route route) {
        entries.add(new Entry(methods, path, true, true, Guard.OPEN, null, route, null));
        return this;
    }

    /** Every registered route as "[METHODS] path-or-pattern GUARD [PERM]" (for tests). */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(new TreeSet<>(e.methods) + " " + (e.pattern != null ? e.pattern.pattern() : e.path) + " " + e.guard
                    + (e.perm == null ? "" : " " + e.perm));
        }
        return out;
    }

    @Override
    public void handle(HttpExchange ex) {
        try {
            Http.securityHeaders(ex.getResponseHeaders());
            if (!access.isAllowed(ex)) {
                Http.error(ex, 403, "forbidden");
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                // CORS preflights always fail: no Access-Control-* headers, ever.
                Http.error(ex, 405, "method not allowed");
                return;
            }

            Entry match = null;
            Set<String> allowed = new TreeSet<>();
            for (Entry e : entries) {
                if (!e.matches(path)) continue;
                if (e.methods.contains(method)) {
                    match = e;
                    break;
                }
                if (!e.fallback) allowed.addAll(e.methods);
            }
            if (match == null) {
                if (allowed.isEmpty()) {
                    Http.error(ex, 404, "not found");
                } else {
                    ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
                    Http.error(ex, 405, "method not allowed");
                }
                return;
            }

            switch (match.guard) {
                case ADDON_KEY -> {
                    if (!botAuth(ex, path)) return;
                }
                case SESSION -> {
                    if (!loggedIn(ex, match.perm)) return;
                }
                case CSRF -> {
                    if (!access.browserWriteAllowed(ex)) {
                        Http.error(
                                ex, 403, "browser write refused (needs POST, X-Stash-Manager: 1, JSON, same origin)");
                        return;
                    }
                }
                case BROWSER_WRITE -> {
                    // CSRF first: a cross-site request learns nothing about the login state.
                    if (!access.browserWriteAllowed(ex)) {
                        Http.error(
                                ex, 403, "browser write refused (needs POST, X-Stash-Manager: 1, JSON, same origin)");
                        return;
                    }
                    if (!loggedIn(ex, match.perm)) return;
                }

                case OPEN -> {}
            }
            match.route.handle(ex);
        } catch (Http.TooLarge e) {
            safeError(ex, 413, "body too large (bot/* 1 MiB, others 8 MiB)");
        } catch (IOException e) {
            // Usually the client went away mid-request or mid-response. Nothing useful to send back.
        } catch (RuntimeException e) {
            System.err.println("Error handling " + ex.getRequestMethod() + " "
                    + ex.getRequestURI().getRawPath() + ": " + e);
            safeError(ex, 500, "internal error");
        } finally {
            boolean detached = Http.attr(ex, Http.DETACHED) != null; // then another thread closes it
            Http.clearAttrs(ex);
            if (!detached) ex.close();
        }
    }

    /**
     * The session check of a logged-in route: who it is, the must-change-password gate, and the route's permission.
     * False after answering 401 or 403.
     */
    private boolean loggedIn(HttpExchange ex, Perm perm) throws IOException {
        LoggedIn s = session(ex);
        if (s == null) {
            Http.error(ex, 401, "login required");
            return false;
        }
        Http.attr(ex, SESSION_TOKEN, s.token());
        Http.attr(ex, USER, s.user());
        if (s.user().mustChange() && perm != Perm.ANY) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", "change your password first");
            o.addProperty("mustChange", true);
            Http.json(ex, 403, o);
            return false;
        }
        if (!perm.allows(s.user())) {
            forbid(ex, perm.what, perm.key);
            return false;
        }
        return true;
    }

    /**
     * A bot request: X-SM-Auth (HMAC, PROTOCOL.md "Signed requests"); the response is then signed too. The old Bearer
     * header only with allowBearer=true and never on bot/*. False after answering 401.
     */
    private boolean botAuth(HttpExchange ex, String path) throws IOException {
        boolean dashboard = path.startsWith("/api/v1/bot/");
        String header = ex.getRequestHeaders().getFirst(Signer.REQUEST_HEADER);
        if (header != null) {
            // Format, timestamp and nonce syntax first, so unauthenticated junk never makes the server read a body.
            Signer.Auth pre = Signer.Auth.parse(header);
            if (!signer.precheck(pre)) {
                Http.error(ex, 401, "bad signature");
                return false;
            }
            boolean named = !pre.botId().equals("-");
            var credential = named ? agentCredentials.find(pre.botId()) : null;
            if ((named && credential == null) || (!named && agentCredentials.scopedOnly())) {
                Http.error(ex, 401, "bad signature");
                return false;
            }
            byte[] body = Http.readBody(ex, dashboard ? Http.MAX_BOT_BODY : Http.MAX_BODY);
            Http.attr(ex, Http.BODY, body);
            String raw = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null
                            ? ""
                            : "?" + ex.getRequestURI().getRawQuery());
            Config.KeyState current = config.keyState();
            Config.KeyState previous = dashboard ? null : config.previousKey();
            byte[] kCurrent = named ? Base64.getDecoder().decode(credential.signingKey()) : current.signingKey();
            byte[] kPrevious = named || previous == null ? null : previous.signingKey();
            Signer.Auth a = Signer.Auth.parse(header);
            int which = signer.verify(a, ex.getRequestMethod(), raw, body, kCurrent, kPrevious);
            if (which < 0) {
                Http.error(ex, 401, "bad signature");
                return false;
            }
            Http.attr(ex, KEY_GENERATION, which == 0 ? current.generation() : previous.generation());
            Http.attr(ex, Http.SIGN_KEY, which == 0 ? kCurrent : kPrevious);
            Http.attr(ex, Http.SIGN_NONCE, a.nonce());
            if (named) {
                int scope = agentScope(ex, path, credential);
                if (scope != 200) {
                    Http.error(
                            ex,
                            scope,
                            scope == 409
                                    ? "API key slot is held by another player, or not bound to a player yet"
                                    : "Agent slot does not authorize this request");
                    return false;
                }
            }
            if (named) Http.attr(ex, SCOPED_AGENT, credential);
            return true;
        }
        if (!dashboard && config.allowBearer && !agentCredentials.scopedOnly()) {
            long generation = access.keyGeneration(ex);
            if (generation >= 0) {
                Http.attr(ex, KEY_GENERATION, generation);
                return true;
            }
        }
        Http.error(
                ex,
                401,
                ex.getRequestHeaders().containsKey("Authorization")
                        ? (dashboard || !config.allowBearer
                                ? "Bearer is not accepted: update the bot (requests must be signed)"
                                : "missing or wrong API key")
                        : "missing signature (X-SM-Auth)");
        return false;
    }

    /**
     * Whether a signed bot request may run: the key slot must be valid, must name itself, and
     * exactly one player may hold the slot at a time. Returns 200, 403 or 409 (slot held by
     * another player, or not bound to a player yet for session calls).
     */
    private int agentScope(HttpExchange ex, String path, AgentCredentials.Slot slot) throws IOException {
        if (path.equals("/api/v1/bot/next") && ex.getRequestMethod().equals("GET")) {
            if (!slot.id().equals(Http.query(ex).get("botId"))) return 403;
            return slot.bound() ? 200 : 409;
        }
        var request = AddonRoutes.readJson(ex);
        if (request == null || !request.isJsonObject()) return 403;
        var body = request.getAsJsonObject();
        String player = AddonRoutes.string(body, "player");
        String agent = AddonRoutes.string(body, "agent");
        return switch (path) {
            case "/api/v1/index", "/api/v1/heartbeat" -> bindOrCheck(slot, agent, player);
            // Delivery reports carry no identity of their own: they only run on a bound slot.
            case "/api/v1/delivery" -> slot.bound() ? 200 : 409;
            case "/api/v1/orders/claim", "/api/v1/boxes/agent", "/api/v1/custody/agent", "/api/v1/deposits/agent" ->
                bindOrCheck(slot, agent, player);
            case "/api/v1/automation" -> bindOrCheck(slot, agent, player);
            case "/api/v1/jobs/agent" -> bindOrCheck(slot, agent, player);
            case "/api/v1/handoffs/agent" -> bindOrCheck(slot, agent, player);
            case "/api/v1/physical/agent" -> {
                var record = object(body, "record");
                var context = record == null ? null : object(record, "context");
                if (context == null) yield 403;
                yield bindOrCheck(slot, AddonRoutes.string(context, "agent"), AddonRoutes.string(context, "player"));
            }
            case "/api/v1/bot/hello" -> {
                var binding = object(body, "manifestScope");
                if (binding != null && !slot.id().equals(AddonRoutes.string(body, "botId"))) yield 403;
                String scopeAgent = binding == null ? agent : AddonRoutes.string(binding, "agent");
                String scopePlayer = binding == null ? player : AddonRoutes.string(binding, "player");
                yield bindOrCheck(slot, scopeAgent, scopePlayer);
            }
            case "/api/v1/bot/state",
                    "/api/v1/bot/next",
                    "/api/v1/bot/results",
                    "/api/v1/bot/activity",
                    "/api/v1/bot/manifest",
                    "/api/v1/bot/manifest/ack" -> {
                if (!slot.id().equals(AddonRoutes.string(body, "botId"))) yield 403;
                yield slot.bound() ? 200 : 409;
            }
            default -> {
                var match = SCOPED_ORDER_STATUS.matcher(path);
                if (!match.matches()) yield 403;
                var where = orderStore.stashOf(match.group(1));
                if (where == null) yield 403;
                yield bindOrCheck(slot, agent, player);
            }
        };
    }

    /**
     * Binds the slot to the player UUID, or checks the live binding. 403 when the identity is
     * missing or malformed, 409 when another player holds the slot. A refused claim never extends
     * the live binding.
     */
    private int bindOrCheck(AgentCredentials.Slot slot, String agentUuid, String playerName) throws IOException {
        if (agentUuid == null) return 403;
        try {
            UUID.fromString(agentUuid);
        } catch (IllegalArgumentException bad) {
            return 403;
        }
        String name = playerName == null || playerName.isBlank() ? "player" : playerName;
        try {
            var touch = agentCredentials.touch(slot.id(), agentUuid, name);
            return touch == AgentCredentials.Touch.CONFLICT ? 409 : 200;
        } catch (IllegalArgumentException bad) {
            return 403;
        }
    }

    private static final Pattern SCOPED_ORDER_STATUS = Pattern.compile("^/api/v1/orders/(o-[0-9a-f]{12})/status$");

    private static com.google.gson.JsonObject object(com.google.gson.JsonObject parent, String name) {
        var value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static void safeError(HttpExchange ex, int status, String message) {
        try {
            Http.error(ex, status, message);
        } catch (IOException | RuntimeException ignored) {
            // Headers were probably already sent.
        }
    }
}
