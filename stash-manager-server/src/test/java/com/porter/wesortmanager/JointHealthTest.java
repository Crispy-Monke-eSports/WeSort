package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Joint health behaviors: stuck/recovering split, presence truth, hello
 * preemption, STALE split, accept-loss, aging freeze and rescan debounce.
 */
class JointHealthTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(1_000_000);
    private StockLedger ledger;
    private IndexStore store;
    private OrderStore orders;

    @BeforeEach
    void initialize() throws Exception {
        ledger = new StockLedger(data, time::get, 60_000);
        store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        orders = new OrderStore(data, store);
        orders.load();
    }

    // ---------- BotHub presence ----------

    @Test
    void inFlightPollAloneIsNotPresence() {
        var hub = new BotHub(time::get);
        var bot = new BotHub.Bot("ghost");
        bot.waiting = 1;
        assertFalse(BotHub.online(bot, time.get()));
        assertEquals("silent", BotHub.presenceCause(bot, time.get()));
    }

    @Test
    void presenceCauseDistinguishesHeartbeatPollAndSilence() {
        var hub = new BotHub(time::get);
        var silent = new BotHub.Bot("never");
        assertEquals("silent", BotHub.presenceCause(silent, time.get()));

        hub.register("legacy", "L", new JsonObject(), new JsonObject(), List.of(), List.of(), null);
        var legacy = hub.bot("legacy");
        assertEquals("poll", BotHub.presenceCause(legacy, time.get()));
        assertTrue(BotHub.online(legacy, time.get()));

        hub.register("modern", "M", new JsonObject(), new JsonObject(), List.of("heartbeat"), List.of(), "session-1");
        var modern = hub.bot("modern");
        assertEquals("poll", BotHub.presenceCause(modern, time.get()));
        assertTrue(hub.heartbeat("modern", "session-1", modern.controlEpoch));
        assertEquals("heartbeat", BotHub.presenceCause(modern, time.get()));
        time.addAndGet(BotHub.ONLINE_MS + 1);
        assertFalse(BotHub.online(modern, time.get()));
        assertEquals("silent", BotHub.presenceCause(modern, time.get()));
    }

    @Test
    void authenticatedHelloPreemptsAStaleControlSession() {
        var hub = new BotHub(time::get);
        assertEquals(
                200, hub.register("b", "B", new JsonObject(), new JsonObject(), List.of(), List.of(), "old-session"));
        // A fresh session answers 409: something is still alive on the old one.
        assertEquals(
                409, hub.register("b", "B", new JsonObject(), new JsonObject(), List.of(), List.of(), "new-session"));
        time.addAndGet(BotHub.TAKEOVER_GRACE_MS + 1);
        String epoch = hub.bot("b").controlEpoch;
        assertEquals(
                200, hub.register("b", "B", new JsonObject(), new JsonObject(), List.of(), List.of(), "new-session"));
        assertNotEquals(epoch, hub.bot("b").controlEpoch);
    }

    @Test
    void sessionlessHelloNeverTakesOverALiveControlSession() {
        var hub = new BotHub(time::get);
        assertEquals(200, hub.register("b", "B", new JsonObject(), new JsonObject(), List.of(), List.of(), "session"));
        assertEquals(409, hub.register("b", "B", new JsonObject(), new JsonObject(), List.of(), List.of(), null));
    }

    // ---------- AgentCredentials ----------

    @Test
    void revokedSlotsStayAsTombstonesAndAuthFailuresExpire() throws Exception {
        var clock = new AtomicLong(5_000_000);
        var credentials = new AgentCredentials(data, clock::get);
        String token = credentials.issue("bot");
        String id = token.substring(0, token.indexOf('.'));
        credentials.noteAuthFailure(id);
        assertTrue(credentials.recentAuthFailure(id, clock.get()));
        assertTrue(credentials.lastAuthFailure(id) > 0);
        clock.addAndGet(AgentCredentials.AUTH_FAILURE_WINDOW_MS + 1);
        assertFalse(credentials.recentAuthFailure(id, clock.get()));

        assertTrue(credentials.revoke(id));
        var tombstone = credentials.all().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow();
        assertFalse(tombstone.active());
        assertNull(tombstone.signingKey());

        JsonArray slots = BrowserRoutes.slotsJson(credentials);
        assertEquals(1, slots.size());
        JsonObject row = slots.get(0).getAsJsonObject();
        assertEquals(id, row.get("id").getAsString());
        assertEquals("bot", row.get("label").getAsString());
        assertFalse(row.get("active").getAsBoolean());
        assertFalse(row.has("bound"), "tombstones carry no presence");
        assertFalse(row.toString().contains("key"), "tombstones carry no secrets");
    }

    // ---------- OperationsRoutes alert codes ----------

    @Test
    void staleSplitTellsExpectedRescansFromUnexpectedAge() {
        assertEquals("STALE_AGE", OperationsRoutes.stockAlertCode("STALE", false));
        assertEquals("STALE_RESCAN", OperationsRoutes.stockAlertCode("STALE", true));
        assertEquals("STALE_AGE", OperationsRoutes.stockAlertCode("STALE_AGE", false));
        assertEquals("STALE_RESCAN", OperationsRoutes.stockAlertCode("STALE_STARTUP", true));
        assertEquals("MISSING", OperationsRoutes.stockAlertCode("MISSING", false));
        assertEquals("MISSING", OperationsRoutes.stockAlertCode("MISSING", true));
        assertEquals("UNREACHABLE", OperationsRoutes.stockAlertCode("UNREACHABLE", false));
        assertEquals("UNKNOWN", OperationsRoutes.stockAlertCode("UNKNOWN", false));
        assertEquals("WITHDRAWN", OperationsRoutes.stockAlertCode("WITHDRAWN", false));
        assertNull(OperationsRoutes.stockAlertCode("FRESH", false));
        assertNull(OperationsRoutes.stockAlertCode("PROTECTED", false));
        assertNull(OperationsRoutes.stockAlertCode("SUPERSEDED", false));
    }

    // ---------- IndexStore rescan debounce ----------

    @Test
    void rescanDebounceMatrix() {
        assertTrue(IndexStore.rescanDebounced(true, false));
        assertTrue(IndexStore.rescanDebounced(false, true));
        assertTrue(IndexStore.rescanDebounced(true, true));
        assertFalse(IndexStore.rescanDebounced(false, false));
    }

    @Test
    void heartbeatDoesNotRestartAPassTheBotIsWorkingThrough() throws Exception {
        observe("a", 10);
        long first = store.requestRescan("s", "a");
        assertTrue(first > 0);
        assertEquals(first, store.rescanPending("s", "a").longValue());
        // Age the stock so the auto-rescan path would fire without the debounce.
        time.addAndGet(120_000);
        assertEquals(
                "STALE_AGE",
                ledger.sources().stream()
                        .filter(s -> s.stash().equals("a"))
                        .findFirst()
                        .orElseThrow()
                        .status());
        store.heartbeat("s", "a", null, null, IndexStore.BotStatus.NONE);
        assertEquals(first, store.rescanPending("s", "a").longValue(), "a live bot keeps its outstanding rescan id");
    }

    @Test
    void rescanExpiryIsBoundedAndTestable() {
        long now = 1_700_000_000_000L;
        assertFalse(IndexStore.rescanExpired(now / 1000, now));
        assertFalse(IndexStore.rescanExpired(now / 1000 - IndexStore.RESCAN_EXPIRE_SEC, now));
        assertTrue(IndexStore.rescanExpired(now / 1000 - IndexStore.RESCAN_EXPIRE_SEC - 1, now));
    }

    @Test
    void sweepClearsADeadBotsOutstandingRescan() throws Exception {
        observe("a", 10);
        long first = store.requestRescan("s", "a");
        assertTrue(first > 0);
        assertEquals(first, store.rescanPending("s", "a").longValue());
        // A fresh sweep leaves a fresh request alone.
        assertTrue(store.sweepStaleRescans(System.currentTimeMillis()).isEmpty());
        assertEquals(first, store.rescanPending("s", "a").longValue());
        // A dead bot never heartbeats: the sweep still clears the id and lifts the freeze.
        var expired = store.sweepStaleRescans(
                System.currentTimeMillis() + (IndexStore.RESCAN_EXPIRE_SEC + 60) * 1000);
        assertEquals(1, expired.size());
        assertNull(store.rescanPending("s", "a"));
    }

    // ---------- StockLedger loss, escalation and freeze ----------

    @Test
    void acceptLossZeroesALostSourceWithAnAuditMarker() throws Exception {
        var l = ledger(100);
        var missing = observation(l, "a", 64, 0, 1, 0);
        source(missing).addProperty("state", "MISSING");
        l.accept(missing);
        assertEquals("MISSING", l.sources().getFirst().status());
        assertTrue(l.acceptLoss(l.sources().getFirst().value().get("id").getAsString(), "owner"));
        var tombstone = l.sources().getFirst();
        assertEquals("WITHDRAWN", tombstone.status());
        assertTrue(tombstone.value().getAsJsonArray("items").isEmpty());
        assertTrue(tombstone.value().getAsJsonArray("shulkers").isEmpty());
        assertEquals(
                "owner",
                tombstone.value().getAsJsonObject("lossAccepted").get("by").getAsString());
        assertTrue(
                l.acceptLoss(tombstone.value().get("id").getAsString(), "owner"),
                "accepting twice is a harmless replay");
    }

    @Test
    void acceptLossRefusesHealthySources() throws Exception {
        var l = ledger(100);
        l.accept(observation(l, "a", 64, 0, 1, 0));
        assertFalse(l.acceptLoss(l.sources().getFirst().value().get("id").getAsString(), "owner"));
        assertEquals("FRESH", l.sources().getFirst().status());
    }

    @Test
    void ancientMissingEscalatesToWithdrawnOnItsOwn() throws Exception {
        var l = ledger(100);
        var missing = observation(l, "a", 64, 0, 1, 0);
        source(missing).addProperty("state", "MISSING");
        l.accept(missing);
        time.set(time.get() + StockLedger.MISSING_WITHDRAWN_AFTER_MS + 1);
        assertEquals("WITHDRAWN", l.sources().getFirst().status());
    }

    @Test
    void agingFreezeHoldsWhileTheRescanIsOutstanding() throws Exception {
        var l = ledger(100);
        l.accept(observation(l, "a", 64, 0, 1, 0));
        time.addAndGet(90);
        assertEquals("FRESH", l.sources().getFirst().status());
        l.freezeAging("s", time.get());
        // Past the normal TTL but inside the bounded 25 ms grace period.
        time.addAndGet(15);
        assertEquals("FRESH", l.sources().getFirst().status(), "frozen stock does not age");
        l.unfreezeAging("s");
        assertEquals("STALE_AGE", l.sources().getFirst().status());
    }

    @Test
    void agingFreezeExpiresWhenTheBotGoesQuiet() throws Exception {
        var l = ledger(100);
        l.accept(observation(l, "a", 64, 0, 1, 0));
        time.addAndGet(90);
        l.freezeAging("s", time.get());
        time.addAndGet(24);
        assertEquals("FRESH", l.sources().getFirst().status());
        time.addAndGet(1);
        assertEquals("STALE_AGE", l.sources().getFirst().status());
    }

    @Test
    void repeatedFreezeDoesNotExtendTheGracePeriod() throws Exception {
        var l = ledger(100);
        l.accept(observation(l, "a", 64, 0, 1, 0));
        time.addAndGet(90);
        l.freezeAging("s", time.get());
        time.addAndGet(20);
        l.freezeAging("s", time.get());
        assertEquals("FRESH", l.sources().getFirst().status());
        time.addAndGet(5);
        assertEquals("STALE_AGE", l.sources().getFirst().status());
        l.freezeAging("s", time.get());
        assertEquals("STALE_AGE", l.sources().getFirst().status());
    }

    // ---------- RuntimeHealth freshness warnings ----------

    @Test
    void stockFreshnessIsAWarningNotAReadinessFlip() throws Exception {
        var health = new RuntimeHealth(true);
        long now = time.get();
        var frame = index("a", 10);
        frame.addProperty("generatedAt", now);
        store.putIndex(frame);
        health.sample("s", store, orders, now, 60_000);
        assertEquals("ready", health.detail(now).get("status").getAsString());
        time.addAndGet(120_000);
        health.sample("s", store, orders, time.get(), 60_000);
        var detail = health.detail(time.get());
        assertEquals("ready", detail.get("status").getAsString());
        assertTrue(detail.get("staleStock").getAsBoolean());
        assertTrue(detail.getAsJsonArray("warnings").toString().contains("STALE_STOCK"));
    }

    // ---------- fixtures ----------

    private StockLedger ledger(long maxAge) throws Exception {
        return new StockLedger(data.resolve("t" + System.nanoTime()), time::get, maxAge);
    }

    private JsonObject index(String stash, int count) throws Exception {
        var frame = frame(count);
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", "s");
        root.addProperty("stash", stash);
        var items = new JsonArray();
        var row = new JsonObject();
        row.addProperty("id", "minecraft:diamond");
        row.addProperty("key", "minecraft:diamond");
        row.addProperty("count", (long) count);
        items.add(row);
        root.add("items", items);
        root.add("shulkers", new JsonArray());
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision() + 1, stash.hashCode());
        var writer = new com.porter.wesort.data.PhysicalSlots.Writer();
        var src = root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        src.add("slots", writer.slots(frame.slots()));
        src.addProperty("fingerprint", frame.fingerprint());
        root.addProperty("physicalVersion", 2);
        root.add("slotVariants", writer.variants());
        return root;
    }

    private com.porter.wesort.data.PhysicalSlots.Snapshot frame(int count) {
        var diamond = new com.porter.wesort.data.PhysicalSlots.Item(
                "minecraft:diamond",
                "minecraft:diamond",
                64,
                "{}",
                com.porter.wesort.data.PhysicalSlots.Kind.ITEM,
                0,
                List.of(),
                "");
        return new com.porter.wesort.data.PhysicalSlots.Snapshot(
                27, List.of(new com.porter.wesort.data.PhysicalSlots.Slot(0, count, diamond)));
    }

    private void observe(String stash, int count) throws Exception {
        var payload = index(stash, count);
        payload.getAsJsonArray("physicalSources").get(0).getAsJsonObject().addProperty("observedAt", time.get());
        store.putIndex(payload);
    }

    private JsonObject observation(StockLedger l, String stash, long count, long base, long sequence, int x) {
        return StockFixtures.physical(
                JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"" + stash
                                + "\",\"receivedAt\":1000,\"items\":[{\"key\":\"minecraft:diamond\","
                                + "\"id\":\"minecraft:diamond\",\"count\":" + count + "}]}")
                        .getAsJsonObject(),
                l.epoch(),
                base,
                sequence,
                x);
    }

    private JsonObject source(JsonObject index) {
        return index.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }
}
