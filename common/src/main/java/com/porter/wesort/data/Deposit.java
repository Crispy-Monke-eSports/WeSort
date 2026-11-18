package com.porter.wesort.data;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * W-038: the verified physical deposition of a package into a chest. A deposit is either a {@code DELIVERY} to a
 * recipient outside the stock (a withdrawal: the guild total decreases) or a {@code TRANSFER} to an internal guild
 * stash (a relocation: the guild total does not change). The distinction is explicit and never inferred. A deposit
 * is {@code DELIVERED} only after a server-confirmed content snapshot; a replay of the same report must not produce a
 * second delivery, and an uncertain outcome goes to {@code NEEDS_RECONCILIATION}. Mutually exclusive custody means a
 * second agent cannot complete another agent's deposit without an explicit reconciliation.
 */
public record Deposit(String packageId, String orderId, Kind kind, String destination, String agent, State state, long revision) {
    public enum Kind { DELIVERY, TRANSFER }
    public enum State { DEPOSITING, DELIVERED, NEEDS_RECONCILIATION }

    public Deposit {
        if (packageId == null) throw new IllegalArgumentException("A deposit needs a package");
        UUID.fromString(packageId);
        if (orderId == null || orderId.isBlank() || orderId.length() > 64) throw new IllegalArgumentException("A deposit needs an order");
        if (destination == null || destination.isBlank() || destination.length() > 256) throw new IllegalArgumentException("A deposit needs a destination");
        if (revision < 1) throw new IllegalArgumentException("Invalid deposit revision");
    }

    /** Whether a deposit may move from its current state to {@code next}. */
    public boolean allows(State next) {
        return switch (state) {
            case DEPOSITING -> next == State.DELIVERED || next == State.NEEDS_RECONCILIATION;
            case DELIVERED -> next == State.NEEDS_RECONCILIATION;
            case NEEDS_RECONCILIATION -> false;
        };
    }

    public static Deposit begin(String packageId, String orderId, Kind kind, String destination, String agent) {
        return new Deposit(packageId, orderId, kind, destination, agent, State.DEPOSITING, 1);
    }

    /** Advances the deposit, enforcing the state graph and mutually exclusive custody (reconciliation is always open). */
    public Deposit advance(State next, String agent) {
        if (!allows(next)) throw new IllegalArgumentException("A deposit cannot move from " + state + " to " + next);
        if (next != State.NEEDS_RECONCILIATION && this.agent != null && !this.agent.equals(agent))
            throw new IllegalArgumentException("Another agent holds the deposit; reconciliation is required");
        return new Deposit(packageId, orderId, kind, destination, agent == null ? this.agent : agent, next, Math.addExact(revision, 1));
    }

    /** "withdrawal" for a DELIVERY, "relocation" for an internal TRANSFER. */
    public String netEffect() { return kind == Kind.DELIVERY ? "withdrawal" : "relocation"; }

    /** A DELIVERY removes stock from the guild total; a TRANSFER only moves it within the network. */
    public boolean reducesStock() { return kind == Kind.DELIVERY; }

    public JsonObject json() {
        var out = new JsonObject();
        out.addProperty("packageId", packageId);
        out.addProperty("orderId", orderId);
        out.addProperty("kind", kind.name());
        out.addProperty("destination", destination);
        out.addProperty("agent", agent);
        out.addProperty("state", state.name());
        out.addProperty("revision", revision);
        out.addProperty("netEffect", netEffect());
        return out;
    }

    public static Deposit read(JsonObject o) {
        return new Deposit(o.get("packageId").getAsString(), o.get("orderId").getAsString(),
            Kind.valueOf(o.get("kind").getAsString()), o.get("destination").getAsString(),
            o.has("agent") && !o.get("agent").isJsonNull() ? o.get("agent").getAsString() : null,
            State.valueOf(o.get("state").getAsString()), o.get("revision").getAsLong());
    }
}
