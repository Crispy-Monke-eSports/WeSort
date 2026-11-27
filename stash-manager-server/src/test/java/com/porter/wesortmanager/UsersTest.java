package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Website sub-users through the real routes on a real server (127.0.0.1, a random port, a temp data folder):
 * migration of a single-password setup, the user lifecycle, permission enforcement per endpoint, stash scope, own vs
 * all orders, sessions ending on disable / password reset / delete, and the audit log.
 */
class UsersTest {
    static final String OWNER_PW = "owner-password-123";
    static final String TEMP_PW = "temporary-pass-1";
    static final String NEW_PW = "brand-new-pass-22";

    @TempDir
    Path data;

    HttpServer server;
    String base;
    Users users;
    SessionStore sessions;
    OrderStore orders;
    AuditLog audit;
    Router router;
    BotHub hub;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    /** A credential-bound owner session persisted before Manager startup. */
    String persistedOwnerSession;

    @BeforeEach
    void start() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        config.setMinecraftServer("s");
        SessionStore old = new SessionStore(data);
        persistedOwnerSession = old.create(Users.OWNER_ID, Auth.tag(config.dashboardPassword()));
        startServer(config);
    }

    private void startServer(Config config) throws Exception {
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        StockFixtures.put(
                store,
                json(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
                                + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}"));
        StockFixtures.put(
                store,
                json("{\"version\":1,\"server\":\"s\",\"stash\":\"b\",\"items\":["
                        + "{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":1000,\"maxStack\":64}]}"));
        orders = new OrderStore(data, store);
        orders.load();
        IconService icons = new IconService(
                data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        sessions = new SessionStore(data);
        sessions.load();
        users = new Users(data);
        users.load();
        audit = new AuditLog(data);
        audit.load();
        hub = new BotHub();
        WeSortManager.Routes r = WeSortManager.routes(
                config,
                store,
                orders,
                icons,
                new LinkState(config.keyState().generation()),
                List.of(),
                sessions,
                users,
                audit,
                hub,
                new LoginLimiter());
        router = r.router();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", router);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ---------- helpers ----------

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    record Res(int status, JsonElement body, HttpResponse<String> raw) {
        JsonObject obj() {
            return body.getAsJsonObject();
        }

        String error() {
            return body != null && body.isJsonObject() && body.getAsJsonObject().has("error")
                    ? body.getAsJsonObject().get("error").getAsString()
                    : null;
        }
    }

    Res get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        return send(b.build());
    }

    Res post(String token, String path, String body) throws Exception {
        return post(token, path, body, null);
    }

    Res post(String token, String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Stash-Manager", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        return send(b.build());
    }

    private Res send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonElement body = null;
        try {
            body = r.body().isEmpty() ? null : JsonParser.parseString(r.body());
        } catch (RuntimeException ignored) {
        }
        return new Res(r.statusCode(), body, r);
    }

    /** Logs in; returns the session token. */
    String login(String username, String password) throws Exception {
        Res r = post(
                null,
                "/api/v1/auth/login",
                username == null
                        ? "{\"password\":\"" + password + "\"}"
                        : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, r.status, "login " + username + ": " + r.raw.body());
        String setCookie = r.raw.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(SessionStore.COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    String owner() throws Exception {
        return login(null, OWNER_PW);
    }

    /** Creates a sub-user (as the owner, who is Admin unlocked right after login); returns its id. */
    String create(String owner, String name, String perms, boolean mustChange) throws Exception {
        Res r = post(
                owner,
                "/api/v1/users",
                "{\"name\":\"" + name + "\",\"password\":\"" + TEMP_PW + "\",\"mustChange\":" + mustChange
                        + (perms.startsWith("{") ? ",\"perms\":" + perms : ",\"preset\":\"" + perms + "\"") + "}");
        assertEquals(200, r.status, r.raw.body());
        return r.obj().getAsJsonObject("user").get("id").getAsString();
    }

    /** Stock alone no longer admits orders: the owner must define both warehouses. */
    private void defineOrderStashes(String owner) throws Exception {
        for (String stash : List.of("a", "b")) {
            int x = stash.hashCode();
            var definition = json("""
                {"action":"create","dimension":"minecraft:overworld",
                 "pos1":{"x":0,"y":64,"z":0},"pos2":{"x":0,"y":64,"z":0}}
                """);
            definition.addProperty("name", stash);
            definition.getAsJsonObject("pos1").addProperty("x", x);
            definition.getAsJsonObject("pos2").addProperty("x", x);
            Res created = post(owner, "/api/v1/stash-defs", definition.toString());
            assertEquals(200, created.status, created.raw.body());
        }
    }

    String placeOrder(String token, String stash, String key, int n) throws Exception {
        Res r = post(
                token,
                "/api/v1/orders",
                "{\"stash\":\"" + stash + "\",\"lines\":[{\"type\":\"item\",\"key\":\"" + key + "\",\"count\":" + n
                        + "}]}");
        assertEquals(200, r.status, r.raw.body());
        return r.obj().getAsJsonObject("order").get("id").getAsString();
    }

    // ---------- tests ----------

    @Test
    void loginReturnsNoCookieWhenItsSessionCannotBePersisted() throws Exception {
        String existing = login(null, OWNER_PW);
        Path file = data.resolve("sessions.json");
        Path saved = Files.move(file, data.resolve("previous-sessions.json"));
        Files.createDirectory(file);
        try {
            JsonObject password = new JsonObject();
            password.addProperty("password", OWNER_PW);
            Res failed = post(null, "/api/v1/auth/login", password.toString());
            assertEquals(503, failed.status, failed.raw.body());
            assertTrue(failed.raw.headers().firstValue("Set-Cookie").isEmpty());
            assertTrue(get(existing, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        } finally {
            Files.delete(file);
            Files.move(saved, file);
        }
        assertFalse(login(null, OWNER_PW).isBlank());
    }

    @Test
    void credentialBoundOwnerSessionSurvivesStartupAndUntaggedSessionsAreRefused() throws Exception {
        Res state = get(persistedOwnerSession, "/api/v1/auth/state");
        assertTrue(state.obj().get("loggedIn").getAsBoolean());
        JsonObject user = state.obj().getAsJsonObject("user");
        assertEquals("owner", user.get("id").getAsString());
        assertTrue(user.get("owner").getAsBoolean());
        assertEquals(200, get(persistedOwnerSession, "/api/v1/users").status);
        String untaggedOwner = sessions.create(Users.OWNER_ID);
        assertEquals(401, get(untaggedOwner, "/api/v1/users").status);
        assertFalse(
                get(untaggedOwner, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        // The owner logs in with no username or with the owner's name.
        assertNotNull(owner());
        assertNotNull(login("owner", OWNER_PW));
        assertNotNull(login(" OWNER ", OWNER_PW));
        assertFalse(Files.exists(data.resolve("users.json")), "nothing written until a user is added");
    }

    @Test
    void membersUseOneGuildStockAndMembersRetainOrdersAfterMemberRevocation() throws Exception {
        // Strict Owner/Member (#26/#43): exactly two identities. Members place and
        // follow only their own SELF orders; GUILD orders, foreign cancellations and
        // revocation belong to the Owner. Elevated custom grants are refused.
        String owner = owner();
        defineOrderStashes(owner);
        String memberId = create(owner, "Maker", "member", false);
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/users",
                                "{\"name\":\"Officer\",\"password\":\"" + TEMP_PW
                                        + "\",\"mustChange\":false,\"perms\":{\"view\":true,\"order\":true,\"ordersAll\":true,\"activity\":true,\"guildOrders\":true}}")
                        .status,
                "elevated custom grant must be refused");
        create(owner, "Reader", "{\"view\":true}", false);
        String member = login("Maker", TEMP_PW);
        String viewer = login("Reader", TEMP_PW);

        var profile = get(member, "/api/v1/guild").obj();
        assertEquals(profile, get(viewer, "/api/v1/guild").obj());
        assertEquals(
                get(member, "/api/v1/stock").obj().get("items"),
                get(viewer, "/api/v1/stock").obj().get("items"));
        var request = json("""
            {"stash":"a","lines":[{"type":"item","key":"minecraft:diamond","count":2}]}
            """);
        assertEquals(403, post(viewer, "/api/v1/orders", request.toString()).status);
        String memberBinding = "/api/v1/users/" + memberId + "/minecraft";
        assertEquals(403, post(member, memberBinding, "{\"account\":\"BuilderIGN\"}").status);
        assertEquals(400, post(owner, memberBinding, "{\"account\":\"Bad Name\"}").status);
        assertEquals(200, post(owner, memberBinding, "{\"account\":\"BuilderIGN\"}").status);
        assertEquals(409, post(owner, "/api/v1/users/owner/minecraft", "{\"account\":\"builderign\"}").status);
        var self = post(member, "/api/v1/orders", request.toString());
        assertEquals(200, self.status);
        var selfOrder = self.obj().getAsJsonObject("order");
        assertEquals("SELF", selfOrder.get("purpose").getAsString());
        assertEquals(memberId, selfOrder.get("requestedBy").getAsString());
        assertEquals(profile.get("id"), selfOrder.get("organizationId"));
        assertEquals("BuilderIGN", selfOrder.get("recipient").getAsString());
        assertEquals("PLAYER", selfOrder.get("deliveryMode").getAsString());

        request.addProperty("purpose", "GUILD");
        request.addProperty("recipient", "minecraftuser");
        request.addProperty("comment", "Projekt Å <test>");
        assertEquals(403, post(member, "/api/v1/orders", request.toString()).status);
        assertEquals(200, post(owner, "/api/v1/users/owner/minecraft", "{\"account\":\"MinecraftUser\"}").status);
        // GUILD orders are Owner-only under the strict model.
        var response = post(owner, "/api/v1/orders", request.toString());
        assertEquals(200, response.status);
        var guildOrder = response.obj().getAsJsonObject("order");
        assertEquals(Users.OWNER_ID, guildOrder.get("requestedBy").getAsString());
        assertEquals("MinecraftUser", guildOrder.get("recipient").getAsString());
        assertEquals("Projekt Å <test>", guildOrder.get("comment").getAsString());
        assertEquals(profile.get("id"), guildOrder.get("organizationId"));
        assertFalse(get(viewer, "/api/v1/orders?stash=a").obj().toString().contains("Projekt Å"));
        String own = selfOrder.get("id").getAsString();
        assertTrue(audit.newest(30).toString().contains(own + " SELF"));
        assertFalse(audit.newest(30).toString().contains("Projekt Å"));
        String other = guildOrder.get("id").getAsString();
        var memberHistory = get(member, "/api/v1/orders?stash=a").obj().getAsJsonArray("orders");
        assertEquals(1, memberHistory.size());
        assertEquals(own, memberHistory.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(get(viewer, "/api/v1/orders?stash=a")
                .obj()
                .getAsJsonArray("orders")
                .isEmpty());
        // A member cannot touch another requester's order; only the Owner can.
        assertEquals(403, post(member, "/api/v1/orders/" + other + "/cancel", "{}").status);
        assertEquals(200, post(owner, "/api/v1/orders/" + other + "/cancel", "{}").status);
        var balances = get(viewer, "/api/v1/stock").obj().get("items");
        assertEquals(200, post(owner, "/api/v1/users/" + memberId, "{\"disabled\":true}").status);
        assertEquals(401, get(member, "/api/v1/orders?stash=a").status);
        request.addProperty("recipient", "BuilderIGN");
        assertEquals(400, post(owner, "/api/v1/orders", request.toString()).status);
        assertEquals(balances, get(viewer, "/api/v1/stock").obj().get("items"));
        assertTrue(get(owner, "/api/v1/orders?stash=a").obj().toString().contains(own));
    }

    @Test
    void concurrentOrderReplayPersistsAcrossRestartAndArchive() throws Exception {
        String owner = owner();
        defineOrderStashes(owner);
        String body = """
            {"stash":"a","lines":[{"type":"item","key":"minecraft:diamond","count":2}]}
            """;
        var gate = new java.util.concurrent.CountDownLatch(1);
        String id;
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Res> send = () -> {
                gate.await();
                return post(owner, "/api/v1/orders", body, "retry-order-1");
            };
            var a = pool.submit(send);
            var b = pool.submit(send);
            gate.countDown();
            var first = a.get();
            var duplicate = b.get();
            assertEquals(200, first.status);
            assertEquals(200, duplicate.status);
            assertEquals(first.obj(), duplicate.obj());
            id = first.obj().getAsJsonObject("order").get("id").getAsString();
        }
        assertEquals(2, orders.reservedFor("s", "a").get("item:minecraft:diamond"));
        assertEquals(
                409, post(owner, "/api/v1/orders", body.replace("\"count\":2", "\"count\":3"), "retry-order-1").status);
        assertEquals(
                1,
                audit.newest(30).asList().stream()
                        .filter(row -> "order.place"
                                .equals(row.getAsJsonObject().get("action").getAsString()))
                        .count());
        assertEquals(200, post(owner, "/api/v1/orders/" + id + "/cancel", "{}").status);
        assertEquals(
                1,
                post(owner, "/api/v1/orders/clear", "{\"stash\":\"a\"}")
                        .obj()
                        .get("removed")
                        .getAsInt());
        stop();
        startServer(Config.load(data));
        var replay = post(owner(), "/api/v1/orders", body, "retry-order-1");
        assertEquals(200, replay.status);
        assertEquals(id, replay.obj().getAsJsonObject("order").get("id").getAsString());
        assertTrue(orders.reservedFor("s", "a").isEmpty());
        var trail = get(owner(), "/api/v1/audit/orders?order=" + id + "&item=diamond&stash=a");
        assertEquals(200, trail.status);
        var archived = trail.obj().getAsJsonArray("orders").get(0).getAsJsonObject();
        var events = archived.getAsJsonArray("events");
        assertEquals(
                List.of("order.reserve", "order.cancel"),
                events.asList().stream()
                        .map(event -> event.getAsJsonObject().get("action").getAsString())
                        .toList());
        assertEquals(id + ":1", events.get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(archived.has("allocations"));
        assertEquals(
                0,
                get(owner(), "/api/v1/audit/orders?order=" + id + "&since=" + Long.MAX_VALUE)
                        .obj()
                        .getAsJsonArray("orders")
                        .size());
    }

    @Test
    void guildApprovalIsOptionalScopedAndKeepsStockReservedBeforeClaim() throws Exception {
        // Strict Owner/Member (#26/#43): review of another requester's order is
        // Owner-only; elevated delegate grants are refused at creation.
        String owner = owner();
        defineOrderStashes(owner);
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/users",
                                "{\"name\":\"Officer\",\"password\":\"" + TEMP_PW
                                        + "\",\"mustChange\":false,\"perms\":{\"view\":true,\"order\":true,\"ordersAll\":true,\"activity\":true,\"guildOrders\":true}}")
                        .status,
                "elevated delegate grant must be refused");
        create(owner, "Builder", "member", false);
        String member = login("Builder", TEMP_PW);
        assertEquals(
                "DIRECT",
                get(member, "/api/v1/guild").obj().get("approvalPolicy").getAsString());
        var policy = """
            {"name":"Guild","description":"","expectedRevision":1,"approvalPolicy":"ALL"}
            """;
        assertEquals(403, post(member, "/api/v1/guild", policy).status);
        assertEquals(200, post(owner, "/api/v1/guild", policy).status);
        var draft = """
            {"stash":"a","lines":[{"type":"item","key":"minecraft:diamond","count":2}]}
            """;
        var placed = post(member, "/api/v1/orders", draft);
        assertEquals(200, placed.status);
        var order = placed.obj().getAsJsonObject("order");
        String id = order.get("id").getAsString();
        assertEquals("PENDING", order.get("approvalState").getAsString());
        assertEquals("RESERVED", order.get("state").getAsString());
        assertNull(orders.claim("s", "a"));
        assertEquals(2L, orders.reservedFor("s", "a").get("item:minecraft:diamond"));
        String review = "/api/v1/orders/" + id + "/approval";
        assertEquals(403, post(member, review, "{\"decision\":\"approve\"}").status);
        var approved = post(owner, review, "{\"decision\":\"approve\"}");
        assertEquals(200, approved.status);
        assertEquals(
                "APPROVED",
                approved.obj().getAsJsonObject("order").get("approvalState").getAsString());
        assertEquals(
                approved.obj(),
                post(owner, review, "{\"decision\":\"approve\"}").obj());
        assertEquals(409, post(owner, review, "{\"decision\":\"reject\"}").status);
        assertEquals(id, orders.claim("s", "a").id);
        var trail = "/api/v1/audit/orders?order=" + id + "&person=Builder";
        assertEquals(403, get(member, trail).status);
        var auditOrder = get(owner, trail).obj().getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals(3, auditOrder.getAsJsonArray("events").size());
        assertEquals(
                "order.approve",
                auditOrder
                        .getAsJsonArray("events")
                        .get(1)
                        .getAsJsonObject()
                        .get("action")
                        .getAsString());
        create(owner, "OtherVaultAuditor", "{\"view\":true,\"stashes\":[{\"stash\":\"b\"}]}", false);
        assertEquals(403, get(login("OtherVaultAuditor", TEMP_PW), trail).status);
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/users",
                                "{\"name\":\"LegacyFullAccess\",\"password\":\"" + TEMP_PW
                                        + "\",\"mustChange\":false,\"perms\":{\"view\":true,\"ordersAll\":true,\"positions\":true}}")
                        .status,
                "legacy full-access grant must be refused");
        var second = post(member, "/api/v1/orders", draft).obj().getAsJsonObject("order");
        String rejected = second.get("id").getAsString();
        assertEquals(200, post(owner, "/api/v1/orders/" + rejected + "/approval", "{\"decision\":\"reject\"}").status);
        assertEquals(2L, orders.reservedFor("s", "a").get("item:minecraft:diamond"));
    }

    @Test
    void everyLoggedInRouteDeclaresAPermission() {
        List<String> routes = router.describe();
        for (String r : routes) {
            if (r.contains(" SESSION") || r.contains(" BROWSER_WRITE")) {
                assertTrue(r.matches(".* (SESSION|BROWSER_WRITE) [A-Z_]+$"), r);
            }
        }
        // Every member can use account controls and read the common guild identity.
        for (String r : routes) {
            if (r.endsWith(" ANY"))
                assertTrue(r.contains("/api/v1/auth/") || r.equals("[GET, HEAD] /api/v1/guild SESSION ANY"), r);
        }
        assertThrows(
                IllegalArgumentException.class, () -> router.exact(Router.GET, "/x", Router.Guard.SESSION, ex -> {}));
        assertTrue(routes.stream().anyMatch(r -> r.contains("/api/v1/users") && r.endsWith(" OWNER")));
    }

    @Test
    void aNewUserMustChangeThePasswordFirst() throws Exception {
        String owner = owner();
        create(owner, "alice", "member", true);
        Res login = post(null, "/api/v1/auth/login", "{\"username\":\"alice\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(200, login.status);
        assertTrue(login.obj().get("mustChange").getAsBoolean());
        String alice = login("Alice", TEMP_PW); // names are case-insensitive
        Res blocked = get(alice, "/api/v1/stashes");
        assertEquals(403, blocked.status);
        assertTrue(blocked.obj().get("mustChange").getAsBoolean());
        assertTrue(get(alice, "/api/v1/auth/state")
                .obj()
                .getAsJsonObject("user")
                .get("mustChange")
                .getAsBoolean());

        assertEquals(
                400,
                post(alice, "/api/v1/auth/password", "{\"current\":\"" + TEMP_PW + "\",\"next\":\"" + TEMP_PW + "\"}")
                        .status,
                "the same password again is refused");
        assertEquals(
                401,
                post(alice, "/api/v1/auth/password", "{\"current\":\"wrong-wrong-wrong\",\"next\":\"" + NEW_PW + "\"}")
                        .status);
        Res changed =
                post(alice, "/api/v1/auth/password", "{\"current\":\"" + TEMP_PW + "\",\"next\":\"" + NEW_PW + "\"}");
        assertEquals(200, changed.status, changed.raw.body());
        assertEquals(1, changed.obj().get("ended").getAsInt(), "alice's other session ended");
        assertEquals(200, get(alice, "/api/v1/stashes").status);
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"alice\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertNotNull(login("alice", NEW_PW));
        // The owner's own session was untouched.
        assertEquals(200, get(owner, "/api/v1/users").status);
    }

    @Test
    void loginErrorsDontTellWhichNamesExist() throws Exception {
        String owner = owner();
        String id = create(owner, "bob", "member", false);
        Res unknown = post(null, "/api/v1/auth/login", "{\"username\":\"nobody\",\"password\":\"whatever-password\"}");
        Res wrong = post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"whatever-password\"}");
        assertEquals(401, unknown.status);
        assertEquals(401, wrong.status);
        assertEquals(unknown.error(), wrong.error());
        assertEquals(400, post(null, "/api/v1/auth/login", "{\"username\":5,\"password\":\"x\"}").status);

        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"disabled\":true}").status);
        Res disabled = post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(403, disabled.status);
        assertEquals("this account is disabled", disabled.error());
        // A wrong password for a disabled account looks like any wrong password.
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"bob\",\"password\":\"whatever-password\"}").status);
    }

    @Test
    void aViewerCanOnlyLook() throws Exception {
        String owner = owner();
        defineOrderStashes(owner);
        String ownerOrder = placeOrder(owner, "a", "minecraft:diamond", 10);
        create(owner, "viewer1", "{\"view\":true}", false);
        String v = login("viewer1", TEMP_PW);

        assertEquals(200, get(v, "/api/v1/stashes").status);
        assertEquals(200, get(v, "/api/v1/index?stash=a").status);
        assertEquals(200, get(v, "/api/v1/orders?stash=a").status);

        String order = "{\"stash\":\"a\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":1}]}";
        Res refused = post(v, "/api/v1/orders", order);
        assertEquals(403, refused.status);
        assertEquals("order", refused.obj().get("permission").getAsString());
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/cancel", null).status);
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/retry", null).status);
        assertEquals(403, post(v, "/api/v1/orders/" + ownerOrder + "/force-cancel", null).status);
        assertEquals(403, post(v, "/api/v1/orders/clear", "{\"stash\":\"a\"}").status);
        assertEquals(403, post(v, "/api/v1/rescan", "{\"stash\":\"a\"}").status);
        assertEquals(404, post(v, "/api/v1/stashes/release", "{\"stash\":\"a\"}").status, "release is removed");
        assertEquals(403, get(v, "/api/v1/link").status);
        assertEquals(403, post(v, "/api/v1/link/regenerate", null).status);
        assertEquals(403, get(v, "/api/v1/bots").status);
        assertEquals(403, get(v, "/api/v1/bots/bot1/activity").status);
        assertEquals(404, get(v, "/api/v1/bots/bot1/console").status);
        assertEquals(404, post(v, "/api/v1/bots/bot1/console", "{\"command\":\"help\"}").status);
        assertEquals(403, post(v, "/api/v1/bots/bot1/settings", "{\"scope\":\"plugin\",\"changes\":[]}").status);
        assertEquals(403, get(v, "/api/v1/users").status);
        assertEquals(403, post(v, "/api/v1/users", "{\"name\":\"x\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertEquals(403, post(v, "/api/v1/users/owner", "{\"name\":\"me\"}").status);
        assertEquals(403, get(v, "/api/v1/audit").status);
        // The order is untouched.
        assertTrue(orders.listJson("s", "a").toString().contains("\"pending\""));
    }

    @Test
    void aMemberChangesOnlyTheirOwnOrders() throws Exception {
        String owner = owner();
        defineOrderStashes(owner);
        String ownerOrder = placeOrder(owner, "a", "minecraft:diamond", 10);
        create(owner, "carol", "member", false);
        String c = login("carol", TEMP_PW);
        String mine = placeOrder(c, "a", "minecraft:diamond", 5);

        JsonArray list = get(c, "/api/v1/orders?stash=a").obj().getAsJsonArray("orders");
        boolean sawMine = false;
        for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(mine)) {
                sawMine = true;
                assertEquals("carol", o.getAsJsonObject("placedBy").get("name").getAsString());
            }
            if (o.get("id").getAsString().equals(ownerOrder))
                assertEquals("owner", o.getAsJsonObject("placedBy").get("id").getAsString());
        }
        assertTrue(sawMine);

        Res other = post(c, "/api/v1/orders/" + ownerOrder + "/cancel", null);
        assertEquals(403, other.status);
        assertEquals("ordersAll", other.obj().get("permission").getAsString());
        assertEquals(200, post(c, "/api/v1/orders/" + mine + "/cancel", null).status);
        Res retried = post(c, "/api/v1/orders/" + mine + "/retry", null);
        assertEquals(200, retried.status, retried.raw.body());
        assertEquals(
                "carol",
                retried.obj()
                        .getAsJsonObject("order")
                        .getAsJsonObject("placedBy")
                        .get("name")
                        .getAsString());
        assertEquals(403, post(c, "/api/v1/orders/clear", "{\"stash\":\"a\"}").status);
        assertEquals(403, get(c, "/api/v1/bots").status);
        // The owner may change anyone's.
        assertEquals(
                200,
                post(
                                owner,
                                "/api/v1/orders/"
                                        + retried.obj()
                                                .getAsJsonObject("order")
                                                .get("id")
                                                .getAsString() + "/cancel",
                                null)
                        .status);
    }

    @Test
    void elevatedDelegateGrantsAreRefused() throws Exception {
        // Strict Owner/Member (#26/#43): ordersAll, settings and rescan cannot be
        // granted to a sub-user at all — there is no delegate role. Each refused
        // grant must fail closed with 400 and create no account.
        String owner = owner();
        defineOrderStashes(owner);
        for (String perms : List.of(
                "{\"view\":true,\"order\":true,\"ordersAll\":true}",
                "{\"view\":true,\"order\":true,\"activity\":true,\"settings\":true,\"rescan\":true}",
                "{\"view\":true,\"guildOrders\":true}",
                "{\"view\":true,\"link\":true}",
                "{\"view\":true,\"positions\":true}")) {
            int before = users.list().size();
            assertEquals(
                    400,
                    post(
                                    owner,
                                    "/api/v1/users",
                                    "{\"name\":\"Delegate\",\"password\":\"" + TEMP_PW
                                            + "\",\"mustChange\":false,\"perms\":" + perms + "}")
                            .status,
                    "elevated grant must be refused: " + perms);
            assertEquals(before, users.list().size(), "refused grant must not create an account");
        }
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"Delegate\",\"password\":\"" + TEMP_PW + "\"}")
                        .status);
    }

    @Test
    void aScopedUserSeesOnlyTheirStash() throws Exception {
        // Strict Owner/Member (#26/#43): scope narrows a member, never widens one.
        // Rescan is Owner-only; a scoped member reads its own stash and gets 404
        // outside it, 403 for operations it may not perform.
        String owner = owner();
        defineOrderStashes(owner);
        String otherOrder = placeOrder(owner, "b", "minecraft:dirt", 10);
        create(
                owner,
                "erin",
                "{\"view\":true,\"order\":true,\"activity\":true,\"stashes\":[{\"stash\":\"a\"}]}",
                false);
        String e = login("erin", TEMP_PW);

        JsonArray stashes = get(e, "/api/v1/stashes").body.getAsJsonArray();
        assertEquals(1, stashes.size());
        assertEquals("a", stashes.get(0).getAsJsonObject().get("stash").getAsString());
        assertEquals(2, get(owner, "/api/v1/stashes").body.getAsJsonArray().size());

        assertEquals(200, get(e, "/api/v1/index?stash=a").status);
        assertEquals(404, get(e, "/api/v1/index?stash=b").status);
        assertEquals(404, get(e, "/api/v1/orders?stash=b").status);
        assertEquals(
                404,
                post(
                                e,
                                "/api/v1/orders",
                                "{\"stash\":\"b\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}")
                        .status);
        assertEquals(
                404,
                post(e, "/api/v1/orders/" + otherOrder + "/cancel", null).status,
                "out of scope even for a member's own cancel path");
        assertEquals(403, post(e, "/api/v1/orders/clear", "{\"stash\":\"b\"}").status);
        // No rescan grant: the permission gate (403) answers before stash scope.
        assertEquals(403, post(e, "/api/v1/rescan", "{\"stash\":\"b\"}").status);
        assertEquals(403, post(e, "/api/v1/rescan", "{\"stash\":\"a\"}").status, "rescan is Owner-only");
    }

    @Test
    void editingOneFlagKeepsStashAndBotScopes() throws Exception {
        // P0-01: a routine flag edit that omits the scope keys must not widen
        // the user to all stashes/bots. Two stashes (a/b) and two bots exist;
        // erin may use only the first of each.
        String owner = owner();
        defineOrderStashes(owner);
        String otherOrder = placeOrder(owner, "b", "minecraft:dirt", 10);
        hub.hello("bot-a", "BotA", new JsonObject(), new JsonObject(), List.of(), List.of());
        hub.hello("bot-b", "BotB", new JsonObject(), new JsonObject(), List.of(), List.of());
        String id = create(
                owner,
                "erin",
                "{\"view\":true,\"order\":true,\"activity\":true,\"stashes\":[{\"stash\":\"a\"}],\"bots\":[\"bot-a\"]}",
                false);
        String e = login("erin", TEMP_PW);
        assertEquals(200, get(e, "/api/v1/index?stash=a").status);
        assertEquals(404, get(e, "/api/v1/index?stash=b").status);
        assertEquals(200, get(e, "/api/v1/bots/bot-a/activity").status);
        assertEquals(404, get(e, "/api/v1/bots/bot-b/activity").status);

        // The old UI payload: only boolean flags, no stashes/bots keys.
        // (order:true kept: dropping it would 403 on permission before scope.)
        Res edited = post(owner, "/api/v1/users/" + id, "{\"perms\":{\"view\":true,\"order\":true,\"activity\":true}}");
        assertEquals(200, edited.status, edited.raw.body());
        JsonObject perms = edited.obj().getAsJsonObject("user").getAsJsonObject("perms");
        assertEquals(
                "a",
                perms.getAsJsonArray("stashes")
                        .get(0)
                        .getAsJsonObject()
                        .get("stash")
                        .getAsString());
        assertEquals("bot-a", perms.getAsJsonArray("bots").get(0).getAsString());

        // Scopes still enforced after the flag edit.
        assertEquals(200, get(e, "/api/v1/index?stash=a").status);
        assertEquals(404, get(e, "/api/v1/index?stash=b").status);
        assertEquals(
                404,
                post(
                                e,
                                "/api/v1/orders",
                                "{\"stash\":\"b\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":1}]}")
                        .status);
        assertEquals(404, post(e, "/api/v1/orders/" + otherOrder + "/cancel", null).status, "still out of scope");
        assertEquals(200, get(e, "/api/v1/bots/bot-a/activity").status);
        assertEquals(404, get(e, "/api/v1/bots/bot-b/activity").status);

        // Explicit null widens to all; explicit [] narrows to none.
        Res widened = post(
                owner,
                "/api/v1/users/" + id,
                "{\"perms\":{\"view\":true,\"order\":true,\"activity\":true,\"stashes\":null,\"bots\":null}}");
        assertEquals(200, widened.status, widened.raw.body());
        assertTrue(widened.obj()
                .getAsJsonObject("user")
                .getAsJsonObject("perms")
                .get("stashes")
                .isJsonNull());
        assertEquals(200, get(e, "/api/v1/index?stash=b").status);
        assertEquals(200, get(e, "/api/v1/bots/bot-b/activity").status);
        Res narrowed = post(
                owner,
                "/api/v1/users/" + id,
                "{\"perms\":{\"view\":true,\"order\":true,\"activity\":true,\"stashes\":[],\"bots\":[]}}");
        assertEquals(200, narrowed.status, narrowed.raw.body());
        assertEquals(0, get(e, "/api/v1/stashes").body.getAsJsonArray().size());
        assertEquals(0, get(e, "/api/v1/bots").body.getAsJsonArray().size());
        assertEquals(404, get(e, "/api/v1/index?stash=a").status);
        assertEquals(404, get(e, "/api/v1/bots/bot-a/activity").status);
    }

    @Test
    void disablingResettingAndDeletingEndSessionsAtOnce() throws Exception {
        String owner = owner();
        String id = create(owner, "hank", "member", false);
        String h = login("hank", TEMP_PW);
        String h2 = login("hank", TEMP_PW);
        assertEquals(200, get(h, "/api/v1/stashes").status);

        // Permissions change on the next request.
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"view\":false}}").status);
        assertEquals(403, get(h, "/api/v1/stashes").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"preset\":\"member\"}").status);
        assertEquals(200, get(h, "/api/v1/stashes").status);

        Res disabled = post(owner, "/api/v1/users/" + id, "{\"disabled\":true}");
        assertEquals(200, disabled.status);
        assertEquals(2, disabled.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/stashes").status);
        assertEquals(401, get(h2, "/api/v1/stashes").status);
        assertFalse(get(h, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"disabled\":false}").status);
        assertEquals(401, get(h, "/api/v1/stashes").status, "re-enabling doesn't bring old sessions back");

        h = login("hank", TEMP_PW);
        Res reset = post(
                owner, "/api/v1/users/" + id + "/password", "{\"password\":\"" + NEW_PW + "\",\"mustChange\":true}");
        assertEquals(200, reset.status);
        assertEquals(1, reset.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/stashes").status);
        h = login("hank", NEW_PW);
        assertEquals(403, get(h, "/api/v1/stashes").status, "must change the reset password first");

        Res logout = post(owner, "/api/v1/users/" + id + "/logout", null);
        assertEquals(1, logout.obj().get("ended").getAsInt());
        assertEquals(401, get(h, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean() ? 200 : 401);

        h = login("hank", NEW_PW);
        assertEquals(200, post(owner, "/api/v1/users/" + id + "/delete", null).status);
        assertEquals(401, post(h, "/api/v1/auth/logout", null).status);
        assertNull(users.byId(id));
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"hank\",\"password\":\"" + NEW_PW + "\"}").status);
        assertEquals(404, post(owner, "/api/v1/users/" + id + "/delete", null).status);
    }

    @Test
    void userManagementNeedsAdminUnlockedAndValidInput() throws Exception {
        String owner = owner();
        assertEquals(200, post(owner, "/api/v1/auth/lock", null).status);
        Res locked = post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"" + TEMP_PW + "\"}");
        assertEquals(403, locked.status);
        assertEquals("confirm password", locked.error());
        assertEquals(200, get(owner, "/api/v1/users").status, "reading the list needs no unlock");
        assertEquals(200, post(owner, "/api/v1/auth/confirm", "{\"password\":\"" + OWNER_PW + "\"}").status);

        assertEquals(
                400, post(owner, "/api/v1/users", "{\"name\":\"bad name\",\"password\":\"" + TEMP_PW + "\"}").status);
        assertEquals(400, post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"short\"}").status);
        assertEquals(
                400,
                post(owner, "/api/v1/users", "{\"name\":\"ivy\",\"password\":\"" + TEMP_PW + "\",\"preset\":\"god\"}")
                        .status);
        assertEquals(409, post(owner, "/api/v1/users", "{\"name\":\"OWNER\",\"password\":\"" + TEMP_PW + "\"}").status);
        create(owner, "ivy", "member", true);
        assertEquals(409, post(owner, "/api/v1/users", "{\"name\":\"IVY\",\"password\":\"" + TEMP_PW + "\"}").status);

        // Renaming the owner: the new name logs in, and no sub-user may take it.
        assertEquals(409, post(owner, "/api/v1/users/owner", "{\"name\":\"ivy\"}").status);
        assertEquals(200, post(owner, "/api/v1/users/owner", "{\"name\":\"boss\"}").status);
        assertNotNull(login("boss", OWNER_PW));
        assertNotNull(login(null, OWNER_PW));
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"owner\",\"password\":\"" + OWNER_PW + "\"}").status);

        JsonObject list = get(owner, "/api/v1/users").obj();
        assertEquals("boss", list.getAsJsonObject("owner").get("name").getAsString());
        assertEquals(1, list.getAsJsonArray("users").size());
        // Strict Owner/Member (#26/#43): member + orderer alias are the only presets.
        assertTrue(list.getAsJsonObject("presets").has("member"));
        assertTrue(list.getAsJsonObject("presets").has("orderer"));
        assertEquals(
                list.getAsJsonObject("presets").getAsJsonObject("member").toString(),
                list.getAsJsonObject("presets").getAsJsonObject("orderer").toString());
        assertFalse(list.getAsJsonObject("presets").has("viewer"));
        assertFalse(list.getAsJsonObject("presets").has("officer"));
        assertFalse(list.getAsJsonObject("presets").has("operator"));
        assertFalse(list.toString().contains("pbkdf2"), "hashes never leave the server");
    }

    @Test
    void usersSurviveARestartAndTheAuditLogRecordsChanges() throws Exception {
        String owner = owner();
        String id = create(owner, "jill", "{\"view\":true,\"console\":\"list\",\"consoleAllow\":[\"status\"]}", false);
        post(null, "/api/v1/auth/login", "{\"username\":\"nobody\",\"password\":\"whatever-password\"}");
        String text = Files.readString(data.resolve("users.json"));
        assertTrue(text.contains("jill") && text.contains("pbkdf2-sha256$"));
        assertFalse(text.contains(TEMP_PW));

        Users reloaded = new Users(data);
        reloaded.load();
        Users.Principal p = reloaded.principal(id);
        assertEquals("jill", p.name());
        assertFalse(p.perms().toJson().has("console"));

        JsonArray entries = get(owner, "/api/v1/audit?limit=50").obj().getAsJsonArray("entries");
        String all = entries.toString();
        assertTrue(all.contains("\"user.create\""), all);
        assertTrue(all.contains("\"login.failed\"") && all.contains("nobody"), all);
        assertTrue(all.contains("\"login\""), all);
        assertEquals(
                "user.create", entries.get(1).getAsJsonObject().get("action").getAsString(), "newest first");
        assertFalse(all.contains(TEMP_PW));
    }

    @Test
    void storedElevatedGrantsAreDowngradedToMemberOnLoad() throws Exception {
        // Registries written before strict Owner/Member (#26/#43) fail closed on
        // load: the elevated grant becomes member-level instead of minting a role.
        // The hash alphabet (pbkdf2-sha256$...) needs no JSON escaping.
        String registry = "{\"version\":1,\"ownerName\":\"owner\",\"users\":[{"
                + "\"id\":\"u-0123456789ab\",\"name\":\"relic\",\"hash\":\"" + Auth.hash(TEMP_PW) + "\","
                + "\"perms\":{\"view\":true,\"order\":true,\"ordersAll\":true,\"activity\":true,"
                + "\"settings\":true,\"rescan\":true,\"link\":true,\"guildOrders\":true,"
                + "\"positions\":true,\"stashes\":null,\"bots\":null},"
                + "\"disabled\":false,\"mustChange\":false,"
                + "\"createdAt\":1,\"lastLoginAt\":0,\"passwordChangedAt\":1}]}";
        Files.writeString(data.resolve("users.json"), registry);
        Users reloaded = new Users(data);
        reloaded.load();
        Users.Principal p = reloaded.principal("u-0123456789ab");
        assertNotNull(p);
        assertFalse(p.perms().elevated(), "stored elevated grant must not survive a load");
        assertTrue(p.perms().view() && p.perms().order());
        assertTrue(p.perms().activity(), "member-level activity is preserved");
    }

    @Test
    void aBrokenUsersFileStopsTheServerInsteadOfDroppingUsers() throws Exception {
        Files.writeString(data.resolve("users.json"), "{not json");
        assertThrows(IOException.class, () -> new Users(data).load());
    }

    @Test
    void badPresetsAndEmptyUpdatesAre400NotDroppedConnections() throws Exception {
        String owner = owner();
        String id = create(owner, "lou", "member", false);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":5}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"god\"}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"viewer\"}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"officer\"}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"operator\"}").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"preset\":\"orderer\"}").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"preset\":\"member\"}").status);
        assertEquals(
                400, post(owner, "/api/v1/users/" + id, "{\"preset\":\"member\",\"perms\":{\"view\":true}}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{}").status);
        assertEquals(
                400,
                post(owner, "/api/v1/users/" + id, "{\"preset\":null}").status,
                "null preset alone: nothing to change");
        assertEquals(
                400,
                post(owner, "/api/v1/users", "{\"name\":\"m\",\"password\":\"" + TEMP_PW + "\",\"preset\":7}").status);
        // perms replaces the whole object, and the implications are applied again.
        // Elevated flags can never be granted: member-level edits succeed.
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"order\":true,\"rescan\":true}}").status);
        assertEquals(400, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"ordersAll\":true}}").status);
        assertEquals(200, post(owner, "/api/v1/users/" + id, "{\"perms\":{\"order\":true,\"activity\":true}}").status);
        Users.Perms p = users.byId(id).perms();
        assertTrue(p.order() && p.activity());
        assertFalse(p.ordersAll() || p.rescan(), "not merged with the old permissions");
    }

    @Test
    void oneAccountCantPushTheOwnerOut() throws Exception {
        String owner = owner();
        String id = create(owner, "nia", "member", false);
        for (int i = 0; i < 230; i++) sessions.create(id);
        assertEquals(SessionStore.MAX_PER_USER, sessions.countByUser().get(id));
        assertEquals(200, get(owner, "/api/v1/users").status, "the owner's session survived");
        assertEquals(200, get(persistedOwnerSession, "/api/v1/stashes").status);
    }

    @Test
    void theLinkPanelIsOwnerOnly() throws Exception {
        // Strict Owner/Member (#26/#43): the link grant (agent key management)
        // cannot be given to a sub-user. The owner link panel never exposes the key.
        String owner = owner();
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/users",
                                "{\"name\":\"oz\",\"password\":\"" + TEMP_PW
                                        + "\",\"mustChange\":false,\"perms\":{\"link\":true,\"stashes\":[{\"stash\":\"a\"}]}}")
                        .status,
                "link grant must be refused");
        assertFalse(get(owner, "/api/v1/link").obj().has("apiKey"));
        assertFalse(post(owner, "/api/v1/link/regenerate", "{}").obj().has("apiKey"));
        assertEquals(
                404,
                post(owner, "/api/v1/stashes/release", "{\"stash\":\"a\"}").status,
                "release is removed: no bot owns a stash");
    }

    @Test
    void aSessionMadeWithAnOldPasswordEndsEvenIfItWasCreatedAfterTheChange() throws Exception {
        String owner = owner();
        String id = create(owner, "pat", "member", false);
        String oldHash = users.byId(id).hash();
        // A login that verified the old password, but only creates its session after the reset went through.
        assertEquals(
                200,
                post(
                                owner,
                                "/api/v1/users/" + id + "/password",
                                "{\"password\":\"" + NEW_PW + "\",\"mustChange\":false}")
                        .status);
        String late = sessions.create(id, Auth.tag(oldHash));
        assertEquals(401, get(late, "/api/v1/stashes").status);
        assertFalse(get(late, "/api/v1/auth/state").obj().get("loggedIn").getAsBoolean());
        // The same for the owner: changing the owner password ends a session made with the old one.
        String ownerLate =
                sessions.create(Users.OWNER_ID, Auth.tag(Config.load(data).dashboardPassword()));
        Res changed =
                post(owner, "/api/v1/auth/password", "{\"current\":\"" + OWNER_PW + "\",\"next\":\"" + NEW_PW + "\"}");
        assertEquals(200, changed.status, changed.raw.body());
        assertEquals(401, get(ownerLate, "/api/v1/users").status);
        assertEquals(200, get(owner, "/api/v1/users").status, "the session that changed it stays");
        // A session without an explicit current credential tag is never authorized.
        String untagged = sessions.create(id);
        assertEquals(401, get(untagged, "/api/v1/stashes").status);
        String untaggedOwner = sessions.create(Users.OWNER_ID);
        assertEquals(401, get(untaggedOwner, "/api/v1/users").status);
    }

    @Test
    void namesDontMatchThroughUnicodeCaseFolding() throws Exception {
        String owner = owner();
        create(owner, "kai", "member", false);
        // U+212A KELVIN SIGN folds to k with equalsIgnoreCase; it must not name "kai" or the owner.
        assertEquals(
                401,
                post(null, "/api/v1/auth/login", "{\"username\":\"\u212Aai\",\"password\":\"" + TEMP_PW + "\"}")
                        .status);
        assertFalse(users.isOwnerName("o\u0175ner"));
        assertNotNull(login("KAI", TEMP_PW));
    }

    @Test
    void addressFailuresAcrossAccountsLockTheAddressAndASuccessDoesntResetThem() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        LoginLimiter l = new LoginLimiter(now::get);
        for (int i = 0; i < LoginLimiter.ADDRESS_FAILURES - 1; i++) {
            l.addressFailure("10.0.0.9", false);
            l.success("10.0.0.9|u-000000000001"); // a good login with another account in between
        }
        assertEquals(0, l.begin("10.0.0.9|u-000000000001", "10.0.0.9", false));
        l.addressFailure("10.0.0.9", false);
        l.end("10.0.0.9", false);
        assertEquals(LoginLimiter.FIRST_LOCK_MS, l.begin("10.0.0.9|u-000000000002", "10.0.0.9", false));
        assertEquals(0, l.begin("10.0.0.10|u-000000000001", "10.0.0.10", false));
        l.end("10.0.0.10", false);
    }

    @Test
    void onlyRealIpLiteralsAreTrustedFromProxyHeaders() {
        assertTrue(AuthRoutes.isIpLiteral("203.0.113.7"));
        assertTrue(AuthRoutes.isIpLiteral("::1"));
        assertTrue(AuthRoutes.isIpLiteral("2001:db8::5"));
        assertFalse(AuthRoutes.isIpLiteral("999.1.1.1"));
        assertFalse(AuthRoutes.isIpLiteral("beef"));
        assertFalse(AuthRoutes.isIpLiteral("......."));
        assertFalse(AuthRoutes.isIpLiteral("example.com"));
        assertFalse(AuthRoutes.isIpLiteral("1.2.3.4.5"));
    }

    @Test
    void aSubUserLogsOutOnlyThemselvesEverywhere() throws Exception {
        String owner = owner();
        create(owner, "kim", "member", false);
        String k = login("kim", TEMP_PW);
        Res r = post(k, "/api/v1/auth/logout-all", null);
        assertEquals(200, r.status);
        assertEquals(1, r.obj().get("ended").getAsInt());
        assertEquals(200, get(owner, "/api/v1/users").status, "the owner stays logged in");
        assertEquals(401, get(k, "/api/v1/stashes").status);
    }

    @Test
    void stashDefsHideCoordinatesFromUnprivileged() throws Exception {
        String owner = owner();
        String defA = "{\"action\":\"create\",\"name\":\"a\",\"dimension\":\"minecraft:overworld\","
                + "\"pos1\":{\"x\":0,\"y\":-60,\"z\":0},\"pos2\":{\"x\":31,\"y\":20,\"z\":31},"
                + "\"dropoff\":{\"x\":4,\"y\":63,\"z\":4},"
                + "\"delivery\":{\"x\":5,\"y\":63,\"z\":5},\"deliveryDimension\":\"minecraft:overworld\","
                + "\"anvil\":{\"x\":6,\"y\":63,\"z\":6},\"table\":{\"x\":7,\"y\":63,\"z\":7}}";
        Res created = post(owner, "/api/v1/stash-defs", defA);
        assertEquals(200, created.status, created.raw.body());
        String defB = "{\"action\":\"create\",\"name\":\"b\",\"dimension\":\"minecraft:the_nether\","
                + "\"pos1\":{\"x\":0,\"y\":30,\"z\":0},\"pos2\":{\"x\":15,\"y\":50,\"z\":15}}";
        assertEquals(200, post(owner, "/api/v1/stash-defs", defB).status);

        // The owner sees full coordinates for every stash.
        Res full = get(owner, "/api/v1/stash-defs");
        assertEquals(200, full.status);
        JsonArray all = full.obj().getAsJsonArray("defs");
        assertEquals(2, all.size());
        for (JsonElement e : all) {
            JsonObject d = e.getAsJsonObject();
            assertTrue(
                    d.has("pos1") && d.getAsJsonObject("pos1").has("x") && d.has("pos2"),
                    "owner sees coordinates: " + d);
        }
        JsonObject a = all.get(0).getAsJsonObject().get("name").getAsString().equals("a")
                ? all.get(0).getAsJsonObject()
                : all.get(1).getAsJsonObject();
        assertEquals(4, a.getAsJsonObject("dropoff").get("x").getAsInt(), "owner sees the drop-off chest");

        // A member scoped to "a" without positions sees the name only, and only their stash.
        create(owner, "erin", "{\"view\":true,\"stashes\":[{\"stash\":\"a\"}]}", false);
        String e = login("erin", TEMP_PW);
        Res redacted = get(e, "/api/v1/stash-defs");
        assertEquals(200, redacted.status);
        JsonArray mine = redacted.obj().getAsJsonArray("defs");
        assertEquals(1, mine.size());
        JsonObject ea = mine.get(0).getAsJsonObject();
        assertEquals("a", ea.get("name").getAsString());
        assertEquals("minecraft:overworld", ea.get("dimension").getAsString());
        assertTrue(ea.has("revision"), "revision stays visible for conflict reloads");
        for (String coord : List.of(
                "pos1", "pos2", "dropoff", "dropoffDimension", "delivery", "deliveryDimension", "anvil", "table"))
            assertFalse(ea.has(coord), "member without positions must not see " + coord);

        // Coordinates are Owner-only (#26/#43): the positions grant is refused.
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/users",
                                "{\"name\":\"ops\",\"password\":\"" + TEMP_PW
                                        + "\",\"mustChange\":false,\"perms\":{\"view\":true,\"positions\":true,\"stashes\":[{\"stash\":\"a\"}]}}")
                        .status,
                "positions grant must be refused");

        // An empty stash scope sees no definitions at all.
        create(owner, "vic", "{\"view\":true,\"stashes\":[]}", false);
        String v = login("vic", TEMP_PW);
        Res none = get(v, "/api/v1/stash-defs");
        assertEquals(200, none.status);
        assertEquals(0, none.obj().getAsJsonArray("defs").size());

        // Non-owners cannot create, update or delete definitions, even in scope.
        assertEquals(403, post(e, "/api/v1/stash-defs", defA).status, "member cannot write stash defs");
        assertEquals(
                403,
                post(e, "/api/v1/stash-defs", "{\"action\":\"delete\",\"name\":\"a\",\"expectedRevision\":1}").status,
                "member cannot delete stash defs");
    }
}
