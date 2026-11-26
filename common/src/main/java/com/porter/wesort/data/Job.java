package com.porter.wesort.data;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;

/**
 * W-034: one schedulable unit of single-agent work. Categories {@code SCAN → SORT → PACK → PICKUP → DELIVER} form an
 * explicit prerequisite chain (each step needs its predecessor completed for the same work unit, the {@code chain}),
 * and {@code RECONCILE} is an override that requires no predecessor. A job moves {@code QUEUED → CLAIMED → (PAUSED)
 * → COMPLETED}, or to {@code NEEDS_RECONCILIATION} when its lease lapses mid-physical-work, and is only reassignable
 * after an operator reconciles it back to {@code QUEUED}. {@code REVOKED} is terminal. Priority orders the queue.
 */
public record Job(
        String id,
        Category category,
        String chain,
        String detail,
        String agent,
        State state,
        int priority,
        long revision) {
    public enum Category {
        SCAN,
        SORT,
        PACK,
        PICKUP,
        DELIVER,
        RECONCILE
    }

    public enum State {
        QUEUED,
        CLAIMED,
        PAUSED,
        COMPLETED,
        NEEDS_RECONCILIATION,
        REVOKED
    }

    public Job {
        if (id == null) id = UUID.randomUUID().toString();
        UUID.fromString(id);
        if (chain == null || chain.isBlank() || chain.length() > 64)
            throw new IllegalArgumentException("A job needs a work unit");
        if (detail == null || detail.length() > 512) throw new IllegalArgumentException("Invalid job detail");
        if (priority < 0) throw new IllegalArgumentException("Invalid job priority");
        if (revision < 1) throw new IllegalArgumentException("Invalid job revision");
    }

    /** The categories that must be COMPLETED for the same chain before this category may be claimed. */
    public static Set<Category> prerequisites(Category category) {
        return switch (category) {
            case SCAN, RECONCILE -> Set.of();
            case SORT -> Set.of(Category.SCAN);
            case PACK -> Set.of(Category.SORT);
            case PICKUP -> Set.of(Category.PACK);
            case DELIVER -> Set.of(Category.PICKUP);
        };
    }

    public boolean allows(State next) {
        return switch (state) {
            case QUEUED -> next == State.CLAIMED || next == State.REVOKED;
            case CLAIMED ->
                next == State.PAUSED
                        || next == State.COMPLETED
                        || next == State.NEEDS_RECONCILIATION
                        || next == State.REVOKED;
            case PAUSED -> next == State.CLAIMED || next == State.NEEDS_RECONCILIATION || next == State.REVOKED;
            case NEEDS_RECONCILIATION -> next == State.QUEUED || next == State.COMPLETED || next == State.REVOKED;
            case COMPLETED, REVOKED -> false;
        };
    }

    public boolean terminal() {
        return state == State.COMPLETED || state == State.REVOKED;
    }

    /** Whether the agent currently holds this job (owns an in-flight physical transaction). */
    public boolean held() {
        return state == State.CLAIMED || state == State.PAUSED;
    }

    /** Advances the job. Claiming sets the owner; reconciling back to QUEUED clears it; every other move keeps it. */
    public Job advance(State next, String agent) {
        if (!allows(next))
            throw new IllegalArgumentException("A " + category + " job cannot move from " + state + " to " + next);
        String owner = switch (next) {
            case CLAIMED -> agent;
            case QUEUED -> null;
            default -> this.agent;
        };
        return new Job(id, category, chain, detail, owner, next, priority, Math.addExact(revision, 1));
    }

    public JsonObject json() {
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("category", category.name());
        out.addProperty("chain", chain);
        out.addProperty("detail", detail);
        out.addProperty("agent", agent);
        out.addProperty("state", state.name());
        out.addProperty("priority", priority);
        out.addProperty("revision", revision);
        return out;
    }

    public static Job read(JsonObject o) {
        return new Job(
                o.get("id").getAsString(),
                Category.valueOf(o.get("category").getAsString()),
                o.get("chain").getAsString(),
                o.get("detail").getAsString(),
                o.has("agent") && !o.get("agent").isJsonNull() ? o.get("agent").getAsString() : null,
                State.valueOf(o.get("state").getAsString()),
                o.get("priority").getAsInt(),
                o.get("revision").getAsLong());
    }
}
