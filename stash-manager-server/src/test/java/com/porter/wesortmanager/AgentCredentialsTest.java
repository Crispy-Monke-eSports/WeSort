package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCredentialsTest {
    @TempDir
    Path data;

    @Test
    void oneNamedKeyCanBeActiveAndRotationSurvivesRestart() throws Exception {
        AgentCredentials credentials = new AgentCredentials(data);
        String first = credentials.issue("6b6t-agent");
        String firstId = first.substring(0, first.indexOf('.'));
        assertThrows(IllegalArgumentException.class, () -> credentials.issue("second"));
        assertNotNull(new AgentCredentials(data).find(firstId));

        assertTrue(credentials.revoke(firstId));
        assertThrows(IllegalArgumentException.class, () -> credentials.issue(""));
        String second = credentials.issue("replacement");
        String secondId = second.substring(0, second.indexOf('.'));
        AgentCredentials restarted = new AgentCredentials(data);
        assertEquals(null, restarted.find(firstId));
        assertNotNull(restarted.find(secondId));
        assertEquals(
                1,
                restarted.all().stream().filter(AgentCredentials.Slot::active).count());
    }

    @Test
    void oldMultiAgentRegistryFailsClosedInsteadOfSelectingOneKey() throws Exception {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        String payload = "{\"version\":3,\"scopedOnly\":true,\"slots\":["
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"label\":\"one\",\"key\":\"" + key + "\"},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"label\":\"two\",\"key\":\"" + key + "\"}]}";
        Files.writeString(data.resolve("agent-credentials.json"), payload);
        assertThrows(IOException.class, () -> new AgentCredentials(data));
    }

    @Test
    void rotationDoesNotExhaustTheCredentialRegistry() throws Exception {
        AgentCredentials credentials = new AgentCredentials(data);
        var revokedIds = new java.util.ArrayList<String>();
        for (int i = 0; i < 70; i++) {
            String token = credentials.issue("agent");
            String id = token.substring(0, token.indexOf('.'));
            assertTrue(credentials.revoke(id));
            revokedIds.add(id);
        }
        String token = credentials.issue("replacement");
        String activeId = token.substring(0, token.indexOf('.'));
        AgentCredentials restarted = new AgentCredentials(data);
        assertNotNull(restarted.find(activeId));
        assertTrue(restarted.all().size() <= 64);
        for (String revokedId : revokedIds) assertEquals(null, restarted.find(revokedId));
    }

    @Test
    void legacyCredentialFormatsFailClosed() throws Exception {
        for (int version : new int[] {1, 2}) {
            Files.writeString(
                    data.resolve("agent-credentials.json"),
                    "{\"version\":" + version + ",\"scopedOnly\":false,\"agents\":[]}");
            assertThrows(IOException.class, () -> new AgentCredentials(data));
        }
    }

    @Test
    void failedIssueCannotCreateAnInMemoryOnlyAgent() throws Exception {
        AgentCredentials credentials = new AgentCredentials(data);
        Path file = data.resolve("agent-credentials.json");
        Files.createDirectory(file);

        assertThrows(IOException.class, () -> credentials.issue("bot"));
        assertTrue(credentials.all().isEmpty());

        Files.delete(file);
        String id = credentials.issue("bot");
        assertNotNull(new AgentCredentials(data).find(id.substring(0, id.indexOf('.'))));
    }

    @Test
    void failedRevokeDoesNotRetireLiveCredentialsOrNotifySessions() throws Exception {
        AgentCredentials credentials = new AgentCredentials(data);
        String token = credentials.issue("bot");
        String id = token.substring(0, token.indexOf('.'));
        AtomicBoolean notified = new AtomicBoolean(false);
        credentials.onRevocation(ignored -> notified.set(true));

        Path file = data.resolve("agent-credentials.json");
        Path saved = data.resolve("valid-agent-credentials.json");
        Files.move(file, saved);
        Files.createDirectory(file);

        assertThrows(IOException.class, () -> credentials.revoke(id));
        assertNotNull(credentials.find(id));
        assertFalse(notified.get());

        Files.delete(file);
        Files.move(saved, file);
        assertTrue(credentials.revoke(id));
        assertTrue(notified.get());
        assertEquals(null, new AgentCredentials(data).find(id));
    }
}
