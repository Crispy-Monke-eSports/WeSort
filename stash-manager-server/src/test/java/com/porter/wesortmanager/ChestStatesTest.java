package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The Manager's recheck decisions from the agent's chest reports: aging, once-per-read, caps. */
class ChestStatesTest {
    static final long HOUR = 3600_000;

    ChestStates states(AtomicLong clock) {
        return new ChestStates(clock::get, HOUR);
    }

    static String chest(int x, int y, int z, long readAt) {
        return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"readAt\":" + readAt + "}";
    }

    @Test
    void freshChestsAreNeverRequested() {
        AtomicLong clock = new AtomicLong(1_000_000);
        ChestStates s = states(clock);
        s.report(
                "s",
                "a",
                ChestStates.parse(
                        JsonParser.parseString("[" + chest(1, 64, 1, 900_000) + "," + chest(2, 64, 2, 999_999) + "]")
                                .getAsJsonArray()));
        assertTrue(s.due("s", "a").isEmpty(), "reads within the max age stay quiet");
        assertTrue(s.due("s", "unknown").isEmpty(), "unknown stashes stay quiet");
    }

    @Test
    void oldChestsAreRequestedOldestFirstAndOncePerRead() {
        AtomicLong clock = new AtomicLong(10_000_000);
        ChestStates s = states(clock);
        s.report(
                "s",
                "a",
                ChestStates.parse(
                        JsonParser.parseString("[" + chest(1, 64, 1, 1_000) + "," + chest(2, 64, 2, 2_000) + "]")
                                .getAsJsonArray()));
        JsonArray due = s.due("s", "a");
        assertEquals(2, due.size());
        assertEquals(1, due.get(0).getAsJsonObject().get("x").getAsInt(), "oldest read first");
        // Asked once: a repeat without a newer report stays quiet.
        assertTrue(s.due("s", "a").isEmpty(), "no repeat until a newer read is reported");
        // A newer read re-arms the chest.
        clock.set(10_000_001 + HOUR - 1000);
        s.report(
                "s",
                "a",
                ChestStates.parse(JsonParser.parseString("[" + chest(1, 64, 1, 10_000_001) + "]")
                        .getAsJsonArray()));
        assertTrue(s.due("s", "a").isEmpty(), "fresh re-read stays quiet");
        clock.set(10_000_001 + HOUR + 1);
        JsonArray again = s.due("s", "a");
        assertEquals(1, again.size(), "aged-out again after the max age");
    }

    @Test
    void failedReadsAreReRequestedAfterTheMaxAge() {
        // Anarchy TPS: reads keep failing, so readAt never advances. The Manager must not
        // give up forever — once the last request itself is older than the max age, ask again.
        AtomicLong clock = new AtomicLong(10_000_000);
        ChestStates s = states(clock);
        s.report(
                "s",
                "a",
                ChestStates.parse(JsonParser.parseString("[" + chest(1, 64, 1, 1_000) + "]")
                        .getAsJsonArray()));
        assertEquals(1, s.due("s", "a").size(), "first ask");
        assertTrue(s.due("s", "a").isEmpty(), "no immediate repeat");
        clock.set(10_000_000 + HOUR - 1);
        assertTrue(s.due("s", "a").isEmpty(), "still quiet within the max age");
        clock.set(10_000_000 + HOUR + 1);
        JsonArray again = s.due("s", "a");
        assertEquals(1, again.size(), "re-asked after the max age with no newer read");
        assertEquals(1, again.get(0).getAsJsonObject().get("x").getAsInt());
        assertTrue(s.due("s", "a").isEmpty(), "quiet again right after the re-ask");
    }

    @Test
    void neverReadChestsAreLeftToTheAgent() {
        AtomicLong clock = new AtomicLong(10_000_000);
        ChestStates s = states(clock);
        s.report(
                "s",
                "a",
                ChestStates.parse(
                        JsonParser.parseString("[" + chest(1, 64, 1, 0) + "]").getAsJsonArray()));
        assertTrue(s.due("s", "a").isEmpty(), "readAt 0 is the agent's own unknown-chest business");
    }

    @Test
    void reportsReplaceTheStoredSet() {
        AtomicLong clock = new AtomicLong(10_000_000);
        ChestStates s = states(clock);
        s.report(
                "s",
                "a",
                ChestStates.parse(JsonParser.parseString("[" + chest(1, 64, 1, 1_000) + "]")
                        .getAsJsonArray()));
        assertEquals(1, s.due("s", "a").size());
        s.report(
                "s",
                "a",
                ChestStates.parse(JsonParser.parseString("[" + chest(9, 64, 9, 9_999_999) + "]")
                        .getAsJsonArray()));
        assertTrue(s.due("s", "a").isEmpty(), "gone chests are forgotten, fresh ones stay quiet");
        assertEquals(1, s.size());
    }

    @Test
    void repliesAreCappedOldestFirst() {
        AtomicLong clock = new AtomicLong(100_000_000);
        ChestStates s = states(clock);
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < ChestStates.MAX_RECHECK + 10; i++) {
            if (i > 0) body.append(',');
            body.append(chest(i, 64, i, i));
        }
        s.report(
                "s",
                "a",
                ChestStates.parse(
                        JsonParser.parseString(body.append(']').toString()).getAsJsonArray()));
        JsonArray due = s.due("s", "a");
        assertEquals(ChestStates.MAX_RECHECK, due.size());
        assertEquals(1, due.get(0).getAsJsonObject().get("x").getAsInt(), "oldest first (readAt 0 is skipped)");
    }

    @Test
    void malformedReportsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChestStates.parse(JsonParser.parseString("{}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChestStates.parse(JsonParser.parseString("[" + chest(1, 64, 1, -1) + "]")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChestStates.parse(JsonParser.parseString("[{\"x\":1.5,\"y\":64,\"z\":1,\"readAt\":5}]")));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChestStates.parse(JsonParser.parseString("[{\"x\":99999999,\"y\":64,\"z\":1,\"readAt\":5}]")));
        List<ChestStates.Chest> ok = ChestStates.parse(JsonParser.parseString("[" + chest(1, 64, 1, 5) + "]"));
        assertEquals(1, ok.size());
        assertEquals(new ChestStates.Chest(1, 64, 1, 5), ok.get(0));
    }
}
