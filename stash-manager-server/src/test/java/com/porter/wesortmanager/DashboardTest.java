package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Settings checks against the schema, the job lifecycle and the console history. */
class DashboardTest {
    private static JsonObject node(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void valueChecks() {
        JsonObject interval = node("{\"path\":\"a\",\"type\":\"int\",\"min\":5,\"max\":600}");
        assertNull(DashboardRoutes.check(interval, JsonParser.parseString("30")));
        assertTrue(DashboardRoutes.check(interval, JsonParser.parseString("4")).contains("at least 5"));
        assertTrue(
                DashboardRoutes.check(interval, JsonParser.parseString("601")).contains("at most 600"));
        assertTrue(
                DashboardRoutes.check(interval, JsonParser.parseString("5.5")).contains("whole"));
        assertTrue(
                DashboardRoutes.check(interval, JsonParser.parseString("\"7\"")).contains("number"));
        assertTrue(
                DashboardRoutes.check(interval, JsonParser.parseString("null")).contains("required"));

        JsonObject nullable = node("{\"path\":\"b\",\"type\":\"int\",\"nullable\":true}");
        assertNull(DashboardRoutes.check(nullable, JsonParser.parseString("null")));
        assertTrue(DashboardRoutes.check(nullable, JsonParser.parseString("3000000000"))
                .contains("at most"));

        JsonObject mode = node("{\"path\":\"m\",\"type\":\"enum\",\"options\":[\"SAFE\",\"FAST\"]}");
        assertNull(DashboardRoutes.check(mode, JsonParser.parseString("\"FAST\"")));
        assertTrue(
                DashboardRoutes.check(mode, JsonParser.parseString("\"fast\"")).contains("choices"));

        JsonObject list = node("{\"path\":\"l\",\"type\":\"stringList\"}");
        assertNull(DashboardRoutes.check(list, JsonParser.parseString("[\"a\",\"b\"]")));
        assertTrue(
                DashboardRoutes.check(list, JsonParser.parseString("[\"a\",1]")).contains("text"));

        assertTrue(DashboardRoutes.check(
                        node("{\"path\":\"k\",\"type\":\"string\",\"secret\":true,\"set\":true,\"readOnly\":true}"),
                        JsonParser.parseString("\"x\""))
                .contains("secret"));
        assertTrue(DashboardRoutes.check(
                        node("{\"path\":\"u\",\"type\":\"string\",\"readOnly\":true,\"reason\":\"fixed\"}"),
                        JsonParser.parseString("\"x\""))
                .contains("read-only: fixed"));
        assertTrue(DashboardRoutes.check(null, JsonParser.parseString("1")).contains("no such"));
        assertTrue(DashboardRoutes.check(node("{\"path\":\"b\",\"type\":\"bool\"}"), JsonParser.parseString("1"))
                .contains("true or false"));
        assertTrue(DashboardRoutes.check(
                        node("{\"path\":\"s\",\"type\":\"string\"}"),
                        JsonParser.parseString("\"" + "x".repeat(1001) + "\""))
                .contains("1000"));
    }

    @Test
    void schemaWalkIndexesAndRejectsBadNodes() {
        Map<String, JsonObject> nodes = new HashMap<>();
        JsonArray ok = JsonParser.parseString(
                        "[{\"path\":\"section:A\",\"type\":\"group\",\"children\":[{\"path\":\"x\",\"type\":\"bool\"}]}]")
                .getAsJsonArray();
        assertNull(BotRoutes.walk(ok, nodes, 0));
        assertTrue(nodes.containsKey("x") && nodes.containsKey("section:A"));
        assertTrue(BotRoutes.walk(
                        JsonParser.parseString("[{\"path\":\"x\",\"type\":\"map\"}]")
                                .getAsJsonArray(),
                        new HashMap<>(),
                        0)
                .contains("unknown type"));
        assertTrue(BotRoutes.walk(
                        JsonParser.parseString("[{\"path\":\"g\",\"type\":\"group\"}]")
                                .getAsJsonArray(),
                        new HashMap<>(),
                        0)
                .contains("children"));
    }

    @Test
    void jobsExpireUnpickedAndBecomeUnknownUnanswered() throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        BotHub hub = new BotHub(now);
        assertTrue(hub.hello("bot-1", "Bob", new JsonObject(), new JsonObject(), List.of("settings", "console")));
        BotHub.Bot b = hub.bot("bot-1");
        String expired = hub.queueSets(b, "plugin", List.of(node("{\"path\":\"x\",\"value\":true}")))
                .get(0);
        now.advance(BotHub.EXPIRE_QUEUED_MS);
        assertEquals(
                "expired",
                hub.jobs(b, List.of(expired))
                        .get(0)
                        .getAsJsonObject()
                        .get("status")
                        .getAsString());

        String sent = hub.queueSets(b, "plugin", List.of(node("{\"path\":\"x\",\"value\":false}")))
                .get(0);
        BotHub.Poll p = hub.next(b);
        assertEquals(200, p.status());
        assertEquals(
                sent,
                p.body()
                        .getAsJsonArray("jobs")
                        .get(0)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString());
        assertEquals(
                "sent",
                hub.jobs(b, List.of(sent))
                        .get(0)
                        .getAsJsonObject()
                        .get("status")
                        .getAsString());
        now.advance(BotHub.UNKNOWN_SENT_MS);
        assertEquals(
                "unknown",
                hub.jobs(b, List.of(sent))
                        .get(0)
                        .getAsJsonObject()
                        .get("status")
                        .getAsString());
    }

    @Test
    void controlJobsUseOneScopedLeaseAndImmutableReceipts() throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        BotHub hub = new BotHub(now);
        String session = "agent-session";
        assertEquals(
                200,
                hub.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("settings"),
                        List.of("plugin"),
                        session));
        assertEquals(
                409,
                hub.register(
                        "b",
                        "Other",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("settings"),
                        List.of("plugin"),
                        "different-session"));
        BotHub.Bot bot = hub.bot("b");
        var first = node("{\"path\":\"scan-interval\",\"value\":12}");
        var second = node("{\"path\":\"order-messages\",\"value\":false}");
        var ids = hub.queueSets(bot, "plugin", List.of(first, second));
        assertEquals(409, hub.next(bot, 0, null).status());
        assertEquals(409, hub.next(bot, 0, "different-session").status());
        var poll = hub.next(bot, 0, session);
        var leased = poll.body().getAsJsonArray("jobs");
        assertEquals(1, leased.size());
        JsonObject job = leased.get(0).getAsJsonObject();
        assertEquals(ids.get(0), job.get("id").getAsString());
        assertEquals(leased, hub.next(bot, 0, session).body().getAsJsonArray("jobs"));
        var receipt = new JsonObject();
        for (String key : List.of("id", "lease", "sequence", "value")) receipt.add(key, job.get(key));
        receipt.addProperty("ok", true);
        assertEquals(BotHub.Acknowledgement.REFUSED, hub.acknowledge(bot, "different-session", receipt));
        var wrongSequence = receipt.deepCopy();
        wrongSequence.addProperty("sequence", 2);
        assertEquals(BotHub.Acknowledgement.REFUSED, hub.acknowledge(bot, session, wrongSequence));
        assertEquals(BotHub.Acknowledgement.APPLIED, hub.acknowledge(bot, session, receipt));
        assertEquals(BotHub.Acknowledgement.REPLAY, hub.acknowledge(bot, session, receipt));
        var conflicting = receipt.deepCopy();
        conflicting.addProperty("ok", false);
        assertEquals(BotHub.Acknowledgement.REFUSED, hub.acknowledge(bot, session, conflicting));

        var next =
                hub.next(bot, 0, session).body().getAsJsonArray("jobs").get(0).getAsJsonObject();
        assertEquals(ids.get(1), next.get("id").getAsString());
        for (String key : List.of("id", "lease", "sequence", "value")) receipt.add(key, next.get(key));
        now.advance(BotHub.UNKNOWN_SENT_MS);
        assertEquals(BotHub.Acknowledgement.REFUSED, hub.acknowledge(bot, session, receipt));
        assertEquals(
                "unknown",
                hub.jobs(bot, List.of(ids.get(1)))
                        .get(0)
                        .getAsJsonObject()
                        .get("status")
                        .getAsString());
    }

    @Test
    void dedicatedHeartbeatIsIndependentOfPollingAndFencedBySession() throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        BotHub hub = new BotHub(now);
        String session = "agent-session";
        assertEquals(
                200,
                hub.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("heartbeat"),
                        List.of("plugin"),
                        session));
        var bot = hub.bot("b");
        String epoch = bot.controlEpoch;
        assertFalse(hub.online(bot), "hello alone is not a verified heartbeat");
        assertFalse(hub.heartbeat("b", "wrong", epoch));
        assertFalse(hub.heartbeat("b", session, "old-epoch"));
        for (int i = 0; i < 12; i++) {
            now.advance(5_000);
            assertTrue(hub.heartbeat("b", session, epoch));
            assertTrue(hub.online(bot));
        }
        now.advance(BotHub.ONLINE_MS);
        assertFalse(hub.online(bot));
        assertFalse(hub.heartbeat("b", session, epoch), "expired session must hello again");
        assertEquals(
                200,
                hub.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("heartbeat"),
                        List.of("plugin"),
                        session));
        assertFalse(hub.online(bot), "hello does not manufacture a new pong");
        assertTrue(hub.heartbeat("b", session, epoch));
        hub.retire("b");
        assertFalse(hub.heartbeat("b", session, epoch));
    }

    @Test
    void idlePollIsSuccessfulAndKeepsSessionAliveAcrossManyPolls() throws Exception {
        FakeClock now = new FakeClock(1_000_000);
        BotHub hub = new BotHub(now);
        String session = "agent-session";
        assertEquals(
                200,
                hub.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("settings"),
                        List.of("plugin"),
                        session));
        var bot = hub.bot("b");
        String epoch = bot.controlEpoch;
        assertEquals(200, hub.next(bot, 0, session).status()); // initial watch state
        for (int i = 0; i < 30; i++) {
            now.advance(2_000);
            assertTrue(hub.currentControlSession(bot, session, epoch));
            assertEquals(204, hub.next(bot, 0, session).status());
        }
        // A Manager restart drops the registry, but hello with the same session
        // immediately registers again and issues a new epoch.
        var restarted = new BotHub(now);
        assertNull(restarted.bot("b"));
        assertEquals(
                200,
                restarted.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("settings"),
                        List.of("plugin"),
                        session));
        var replacement = restarted.bot("b");
        assertFalse(restarted.currentControlSession(replacement, session, epoch));
        assertTrue(restarted.currentControlSession(replacement, session, replacement.controlEpoch));
    }

    @Test
    void acceptedStateRefreshesControlPresenceEvenWithoutPolls() {
        FakeClock now = new FakeClock(1_000_000);
        var hub = new BotHub(now);
        String session = "agent-session";
        assertEquals(
                200,
                hub.register(
                        "b",
                        "Builder",
                        new JsonObject(),
                        new JsonObject(),
                        List.of("settings"),
                        List.of("plugin"),
                        session));
        var bot = hub.bot("b");
        now.advance(30_000);
        hub.state(bot, "plugin", "schema", "values", new JsonArray(), new HashMap<>(), new JsonObject());
        now.advance(30_000);
        assertTrue(hub.currentControlSession(bot, session, bot.controlEpoch));
    }

    @Test
    void secretsAreDroppedAndMarked() {
        Map<String, JsonObject> nodes = new HashMap<>();
        JsonArray schema = JsonParser.parseString(
                        "[{\"path\":\"discord.token\",\"type\":\"string\",\"secret\":true,\"set\":true},"
                                + "{\"path\":\"notifications.ntfy.topic\",\"type\":\"string\"},{\"path\":\"client.name\",\"type\":\"string\"},"
                                + "{\"path\":\"weird.secretThing\",\"type\":\"string\"}]")
                .getAsJsonArray();
        assertNull(BotRoutes.walk(schema, nodes, 0));
        assertTrue(
                nodes.get("notifications.ntfy.topic").get("secret").getAsBoolean(),
                "a secret-looking path is marked secret");
        assertTrue(nodes.get("notifications.ntfy.topic").get("readOnly").getAsBoolean());
        JsonObject values =
                node("{\"discord.token\":\"LEAK\",\"notifications.ntfy.topic\":\"LEAK\",\"client.name\":\"ok\","
                        + "\"database.password\":\"LEAK\",\"weird.secretThing\":\"LEAK\",\"x.apiKey\":\"LEAK\"}");
        JsonObject clean = BotRoutes.scrubSecrets(values, nodes);
        assertEquals(1, clean.size());
        assertEquals("ok", clean.get("client.name").getAsString());
        assertFalse(clean.toString().contains("LEAK"));
        assertEquals(1, BotRoutes.scrubSecrets(values, null).size(), "the path rule works without a schema too");
        Map<String, JsonObject> n2 = new HashMap<>();
        assertNull(BotRoutes.walk(
                JsonParser.parseString(
                                "[{\"path\":\"database.url\",\"type\":\"string\"},{\"path\":\"web.host\",\"type\":\"string\"}]")
                        .getAsJsonArray(),
                n2,
                0));
        JsonObject withUrl = BotRoutes.scrubSecrets(
                node("{\"database.url\":\"postgres://admin:hunter2@db:5432/x\",\"web.host\":\"http://example.com/a@b\","
                        + "\"server.sessionTimeout\":5,\"license\":\"x\"}"),
                n2);
        assertFalse(withUrl.has("database.url"), "a URL with user info is a secret");
        assertTrue(n2.get("database.url").get("secret").getAsBoolean(), "and its node is marked");
        assertTrue(withUrl.has("web.host"), "an @ after the path is no user info");
        assertFalse(withUrl.has("server.sessionTimeout") || withUrl.has("license"), "the wider name list");
        assertFalse(BotRoutes.SCOPE.matcher("plugin:Auto-Reply_2").matches());
        assertFalse(BotRoutes.SCOPE.matcher("other").matches());
    }

    @Test
    void activityTimesOutOfRangeBecomeTheReceiveTime() {
        long now = 1_800_000_000_000L;
        JsonObject raw = node("{\"at\":5,\"current\":[{\"source\":\"A\",\"since\":5},{\"source\":\"B\",\"since\":"
                + (now - 60_000) + "}],"
                + "\"events\":[{\"at\":5,\"source\":\"x\",\"text\":\"old\"},{\"at\":" + (now + 3_600_000)
                + ",\"source\":\"x\",\"text\":\"future\"},"
                + "{\"at\":" + (now - 1000) + ",\"source\":\"x\",\"text\":\"fine\"}]}");
        JsonObject a = Activity.clean(raw, now);
        assertEquals(now, a.get("at").getAsLong());
        assertFalse(a.getAsJsonArray("current").get(0).getAsJsonObject().has("since"), "a nonsense since is dropped");
        assertEquals(
                now - 60_000,
                a.getAsJsonArray("current")
                        .get(1)
                        .getAsJsonObject()
                        .get("since")
                        .getAsLong());
        JsonArray ev = Activity.events(raw, now);
        assertEquals(now, ev.get(0).getAsJsonObject().get("at").getAsLong());
        assertEquals(now, ev.get(1).getAsJsonObject().get("at").getAsLong());
        assertEquals(now - 1000, ev.get(2).getAsJsonObject().get("at").getAsLong());
    }

    @Test
    void stateAsksForAMissingSchema() {
        BotHub hub = new BotHub();
        hub.hello("bot-3", null, new JsonObject(), new JsonObject(), List.of("settings"));
        BotHub.Bot b = hub.bot("bot-3");
        JsonArray need = hub.state(b, "plugin", "h1", "v1", null, null, new JsonObject());
        assertEquals("schema", need.get(0).getAsJsonObject().get("what").getAsString());
        JsonArray schema = new JsonArray();
        assertEquals(
                0,
                hub.state(b, "plugin", "h1", "v1", schema, new HashMap<>(), new JsonObject())
                        .size(),
                "an unrequested schema is taken too");
        assertEquals(
                1,
                hub.state(b, "plugin", "h2", "v2", null, null, new JsonObject()).size(),
                "a new schema hash without the schema asks again");
    }
}
