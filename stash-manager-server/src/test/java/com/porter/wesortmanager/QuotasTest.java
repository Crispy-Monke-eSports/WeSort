package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuotasTest {
    @TempDir
    Path data;

    private static JsonObject policy(boolean enabled, String subject, String key, long max) {
        var root = new JsonObject();
        root.addProperty("enabled", enabled);
        var rules = new JsonArray();
        if (key != null) {
            var rule = new JsonObject();
            rule.addProperty("subject", subject);
            rule.addProperty("key", key);
            rule.addProperty("max", max);
            rule.addProperty("windowSeconds", 3600);
            rules.add(rule);
        }
        root.add("rules", rules);
        return root;
    }

    @Test
    void disabledQuotasImposeNoLimitAndReserveIsANoOp() throws Exception {
        var clock = new AtomicLong(1_000_000_000_000L);
        var quotas = new Quotas(data, clock::get);
        var policy = policy(false, "*", "item:minecraft:diamond", 64);
        quotas.update(policy, 1);
        // No exception, no reservation recorded.
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 1000)), "o-1");
        assertTrue(quotas.usageFor("u-1").getAsJsonObject("usage").size() == 0);
    }

    @Test
    void enabledQuotaBlocksConcurrentOverdraw() throws Exception {
        var clock = new AtomicLong(1_000_000_000_000L);
        var quotas = new Quotas(data, clock::get);
        quotas.update(policy(true, "*", "item:minecraft:diamond", 64), 1);
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 40)), "o-1");
        // 40 reserved; another 40 exceeds 64.
        var exceeded = assertThrows(
                Quotas.Exceeded.class,
                () -> quotas.checkAndReserve(
                        "u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 40)), "o-2"));
        assertEquals("item:minecraft:diamond", exceeded.ref);
        assertEquals(64, exceeded.limit);
        // 24 more is fine.
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 24)), "o-2");
        // Replaying the same order id is idempotent.
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 1000)), "o-2");
    }

    @Test
    void releaseAndConsumeAffectQuotaExactlyOnce() throws Exception {
        var clock = new AtomicLong(1_000_000_000_000L);
        var quotas = new Quotas(data, clock::get);
        quotas.update(policy(true, "*", "item:minecraft:diamond", 64), 1);
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 40)), "o-1");
        assertEquals(
                40,
                quotas.usageFor("u-1")
                        .getAsJsonObject("usage")
                        .getAsJsonObject("item:minecraft:diamond")
                        .get("reserved")
                        .getAsLong());
        quotas.release("o-1");
        assertEquals(
                0,
                quotas.usageFor("u-1")
                        .getAsJsonObject("usage")
                        .getAsJsonObject("item:minecraft:diamond")
                        .get("reserved")
                        .getAsLong());
        // Release twice is idempotent; a fresh 64 still fits.
        quotas.release("o-1");
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 64)), "o-2");
        quotas.consume("o-2");
        var usage = quotas.usageFor("u-1").getAsJsonObject("usage").getAsJsonObject("item:minecraft:diamond");
        assertEquals(64, usage.get("consumed").getAsLong());
        assertEquals(0, usage.get("reserved").getAsLong());
        quotas.consume("o-2");
        assertEquals(
                64,
                quotas.usageFor("u-1")
                        .getAsJsonObject("usage")
                        .getAsJsonObject("item:minecraft:diamond")
                        .get("consumed")
                        .getAsLong());
    }

    @Test
    void usagePersistsAcrossRestart() throws Exception {
        var clock = new AtomicLong(1_000_000_000_000L);
        var quotas = new Quotas(data, clock::get);
        quotas.update(policy(true, "*", "item:minecraft:diamond", 64), 1);
        quotas.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 30)), "o-1");
        var reloaded = new Quotas(data, clock::get);
        assertEquals(
                30,
                reloaded.usageFor("u-1")
                        .getAsJsonObject("usage")
                        .getAsJsonObject("item:minecraft:diamond")
                        .get("reserved")
                        .getAsLong());
        // The same order id still cannot double-reserve after restart.
        reloaded.checkAndReserve("u-1", List.of(), List.of(new Quotas.Line("item:minecraft:diamond", 1000)), "o-1");
        assertEquals(
                30,
                reloaded.usageFor("u-1")
                        .getAsJsonObject("usage")
                        .getAsJsonObject("item:minecraft:diamond")
                        .get("reserved")
                        .getAsLong());
    }

    @Test
    void policyRevisionConflictIsEnforced() throws Exception {
        var quotas = new Quotas(data);
        quotas.update(policy(true, "*", "item:minecraft:diamond", 64), 1);
        assertThrows(Quotas.Conflict.class, () -> quotas.update(policy(false, null, null, 0), 1));
    }
}
