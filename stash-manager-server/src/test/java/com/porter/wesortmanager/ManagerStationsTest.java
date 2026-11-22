package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** A station configured in Settings is sent by the agent with source "manager". */
class ManagerStationsTest {
    @Test
    void recognizesManagerOwnedAnvilAndCraftingTableInTheSameHeartbeat() {
        var payload = JsonParser.parseString("""
                {"naming":{"anvil":true,"xpLevels":3,"anvilSource":"manager"},
                 "crafting":{"enabled":true,"table":true,"tableSource":"manager"}}
                """).getAsJsonObject();
        var status = AddonRoutes.botStatus(payload);
        assertTrue(status.hasNaming());
        assertTrue(status.naming().anvil());
        assertEquals("manager", status.naming().anvilSource());
        assertTrue(status.hasCrafting());
        assertTrue(status.crafting().enabled());
        assertTrue(status.crafting().table());
        assertEquals("manager", status.crafting().tableSource());
    }

    @Test
    void refusesUnknownCraftingTableSource() {
        var payload = JsonParser.parseString("""
                {"crafting":{"enabled":true,"table":true,"tableSource":"untrusted"}}
                """).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> AddonRoutes.botStatus(payload));
    }
}
