package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 Step 5: the service split is wiring only. The order store runs against
 * an in-memory {@link OrderRepository}, the ledger services are built once
 * with the single ledger, and both route constructor paths (explicit services
 * and the legacy adapters) serve the same state (Refs #142).
 */
class ServiceWiringTest {
    @TempDir
    Path data;

    /** In-memory {@link OrderRepository}: records commits, keeps the latest book per stash. */
    static final class InMemoryOrderRepository implements OrderRepository {
        final Map<String, JsonObject> books = new LinkedHashMap<>();
        final List<String> commits = new ArrayList<>();

        public void load(Consumer<OrderBookRepository.Snapshot> validate) {
            // No durable books yet; nothing to validate.
        }

        public long commit(
                String server,
                String stash,
                long expected,
                JsonObject root,
                StockLedger.BoxRelease release)
                throws java.io.IOException {
            return commitExtended(server, stash, expected, root, release, List.of(), List.of());
        }

        public long commitExtended(
                String server,
                String stash,
                long expected,
                JsonObject root,
                StockLedger.BoxRelease release,
                List<AuditLog.Entry> audit,
                List<OrderRepository.SqliteWrite> extra)
                throws java.io.IOException {
            books.put(server + "\0" + stash, root.deepCopy());
            commits.add(server + "/" + stash + "#" + expected);
            return expected + 1;
        }

        public JsonObject resumeReceipt(String server, String stash, String agent, String id) {
            return null;
        }
    }

    private static JsonObject index() {
        return JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
                                + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}")
                .getAsJsonObject();
    }

    private OrderStore wiredOrders(IndexStore store, AtomicLong time, InMemoryOrderRepository repo) throws Exception {
        var orders = new OrderStore(data, store, time::get, EventPublisher.noop(), repo);
        orders.load();
        return orders;
    }

    @Test
    void orderStorePersistsAgainstInMemoryRepository() throws Exception {
        var time = new AtomicLong(1_000L);
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        StockFixtures.put(store, index());
        var repo = new InMemoryOrderRepository();
        var orders = wiredOrders(store, time, repo);

        var placed = orders.placement().place(
                        "s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertNotNull(placed.id);
        assertTrue(repo.commits.contains("s/a#0"));
        assertTrue(repo.books.containsKey("s\0a"));

        var cancelled = orders.lifecycle().cancel(placed.id);
        assertEquals("cancelled", cancelled.status);
        assertEquals(2, repo.commits.size());

        var listed = orders.listJson("s", "a");
        assertEquals(1, listed.getAsJsonArray("orders").size());
    }

    @Test
    void ledgerServicesAreBuiltOnceWithTheSingleLedger() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        assertSame(store.stock(), store.stock());
        assertSame(store.observationService(), store.observationService());
        assertSame(store.boxUuidRegistry(), store.boxUuidRegistry());
        assertSame(store.returnIntakeService(), store.returnIntakeService());
        assertSame(store.operationJournal(), store.operationJournal());
    }

    @Test
    void routesWireExplicitServicesAndLegacyAdaptersAgree() throws Exception {
        Config.load(data);
        Files.writeString(data.resolve("config.properties"), "allowBearer=true\n", StandardOpenOption.APPEND);
        Config config = Config.load(data);
        config.setMinecraftServer("s");
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        StockFixtures.put(store, index());
        var orders = wiredOrders(store, new AtomicLong(1_000L), new InMemoryOrderRepository());
        orders.placement()
                .place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);

        var icons = new IconService(
                data, id -> null, v -> false, new GameIcons(data.resolve("icons"), false, false), false);
        var sessions = new SessionStore(data);
        sessions.load();
        var users = new Users(data);
        users.load();
        var audit = new AuditLog(store.stock(), data);
        audit.load();
        WeSortManager.Routes routes = WeSortManager.routes(
                config,
                store,
                orders,
                icons,
                new LinkState(config.keyState().generation()),
                List.of(),
                sessions,
                users,
                audit,
                new BotHub(),
                new LoginLimiter());
        assertNotNull(routes.router());
        assertNotNull(routes.jobs());
        assertNotNull(routes.handoffs());

        var owner = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);
        var explicit = new StockRoutes(
                        store, orders, store.observationService(), store.returnIntakeService(), store.boxUuidRegistry())
                .view(owner);
        var legacy = new StockRoutes(store, orders).view(owner);
        // The view stamps each call; the served state must otherwise agree.
        explicit.remove("asOf");
        legacy.remove("asOf");
        assertEquals(explicit, legacy);
    }
}
