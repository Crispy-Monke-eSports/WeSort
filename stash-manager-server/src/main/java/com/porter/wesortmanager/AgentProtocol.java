package com.porter.wesortmanager;

import java.util.List;
import java.util.Set;

/**
 * PR4 (#28/#227/#263): versioned Agent↔Manager wire contract, v3.
 *
 * <p>v3 does not change HMAC canonicalization ({@link Signer}): it standardizes
 * the fields every signed agent request carries and the machine-readable error
 * reasons every rejection returns, so Manager and Java-agent can share golden
 * vectors. v1/v2 requests keep working until the v2 fallback is removed after
 * a green 26.3 run; new physical evidence must use v3.
 */
public final class AgentProtocol {
    private AgentProtocol() {}

    /** Current wire version. v1 = legacy sessions, v2 = control plane, v3 = full contract. */
    public static final int PROTOCOL_VERSION = 3;

    /** Fields every signed agent POST carries in v3 (besides X-SM-Auth). */
    public static final List<String> REQUIRED_AGENT_FIELDS =
            List.of("protocol", "agent", "lease", "expectedRevision", "idempotencyKey");

    /** Machine-readable rejection reasons (HTTP 409 unless noted). */
    public static final Set<String> REASONS = Set.of(
            "stale-lease",
            "unknown-agent",
            "wrong-server",
            "lease-held",
            "revision-mismatch",
            "duplicate-receipt",
            "unsupported-version", // 400
            "malformed-payload", // 400
            "payload-too-large", // 413
            "snapshot-mismatch");

    /** Error reasons that are client bugs (400/413), not conflicts. */
    public static final Set<String> CLIENT_ERROR_REASONS =
            Set.of("unsupported-version", "malformed-payload", "payload-too-large");

    public static boolean isVersioned(int protocol) {
        return protocol == PROTOCOL_VERSION;
    }
}
