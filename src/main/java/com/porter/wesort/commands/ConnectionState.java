package com.porter.wesort.commands;

/**
 * Truthful connection states for the WeSort Manager link (P1-12). Storing an address, port and key is
 * not a connection: only a verified server reply counts as online. The GUI and the chat command report
 * these states instead of claiming "Connected" after a local save.
 */
public enum ConnectionState {
    /** The module is off: nothing is attempted. */
    DISCONNECTED("Disconnected", "Turn the wesort-stash-manager module on to connect."),
    /** No address or no key stored yet. */
    NOT_CONFIGURED("Not configured", "Enter the Manager address, port and API key."),
    /** The stored address does not parse as http/https without a query. */
    BAD_ADDRESS("Bad address", "Use http(s)://host:port without a path, e.g. http://127.0.0.1:8765."),
    /** Saved and enabled, but no verified reply yet (or busy with local work). */
    CONTACTING("Contacting…", "Saved. Waiting for the first verified Manager reply — reopen this screen to refresh."),
    /** Verified two-way communication. */
    ONLINE("Online", "Authenticated: the Manager accepts this key and replies."),
    /** The server refused the key (HTTP 401). */
    WRONG_KEY("Wrong key", "The Manager rejected the API key. Reissue it with agent-key on the Manager host."),
    /** No reply at all (network down, wrong port, Manager stopped). */
    UNREACHABLE("Cannot reach Manager", "No reply. Check the address, port and that the Manager is running."),
    /** The Manager serves a different Minecraft server (409). */
    WRONG_SERVER("Wrong Manager", "This Manager serves another Minecraft server. Point at the right one."),
    /** Replies arrive but are not signed with this key: never trusted. */
    SIGNATURE_INVALID("Reply not trusted", "The reply was not signed with this API key, so it was ignored.");

    private final String label;
    private final String hint;

    ConnectionState(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /**
     * Maps the module's raw status to a connection state. Unknown operational statuses (indexing,
     * reconciliation, physical work) mean the link is being worked, not verified: CONTACTING, never ONLINE.
     * Only the module's own "online" verdict — set after a verified reply — maps to ONLINE.
     */
    public static ConnectionState of(boolean active, String serverUrl, String apiKey, String status) {
        if (!active) return DISCONNECTED;
        if (serverUrl == null || serverUrl.isBlank() || apiKey == null || apiKey.isBlank()) return NOT_CONFIGURED;
        String s = status == null ? "" : status;
        return switch (s) {
            case "server URL not set", "no key" -> NOT_CONFIGURED;
            case "bad url" -> BAD_ADDRESS;
            case "error 401" -> WRONG_KEY;
            case "offline" -> UNREACHABLE;
            case "wrong server" -> WRONG_SERVER;
            case "server signature invalid" -> SIGNATURE_INVALID;
            case "online" -> ONLINE;
            default -> CONTACTING;
        };
    }
}
