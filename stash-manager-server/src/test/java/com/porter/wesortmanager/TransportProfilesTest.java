package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TransportProfilesTest {
    @TempDir Path data;

    @Test void defaultsOffAndUpdatesWithRevision() throws Exception {
        var profiles = new TransportProfiles(data);
        var initial = profiles.get("play.example.com");
        assertFalse(initial.allowTpa());
        assertFalse(initial.allowHome());
        assertFalse(initial.tpaVerified());
        assertFalse(initial.homeVerified());
        assertEquals(1, initial.revision());

        var request = new JsonObject();
        request.addProperty("allowTpa", true);
        var updated = profiles.update("play.example.com", request, 1);
        assertTrue(updated.allowTpa());
        assertFalse(updated.allowHome());
        assertEquals(2, updated.revision());

        // A wrong revision is a conflict, and the change is not applied.
        var conflict = new JsonObject();
        conflict.addProperty("allowHome", true);
        assertThrows(TransportProfiles.Conflict.class, () -> profiles.update("play.example.com", conflict, 1));
        assertFalse(profiles.get("play.example.com").allowHome());
    }

    @Test void profilesPersistAndAreIndependentPerServer() throws Exception {
        var profiles = new TransportProfiles(data);
        var a = new JsonObject(); a.addProperty("allowTpa", true); a.addProperty("tpaVerified", true);
        profiles.update("a.example.com", a, 1);
        var b = new JsonObject(); b.addProperty("allowHome", true); b.addProperty("homeVerified", true);
        profiles.update("b.example.com", b, 1);

        var reloaded = new TransportProfiles(data);
        assertTrue(reloaded.get("a.example.com").allowTpa());
        assertFalse(reloaded.get("a.example.com").allowHome());
        assertFalse(reloaded.get("b.example.com").allowTpa());
        assertTrue(reloaded.get("b.example.com").allowHome());
        assertEquals(2, reloaded.list().size());
    }

    @Test void unknownServerStartsWithBothOff() throws Exception {
        var profiles = new TransportProfiles(data);
        var fresh = profiles.get("brand-new.example.com");
        assertFalse(fresh.allowTpa());
        assertFalse(fresh.allowHome());
        assertEquals(1, fresh.revision());
    }
}
