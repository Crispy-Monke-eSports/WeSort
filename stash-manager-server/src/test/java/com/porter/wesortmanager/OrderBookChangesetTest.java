package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#265/#142): every order-book commit proves a revision-linked changeset
 * and publishes only after the single transaction commits. A conflicting
 * revision rolls back and publishes nothing.
 */
class OrderBookChangesetTest {
    @TempDir
    Path data;

    record Published(String channel, long revision) {}

    static final class RecordingPublisher implements EventPublisher {
        final List<Published> published = new ArrayList<>();

        @Override
        public void afterCommit(String channel, long revision) {
            published.add(new Published(channel, revision));
        }
    }

    private static JsonObject book(String status, String historyNote) {
        var order = new JsonObject();
        order.addProperty("id", "o-1");
        order.addProperty("status", status);
        var history = new JsonArray();
        var event = new JsonObject();
        event.addProperty("t", historyNote);
        history.add(event);
        order.add("history", history);
        var root = new JsonObject();
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        var orders = new JsonArray();
        orders.add(order);
        root.add("orders", orders);
        return root;
    }

    private static JsonObject withAgent(JsonObject root, String agent) {
        var order = root.getAsJsonArray("orders").get(0).getAsJsonObject();
        order.addProperty("agent", agent);
        return root;
    }

    @Test
    void commitProvesChangesetAndPublishesOnceAfterCommit() throws Exception {
        var ledger = new StockLedger(data);
        var publisher = new RecordingPublisher();
        var repo = new OrderBookRepository(ledger, publisher);
        long rev1 = repo.commit("s", "a", 0, book("pending", "created"), null);
        assertEquals(1, rev1);
        assertEquals(List.of(new Published("orders", 1)), publisher.published);
        // Progress the same order: changed row + new event, same revision chain.
        var progressed = book("pending", "created");
        progressed.getAsJsonArray("orders").get(0).getAsJsonObject().addProperty("note", "packed");
        var second = progressed.getAsJsonArray("orders").get(0).getAsJsonObject();
        var extra = new JsonObject();
        extra.addProperty("t", "packed");
        second.getAsJsonArray("history").add(extra);
        long rev2 = repo.commit("s", "a", 1, progressed, null);
        assertEquals(2, rev2);
        assertEquals(
                List.of(new Published("orders", 1), new Published("orders", 2)), publisher.published);
        var changesets = OrderChangeSet.forBook(
                JsonParser.parseString("{\"orders\":[]}").getAsJsonObject(), book("pending", "created"), 0);
        assertEquals(1, changesets.size());
        assertEquals("o-1", changesets.getFirst().orderId());
        assertEquals(1, changesets.getFirst().nextRevision());
    }

    @Test
    void conflictingRevisionRollsBackAndPublishesNothing() throws Exception {
        var ledger = new StockLedger(data);
        var publisher = new RecordingPublisher();
        var repo = new OrderBookRepository(ledger, publisher);
        repo.commit("s", "a", 0, book("pending", "created"), null);
        assertThrows(java.io.IOException.class, () -> repo.commit("s", "a", 0, book("pending", "other"), null));
        assertEquals(1, publisher.published.size(), "rollback must not publish");
    }

    @Test
    void rewrittenHistoryFailsClosedBeforeWrite() throws Exception {
        var first = book("pending", "created");
        var tampered = book("pending", "forged");
        assertThrows(
                IllegalArgumentException.class, () -> OrderChangeSet.forBook(first, tampered, 0));
    }

    @Test
    void terminalOrderCarriesResumeReceipt() throws Exception {
        var terminal = withAgent(book("delivered", "created"), "agent-1");
        var changesets = OrderChangeSet.forBook(
                JsonParser.parseString("{\"orders\":[]}").getAsJsonObject(), terminal, 0);
        assertEquals(1, changesets.size());
        assertNotNull(changesets.getFirst().terminalResumeReceipt());
    }
}
