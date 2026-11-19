package com.porter.wesort.data;

import com.google.gson.JsonObject;
import java.util.UUID;

/**
 * W-039: a direct handoff of a package to a player at a registered meeting place. A handoff is
 * {@code SCHEDULED → AWAITING_RECIPIENT → DELIVERED} only with an explicit recipient receipt ({@code confirmedBy}),
 * never because a drop command ran: a drop without a verified pickup goes to {@code NEEDS_RECONCILIATION}. A no-show
 * is {@code NO_SHOW} (traceable, and the stock is not returned until an explicit {@code RETURNED}), and an uncertain
 * handoff is resolved by the owner/admin back to {@code DELIVERED} (with a receipt) or {@code RETURNED}. "Receipt"
 * and physical takeover are never conflated: the receipt records who confirmed, not that the item changed hands.
 */
public record Handoff(
        String id,
        String orderId,
        String recipient,
        String meetingPoint,
        String agent,
        String confirmedBy,
        State state,
        long revision) {
    public enum State {
        SCHEDULED,
        AWAITING_RECIPIENT,
        DELIVERED,
        NO_SHOW,
        NEEDS_RECONCILIATION,
        RETURNED
    }

    public Handoff {
        if (id == null) id = UUID.randomUUID().toString();
        UUID.fromString(id);
        if (orderId == null || orderId.isBlank() || orderId.length() > 64)
            throw new IllegalArgumentException("A handoff needs an order");
        if (recipient == null || recipient.isBlank() || recipient.length() > 64)
            throw new IllegalArgumentException("A handoff needs a recipient");
        if (meetingPoint == null || meetingPoint.isBlank() || meetingPoint.length() > 256)
            throw new IllegalArgumentException("A handoff needs a meeting point");
        if (revision < 1) throw new IllegalArgumentException("Invalid handoff revision");
    }

    public boolean allows(State next) {
        return switch (state) {
            case SCHEDULED ->
                next == State.AWAITING_RECIPIENT || next == State.NEEDS_RECONCILIATION || next == State.RETURNED;
            case AWAITING_RECIPIENT ->
                next == State.DELIVERED
                        || next == State.NO_SHOW
                        || next == State.NEEDS_RECONCILIATION
                        || next == State.RETURNED;
            case NO_SHOW -> next == State.RETURNED || next == State.AWAITING_RECIPIENT;
            case NEEDS_RECONCILIATION -> next == State.DELIVERED || next == State.RETURNED;
            case DELIVERED, RETURNED -> false;
        };
    }

    public boolean terminal() {
        return state == State.DELIVERED || state == State.RETURNED;
    }

    public static Handoff schedule(String orderId, String recipient, String meetingPoint, String agent) {
        return new Handoff(null, orderId, recipient, meetingPoint, agent, null, State.SCHEDULED, 1);
    }

    /**
     * Advances the handoff. {@code DELIVERED} requires a non-blank receipt actor ({@code confirmedBy}) — a bare drop
     * can never reach DELIVERED. Every other move keeps the receipt actor, and only the recorded agent may move a
     * scheduled/awaiting handoff toward arrival, no-show or an unverified drop.
     */
    public Handoff advance(State next, String actor) {
        if (!allows(next)) throw new IllegalArgumentException("A handoff cannot move from " + state + " to " + next);
        if (next == State.DELIVERED && (actor == null || actor.isBlank()))
            throw new IllegalArgumentException("DELIVERED requires a recipient receipt");
        return new Handoff(
                id,
                orderId,
                recipient,
                meetingPoint,
                agent,
                next == State.DELIVERED ? actor : confirmedBy,
                next,
                Math.addExact(revision, 1));
    }

    public JsonObject json() {
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("orderId", orderId);
        out.addProperty("recipient", recipient);
        out.addProperty("meetingPoint", meetingPoint);
        out.addProperty("agent", agent);
        out.addProperty("confirmedBy", confirmedBy);
        out.addProperty("state", state.name());
        out.addProperty("revision", revision);
        return out;
    }

    public static Handoff read(JsonObject o) {
        return new Handoff(
                o.get("id").getAsString(),
                o.get("orderId").getAsString(),
                o.get("recipient").getAsString(),
                o.get("meetingPoint").getAsString(),
                o.has("agent") && !o.get("agent").isJsonNull() ? o.get("agent").getAsString() : null,
                o.has("confirmedBy") && !o.get("confirmedBy").isJsonNull()
                        ? o.get("confirmedBy").getAsString()
                        : null,
                State.valueOf(o.get("state").getAsString()),
                o.get("revision").getAsLong());
    }
}
