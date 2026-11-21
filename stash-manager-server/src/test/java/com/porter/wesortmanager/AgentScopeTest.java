package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The named API key's signature alone grants agent access. Minecraft name/UUID
 * are optional presence information. Server, stash, bot session, key revocation
 * and physical safety policies remain separately enforced.
 */
class AgentScopeTest {
    static final String OWNER_PW = "owner-password-123";

    @TempDir
    Path data;

    HttpServer server;
    String base;
    byte[] k;
    final HttpClient http = HttpClient.newHttpClient();
    String agentA;
    byte[] agentKeyA;
    AgentCredentials credentials;
    /** Example Minecraft UUID, never an authorization identity. */
    static final String PLAYER_A = "11111111-2222-4333-8444-555555555555";

    static final String PLAYER_B = "66666666-7777-4888-8999-000000000000";

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
        // Deliberately no Minecraft server yet: physical work must fail closed before first setup.
        k = Signer.deriveKey(config.apiKey());
        // A named key slot, issued without any binding, before the routes load so the
        // Router's own AgentCredentials sees it.
        var issued = new AgentCredentials(data);
        String token = issued.issue("Bot");
        agentA = token.substring(0, token.indexOf('.'));
        agentKeyA = Signer.deriveKey(token.substring(token.indexOf('.') + 1));
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
        credentials = r.agentCredentials();
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

    /** Manager-key-signed bot call (the unnamed path). */
    Res botPost(String path, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
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

    /** Named-agent-signed bot call: the X-SM-Auth botId is the credential's agent UUID. */
    Res agentPost(String agent, byte[] key, String path, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header(
                        Signer.REQUEST_HEADER,
                        "v1 " + agent + " " + ts + " " + nonce + " "
                                + Signer.requestSig(key, "POST", path, ts, nonce, b))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(b))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body());
    }

    /** Named-agent-signed bot GET: the X-SM-Auth botId is the credential's key id. */
    Res agentGet(String path) throws Exception {
        byte[] b = new byte[0];
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header(
                        Signer.REQUEST_HEADER,
                        "v1 " + agentA + " " + ts + " " + nonce + " "
                                + Signer.requestSig(agentKeyA, "GET", path, ts, nonce, b))
                .GET()
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
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
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(SessionStore.COOKIE.length() + 1, setCookie.indexOf(';'));
    }

    void setServer(String owner, String s) throws Exception {
        Res r = post(owner, "/api/v1/server", "{\"server\":\"" + s + "\"}");
        assertEquals(200, r.status, r.body);
    }

    void defineStash(String owner, String name) throws Exception {
        Res r = post(
                owner,
                "/api/v1/stash-defs",
                "{\"action\":\"create\",\"name\":\"" + name + "\",\"dimension\":\"minecraft:overworld\","
                        + "\"pos1\":{\"x\":0,\"y\":-60,\"z\":0},\"pos2\":{\"x\":31,\"y\":20,\"z\":31}}");
        assertEquals(200, r.status, r.body);
    }

    static String heartbeat(String server, String stash) {
        return heartbeat(server, stash, PLAYER_A);
    }

    static String heartbeat(String server, String stash, String agent) {
        return "{\"server\":\"" + server + "\",\"stash\":\"" + stash + "\",\"player\":\"Bot\",\"agent\":\"" + agent
                + "\"}";
    }

    static String index(String server, String stash) {
        return "{\"version\":1,\"server\":\"" + server + "\",\"stash\":\"" + stash
                + "\",\"player\":\"Bot\",\"agent\":\"" + PLAYER_A + "\",\"items\":[]}";
    }

    static String claim(String server, String stash) {
        return "{\"server\":\"" + server + "\",\"stash\":\"" + stash + "\",\"agent\":\"" + PLAYER_A + "\"}";
    }

    static String helloFor(String session, String srv, String stash, String botId) {
        String binding = "{\"server\":\"" + srv + "\",\"stash\":\"" + stash
                + "\",\"dimension\":\"minecraft:overworld\"," + "\"agent\":\"" + PLAYER_A + "\",\"player\":\"Bot\"}";
        return "{\"botId\":\"" + botId + "\",\"agent\":\"" + PLAYER_A
                + "\",\"player\":\"Bot\",\"controlSession\":\"" + session
                + "\",\"manifestScope\":" + binding + ",\"caps\":[]}";
    }

    static String helloBootstrap(String botId) {
        return "{\"botId\":\"" + botId + "\",\"agent\":\"" + PLAYER_A + "\",\"player\":\"Bot\",\"controlSession\":\""
                + "1".repeat(64) + "\",\"caps\":[]}";
    }

    @Test
    void physicalWorkFailsClosedBeforeFirstSetup() throws Exception {
        // No server configured: every server-carrying agent call is refused with 400, not 409.
        assertEquals(400, botPost("/api/v1/heartbeat", heartbeat("s", "a")).status);
        assertEquals(400, botPost("/api/v1/index", index("s", "a")).status);
        assertEquals(
                400,
                botPost(
                                "/api/v1/delivery",
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"player\":\"Bot\"," + "\"hash\":\""
                                        + "a".repeat(40) + "\",\"readAt\":0,\"chests\":[]}")
                        .status);
        assertEquals(
                400,
                botPost(
                                "/api/v1/automation",
                                "{\"server\":\"s\",\"agent\":\"" + UUID.randomUUID() + "\",\"action\":\"acquire\"}")
                        .status);
        assertEquals(400, botPost("/api/v1/orders/claim", claim("s", "a")).status);
        assertEquals(
                400,
                botPost(
                                "/api/v1/boxes/agent",
                                "{\"action\":\"claim\",\"server\":\"s\",\"stash\":\"a\",\"agent\":\""
                                        + UUID.randomUUID() + "\"}")
                        .status);
        assertEquals(
                400,
                botPost("/api/v1/jobs/agent", "{\"action\":\"claim\",\"agent\":\"" + UUID.randomUUID() + "\"}").status);
        // Browser order placement is refused the same way.
        String owner = login(null, OWNER_PW);
        assertEquals(400, post(owner, "/api/v1/orders", "{\"stash\":\"a\",\"lines\":[]}").status);
        // After setup the gate opens (the stash is still undefined: 404, not 400).
        setServer(owner, "s");
        assertEquals(404, botPost("/api/v1/heartbeat", heartbeat("s", "a")).status);
    }

    @Test
    void forgedServerIsRefusedWith409() throws Exception {
        String owner = login(null, OWNER_PW);
        setServer(owner, "s");
        defineStash(owner, "a");
        // A bot connecting from any other Minecraft server is refused with 409.
        assertEquals(409, botPost("/api/v1/heartbeat", heartbeat("evil", "a")).status);
        assertEquals(409, botPost("/api/v1/index", index("evil", "a")).status);
        assertEquals(
                409,
                botPost(
                                "/api/v1/delivery",
                                "{\"version\":1,\"server\":\"evil\",\"stash\":\"a\",\"player\":\"Bot\"," + "\"hash\":\""
                                        + "a".repeat(40) + "\",\"readAt\":0,\"chests\":[]}")
                        .status);
        assertEquals(
                409,
                botPost(
                                "/api/v1/automation",
                                "{\"server\":\"evil\",\"agent\":\"" + UUID.randomUUID() + "\",\"action\":\"acquire\"}")
                        .status);
        assertEquals(409, botPost("/api/v1/orders/claim", claim("evil", "a")).status);
        assertEquals(
                409,
                botPost(
                                "/api/v1/boxes/agent",
                                "{\"action\":\"claim\",\"server\":\"evil\",\"stash\":\"a\",\"agent\":\""
                                        + UUID.randomUUID() + "\"}")
                        .status);
        // The right server but an undefined stash is 404, never silently accepted.
        assertEquals(404, botPost("/api/v1/heartbeat", heartbeat("s", "nope")).status);
        assertEquals(404, botPost("/api/v1/index", index("s", "nope")).status);
        assertEquals(404, botPost("/api/v1/orders/claim", claim("s", "nope")).status);
        // The right server and a defined stash proceed.
        assertEquals(200, botPost("/api/v1/heartbeat", heartbeat("s", "a")).status);
        assertEquals(200, botPost("/api/v1/index", index("s", "a")).status);
        assertEquals(204, botPost("/api/v1/orders/claim", claim("s", "a")).status);
    }

    @Test
    void signedKeyWorksForDifferentPlayersAndReportsTheirNames() throws Exception {
        String owner = login(null, OWNER_PW);
        setServer(owner, "s");
        defineStash(owner, "a");
        defineStash(owner, "b");
        assertEquals(200, agentPost(agentA, agentKeyA, "/api/v1/heartbeat",
                heartbeat("s", "a", PLAYER_A).replace("Bot", "Eve")).status);
        assertEquals("Eve", credentials.find(agentA).binding().playerName());
        assertEquals(200, agentPost(agentA, agentKeyA, "/api/v1/heartbeat",
                heartbeat("s", "a", PLAYER_B).replace("Bot", "OtherBot")).status);
        assertEquals("OtherBot", credentials.find(agentA).binding().playerName());
        JsonObject claim = new JsonObject();
        claim.addProperty("server", "s");
        claim.addProperty("stash", "a");
        claim.addProperty("player", "OtherBot");
        claim.addProperty("agent", PLAYER_B);
        assertEquals(204, agentPost(agentA, agentKeyA, "/api/v1/orders/claim", claim.toString()).status);
        assertEquals(409, agentPost(agentA, agentKeyA, "/api/v1/heartbeat", heartbeat("evil", "a")).status);
        assertEquals(404, agentPost(agentA, agentKeyA, "/api/v1/heartbeat", heartbeat("s", "missing")).status);
        assertEquals(200, agentPost(agentA, agentKeyA, "/api/v1/heartbeat", heartbeat("s", "b")).status);
        JsonObject foreignSession = new JsonObject();
        foreignSession.addProperty("botId", "00000000-0000-4000-8000-000000000000");
        foreignSession.addProperty("controlSession", "1".repeat(64));
        assertEquals(403, agentPost(agentA, agentKeyA, "/api/v1/bot/state", foreignSession.toString()).status);
    }

    @Test
    void signedKeyDoesNotRequireMinecraftIdentity() throws Exception {
        String owner = login(null, OWNER_PW);
        setServer(owner, "s");
        defineStash(owner, "a");
        JsonObject heartbeat = new JsonObject();
        heartbeat.addProperty("server", "s");
        heartbeat.addProperty("stash", "a");
        assertEquals(200, agentPost(agentA, agentKeyA, "/api/v1/heartbeat", heartbeat.toString()).status);
        assertEquals(null, credentials.find(agentA).binding());
    }

    @Test
    void helloBindingIsVerifiedAndStashesAnnounced() throws Exception {
        String owner = login(null, OWNER_PW);
        setServer(owner, "s");
        defineStash(owner, "a");
        String session = "1".repeat(64);
        // Wrong server in the binding: 409.
        assertEquals(409, botPost("/api/v1/bot/hello", helloFor(session, "evil", "a", agentA)).status);
        // Undefined stash in the binding: 404.
        assertEquals(404, botPost("/api/v1/bot/hello", helloFor(session, "s", "nope", agentA)).status);
        // Presence without a binding still works (a bot that only reports presence).
        assertEquals(200, botPost("/api/v1/bot/hello", "{\"botId\":\"bot-plain\",\"caps\":[]}").status);
        // A correct binding registers and announces the Manager-defined stashes (names only).
        Res ok = botPost("/api/v1/bot/hello", helloFor(session, "s", "a", agentA));
        assertEquals(200, ok.status, ok.body);
        JsonArray stashes = ok.obj().getAsJsonArray("stashes");
        assertTrue(
                stashes != null
                        && stashes.size() == 1
                        && stashes.get(0).getAsString().equals("a"),
                "hello announces defined stash names: " + ok.body);
        assertTrue(!ok.body.contains("\"pos1\""), "coordinates are never announced in hello: " + ok.body);
    }

    @Test
    void helloBootstrapsWithoutALocalStash() throws Exception {
        String owner = login(null, OWNER_PW);
        setServer(owner, "s");
        defineStash(owner, "a");
        // No work scope at all: a clean client that only knows address, port and key.
        Res ok = agentPost(agentA, agentKeyA, "/api/v1/bot/hello", helloBootstrap(agentA));
        assertEquals(200, ok.status, ok.body);
        JsonArray stashes = ok.obj().getAsJsonArray("stashes");
        assertTrue(
                stashes != null
                        && stashes.size() == 1
                        && stashes.get(0).getAsString().equals("a"),
                "bootstrap hello announces defined stash names: " + ok.body);
        // The bootstrap registers a key-id bot session with no player restriction.
        // POST /bot/next answers immediately (GET long-polls); the session must match hello.
        assertEquals(
                200,
                agentPost(
                                agentA,
                                agentKeyA,
                                "/api/v1/bot/next",
                                "{\"botId\":\"" + agentA + "\",\"controlSession\":\"" + "1".repeat(64) + "\"}")
                        .status);
        // A foreign key id is refused even when signed correctly.
        assertEquals(
                403,
                agentPost(
                                agentA,
                                agentKeyA,
                                "/api/v1/bot/state",
                                "{\"botId\":\"00000000-0000-4000-8000-000000000000\",\"controlSession\":\""
                                        + "1".repeat(64) + "\"}")
                        .status);
        // A valid unused key still cannot poll an unregistered bot session (409).
        String freshToken = credentials.issue("spare");
        String freshId = freshToken.substring(0, freshToken.indexOf('.'));
        byte[] freshKey = Signer.deriveKey(freshToken.substring(freshToken.indexOf('.') + 1));
        byte[] b = new byte[0];
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String path = "/api/v1/bot/next?botId=" + freshId;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header(
                        Signer.REQUEST_HEADER,
                        "v1 " + freshId + " " + ts + " " + nonce + " "
                                + Signer.requestSig(freshKey, "GET", path, ts, nonce, b))
                .GET()
                .build();
        HttpResponse<String> unbound = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, unbound.statusCode());
    }
}
