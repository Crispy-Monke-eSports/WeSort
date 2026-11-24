package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserRegistryAvailabilityTest {
    @TempDir
    Path data;

    @Test
    void incompleteRegistryKeepsOwnerAvailableWithoutWideningOrOverwritingPermissions() throws Exception {
        Path registry = data.resolve("users.json");
        String broken = "{\"version\":1,\"minecraftAccounts\":{\"owner\":\"Example\"}}";
        Files.writeString(registry, broken);
        Users users = new Users(data);
        assertThrows(IOException.class, users::load);
        users.loadForService();
        assertTrue(users.registryUnavailable());
        assertTrue(users.principal(Users.OWNER_ID).owner());
        assertNull(users.principal("u-0123456789ab"));
        assertNull(users.activeMinecraftAccount(Users.OWNER_ID));
        assertThrows(IOException.class, () -> users.renameOwner("NewOwner"));
        assertThrows(IOException.class, () -> users.linkMinecraftAccount(Users.OWNER_ID, "Example"));
        assertEquals("owner", users.ownerName());
        assertNull(users.minecraftAccount(Users.OWNER_ID));
        assertEquals(broken, Files.readString(registry));
        RuntimeHealth health = new RuntimeHealth(true);
        health.userRegistryUnavailable(users.registryUnavailable());
        assertEquals("degraded", health.detail(0).get("status").getAsString());
        assertTrue(health.detail(0).get("userRegistryUnavailable").getAsBoolean());

        Files.writeString(registry, "{\"version\":1,\"ownerName\":\"Owner\",\"users\":[]}");
        Users restarted = new Users(data);
        restarted.loadForService();
        assertFalse(restarted.registryUnavailable());
        assertEquals("Owner", restarted.ownerName());
        restarted.renameOwner("Restored");
        assertEquals("Restored", restarted.ownerName());
    }
}
