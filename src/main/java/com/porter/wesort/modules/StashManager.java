package com.porter.wesort.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.porter.wesort.WeSortAddon;
import com.porter.wesort.compat.InventorySync;
import com.porter.wesort.compat.PhysicalJournalRuntime;
import com.porter.wesort.stash.Order;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Item;

/**
 * Pushes what is in the saved stash (the one wesort-stash-sorter uses) to the WeSort Manager web server on the local network,
 * which shows it. The index is built on the client thread; only bytes go to the network thread, and the reply is handed
 * back to the client thread, so all state here is only touched on the client thread.
 */
public class StashManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // First entry: opens the connection screen (address, port, masked key, status) from the
    // normal Meteor menu, so no chat command is needed. The raw settings below stay for
    // compatibility; the key itself is always rendered masked.
    private final Setting<?> connectionEntry = sgGeneral.add(com.porter.wesort.commands.ConnectionOpener.setting());

    private final Setting<String> serverUrl = sgGeneral.add(new StringSetting.Builder()
            .name("server-url")
            .description(
                    "Address of the WeSort Manager server, http or https, e.g. http://127.0.0.1:8765. Empty: no server, nothing is sent. Prefer the Connection screen above: it splits address and port and masks the key.")
            .defaultValue("")
            .visible(() -> false)
            .build());

    private final Setting<String> apiKey = sgGeneral.add(new StringSetting.Builder()
            .name("api-key")
            .description(
                    "The agent API key from the Manager host's agent-key command. Keep it private: it is masked here, in the Connection screen and in logs. Prefer the Connection screen above.")
            .defaultValue("")
            .renderer(com.porter.wesort.manager.gui.widgets.MaskedRenderer.class)
            .visible(() -> false)
            .build());

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
            .name("scan-interval")
            .description("How often, in seconds, the stash is checked for changes. Only changes are sent.")
            .defaultValue(10)
            .min(2)
            .sliderRange(2, 60)
            .visible(() -> false)
            .build());

    private final Setting<Boolean> includeExcluded = sgGeneral.add(new BoolSetting.Builder()
            .name("include-excluded")
            .description("Also list containers excluded from sorting.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    private final Setting<Boolean> acceptOrders = sgGeneral.add(new BoolSetting.Builder()
            .name("accept-orders")
            .description(
                    "Let the bot take orders from the web page: it builds shulkers from the stash and delivers them to the delivery container (wesort-stash-sorter pick: Delivery). wesort-stash-sorter does not need to be on: while this module is on it reads containers, takes orders and puts leftovers back by itself, using wesort-stash-sorter's settings (stash-name, area, safe-walk...), and never sorts. Needs a free inventory slot and enough empty shulkers. Loose items an order took out and did not deliver go back to the container they came from, or the nearest one with room when that is not known; shulkers it unpacked go back where they came from. To stop the bot, turn off accept-orders or wesort-stash-manager. Turning wesort-stash-sorter off hands control back to wesort-stash-manager while accept-orders is on.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    // The server controls when the internal engine may sort; never configured in Meteor.
    private final Setting<Boolean> sortEnabled = sgGeneral.add(new BoolSetting.Builder()
            .name("sort-enabled")
            .description("Manager-controlled physical sorting. Requires a current server-issued sorting permit.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    private final Setting<Boolean> orderIntakePaused = sgGeneral.add(new BoolSetting.Builder()
            .name("order-intake-paused")
            .description("Manager pauses new warehouse orders while the current order finishes.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    private final Setting<Boolean> useNamedShulkers = sgGeneral.add(new BoolSetting.Builder()
            .name("use-named-shulkers")
            .description(
                    "Orders may take items out of shulkers that have a custom name. Off: named shulkers are left alone, as the sorter does.")
            .defaultValue(true)
            .visible(() -> false)
            .build());

    private final Setting<Integer> freshMinutes = sgGeneral.add(new IntSetting.Builder()
            .name("fresh-minutes")
            .description(
                    "A container read within this many minutes is not read again when wesort-stash-sorter or an order starts, unless something marked it changed (another player opened it, a sign changed, items were not where they were saved). 0: read everything again every time. A website rescan always reads everything. wesort-stash-sorter uses this too.")
            .defaultValue(5)
            .min(0)
            .sliderRange(0, 30)
            .visible(() -> false)
            .build());

    private final Setting<Boolean> orderChat = sgGeneral.add(new BoolSetting.Builder()
            .name("order-messages")
            .description("Tell you in chat when an order is taken, delivered or fails.")
            .defaultValue(true)
            .visible(() -> false)
            .build());

    private final SettingGroup sgDelivery = settings.createGroup("Delivery");

    private final Setting<Boolean> autoExpandDelivery = sgDelivery.add(new BoolSetting.Builder()
            .name("auto-expand-delivery")
            .description(
                    "When every chest of the delivery storage is full, put another double chest up one block above the highest one (taken from the stash, or crafted when enable-crafting is on) and keep filling the lowest chest with room. A chest a player emptied is used again. Off: an order waits for space (paused for up to 2 hours, then it ends). It goes up for as long as the new chests could be opened and the bot can reach them: from the floor the first row above the delivery container, higher ones need something to stand on beside the stack. The chests are never sorted into or listed.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    private final SettingGroup sgCrafting = settings.createGroup("Crafting");

    private final Setting<Boolean> enableCrafting = sgCrafting.add(new BoolSetting.Builder()
            .name("enable-crafting")
            .description(
                    "Orders and compaction may craft a plain Shulker Box when no empty one is left: shulker shells and a chest (made of planks, made of logs) are taken from the stash and crafted at the crafting table below. Shulker shells can't be crafted, so they have to be in the stash. Needs a crafting table: the one set below, else one is found in the stash area, taken from the stash and placed, or crafted in the inventory grid from 4 planks (or logs) and placed. Kits never craft.")
            .defaultValue(false)
            .visible(() -> false)
            .build());

    private final Setting<Integer> craftMaxPerOrder = sgCrafting.add(new IntSetting.Builder()
            .name("craft-max-per-order")
            .description("The most Shulker Boxes crafted for one order.")
            .defaultValue(27)
            .min(1)
            .sliderRange(1, 54)
            .visible(() -> false)
            .build());

    private final Setting<Integer> craftMaxOpsPerTask = sgCrafting.add(new IntSetting.Builder()
            .name("craft-max-ops-per-task")
            .description(
                    "The most grid fills (ingredients put into the crafting grid) in one trip to the table. A bigger craft is done in several trips.")
            .defaultValue(128)
            .min(8)
            .sliderRange(8, 512)
            .visible(() -> false)
            .build());

    private final Setting<Integer> craftMaxPerHour = sgCrafting.add(new IntSetting.Builder()
            .name("craft-max-per-hour")
            .description("The most Shulker Boxes crafted in an hour.")
            .defaultValue(54)
            .min(1)
            .sliderRange(1, 216)
            .visible(() -> false)
            .build());

    private final Setting<List<Item>> craftNeverUse = sgCrafting.add(new ItemListSetting.Builder()
            .name("craft-never-use")
            .description("Items crafting never uses as ingredients, whatever the stash holds.")
            .defaultValue(List.of())
            .visible(() -> false)
            .build());

    private static final long HEARTBEAT_MS = 15_000;
    private static final long CLAIM_MS = 5_000, STATUS_MS = 2_000;
    private static final long REBUILD_MS = 60_000;
    private static final long BACKOFF_MIN_MS = 5_000, BACKOFF_MAX_MS = 60_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wesort-stash-manager");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Everything below is client thread only
    private StashIndex.Built current;
    private long fingerprint, lastBuild;
    private int scanTicks;
    private int seenInterval = -1;
    private boolean seenInclude;
    private long lastHeartbeat, nextAttemptAt, backoff;
    private boolean warnedKey, warnedUrl;
    private boolean inFlight;
    private int generation;
    private String lastPushed;
    private boolean needIndex;
    private String status = "idle";
    private String lastLogged = "";
    private ControlScope controlScope;
    private String controlSession;
    private String controlEpoch;
    private boolean controlRegistered;
    private String sentControlValues;
    private JsonArray controlResults;
    private JsonObject lastControlResult;
    private long nextControlAt;
    private PendingManifest pendingManifest;

    // Orders
    private int pendingOrders;
    private long lastClaim, lastStatusAt;
    private String lastStatusSig = "";
    private String rejectedId, rejectedWhy, rejectedStatus = "failed";
    // The order whose full status report was refused, so its short form is sent
    private String minimalFor;
    private String ordersNote = "";

    // What the last heartbeat said about delivery.
    private String sentDelivery = "";
    // The delivery contents report (0.4.0-dev.7): when to build and send it, the last stamp of the delivery chests, the
    // hash of the
    // last one built (the heartbeat's deliveryHash) and the one built and not yet taken
    private final com.porter.wesort.stash.DeliveryContents.Sync deliverySync =
            new com.porter.wesort.stash.DeliveryContents.Sync();
    private final com.porter.wesort.stash.DeliveryContents.SentLog deliveryLog =
            new com.porter.wesort.stash.DeliveryContents.SentLog();
    private long deliveryStamp;
    private String deliveryFor = "", deliveryHash;
    private com.porter.wesort.stash.DeliveryContents.Built deliveryPending;
    // The rescan id handled and not yet acknowledged to the server, or -1
    private long rescanDone = -1;
    // A website rescan is being carried out: the bot reads until nothing is stale or unread
    private boolean rescanning;
    // The last rescan acted on, kept over a reset so one that was not acknowledged yet is not done twice
    private long lastRescanId = -1, lastRescanAt;

    public StashManager() {
        super(
                WeSortAddon.CATEGORY,
                "WeSort Agent",
                "Sends what's in your saved stash to the WeSort Manager web server on your network, which shows it. Uses the stash saved by wesort-stash-sorter. With accept-orders on, or during a website rescan, it moves the bot by itself; to stop that, turn off accept-orders or this module. Turning wesort-stash-sorter off hands control back to this module while accept-orders is on.");
    }

    @Override
    public void onActivate() {
        selfCheck();
        reset();
    }

    public boolean dedicatedProfile() {
        return !serverUrl.get().isBlank() && !apiKey.get().isBlank();
    }

    /**
     * The Manager order the bot is currently working, or null (W-061: the job tag every world-interaction
     * permit is issued under; permits without a job are still exact-position, exact-block and expiring).
     */
    public String activeOrderId() {
        try {
            var sorter = WeSortAddon.sorter();
            if (sorter == null) return null;
            var orders = sorter.orders();
            return orders == null ? null : orders.orderId();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** What the bot is currently doing, for permit audit when no order is in hand (never a secret). */
    public String activeTask() {
        return status;
    }

    /**
     * Truthful link state for the connection GUI (P1-12): storing an address and key is not a
     * connection, so this never reports online before a verified Manager reply. See ConnectionState.
     */
    public String connectionState() {
        return com.porter.wesort.commands.ConnectionState.of(isActive(), serverUrl.get(), apiKey.get(), status)
                .name();
    }

    @Override
    public void onDeactivate() {
        sortingUntil = 0;
        // Nobody is left to tell the server how an order is going: park it so a later claim carries on
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter != null) {
            sorter.orders().park();
            sorter.drive(false);
        }
        reset();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        reset();
    }

    private void reset() {
        generation++;
        current = null;
        fingerprint = 0;
        lastBuild = 0;
        scanTicks = 0;
        seenInterval = -1;
        lastHeartbeat = 0;
        nextAttemptAt = 0;
        backoff = 0;
        warnedKey = warnedUrl = false;
        lastPushed = null;
        needIndex = false;
        inFlight = false;
        status = "idle";
        lastLogged = "";
        controlRegistered = false;
        sentControlValues = null;
        controlResults = null;
        nextControlAt = 0;
        pendingManifest = null;
        pendingOrders = 0;
        lastClaim = lastStatusAt = 0;
        lastStatusSig = "";
        rejectedId = rejectedWhy = null;
        rejectedStatus = "failed";
        ordersNote = "";
        sentDelivery = "";
        resetDelivery();
        rescanDone = -1;
        rescanning = false;
        StashIndex.clearCache();
    }

    private boolean servicePhysicalJournal() {
        if (inFlight || mc.player == null || mc.level == null) return false;
        String scope = linkScope();
        if (scope.isEmpty()) return false;
        URI base = parse(serverUrl.get());
        if (base == null) return false;
        String agent = mc.player.getUUID().toString();
        var record = PhysicalJournalRuntime.upload(scope, Stash.currentServer(), agent, System.currentTimeMillis());
        if (record == null) return false;
        var request = new JsonObject();
        request.addProperty("agent", agent);
        request.add("record", record);
        var result = PhysicalJournalRuntime.inspectionResult();
        if (result != null) request.add("result", result);
        send(
                base,
                "/api/v1/physical/agent",
                bytes(request),
                apiKey.get().trim(),
                (code, body) -> {
                    if (code != 200
                            || !scope.equals(linkScope())
                            || mc.player == null
                            || !agent.equals(mc.player.getUUID().toString())) return true;
                    var reply = JsonParser.parseString(body).getAsJsonObject();
                    var receipt = reply.getAsJsonObject("receipt");
                    for (String key : java.util.List.of("id", "revision", "checksum")) {
                        if (!record.get(key).equals(receipt.get(key))) return true;
                    }
                    PhysicalJournalRuntime.mirrored(receipt, reply.has("decision"));
                    if (receipt.has("releasedLease")
                            && receipt.get("releasedLease").getAsString().equals(physicalLeaseFingerprint())) {
                        sortingToken = null;
                        sortingUntil = 0;
                    }
                    if (reply.has("decision")) PhysicalJournalRuntime.inspect(reply.getAsJsonObject("decision"));
                    if (result != null)
                        PhysicalJournalRuntime.acknowledgedInspection(
                                result.get("id").getAsString());
                    return true;
                },
                true);
        return true;
    }

    private String sortingToken, sortingScope = "";
    private long sortingUntil, sortingPoll;

    public boolean sortingEnabled() {
        return isActive() && dedicatedProfile() && sortEnabled.get();
    }

    public boolean sortingAllowed() {
        // Only the isolated, marker-protected Minecraft E2E harness can run the legacy local sorter.
        if (Boolean.getBoolean("wesort.e2e") && serverUrl.get().isBlank()) return true;
        return isActive()
                && mc.player != null
                && sortingToken != null
                && sortingScope.equals(sortingScope())
                && System.nanoTime() < sortingUntil;
    }

    public String physicalLeaseFingerprint() {
        return sortingToken == null || !sortingScope.equals(sortingScope())
                ? null
                : com.porter.wesort.data.ContainerIdentity.hash(sortingToken);
    }

    private String sortingScope() {
        return serverUrl.get() + "|" + Stash.currentServer() + "|" + (mc.player == null ? "" : mc.player.getUUID());
    }

    private boolean serviceSorting() {
        if (mc.player == null
                || mc.level == null
                || serverUrl.get().isBlank()
                || apiKey.get().isBlank()
                || inFlight) return false;
        long now = System.nanoTime();
        if (now < sortingPoll) return false;
        var sorter =
                WeSortAddon.sorter();
        boolean wants = isActive() && sorter != null && sorter.requiresSortingPermit();
        if (!wants && sortingToken == null) return false;
        if (!wants && (PhysicalJournalRuntime.awaitingMirror() || !InventorySync.ready() && !InventorySync.failed()))
            return false;
        if (!wants && PhysicalJournalRuntime.unfinished()) {
            PhysicalJournalRuntime.block("Sorting stopped before physical verification");
        }
        URI base = parse(serverUrl.get());
        if (base == null) return false;
        String scope = sortingScope();
        if (!scope.equals(sortingScope)) {
            sortingToken = null;
            sortingUntil = 0;
            sortingScope = scope;
        }
        JsonObject request = new JsonObject();
        request.addProperty("server", Stash.currentServer());
        request.addProperty("agent", mc.player.getUUID().toString());
        request.addProperty("token", sortingToken);
        boolean uncertain = InventorySync.failed() || PhysicalJournalRuntime.blocked();
        if (uncertain && sortingToken == null) return false;
        request.addProperty("action", uncertain ? "uncertain" : wants ? "acquire" : "release");
        sortingPoll = now + 2_000_000_000L;
        send(base, "/api/v1/automation", bytes(request), apiKey.get().trim(), (code, body) -> {
            if (code != 200) {
                sortingUntil = 0;
                return false;
            }
            if (uncertain) {
                sortingUntil = 0;
                return true;
            }
            if (!wants) {
                sortingToken = null;
                sortingUntil = 0;
                return true;
            }
            var reply = JsonParser.parseString(body).getAsJsonObject();
            sortingToken = reply.get("token").getAsString();
            // Measured from request start, not reply receipt. A delayed reply cannot extend a permit.
            sortingUntil = now + 5_000_000_000L;
            return true;
        });
        return true;
    }

    @Override
    public String getInfoString() {
        return ordersNote.isEmpty() ? status : status + " " + ordersNote;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (servicePhysicalJournal() || serviceSorting()) return;
        if (com.porter.wesort.compat.NavigationRuntime.paused()) {
            setStatus(com.porter.wesort.compat.NavigationRuntime.status());
            serviceBlockedControl();
            return;
        }
        if (PhysicalJournalRuntime.blocked()) {
            setStatus("physical operation needs reconciliation");
            serviceBlockedControl();
            return;
        }
        if (!InventorySync.ready()) {
            setStatus(InventorySync.failed() ? "inventory needs reconciliation" : "awaiting server inventory");
            serviceBlockedControl();
            return;
        }
        if (mc.level == null || mc.player == null) return;

        driveSorter();
        updateOrdersNote();

        // No server set (the default since 0.4.0-dev.8): the addon works without one, so this is a status, not a
        // warning
        if (serverUrl.get().isBlank()) {
            setStatus("server URL not set");
            return;
        }

        String key = apiKey.get().trim();
        if (key.isEmpty()) {
            setStatus("no key");
            if (!warnedKey) {
                warnedKey = true;
                warning("No API key set. Retrieve it with agent-key on the Manager host and set it here.");
            }
            return;
        }

        URI base = parse(serverUrl.get());
        if (base == null) {
            setStatus("bad url");
            if (!warnedUrl) {
                warnedUrl = true;
                warning("The server-url must be a http or https address without a query, like http://127.0.0.1:8765.");
            }
            return;
        }
        warnedUrl = false;

        // A changed setting is looked at right away
        if (seenInterval != scanInterval.get() || seenInclude != includeExcluded.get()) {
            seenInterval = scanInterval.get();
            seenInclude = includeExcluded.get();
            scanTicks = 0;
        }

        if (scanTicks-- <= 0) {
            scanTicks = seenInterval * 20;
            scan();
        }
        if (inFlight) return;
        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;

        // Orders come first: the server should hear how the bot is doing, and a claim is cheap
        if (orderStep(base, key, now)) return;

        if (current == null) return;
        if (serviceControl(base, key, now)) return;
        StashIndex.Built built = current;
        String id = built.server() + "|" + built.stash() + "|" + built.hash();
        updateDelivery(now, built.player());

        if (needIndex || !id.equals(lastPushed)) {
            lastHeartbeat = now;
            send(base, "/api/v1/index", built.json(), key, (code, body) -> {
                if (com.porter.wesort.compat.ObservationRuntime.reply(code, body)) {
                    StashSorter sorter = WeSortAddon.sorter();
                    int count = sorter == null ? -1 : sorter.rescan();
                    rescanning = count >= 0;
                    current = null;
                    scanTicks = 0;
                    needIndex = true;
                    return false;
                }
                if (code != 200) return false;
                lastPushed = id;
                needIndex = false;
                return true;
            });
        } else if (now - lastHeartbeat >= HEARTBEAT_MS || !deliverySig().equals(sentDelivery)) {
            lastHeartbeat = now;
            String sig = deliverySig();
            long done = rescanDone;
            send(
                    base,
                    "/api/v1/heartbeat",
                    StashIndex.heartbeat(
                            built,
                            deliveryPos(),
                            orderBlocker(),
                            done,
                            rescanBlocked(),
                            namingInfo(),
                            craftingInfo(),
                            anvilSource(),
                            deliveryStorage(),
                            tableSource(),
                            deliveryHash),
                    key,
                    (code, body) -> {
                        if (code != 200) return false;
                        sentDelivery = sig;
                        if (done >= 0 && rescanDone == done) rescanDone = -1;
                        readRescan(body);
                        needIndex = StashIndex.needsIndex(body);
                        deliverySync.onHeartbeatReply(body);
                        readOrders(body);
                        return true;
                    });
        } else if (deliveryPending != null && deliverySync.canSend(now)) {
            sendDelivery(base, key, now);
        }
    }

    private void serviceBlockedControl() {
        if (inFlight || current == null || mc.player == null || mc.level == null) return;
        var sorter = WeSortAddon.sorter();
        var stash = sorter == null ? null : sorter.currentStash();
        if (stash == null
                || !current.server().equals(stash.server)
                || !current.stash().equals(stash.name)
                || !current.player().equals(mc.player.getName().getString())) return;
        String key = apiKey.get().trim();
        URI base = parse(serverUrl.get());
        long now = System.currentTimeMillis();
        if (base != null && !key.isEmpty() && now >= nextAttemptAt) serviceControl(base, key, now);
    }

    private record ControlScope(URI manager, String server, String stash, String dimension, java.util.UUID player) {
        String botId() {
            return java.util
                    .UUID
                    .nameUUIDFromBytes((server + "\u0000" + player).getBytes(StandardCharsets.UTF_8))
                    .toString();
        }
    }

    private record PendingManifest(
            String order, String server, String stash, String dimension, int packages, String hash) {
        boolean matches(ControlScope scope) {
            return server.equals(scope.server()) && stash.equals(scope.stash()) && dimension.equals(scope.dimension());
        }

        PendingManifest withHash(String value) {
            return new PendingManifest(order, server, stash, dimension, packages, value);
        }
    }

    private boolean serviceControl(URI base, String key, long now) {
        var observed =
                new ControlScope(base, current.server(), current.stash(), StashIndex.dimension(), mc.player.getUUID());
        if (!observed.equals(controlScope)) {
            if (controlScope == null
                    || !observed.manager().equals(controlScope.manager())
                    || !observed.botId().equals(controlScope.botId())) {
                controlSession = randomHex(32);
            }
            controlScope = observed;
            controlRegistered = false;
            sentControlValues = null;
            controlResults = null;
            lastControlResult = null;
            nextControlAt = 0;
        }
        if (now < nextControlAt) return false;
        nextControlAt = now + 2_000;
        var request = new JsonObject();
        request.addProperty("botId", observed.botId());
        request.addProperty("controlSession", controlSession);
        if (!controlRegistered) {
            request.addProperty("player", current.player());
            var binding = new JsonObject();
            binding.addProperty("server", observed.server());
            binding.addProperty("stash", observed.stash());
            binding.addProperty("dimension", observed.dimension());
            binding.addProperty("agent", observed.player().toString());
            request.add("manifestScope", binding);
            var plugin = new JsonObject();
            plugin.addProperty("id", "wesort");
            net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("wesort")
                    .ifPresent(mod -> plugin.addProperty(
                            "version", mod.getMetadata().getVersion().getFriendlyString()));
            request.add("plugin", plugin);
            var runtime = new JsonObject();
            runtime.addProperty("mc", "26.3");
            request.add("runtime", runtime);
            var capabilities = new JsonArray();
            capabilities.add("settings");
            capabilities.add("activity");
            capabilities.add("warehouse-scan");
            capabilities.add("warehouse-sort");
            capabilities.add("pack");
            capabilities.add("stage");
            request.add("caps", capabilities);
            var scopes = new JsonArray();
            scopes.add("plugin");
            request.add("scopes", scopes);
            send(
                    base,
                    "/api/v1/bot/hello",
                    bytes(request),
                    key,
                    (code, body) -> {
                        controlRegistered = false;
                        if (code == 200) {
                            var hello = JsonParser.parseString(body).getAsJsonObject();
                            String epoch = text(hello, "controlEpoch");
                            if (hello.get("protocol").getAsInt() == 2 && epoch != null) {
                                if (!epoch.equals(controlEpoch)) lastControlResult = null;
                                controlEpoch = epoch;
                                controlRegistered = true;
                                sentControlValues = null;
                            }
                            // Clean client: adopt the first Manager-announced stash when no
                            // local stash is configured, so .wesort-connect alone is enough.
                            if (hello.has("stashes") && hello.get("stashes").isJsonArray()) {
                                for (var el : hello.getAsJsonArray("stashes")) {
                                    if (!el.isJsonPrimitive()
                                            || !el.getAsJsonPrimitive().isString()) continue;
                                    StashSorter sorter = WeSortAddon.sorter();
                                    if (sorter != null && sorter.adoptAnnouncedStash(el.getAsString())) break;
                                }
                            }
                        }
                        if (!controlRegistered) nextControlAt = now + 15_000;
                        return controlRegistered;
                    },
                    true);
            return true;
        }
        if (controlResults != null) {
            request.add("results", controlResults);
            send(
                    base,
                    "/api/v1/bot/results",
                    bytes(request),
                    key,
                    (code, body) -> {
                        if (code == 200) controlResults = null;
                        else if (code == 409) {
                            controlRegistered = false;
                            controlResults = null;
                        }
                        return code == 200;
                    },
                    true);
            return true;
        }
        var pending = pendingManifest;
        if (pending != null) {
            if (!pending.matches(observed)) {
                pendingManifest = null;
                return false;
            }
            request.addProperty("controlEpoch", controlEpoch);
            request.addProperty("orderId", pending.order());
            boolean acknowledging = pending.hash() != null;
            if (acknowledging) request.addProperty("manifestHash", pending.hash());
            send(
                    base,
                    acknowledging ? "/api/v1/bot/manifest/ack" : "/api/v1/bot/manifest",
                    bytes(request),
                    key,
                    (code, body) -> {
                        if (code == 409) {
                            controlRegistered = false;
                            if (acknowledging) pendingManifest = pending.withHash(null);
                            return false;
                        }
                        if (code != 200 && code != 404) return false;
                        pendingManifest = null;
                        if (code == 404) {
                            controlRegistered = false;
                            return false;
                        }
                        var reply = JsonParser.parseString(body).getAsJsonObject();
                        if (!pending.order().equals(text(reply, "orderId"))) return false;
                        if (acknowledging) {
                            if (!pending.hash().equals(text(reply, "manifestHash"))
                                    || reply.get("acknowledgedAt").getAsLong() <= 0) return false;
                            if (orderChat.get())
                                info(
                                        "Manager confirmed the staging manifest for %s (%d shulker packages).",
                                        pending.order(), pending.packages());
                            return true;
                        }
                        var manifest = reply.getAsJsonObject("manifest");
                        var hash = text(manifest, "hash");
                        var packages = manifest.getAsJsonArray("packages");
                        if (manifest.get("version").getAsInt() != 1
                                || hash == null
                                || !hash.matches("[0-9a-f]{64}")
                                || packages.size() != pending.packages()) return false;
                        var ids = new HashSet<String>();
                        for (var value : packages) {
                            var entry = value.getAsJsonObject();
                            String packageId = text(entry, "packageId");
                            String fingerprint = text(entry, "stackFingerprint");
                            if (packageId == null
                                    || !packageId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                                    || fingerprint == null
                                    || !fingerprint.matches("[a-f0-9]{64}")
                                    || !ids.add(packageId)) return false;
                        }
                        pendingManifest = pending.withHash(hash);
                        return true;
                    },
                    true);
            return true;
        }

        var values = new JsonObject();
        var engine = WeSortAddon.sorter();
        if (engine != null) {
            for (var entry : engine.managerValues().entrySet()) values.add(entry.getKey(), entry.getValue());
        }
        values.addProperty(scanInterval.name, scanInterval.get());
        values.addProperty(sortEnabled.name, sortEnabled.get());
        values.addProperty(acceptOrders.name, acceptOrders.get());
        values.addProperty(includeExcluded.name, includeExcluded.get());
        values.addProperty(useNamedShulkers.name, useNamedShulkers.get());
        values.addProperty(autoExpandDelivery.name, autoExpandDelivery.get());
        values.addProperty(enableCrafting.name, enableCrafting.get());
        values.addProperty(craftMaxPerOrder.name, craftMaxPerOrder.get());
        values.addProperty(craftMaxOpsPerTask.name, craftMaxOpsPerTask.get());
        values.addProperty(craftMaxPerHour.name, craftMaxPerHour.get());
        values.addProperty(freshMinutes.name, freshMinutes.get());
        values.addProperty(orderChat.name, orderChat.get());
        values.addProperty(orderIntakePaused.name, orderIntakePaused.get());
        String valuesHash = sha256Hex(values.toString().getBytes(StandardCharsets.UTF_8));
        if (!valuesHash.equals(sentControlValues)) {
            var schema = new JsonArray();
            if (engine != null) {
                for (var entry : engine.managerSchema()) schema.add(entry);
            }
            schema.add(controlSetting(scanInterval.name, "Scan interval (seconds)", "int", 2, 60));
            schema.add(controlSetting(sortEnabled.name, "Enable physical sorting", "bool", null, null));
            schema.add(controlSetting(acceptOrders.name, "Accept warehouse orders", "bool", null, null));
            schema.add(controlSetting(includeExcluded.name, "Index excluded containers", "bool", null, null));
            schema.add(controlSetting(useNamedShulkers.name, "Allow named shulkers in orders", "bool", null, null));
            schema.add(controlSetting(autoExpandDelivery.name, "Expand delivery storage", "bool", null, null));
            schema.add(controlSetting(enableCrafting.name, "Enable crafting", "bool", null, null));
            schema.add(controlSetting(craftMaxPerOrder.name, "Crafting boxes per order", "int", 1, 54));
            schema.add(controlSetting(craftMaxOpsPerTask.name, "Crafting operations per task", "int", 8, 512));
            schema.add(controlSetting(craftMaxPerHour.name, "Crafting boxes per hour", "int", 1, 216));
            schema.add(controlSetting(freshMinutes.name, "Stock freshness (minutes)", "int", 0, 30));
            schema.add(controlSetting(orderChat.name, "Order chat messages", "bool", null, null));
            schema.add(controlSetting(orderIntakePaused.name, "Pause new warehouse orders", "bool", null, null));
            request.addProperty("scope", "plugin");
            request.addProperty("schemaHash", sha256Hex(schema.toString().getBytes(StandardCharsets.UTF_8)));
            request.addProperty("valuesHash", valuesHash);
            request.add("schema", schema);
            request.add("values", values);
            send(
                    base,
                    "/api/v1/bot/state",
                    bytes(request),
                    key,
                    (code, body) -> {
                        if (code == 200) sentControlValues = valuesHash;
                        else if (code == 409) controlRegistered = false;
                        return code == 200;
                    },
                    true);
            return true;
        }
        request.addProperty("controlEpoch", controlEpoch);
        request.add("activity", controlActivity());
        send(
                base,
                "/api/v1/bot/next",
                bytes(request),
                key,
                (code, body) -> {
                    // Replies are dispatched on the client thread after the transport verifies their signature.
                    if (code == 409) {
                        controlRegistered = false;
                        sentControlValues = null;
                    }
                    if (code != 200
                            || mc.level == null
                            || mc.player == null
                            || !base.equals(parse(serverUrl.get()))
                            || !key.equals(apiKey.get().trim())
                            || !observed.player().equals(mc.player.getUUID())
                            || !observed.server().equals(Stash.currentServer())
                            || current == null
                            || !observed.stash().equals(current.stash())
                            || !observed.dimension().equals(StashIndex.dimension())) return false;
                    var poll = JsonParser.parseString(body).getAsJsonObject();
                    if (poll.has("pendingManifest")) {
                        var hint = poll.getAsJsonObject("pendingManifest");
                        String orderId = text(hint, "orderId");
                        int count = hint.get("packageCount").getAsInt();
                        if (orderId != null
                                && orderId.matches("o-[0-9a-f]{12}")
                                && count > 0
                                && count <= Order.MAX_BINS) {
                            pendingManifest = new PendingManifest(
                                    orderId, observed.server(), observed.stash(), observed.dimension(), count, null);
                        }
                    }
                    var jobs = poll.getAsJsonArray("jobs");
                    if (jobs.size() != 1 || !jobs.get(0).isJsonObject()) return jobs.isEmpty();
                    var job = jobs.get(0).getAsJsonObject();
                    var result = receiptFor(job, values);
                    if (result == null) return false;
                    controlResults = new JsonArray();
                    controlResults.add(result);
                    return true;
                },
                true);
        return true;
    }

    private JsonObject controlActivity() {
        var sorter = WeSortAddon.sorter();
        var runner = sorter == null ? null : sorter.orders();
        boolean needsReconciliation = InventorySync.failed() || PhysicalJournalRuntime.blocked();
        String incident = null;
        String task;
        if (mc.player.getHealth() <= 0) {
            task = "Player died";
            incident = "DEATH";
        } else if (needsReconciliation) {
            task = "Needs physical reconciliation";
            incident = "INVENTORY_UNVERIFIED";
        } else if (com.porter.wesort.compat.NavigationRuntime.paused()) task = "Navigation paused";
        else if (!InventorySync.ready()) task = "Awaiting server inventory";
        else if (runner != null && runner.running()) task = "Packing " + runner.info();
        else if (orderIntakePaused.get()) task = "New orders paused";
        else if (sorter != null && acceptOrders.get() && "no free inventory slot".equals(sorter.orderBlocker())) {
            task = "No free inventory slot";
            incident = "NO_CAPACITY";
        } else task = "Monitoring stock";
        var work = new JsonObject();
        work.addProperty("title", task);
        work.addProperty("level", incident == null ? "info" : "warn");
        var tasks = new JsonArray();
        tasks.add(work);
        var activity = new JsonObject();
        activity.add("current", tasks);
        if (incident != null) activity.addProperty("incident", incident);
        return activity;
    }

    private static JsonObject controlSetting(String path, String label, String type, Integer min, Integer max) {
        var value = new JsonObject();
        value.addProperty("path", path);
        value.addProperty("label", label);
        value.addProperty("type", type);
        if (min != null) value.addProperty("min", min);
        if (max != null) value.addProperty("max", max);
        return value;
    }

    private JsonObject applyControlSetting(JsonObject job, JsonObject published) {
        String id = text(job, "id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) return null;
        var result = new JsonObject();
        result.addProperty("id", id);
        String path = text(job, "path");
        JsonElement newValue = job.get("value");
        if (!"set".equals(text(job, "type"))
                || !"plugin".equals(text(job, "scope"))
                || path == null
                || !published.has(path)
                || !(newValue instanceof JsonPrimitive value)) {
            result.addProperty("ok", false);
            result.addProperty("error", "Unsupported setting");
            return result;
        }
        boolean applied;
        if (path.equals(scanInterval.name)) applied = changeInteger(scanInterval, job.get("expect"), value, 2, 60);
        else if (path.equals(freshMinutes.name)) applied = changeInteger(freshMinutes, job.get("expect"), value, 0, 30);
        else if (path.equals(orderIntakePaused.name)) {
            boolean before = orderIntakePaused.get();
            applied = changeBoolean(orderIntakePaused, job.get("expect"), value);
            if (applied && before != value.getAsBoolean()) {
                info(
                        value.getAsBoolean()
                                ? "Manager paused new warehouse orders."
                                : "Manager resumed new warehouse orders.");
            }
        } else if (path.equals(sortEnabled.name)) applied = changeBoolean(sortEnabled, job.get("expect"), value);
        else if (path.equals(acceptOrders.name)) applied = changeBoolean(acceptOrders, job.get("expect"), value);
        else if (path.equals(includeExcluded.name)) applied = changeBoolean(includeExcluded, job.get("expect"), value);
        else if (path.equals(useNamedShulkers.name)) applied = changeBoolean(useNamedShulkers, job.get("expect"), value);
        else if (path.equals(autoExpandDelivery.name)) applied = changeBoolean(autoExpandDelivery, job.get("expect"), value);
        else if (path.equals(enableCrafting.name)) applied = changeBoolean(enableCrafting, job.get("expect"), value);
        else if (path.equals(craftMaxPerOrder.name)) applied = changeInteger(craftMaxPerOrder, job.get("expect"), value, 1, 54);
        else if (path.equals(craftMaxOpsPerTask.name)) applied = changeInteger(craftMaxOpsPerTask, job.get("expect"), value, 8, 512);
        else if (path.equals(craftMaxPerHour.name)) applied = changeInteger(craftMaxPerHour, job.get("expect"), value, 1, 216);
        else if (path.equals(orderChat.name)) applied = changeBoolean(orderChat, job.get("expect"), value);
        else if (path.startsWith("sorter.")) {
            var engine = WeSortAddon.sorter();
            applied = engine != null && engine.applyManagerSetting(path, job.get("expect"), value);
        } else applied = false;
        result.addProperty("ok", applied);
        if (applied) result.add("value", value);
        else result.addProperty("error", "Setting changed or value is invalid");
        return result;
    }

    private JsonObject receiptFor(JsonObject job, JsonObject values) {
        String lease = text(job, "lease");
        var sequence = job.get("sequence");
        var expiresAt = job.get("expiresAt");
        if (lease == null
                || !lease.matches("[a-f0-9-]{36}")
                || !(sequence instanceof JsonPrimitive order)
                || !order.isNumber()
                || !(expiresAt instanceof JsonPrimitive deadline)
                || !deadline.isNumber()
                || deadline.getAsLong() <= System.currentTimeMillis()) return null;
        if (lastControlResult != null) {
            if (lease.equals(text(lastControlResult, "lease")) && sequence.equals(lastControlResult.get("sequence")))
                return lastControlResult;
            if (order.getAsLong() <= lastControlResult.get("sequence").getAsLong()) return null;
        }
        var result = applyControlSetting(job, values);
        if (result == null) return null;
        result.addProperty("lease", lease);
        result.add("sequence", sequence);
        lastControlResult = result;
        return result;
    }

    private static boolean changeInteger(
            Setting<Integer> setting, JsonElement expected, JsonPrimitive newValue, int minimum, int maximum) {
        if (!newValue.isNumber() || !matchesSetting(expected, new JsonPrimitive(setting.get()))) return false;
        try {
            int number = newValue.getAsBigDecimal().intValueExact();
            return number >= minimum && number <= maximum && setting.set(number);
        } catch (ArithmeticException | NumberFormatException malformed) {
            return false;
        }
    }

    private static boolean matchesSetting(JsonElement expected, JsonElement observed) {
        return expected == null || expected.equals(observed);
    }

    private static boolean changeBoolean(Setting<Boolean> setting, JsonElement expected, JsonPrimitive value) {
        return value.isBoolean()
                && matchesSetting(expected, new JsonPrimitive(setting.get()))
                && setting.set(value.getAsBoolean());
    }

    private static String text(JsonObject body, String field) {
        JsonElement element = body.get(field);
        return element instanceof JsonPrimitive value && value.isString() ? value.getAsString() : null;
    }

    private void resetDelivery() {
        deliverySync.reset();
        deliveryStamp = 0;
        deliveryFor = "";
        deliveryHash = null;
        deliveryPending = null;
    }

    /**
     * The delivery contents report: built when a delivery chest was read (at most every 5 seconds) or the server asked, and kept to be
     * sent when its hash differs from the last one the server took, or the server asked. Client thread. Never touches the index.
     */
    private void updateDelivery(long now, String player) {
        StashSorter sorter = WeSortAddon.sorter();
        Stash stash = sorter == null ? null : sorter.currentStash();
        // No world or no stash yet: nothing is known, so nothing changes
        if (stash == null || mc.level == null) return;
        String owner = stash.server + "|" + stash.name;
        if (!owner.equals(deliveryFor)) {
            resetDelivery();
            deliveryFor = owner;
        }
        long stamp = StashIndex.deliveryStamp(stash);
        if (stamp != deliveryStamp) {
            deliveryStamp = stamp;
            deliverySync.changed();
        }
        if (!deliverySync.due(now)) return;
        com.porter.wesort.stash.DeliveryContents.Built report;
        try {
            report = StashIndex.deliveryReport(stash, player);
        } catch (RuntimeException e) {
            WeSortAddon.LOG.warn("wesort-stash-manager: couldn't build the delivery contents: {}", e.toString());
            report = null;
        }
        if (report == null) {
            // Only a stash with no delivery container at all has nothing to report; one not resolved yet is looked at
            // again
            if (stash.deliveryPos != null) return;
            deliverySync.none(now);
            deliveryHash = null;
            deliveryPending = null;
            return;
        }
        deliveryHash = report.hash();
        deliveryPending = deliverySync.built(report.hash(), now) ? report : null;
    }

    /**
     * Sends the delivery report. It is isolated: whatever goes wrong (offline, a bad or missing signature, 404, 5xx) is the report's own
     * business, tried again on its own timer, and never touches the shared backoff or the module's status.
     */
    private void sendDelivery(URI base, String key, long now) {
        com.porter.wesort.stash.DeliveryContents.Built report = deliveryPending;
        send(
                base,
                "/api/v1/delivery",
                report.json(),
                key,
                (code, body) -> {
                    if (code == 200) {
                        deliverySync.sent(report.hash());
                        if (deliveryLog.sent(System.currentTimeMillis()))
                            WeSortAddon.LOG.info(
                                    "wesort-stash-manager: {}",
                                    com.porter.wesort.stash.DeliveryContents.SentLog.line(report.chests()));
                        if (deliveryPending == report) deliveryPending = null;
                        return true;
                    }
                    // Refused for good: that hash is not sent again, even when the server asks, until the contents
                    // change
                    if (code == 400 || code == 413) {
                        WeSortAddon.LOG.warn(
                                "wesort-stash-manager: the server refused the delivery contents ({}): {}", code, body);
                        deliverySync.rejected(report.hash());
                        deliveryLog.failed();
                        if (deliveryPending == report) deliveryPending = null;
                        return true;
                    }
                    deliverySync.retryLater(System.currentTimeMillis());
                    deliveryLog.failed();
                    return false;
                },
                true);
    }

    /**
     * Reports the order to the server, or claims a new one. Cancels asked for by the server are passed on to the sorter.
     * @return true when a request was sent
     */
    private boolean orderStep(URI base, String key, long now) {
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter == null) return false;
        OrderRunner runner = sorter.orders();
        runner.chat(orderChat.get());

        Stash stash = sorter.currentStash();
        String server = stash == null ? "" : stash.server;
        String name = stash == null ? "" : stash.name;

        // An order the sorter could not take
        if (rejectedId != null) {
            String id = rejectedId, why = rejectedWhy, end = rejectedStatus;
            JsonObject o = new JsonObject();
            o.addProperty("status", end);
            o.addProperty("message", StashIndex.clamp(why, 300));
            send(base, "/api/v1/orders/" + id + "/status", orderReportBytes(o), key, (code, body) -> {
                // 409: the server already has it finished; another refusal is not tried again
                if (code >= 400 && code < 500 && code != 401 && code != 409)
                    WeSortAddon.LOG.warn(
                            "wesort-stash-manager: the server refused the status of order {} ({}): {}", id, code, body);
                else if (code != 200 && code != 409) return false;
                if (rejectedId != null && rejectedId.equals(id)) rejectedId = rejectedWhy = null;
                if (end.equals("cancelled")) runner.dropSaved(null);
                return true;
            });
            return true;
        }

        // Taking orders was switched off with one in hand: it is parked, and resumed when it is switched on again
        if (!acceptOrders.get() && runner.running()) runner.park();

        OrderRunner.Report report = runner.report();
        String blocker = acceptOrders.get() ? sorter.orderBlocker() : "off";

        // What the bot is doing, when it changed
        if (report != null) {
            String sig = report.id() + "|" + report.status() + "|" + report.phase() + "|" + report.done() + "|"
                    + report.message();
            if (!sig.equals(lastStatusSig) && (report.terminal() || now - lastStatusAt >= STATUS_MS)) {
                lastStatusAt = now;
                boolean minimal = report.id().equals(minimalFor);
                send(
                        base,
                        "/api/v1/orders/" + report.id() + "/status",
                        orderReportBytes(minimal ? minimalJson(report) : statusJson(report, runner)),
                        key,
                        (code, body) -> {
                            // A report the server refuses (4xx other than a wrong key or "already finished"): said
                            // again in short once,
                            // then given up on, so the bot is never stuck on one it can't send
                            if (code >= 400 && code < 500 && code != 401 && code != 409) {
                                WeSortAddon.LOG.warn(
                                        "wesort-stash-manager: the server refused the status of order {} ({}): {}",
                                        report.id(),
                                        code,
                                        body);
                                if (!minimal) {
                                    minimalFor = report.id();
                                    return false;
                                }
                                minimalFor = null;
                                if (report.terminal()) {
                                    runner.ack(report.id());
                                    lastStatusSig = "";
                                } else lastStatusSig = sig;
                                return true;
                            }
                            // 409: the order is already finished or moved on, nothing more to say about it
                            if (code != 200 && code != 409) return false;
                            minimalFor = null;
                            lastStatusSig = sig;
                            if (report.terminal()) {
                                runner.ack(report.id());
                                lastStatusSig = "";
                            }
                            return true;
                        });
                return true;
            }
            return false;
        }

        // Nothing in hand: take the oldest pending order, or the one still held from before a restart
        if (acceptOrders.get()
                && !orderIntakePaused.get()
                && blocker == null
                && stash != null
                && now - lastClaim >= CLAIM_MS
                && (pendingOrders > 0 || runner.resumeId() != null)) {
            lastClaim = now;
            JsonObject o = new JsonObject();
            o.addProperty("server", server);
            o.addProperty("stash", name);
            send(base, "/api/v1/orders/claim", bytes(o), key, (code, body) -> {
                if (code == 204) {
                    pendingOrders = 0;
                    runner.dropSaved(null);
                    return true;
                }
                if (code != 200) return false;
                takeOrder(sorter, runner, body);
                return true;
            });
            return true;
        }
        return false;
    }

    private int[] deliveryPos() {
        StashSorter sorter = WeSortAddon.sorter();
        Stash.Box d = sorter == null ? null : sorter.orders().deliveryBox();
        return d == null ? null : new int[] {d.pos.getX(), d.pos.getY(), d.pos.getZ()};
    }

    /** What the heartbeat says about naming: whether an anvil is ready, and the bot's XP level (-1: unknown). */
    private int[] namingInfo() {
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter == null) return new int[] {0, -1};
        return new int[] {sorter.anvilReady() ? 1 : 0, sorter.xpLevels()};
    }

    /** Where the anvil in use came from, for the heartbeat: "set", "found", "placed" or "none". */
    private String anvilSource() {
        StashSorter sorter = WeSortAddon.sorter();
        return sorter == null ? "none" : sorter.anvilSource();
    }

    /** Where the crafting table in use came from, for the heartbeat: "set", "found", "placed", "crafted" or "none". */
    private String tableSource() {
        StashSorter sorter = WeSortAddon.sorter();
        return sorter == null ? "none" : sorter.craftingTableSource();
    }

    /** What the heartbeat says about crafting: whether it is on, and whether a crafting table is ready. */
    private int[] craftingInfo() {
        StashSorter sorter = WeSortAddon.sorter();
        return new int[] {enableCrafting.get() ? 1 : 0, sorter != null && sorter.craftingTableReady() ? 1 : 0};
    }

    /** Why a rescan can't start yet, or null. */
    private String rescanBlocked() {
        StashSorter sorter = WeSortAddon.sorter();
        return sorter == null || sorter.currentStash() == null ? "no stash" : null;
    }

    /** The website asked for a rescan: every container is marked to be read again, and the server is told at once. */
    private void readRescan(String body) {
        try {
            JsonObject in = JsonParser.parseString(body).getAsJsonObject();
            readDirective(in);
            if (!in.has("rescan") || in.get("rescan").isJsonNull()) return;
            long id = in.get("rescan").getAsLong();
            // The server sends an id until it has our rescanDone, so one handled and waiting to be acknowledged is not
            // done twice
            if (id == rescanDone) return;
            boolean duplicate = id == lastRescanId && System.currentTimeMillis() - lastRescanAt < 120_000;
            rescanDone = id;
            if (duplicate) return;
            lastRescanId = id;
            lastRescanAt = System.currentTimeMillis();

            StashSorter sorter = WeSortAddon.sorter();
            int count = sorter == null ? -1 : sorter.rescan();
            rescanning = count >= 0;
            if (count < 0) warning("Rescan requested from the website, but no stash is loaded.");
            else info("Rescan requested from the website: re-reading %d containers.", count);
        } catch (RuntimeException ex) {
            WeSortAddon.LOG.warn("wesort-stash-manager: couldn't read the rescan request", ex);
        }
    }

    /** A Manager directive from a heartbeat reply: region, delivery, anvil, workbench. Only applied to the matching stash. */
    private void readDirective(JsonObject in) {
        try {
            if (!in.has("directive") || in.get("directive").isJsonNull()) return;
            JsonObject d = in.getAsJsonObject("directive");
            StashSorter sorter = WeSortAddon.sorter();
            if (sorter == null || sorter.currentStash() == null) return;
            String name = d.has("stash") && d.get("stash").isJsonPrimitive()
                    ? d.get("stash").getAsString()
                    : null;
            if (name == null || !name.equals(sorter.currentStash().name)) return;
            long rev = d.has("revision") && d.get("revision").isJsonPrimitive()
                    ? d.get("revision").getAsLong()
                    : -1;
            // Never move the physical workspace or delivery position mid-uncertainty: pause
            // and require reconciliation first. The directive is revision-pinned, so the next
            // heartbeat after recovery applies exactly what was skipped.
            if (com.porter.wesort.compat.PhysicalJournalRuntime.blocked() || !InventorySync.ready()) {
                info("Manager directive (revision %d) deferred: physical reconciliation required first.", rev);
                return;
            }
            sorter.applyDirective(d, rev);
        } catch (RuntimeException ex) {
            WeSortAddon.LOG.warn("wesort-stash-manager: couldn't read the Manager directive", ex);
        }
    }

    /** Why the bot can't take orders right now, or null. */
    private String orderBlocker() {
        if (!acceptOrders.get()) return "accept-orders off";
        if (orderIntakePaused.get()) return "new warehouse orders paused by Manager";
        StashSorter sorter = WeSortAddon.sorter();
        return sorter == null ? "sorter off" : sorter.orderBlocker();
    }

    /** What the heartbeat says about delivery; when it changes, another heartbeat goes out at once. */
    private String storageSig() {
        StashIndex.DeliveryStorage d = deliveryStorage();
        return d == null
                ? "-"
                : d.autoExpand() + "/" + d.chests() + "/" + d.full() + "/" + d.canExpand() + "/" + d.blocked();
    }

    private String deliverySig() {
        int[] d = deliveryPos();
        return (d == null ? "none" : d[0] + "," + d[1] + "," + d[2]) + "|" + (namingInfo()[0]) + anvilSource() + "|"
                + craftingInfo()[0] + craftingInfo()[1] + tableSource() + "|" + orderBlocker() + "|" + rescanDone + "|"
                + rescanBlocked() + "|" + storageSig();
    }

    /** The module is on and taking orders. */
    boolean takingOrders() {
        return isActive() && acceptOrders.get();
    }

    int freshMinutes() {
        return freshMinutes.get();
    }

    boolean useNamedShulkers() {
        return useNamedShulkers.get();
    }

    /** auto-expand-delivery. */
    boolean autoExpandDelivery() {
        return autoExpandDelivery.get();
    }

    /** What the heartbeat says about the delivery storage, or null when no delivery container is set. */
    private StashIndex.DeliveryStorage deliveryStorage() {
        StashSorter sorter = WeSortAddon.sorter();
        return sorter == null ? null : sorter.deliveryStorage();
    }

    /** enable-crafting. */
    boolean craftingEnabled() {
        return enableCrafting.get();
    }

    /** The items crafting never uses, as ids like minecraft:beacon. */
    Set<String> craftNeverUse() {
        Set<String> out = new HashSet<>();
        for (Item item : craftNeverUse.get())
            out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(item)
                    .toString());
        return out;
    }

    com.porter.wesort.craft.CraftPlanner.Caps craftCaps() {
        return new com.porter.wesort.craft.CraftPlanner.Caps(
                craftMaxPerOrder.get(), craftMaxOpsPerTask.get(), craftMaxPerHour.get());
    }

    /** Starts or stops the sorter's loop for this module: when it takes orders, or containers wait to be read. */
    private void driveSorter() {
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter == null) return;
        // The bot walks only when asked: orders are accepted, or a website rescan is under way. Containers a player
        // opened
        // are not read by the bot on their own
        if (rescanning && !sorter.needsReading()) rescanning = false;
        boolean want = isActive()
                && dedicatedProfile()
                && sorter.currentStash() != null
                && (acceptOrders.get() || rescanning || sortEnabled.get());
        sorter.drive(want);
    }

    /** The order progress, or why no order is taken, for the info string. Every tick. */
    private void updateOrdersNote() {
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter == null) {
            ordersNote = "";
            return;
        }
        OrderRunner runner = sorter.orders();
        String info = runner.info();
        Stash.Box delivery = runner.deliveryBox();
        if (sorter.returningLeftovers()) {
            ordersNote = "(returning leftovers)";
            return;
        }
        if (acceptOrders.get() && info != null && runner.running()) {
            ordersNote = "(" + info + ")";
            return;
        }
        int stale = sorter.staleCount();
        if (stale > 0 && (sorter.driving() || sorter.isActive())) {
            ordersNote = "(re-reading " + stale + ")";
            return;
        }
        String blocker = acceptOrders.get() ? sorter.orderBlocker() : null;
        if (blocker != null && !blocker.equals("busy") && !blocker.equals("no delivery box"))
            ordersNote = "(" + blocker + ")";
        else if (acceptOrders.get() && info != null) ordersNote = "(" + info + ")";
        else if (sorter.driving() && delivery != null)
            ordersNote = "(idle, delivery " + delivery.pos.getX() + "," + delivery.pos.getY() + ","
                    + delivery.pos.getZ() + ")";
        else
            ordersNote = delivery == null
                    ? "(no delivery box)"
                    : "(delivery " + delivery.pos.getX() + "," + delivery.pos.getY() + "," + delivery.pos.getZ() + ")";
    }

    private void takeOrder(StashSorter sorter, OrderRunner runner, String body) {
        Order order = null;
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("order"))
                order = Order.parse(e.getAsJsonObject().getAsJsonObject("order"));
        } catch (RuntimeException e) {
            // Treated as no usable order below
        }
        if (order == null) {
            // Left alone it would be handed to us again forever: say it failed, if there is an id to say it with
            String id = null;
            try {
                JsonElement e = JsonParser.parseString(body);
                if (e.isJsonObject() && e.getAsJsonObject().has("order")) {
                    JsonObject o = e.getAsJsonObject().getAsJsonObject("order");
                    if (o.has("id")) id = o.get("id").getAsString();
                }
            } catch (RuntimeException e) {
                // No id to report
            }
            WeSortAddon.LOG.warn("wesort-stash-manager: the server sent an order that could not be read ({})", id);
            if (id != null && !id.isEmpty()) {
                rejectedId = id;
                rejectedWhy = "couldn't read order";
                rejectedStatus = "failed";
            }
            return;
        }

        runner.dropSaved(order.id);
        String why = runner.offer(order);
        if (why == null) {
            sorter.orderTaken();
            pendingOrders = Math.max(0, pendingOrders - 1);
        } else {
            rejectedId = order.id;
            rejectedWhy = why;
            rejectedStatus = "failed";
            WeSortAddon.LOG.warn("wesort-stash-manager: rejected order {}: {}", order.id, why);
            if (orderChat.get()) warning("Order %s can't be done: %s.", order.id, why);
        }
    }

    /** The orders part of a heartbeat reply: how many wait, and which of ours were cancelled. */
    private void readOrders(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("orders")) {
                pendingOrders = 0;
                return;
            }
            JsonObject orders = e.getAsJsonObject().getAsJsonObject("orders");
            pendingOrders = orders.has("pending") ? orders.get("pending").getAsInt() : 0;
            StashSorter sorter = WeSortAddon.sorter();
            if (sorter != null && orders.has("cancel") && orders.get("cancel").isJsonArray()) {
                for (JsonElement id : orders.getAsJsonArray("cancel")) {
                    String cancelled = id.getAsString();
                    // An order finished here whose report never got through: it is sent again now
                    if (!sorter.orders().running() && sorter.orders().holdsOrder()) {
                        lastStatusSig = "";
                        nextAttemptAt = 0;
                    }
                    // Not held here, but saved from before a restart: say it is cancelled and forget it
                    if (!sorter.orderCancel(cancelled)
                            && cancelled.equals(sorter.orders().resumeId())
                            && rejectedId == null) {
                        rejectedId = cancelled;
                        rejectedWhy = "cancelled";
                        rejectedStatus = "cancelled";
                    }
                }
            }
        } catch (RuntimeException ex) {
            pendingOrders = 0;
        }
    }

    private static JsonObject statusJson(OrderRunner.Report r, OrderRunner runner) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("phase", StashIndex.clamp(r.phase(), 100));
        JsonObject progress = new JsonObject();
        progress.addProperty("done", r.done());
        progress.addProperty("total", r.total());
        o.add("progress", progress);
        o.addProperty("message", StashIndex.clamp(r.message(), 300));
        if (r.terminal() && !r.status().equals("cancelled")) {
            JsonObject result = new JsonObject();
            result.addProperty("shulkers", r.shulkers());
            JsonArray lines = new JsonArray();
            for (String key : runner.requested().keySet()) {
                // The server takes at most 400 lines with keys up to 256 characters
                if (lines.size() >= 400 || key.length() > 256) continue;
                JsonObject line = new JsonObject();
                line.addProperty("key", key);
                line.addProperty("delivered", r.delivered().getOrDefault(key, 0));
                lines.add(line);
            }
            result.add("lines", lines);
            o.add("result", result);
        }
        return o;
    }

    /** Only the status and a short message, for a report the server would not take in full. */
    private static JsonObject minimalJson(OrderRunner.Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("message", StashIndex.clamp(r.message(), 100));
        return o;
    }

    private byte[] orderReportBytes(JsonObject report) {
        report.addProperty(
                "reportId",
                com.porter.wesort.data.ContainerIdentity.hash(com.porter.wesort.data.CanonicalJson.stringify(report)));
        return bytes(report);
    }

    private byte[] bytes(JsonObject o) {
        // Every order request says which bot it comes from: the server only takes them from the stash's owner
        if (mc.player != null) {
            if (!o.has("player")) o.addProperty("player", mc.player.getName().getString());
            if (!o.has("agent")) o.addProperty("agent", mc.player.getUUID().toString());
        }
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Looks at the stash and rebuilds the index only if something changed, or it's been a while. */
    private void scan() {
        Stash stash = null;
        StashSorter sorter = WeSortAddon.sorter();
        if (sorter != null) stash = sorter.currentStash();
        if (stash == null) {
            current = null;
            setStatus("no stash");
            return;
        }

        try {
            long fp = StashIndex.fingerprint(stash, seenInclude);
            long now = System.currentTimeMillis();
            if (current != null && fp == fingerprint && now - lastBuild < REBUILD_MS) return;

            StashIndex.Built built = StashIndex.build(stash, seenInclude);
            if (built == null) return;
            current = built;
            fingerprint = fp;
            lastBuild = now;
            if (status.equals("no stash") || status.equals("index error")) status = "idle";
        } catch (Throwable t) {
            current = null;
            if (!status.equals("index error"))
                WeSortAddon.LOG.error("wesort-stash-manager: couldn't build the index", t);
            setStatus("index error");
        }
    }

    private static URI parse(String url) {
        try {
            String s = url.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String linkScope() {
        if (!isActive()
                || mc.level == null
                || serverUrl.get().isBlank()
                || apiKey.get().isBlank()) return "";
        return serverUrl.get().trim() + "|" + Stash.currentServer() + "|"
                + com.porter.wesort.data.ContainerIdentity.hash(apiKey.get().trim());
    }

    // Kit jobs were removed: the Manager no longer assigns kits. Local shulker-packing for orders is untouched.

    private interface Reply {
        /** @return false when it failed and should be tried again later */
        boolean done(int code, String body);
    }

    private void send(URI base, String path, byte[] body, String key, Reply reply) {
        send(base, path, body, key, reply, false);
    }

    /** @param isolated the request is not part of the module's link to the server: its failures never set the shared backoff or the status */
    private void send(URI base, String path, byte[] body, String key, Reply reply, boolean isolated) {
        URI uri;
        try {
            uri = URI.create(base + path);
        } catch (IllegalArgumentException e) {
            if (!isolated) setStatus("bad url");
            return;
        }

        // The key itself never goes on the wire: the request is signed with a key derived from it (see PROTOCOL.md)
        String credentialId = "-";
        String secret = key;
        if (key.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}")) {
            credentialId = key.substring(0, 36);
            secret = key.substring(37);
        }
        byte[] signingKey = signingKey(secret);
        String nonce = randomHex(16);
        long ts = System.currentTimeMillis();
        String pathQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String sig = requestSignature(signingKey, "POST", pathQuery, ts, nonce, body);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("X-SM-Auth", "v1 " + credentialId + " " + ts + " " + nonce + " " + sig)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        } catch (IllegalArgumentException e) {
            if (!isolated) setStatus("bad url");
            return;
        }

        inFlight = true;
        int gen = generation;

        executor.execute(() -> {
            int code;
            String text = "";
            boolean badSignature = false;
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                code = response.statusCode();
                byte[] bytes = response.body() == null ? new byte[0] : response.body();
                // The signature covers the body as it is without compression
                if (response.headers()
                                .firstValue("Content-Encoding")
                                .map(v -> v.toLowerCase().contains("gzip"))
                                .orElse(false)
                        && bytes.length > 0) {
                    try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
                        bytes = in.readAllBytes();
                    } catch (java.io.IOException e) {
                        badSignature = true;
                    }
                }
                text = new String(bytes, StandardCharsets.UTF_8);
                // Everything but a refusal before the key was checked (401, 403) or a server error must be signed by
                // the server
                // for this request. Anything else is not acted on
                boolean unsignedOk = code == 401 || code == 403 || code >= 500;
                if (!unsignedOk && !badSignature) {
                    badSignature = !responseSignatureValid(
                            signingKey,
                            nonce,
                            code,
                            response.headers().firstValue("X-SM-Sig").orElse(""),
                            bytes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                code = -1;
            }

            int finalCode = code;
            String finalText = text;
            boolean finalBad = badSignature;
            mc.execute(() -> {
                if (gen != generation) return;
                inFlight = false;

                if (isolated) {
                    // No signature check failure, 404 or server refusal here may reach the shared backoff or the status
                    if (finalCode == 409) {
                        deliverySync.forget();
                        deliverySync.retryLater(System.currentTimeMillis());
                        if (isWrongServer(finalText))
                            warning(
                                    "This Manager serves another Minecraft server: %s. Set server-url to the right Manager.",
                                    finalText);
                        return;
                    }
                    if (finalBad) {
                        reply.done(-2, "");
                        return;
                    }
                    try {
                        reply.done(finalCode, finalText);
                    } catch (RuntimeException e) {
                        WeSortAddon.LOG.warn("wesort-stash-manager: couldn't handle the reply", e);
                        deliverySync.retryLater(System.currentTimeMillis());
                    }
                    return;
                }

                // A reply that is not signed by the server for this request is thrown away: no orders, rescans or
                // cancels in it
                if (finalBad) {
                    backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
                    nextAttemptAt = System.currentTimeMillis() + backoff;
                    setStatus("server signature invalid");
                    return;
                }

                // A 409 names a real conflict: the wrong Minecraft server, an unknown stash,
                // or a physical conflict. A wrong server waits a minute (reconfiguration needs
                // no hot retry loop); anything else flows through the reply handler.
                if (finalCode == 409) {
                    deliverySync.forget();
                    if (isWrongServer(finalText)) {
                        backoff = 0;
                        nextAttemptAt = System.currentTimeMillis() + 60_000;
                        warning(
                                "This Manager serves another Minecraft server: %s. Set server-url to the right Manager.",
                                finalText);
                        setStatus("wrong server");
                        return;
                    }
                }

                boolean ok;
                try {
                    ok = reply.done(finalCode, finalText);
                } catch (RuntimeException e) {
                    WeSortAddon.LOG.warn("wesort-stash-manager: couldn't handle the reply", e);
                    ok = false;
                }
                if (ok) {
                    backoff = 0;
                    nextAttemptAt = 0;
                } else {
                    backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
                    nextAttemptAt = System.currentTimeMillis() + backoff;
                }
                setStatus(ok ? "online" : finalCode < 0 ? "offline" : "error " + finalCode);
            });
        });
    }

    // Signed requests

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        return hex(random);
    }

    /** K = HMAC-SHA256(key = the API key, message = "stash-manager v1"). */
    private static byte[] signingKey(String apiKey) {
        return hmac(apiKey.getBytes(StandardCharsets.UTF_8), "stash-manager v1".getBytes(StandardCharsets.UTF_8));
    }

    private static String requestSignature(
            byte[] signingKey, String method, String pathQuery, long ts, String nonce, byte[] body) {
        String message = method + "\n" + pathQuery + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body);
        return hex(hmac(signingKey, message.getBytes(StandardCharsets.UTF_8)));
    }

    /** The server's signature on a reply: bound to our nonce, within 60 seconds, compared in constant time. */
    private static boolean responseSignatureValid(
            byte[] signingKey, String nonce, int status, String header, byte[] body) {
        String[] parts = header.trim().split(" ");
        if (parts.length != 3 || !parts[0].equals("v1")) return false;
        try {
            long ts = Long.parseLong(parts[1]);
            if (Math.abs(System.currentTimeMillis() - ts) > 60_000) return false;
            String message = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
            byte[] expected = hex(hmac(signingKey, message.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(
                    expected, parts[2].toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The fixed vector of PROTOCOL.md (computed here until the file has it), checked when the module turns on. */
    private static void selfCheck() {
        String got = requestSignature(
                signingKey("test-key"),
                "POST",
                "/api/v1/heartbeat",
                1700000000000L,
                "00112233445566778899aabbccddeeff",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        String expected = "7d2f41009d9f0c6c61216ee00811bc643c646652c3d96b36fb89df09bc9356f1";
        if (got.equals(expected)) WeSortAddon.LOG.info("wesort-stash-manager: request signing self-check passed");
        else
            WeSortAddon.LOG.error(
                    "wesort-stash-manager: request signing self-check FAILED: got {}, expected {}", got, expected);
    }

    /** A "this Manager serves ..." refusal: the bot talks to the Manager of another Minecraft server. */
    private static boolean isWrongServer(String body) {
        if (body == null) return false;
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) return false;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("error") || !o.get("error").isJsonPrimitive()) return false;
            return o.get("error").getAsString().startsWith("this Manager serves");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Sets the status and, only when it changes, logs it and tells the user about a rejected key. */
    private void setStatus(String next) {
        status = next;
        if (next.equals(lastLogged)) return;
        lastLogged = next;

        if (next.equals("error 401")) warning("API key rejected by the server. Check the api-key setting.");
        if (next.equals("server signature invalid"))
            error(
                    "The server's reply was not signed with your API key, so it was ignored. Check the api-key and server-url settings.");
        if (next.equals("online") || next.equals("idle")) WeSortAddon.LOG.info("wesort-stash-manager: {}", next);
        else WeSortAddon.LOG.warn("wesort-stash-manager: {}", next);
    }
}
