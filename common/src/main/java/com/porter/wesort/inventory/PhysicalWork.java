package com.porter.wesort.inventory;

import com.google.gson.*;
import java.util.*;

/** Bounded physical phase tracking. Progress is evidence supplied by the native readback fence, never a click count. */
public final class PhysicalWork {
    public enum Phase { SELECT, PLACE, OPEN, TRANSFER, VERIFY, BREAK, PICKUP, STORE, COMPLETED, NEEDS_RECONCILIATION, FAILED }
    private final String id = UUID.randomUUID().toString();
    private final String kind;
    private final long started;
    private long since;
    private Phase phase = Phase.SELECT;
    private int attempts;
    private String reason = "";
    private final JsonArray events = new JsonArray();
    public PhysicalWork(String kind, long now) { this.kind = kind; started = since = now; event("selected"); }
    public Phase phase() { return phase; }
    public boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.NEEDS_RECONCILIATION || phase == Phase.FAILED; }
    public void enter(Phase next, long now) {
        if (terminal() || next == phase) return;
        phase = next; since = now; attempts = 0; event("phase");
    }
    public boolean attempt(long now) {
        if (expired(now) || terminal()) return false;
        if (++attempts > 3) { reconcile("Physical phase exceeded three verified attempts"); return false; }
        event("attempt"); return true;
    }
    public boolean expired(long now) {
        if (terminal()) return phase == Phase.NEEDS_RECONCILIATION;
        long limit = phase == Phase.PICKUP ? 120_000_000_000L : 60_000_000_000L;
        if (now - started > 300_000_000_000L || now - since > limit) reconcile("Physical phase exceeded its absolute deadline");
        return phase == Phase.NEEDS_RECONCILIATION;
    }
    public void reconcile(String reason) { if (!terminal()) { this.reason = reason; phase = Phase.NEEDS_RECONCILIATION; event("blocked"); } }
    public void failBeforeMutation(String reason) { if (!terminal()) { this.reason = reason; phase = Phase.FAILED; event("precondition"); } }
    public JsonObject json() {
        JsonObject out = new JsonObject(); out.addProperty("id", id); out.addProperty("kind", kind); out.addProperty("phase", phase.name());
        out.addProperty("attempts", attempts); out.addProperty("reason", reason); out.add("events", events.deepCopy()); return out;
    }
    private void event(String kind) {
        if (events.size() == 128) events.remove(0);
        var value = new JsonObject(); value.addProperty("phase", phase.name()); value.addProperty("event", kind); events.add(value);
    }
}
