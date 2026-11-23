package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserRegistryPersistenceTest {
    @TempDir Path data;

    @Test
    void malformedRegistryCannotPublishPartialAccountsOrWidenScopes() throws Exception {
        var users = new Users(data);
        var member = users.create("member", Auth.hash("test-password-123"), Users.PRESETS.get("member"), false);
        users.linkMinecraftAccount(member.id(), "MemberMC");
        users.renameOwner("boss");
        Path file = data.resolve("users.json");
        String original = Files.readString(file);
        for (String problem : new String[] {"scope", "flag", "duplicate", "mapping", "version", "owner"}) {
            var root = JsonParser.parseString(original).getAsJsonObject();
            var row = root.getAsJsonArray("users").get(0).getAsJsonObject();
            switch (problem) {
                case "scope" -> row.getAsJsonObject("perms").remove("stashes");
                case "flag" -> row.addProperty("disabled", "false");
                case "duplicate" -> root.getAsJsonArray("users").add(row.deepCopy());
                case "mapping" -> root.getAsJsonObject("minecraftAccounts").addProperty("u-ffffffffffff", "Ghost");
                case "version" -> root.addProperty("version", 1.5);
                case "owner" -> root.addProperty("ownerName", "bad owner");
            }
            Files.writeString(file, root.toString());
            assertThrows(IOException.class, users::load, problem);
            assertThrows(IOException.class, () -> new Users(data).load(), problem);
            assertEquals("boss", users.ownerName());
            assertEquals(member, users.byId(member.id()));
            assertEquals("MemberMC", users.minecraftAccount(member.id()));
        }
        Files.writeString(file, original);
        users.load();
        assertEquals(member, users.byId(member.id()));
        var restarted = new Users(data);
        restarted.load();
        assertEquals(member, restarted.byId(member.id()));
        assertEquals("MemberMC", restarted.minecraftAccount(member.id()));
    }

    @Test
    void missingPreviouslyLoadedRegistryDoesNotResetOwnerOrMembers() throws Exception {
        var users = new Users(data);
        users.renameOwner("boss");
        Files.delete(data.resolve("users.json"));
        assertThrows(IOException.class, users::load);
        assertEquals("boss", users.ownerName());
    }

    @Test
    void existingEmptyDocumentIsNotAFreshInstallation() throws Exception {
        Files.writeString(data.resolve("users.json"), "{}");
        assertThrows(IOException.class, () -> new Users(data).load());
    }
}
