package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Delivery storage contents (PROTOCOL.md, "Additions: delivery storage contents") through the real routes: the
 * report's validation, the owner check, the heartbeat's needDelivery, the size limit, stash scope, and that the
 * contents never reach the index or the stock counts.
 */
class DeliveryTest {
    static final String OWNER_PW = "owner-password-123";
    static final String TEMP_PW = "temporary-pass-1";
    static final String HASH_A = "a".repeat(40);
    static final String HASH_B = "b".repeat(40);

    @TempDir
    Path data;

    HttpServer server;
    String base;
    byte[] k;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void fastHashes() {
        Auth.iterations = 1000;
    }

    @AfterAll
    static void realHashes() {
        Auth.iterations = Auth.ITERATIONS;
    }

    @BeforeEach
    void start() throws Exception {
        Config config = Config.load(data);
        config.setDashboardPassword(Auth.hash(OWNER_PW));
        config.setMinecraftServer("s");
        k = Signer.deriveKey(config.apiKey());
        IndexStore store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        OrderStore orders = new OrderStore(data, store);
        orders.load();
        IconService icons = new IconService(
                data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        SessionStore sessions = new SessionStore(data);
        sessions.load();
        Users users = new Users(data);
        users.load();
        WeSortManager.Routes r = WeSortManager.routes(
                config,
                store,
                orders,
                icons,
                new LinkState(config.keyState().generation()),
                List.of(),
                sessions,
                users,
                new AuditLog(data),
                new BotHub(),
                new LoginLimiter());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", r.router());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    record Res(int status, String body) {
        JsonObject obj() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    Res botPost(String path, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        byte[] n = new byte[16];
        new java.security.SecureRandom().nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header(
                        Signer.REQUEST_HEADER,
                        "v1 - " + ts + " " + nonce + " " + Signer.requestSig(k, "POST", path, ts, nonce, b))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(b))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res get(String token, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    Res post(String token, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Stash-Manager", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Cookie", SessionStore.COOKIE + "=" + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    String login(String username, String password) throws Exception {
        String body = username == null
                ? "{\"password\":\"" + password + "\"}"
                : "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("X-Stash-Manager", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring(SessionStore.COOKIE.length() + 1, c.indexOf(';'));
    }

    static String report(String stash, String player, String hash) {
        return "{\"version\":1,\"server\":\"s\",\"stash\":\"" + stash + "\",\"player\":\"" + player + "\",\"hash\":\""
                + hash + "\","
                + "\"readAt\":1767225600000,\"chests\":["
                + "{\"n\":0,\"size\":54,\"full\":false,\"readAt\":1767225500000,\"items\":["
                + "{\"slot\":0,\"id\":\"minecraft:red_shulker_box\",\"name\":\"Gems\",\"count\":1,\"maxStack\":1,\"shulker\":true,"
                + "\"contents\":[{\"id\":\"minecraft:diamond\",\"count\":1728}]},"
                + "{\"slot\":5,\"id\":\"minecraft:diamond\",\"name\":\"Diamond\",\"count\":64,\"maxStack\":64}]},"
                + "{\"n\":1,\"size\":54,\"full\":true,\"readAt\":0,\"items\":null}]}";
    }

    String heartbeat(String stash, String player, String deliveryHashJson) throws Exception {
        Res r = botPost(
                "/api/v1/heartbeat",
                "{\"server\":\"s\",\"stash\":\"" + stash + "\",\"player\":\"" + player + "\""
                        + (deliveryHashJson == null ? "" : ",\"deliveryHash\":" + deliveryHashJson) + "}");
        assertEquals(200, r.status, r.body);
        assertTrue(r.obj().has("needDelivery"), "the key is always there");
        return r.obj().get("needDelivery").getAsString();
    }

    // ---------- validation ----------

    static DeliveryContents.Snapshot parse(String json) {
        return new DeliveryContents().parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void validationCleansAndRefuses() {
        DeliveryContents.Snapshot s = parse(report("a", "Bob", HASH_A)
                .replace("\"Gems\"", "\"G\\u0007ems" + "x".repeat(80) + "\"")
                .replace("minecraft:diamond\",\"name\"", "Minecraft:Diamond\",\"name\""));
        JsonObject c0 = s.json().getAsJsonArray("chests").get(0).getAsJsonObject();
        JsonObject gems = c0.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(64, gems.get("name").getAsString().length(), "cut to 64");
        assertTrue(gems.get("name").getAsString().startsWith("Gems"), "control characters dropped");
        assertEquals(
                "minecraft:diamond",
                c0.getAsJsonArray("items").get(1).getAsJsonObject().get("id").getAsString());
        assertEquals(2, s.chests());
        assertEquals(2, s.usedSlots());
        assertEquals(52, s.freeSlots());
        assertEquals(1, s.unknownChests());
        // A chest never read has items null, whatever was sent.
        DeliveryContents.Snapshot unread =
                parse(report("a", "Bob", HASH_A).replace("\"readAt\":0,\"items\":null", "\"readAt\":0,\"items\":[]"));
        assertTrue(unread.json()
                .getAsJsonArray("chests")
                .get(1)
                .getAsJsonObject()
                .get("items")
                .isJsonNull());
        // Contents left out to fit: fine. An empty shulker: contents [].
        parse(report("a", "Bob", HASH_A).replace(",\"contents\":[{\"id\":\"minecraft:diamond\",\"count\":1728}]", ""));
        parse(report("a", "Bob", HASH_A).replace("[{\"id\":\"minecraft:diamond\",\"count\":1728}]", "[]"));

        String ok = report("a", "Bob", HASH_A);
        String[] bad = {
            ok.replace("\"version\":1", "\"version\":2"),
            ok.replace(HASH_A, "xyz"),
            ok.replace("\"n\":1,\"size\":54", "\"n\":0,\"size\":54"), // duplicate n
            ok.replace("\"n\":1,", "\"n\":16,"), // n out of range
            ok.replace("\"size\":54,\"full\":false", "\"size\":30,\"full\":false"),
            ok.replace("\"slot\":5", "\"slot\":54"), // slot >= size
            ok.replace("\"slot\":5", "\"slot\":0"), // duplicate slot
            ok.replace("\"count\":64,", "\"count\":0,"),
            ok.replace("\"count\":64,", "\"count\":10001,"),
            ok.replace("\"id\":\"minecraft:red_shulker_box\"", "\"id\":\"red shulker\""),
            ok.replace("\"full\":true", "\"full\":\"yes\""),
            ok.replace(
                    "[{\"id\":\"minecraft:diamond\",\"count\":1728}]",
                    "[" + "{\"id\":\"minecraft:dirt\",\"count\":1},".repeat(27)
                            + "{\"id\":\"minecraft:dirt\",\"count\":1}]"),
        };
        for (String b : bad) assertThrows(IllegalArgumentException.class, () -> parse(b), b);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 17; i++)
            many.append(i == 0 ? "" : ",")
                    .append("{\"n\":")
                    .append(i % 16)
                    .append(",\"size\":27,\"full\":false,\"readAt\":0,\"items\":null}");
        assertThrows(
                IllegalArgumentException.class,
                () -> parse("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"hash\":\"" + HASH_A
                        + "\",\"readAt\":0,\"chests\":[" + many + "]}"),
                "17 chests");
    }

    // ---------- routes ----------

    @Test
    void reportIsStoredSummarizedAndServed() throws Exception {
        assertEquals("false", heartbeat("a", "Bob", null), "an older bot (no deliveryHash): nothing to send");
        assertEquals("true", heartbeat("a", "Bob", "\"" + HASH_A + "\""), "no report yet");
        Res r = botPost("/api/v1/delivery", report("a", "Bob", HASH_A));
        assertEquals(200, r.status, r.body);
        assertEquals("false", heartbeat("a", "Bob", "\"" + HASH_A + "\""), "the same hash");
        assertEquals("true", heartbeat("a", "Bob", "\"" + HASH_B + "\""), "a different hash");

        String owner = login(null, OWNER_PW);
        JsonObject d = get(owner, "/api/v1/delivery?stash=a").obj();
        assertEquals(HASH_A, d.get("hash").getAsString());
        assertEquals(
                "Gems",
                d.getAsJsonArray("chests")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
        JsonObject sum = stashEntry(owner, "a").getAsJsonObject("deliveryContents");
        assertEquals(2, sum.get("chests").getAsInt());
        assertEquals(2, sum.get("usedSlots").getAsInt());
        assertEquals(52, sum.get("freeSlots").getAsInt());
        assertEquals(1, sum.get("unknownChests").getAsInt());
        assertEquals(1767225600000L, sum.get("readAt").getAsLong());
        assertEquals("null", get(owner, "/api/v1/delivery?stash=nothing").body.trim(), "no report");
        assertEquals(400, get(owner, "/api/v1/delivery").status, "stash is required");

        // An explicit null (the delivery container was unset) drops the report.
        assertEquals("false", heartbeat("a", "Bob", "null"));
        assertTrue(stashEntry(owner, "a").get("deliveryContents").isJsonNull());
        assertEquals(
                400,
                botPost("/api/v1/heartbeat", "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"deliveryHash\":5}")
                        .status);
        assertEquals(
                400,
                botPost(
                                "/api/v1/heartbeat",
                                "{\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"deliveryHash\":\"short\"}")
                        .status);
    }

    JsonObject stashEntry(String token, String stash) throws Exception {
        for (JsonElement e :
                JsonParser.parseString(get(token, "/api/v1/stashes").body).getAsJsonArray()) {
            if (e.getAsJsonObject().get("stash").getAsString().equals(stash)) return e.getAsJsonObject();
        }
        throw new AssertionError("no stash " + stash);
    }

    @Test
    void ownerCheckSizeLimitAndBadBodies() throws Exception {
        heartbeat("a", "Bob", null);
        // No bot owns a stash: Eve's report for the same stash is stored too.
        Res stored = botPost("/api/v1/delivery", report("a", "Eve", HASH_A));
        assertEquals(200, stored.status, stored.body);
        StringBuilder big = new StringBuilder(report("a", "Bob", HASH_A));
        big.insert(big.length() - 1, ",\"pad\":\"" + "x".repeat(257 * 1024) + "\"");
        assertEquals(413, botPost("/api/v1/delivery", big.toString()).status);
        assertEquals(400, botPost("/api/v1/delivery", "[1]").status);
        assertEquals(400, botPost("/api/v1/delivery", report("a", "Bob", "nothex")).status);
        assertEquals(200, botPost("/api/v1/delivery", report("a", "Bob", HASH_A)).status);
    }

    @Test
    void stashScopeAnswers404() throws Exception {
        heartbeat("a", "Bob", null);
        heartbeat("b", "Al", null);
        botPost("/api/v1/delivery", report("a", "Bob", HASH_A));
        String owner = login(null, OWNER_PW);
        Res made = post(
                owner,
                "/api/v1/users",
                "{\"name\":\"sam\",\"password\":\"" + TEMP_PW + "\",\"mustChange\":false,"
                        + "\"perms\":{\"view\":true,\"stashes\":[{\"stash\":\"b\"}]}}");
        assertEquals(200, made.status, made.body);
        String sam = login("sam", TEMP_PW);
        assertEquals(404, get(sam, "/api/v1/delivery?stash=a").status);
        assertEquals(200, get(sam, "/api/v1/delivery?stash=b").status);
        assertEquals(401, get(null, "/api/v1/delivery?stash=a").status);
        Res viewerOnly = post(
                owner,
                "/api/v1/users",
                "{\"name\":\"vic\",\"password\":\"" + TEMP_PW
                        + "\",\"mustChange\":false,\"perms\":{\"activity\":true}}");
        assertEquals(200, viewerOnly.status);
        assertEquals(403, get(login("vic", TEMP_PW), "/api/v1/delivery?stash=a").status, "needs view");
    }

    @Test
    void neverInTheIndexOrTheStock() throws Exception {
        String index = "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bob\",\"items\":["
                + "{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":100,\"maxStack\":64}]}";
        assertEquals(200, botPost("/api/v1/index", index).status);
        assertEquals(200, botPost("/api/v1/delivery", report("a", "Bob", HASH_A)).status);
        String owner = login(null, OWNER_PW);
        JsonObject s = stashEntry(owner, "a");
        assertEquals(100, s.get("itemTotal").getAsLong());
        assertEquals(1, s.get("itemTypes").getAsInt());
        String idx = get(owner, "/api/v1/index?stash=a").body;
        assertFalse(idx.contains("minecraft:diamond") || idx.contains("Gems") || idx.contains("red_shulker_box"), idx);
        JsonArray items = JsonParser.parseString(idx).getAsJsonObject().getAsJsonArray("items");
        assertEquals(1, items.size());
        assertNotNull(get(owner, "/api/v1/delivery?stash=a").obj().get("chests"));
    }

    @Test
    void retiredPlannerRoutesAndPermissionsAreUnavailable() throws Exception {
        String owner = login(null, OWNER_PW);
        for (String path : List.of(
                "/api/v1/planner",
                "/api/v1/planner/upload",
                "/api/v1/planner/p-aaaaaaaaaaaa",
                "/api/v1/planner/p-aaaaaaaaaaaa/order")) {
            assertEquals(404, get(owner, path).status(), path);
            assertEquals(404, post(owner, path, "{}").status(), path);
        }
        JsonObject perms = Users.Perms.ALL.toJson();
        assertFalse(perms.has("planner"));
        assertFalse(perms.has("plannerOrder"));
        assertFalse(Users.Perms.parse(JsonParser.parseString("{\"plannerOrder\":true}"))
                .order());
    }

    @Test
    void signedControlReceiptsAreScopedAndAuditedOnce() throws Exception {
        String session = "1".repeat(64);
        var identity = new JsonObject();
        identity.addProperty("botId", "control-fixture");
        identity.addProperty("controlSession", session);
        var hello = identity.deepCopy();
        hello.add("caps", JsonParser.parseString("[\"settings\"]"));
        var established = botPost("/api/v1/bot/hello", hello.toString()).obj();
        assertEquals(2, established.get("protocol").getAsInt());
        var manifest = identity.deepCopy();
        manifest.add("controlEpoch", established.get("controlEpoch"));
        manifest.addProperty("orderId", "o-0123456789ab");
        assertEquals(404, botPost("/api/v1/bot/manifest", manifest.toString()).status());
        assertEquals(
                401,
                post(login(null, OWNER_PW), "/api/v1/bot/manifest", manifest.toString())
                        .status());

        var settings = identity.deepCopy();
        settings.addProperty("scope", "plugin");
        settings.addProperty("schemaHash", HASH_A);
        settings.addProperty("valuesHash", HASH_B);
        settings.add(
                "schema",
                JsonParser.parseString("[{\"path\":\"scan-interval\",\"type\":\"int\",\"min\":2,\"max\":60}]"));
        settings.add("values", JsonParser.parseString("{\"scan-interval\":10}"));
        assertEquals(200, botPost("/api/v1/bot/state", settings.toString()).status());
        String owner = login(null, OWNER_PW);
        String browserRoute = "/api/v1/bots/control-fixture/settings";
        String change = "{\"scope\":\"plugin\",\"changes\":[{\"path\":\"scan-interval\",\"value\":12,\"expect\":10}]}";
        assertEquals(401, post(null, browserRoute, change).status());
        assertEquals(200, post(owner, browserRoute, change).status());
        assertEquals(401, post(owner, "/api/v1/bot/next", identity.toString()).status());
        var foreign = identity.deepCopy();
        foreign.addProperty("controlSession", "2".repeat(64));
        assertEquals(409, botPost("/api/v1/bot/next", foreign.toString()).status());
        var job = botPost("/api/v1/bot/next", identity.toString())
                .obj()
                .getAsJsonArray("jobs")
                .get(0)
                .getAsJsonObject();
        var receipt = new JsonObject();
        for (String key : List.of("id", "lease", "sequence", "value")) receipt.add(key, job.get(key));
        receipt.addProperty("ok", true);
        var results = new JsonArray();
        results.add(receipt);
        var report = identity.deepCopy();
        report.add("results", results);
        assertEquals(200, botPost("/api/v1/bot/results", report.toString()).status());
        assertEquals(200, botPost("/api/v1/bot/results", report.toString()).status());
        receipt.addProperty("ok", false);
        assertEquals(409, botPost("/api/v1/bot/results", report.toString()).status());
        var entries = get(owner, "/api/v1/audit").obj().getAsJsonArray("entries");
        assertEquals(
                1,
                entries.asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(entry ->
                                "agent.job.applied".equals(entry.get("action").getAsString()))
                        .count());
        assertFalse(entries.toString().contains(session));
    }

    @Test
    void retiredAdminEndpointsCannotQueueCommandsOrProxyChanges() throws Exception {
        String owner = login(null, OWNER_PW);
        assertEquals(
                200,
                botPost(
                                "/api/v1/bot/hello",
                                "{\"botId\":\"b1\",\"caps\":[\"settings\",\"activity\",\"console\",\"viewer\",\"proxy\"]}")
                        .status());
        for (String path : List.of(
                "/api/v1/bots/b1/console",
                "/api/v1/bots/b1/viewer",
                "/api/v1/bots/b1/viewer/stream",
                "/api/v1/bot/viewer")) {
            assertEquals(404, get(owner, path).status(), path);
            assertEquals(404, post(owner, path, "{\"command\":\"stop\"}").status(), path);
        }
        assertEquals(404, get(owner, "/api/v1/bots/b1/settings?scope=proxy").status());
        assertEquals(
                400,
                post(
                                owner,
                                "/api/v1/bots/b1/settings",
                                "{\"scope\":\"plugin:other\",\"changes\":[{\"path\":\"x\",\"value\":true}]}")
                        .status());
        JsonObject perms = Users.Perms.ALL.toJson();
        for (String key : List.of("proxy", "console", "consoleAllow", "viewer")) assertFalse(perms.has(key));
        BotHub hub = new BotHub();
        hub.hello(
                "b",
                "P",
                new JsonObject(),
                new JsonObject(),
                List.of("settings", "console", "viewer", "proxy"),
                List.of("plugin", "proxy"));
        BotHub.Bot bot = hub.bot("b");
        assertEquals(java.util.Set.of("settings"), bot.caps);
        assertEquals(java.util.Set.of("plugin"), bot.declaredScopes);
        assertThrows(IllegalArgumentException.class, () -> hub.queueSets(bot, "proxy", List.of()));
        assertTrue(hub.next(bot).body().getAsJsonArray("jobs").isEmpty());
    }
}
