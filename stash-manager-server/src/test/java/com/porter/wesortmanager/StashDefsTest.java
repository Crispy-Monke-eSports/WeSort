package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A failed write must never leave phantom coordinates in memory. */
class StashDefsTest {
    @TempDir
    Path data;

    final AtomicLong clock = new AtomicLong(1_000_000);

    StashDefs defs;

    private static JsonObject stash(String name, int x) {
        return JsonParser.parseString(
                        "{\"name\":\"" + name + "\",\"dimension\":\"minecraft:overworld\","
                                + "\"pos1\":{\"x\":" + x + ",\"y\":-60,\"z\":0},"
                                + "\"pos2\":{\"x\":31,\"y\":20,\"z\":31}}")
                .getAsJsonObject();
    }

    @BeforeEach
    void setUp() throws Exception {
        defs = new StashDefs(data, clock::get);
    }

    private Path file() {
        return data.resolve("stash-defs.json");
    }

    private void breakWrites() throws Exception {
        Files.deleteIfExists(file());
        Files.createDirectory(file());
    }

    private void repairWrites() throws Exception {
        Files.deleteIfExists(file());
    }

    @Test
    void failedUpdateKeepsOldCoordinatesAndRevision() throws Exception {
        defs.create(stash("main", 0));
        long rev = defs.get("main").revision();
        breakWrites();
        var moved = stash("main", 100);
        assertThrows(java.io.IOException.class, () -> defs.update(moved, rev));
        // Nothing phantom may steer the bot: old coordinates and the old revision survive.
        assertEquals(0, defs.get("main").pos1().x());
        assertEquals(rev, defs.get("main").revision());
        assertTrue(defs.directive("main").getAsJsonObject("pos1").get("x").getAsInt() == 0);
        // And retrying with the same revision works once the disk is healthy again.
        repairWrites();
        var applied = defs.update(moved, rev);
        assertEquals(100, applied.pos1().x());
        assertEquals(100, defs.directive("main").getAsJsonObject("pos1").get("x").getAsInt());
    }

    @Test
    void failedCreateLeavesNothingBehind() throws Exception {
        breakWrites();
        assertThrows(java.io.IOException.class, () -> defs.create(stash("main", 0)));
        assertNull(defs.get("main"));
        repairWrites();
        defs.create(stash("main", 0));
        assertNotNull(defs.get("main"));
    }

    @Test
    void failedDeleteKeepsTheStash() throws Exception {
        defs.create(stash("main", 0));
        long rev = defs.get("main").revision();
        breakWrites();
        assertThrows(java.io.IOException.class, () -> defs.delete("main", rev));
        assertNotNull(defs.get("main"));
        repairWrites();
        defs.delete("main", rev);
        assertNull(defs.get("main"));
    }
}
