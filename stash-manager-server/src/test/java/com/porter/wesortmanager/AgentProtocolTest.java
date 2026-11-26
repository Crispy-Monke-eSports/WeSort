package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentProtocolTest {
    @Test
    void v3IsCurrent() {
        assertEquals(3, AgentProtocol.PROTOCOL_VERSION);
        assertTrue(AgentProtocol.isVersioned(3));
        assertFalse(AgentProtocol.isVersioned(2));
        assertTrue(AgentProtocol.REQUIRED_AGENT_FIELDS.containsAll(
                List.of("protocol", "agent", "lease", "expectedRevision", "idempotencyKey")));
    }

    @Test
    void batchEnvelopeRequiresExplicitMembership() {
        var upsert = new BatchEnvelope.Op(BatchEnvelope.OpKind.SOURCE_UPSERT, "c:abc", 7L, "0".repeat(64));
        var env = new BatchEnvelope(
                1,
                "6b6t.org",
                "main",
                "minecraft:overworld",
                "agent-1",
                "session-1",
                1L,
                9L,
                "batch-1",
                0,
                1,
                List.of(upsert));
        assertEquals(1, env.ops().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new BatchEnvelope(
                        1,
                        "6b6t.org",
                        "main",
                        "minecraft:overworld",
                        "agent-1",
                        "session-1",
                        1L,
                        9L,
                        "batch-1",
                        0,
                        1,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BatchEnvelope(
                        2,
                        "6b6t.org",
                        "main",
                        "minecraft:overworld",
                        "agent-1",
                        "session-1",
                        1L,
                        9L,
                        "batch-1",
                        0,
                        1,
                        List.of(upsert)));
    }
}
