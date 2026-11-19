package com.porter.wesort.manager.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class AuthState {
    /** A server without sub-users sends no user: that is the owner, who can do everything. */
    private static final String[] OWNER_FLAGS = {"view", "order", "ordersAll", "activity", "settings", "rescan", "link"
    };

    private final boolean loggedIn;
    private final boolean setupNeeded;
    private final boolean setupAllowed;
    private final int minLength;
    private final long elevatedUntil;
    private final boolean owner;
    private final boolean mustChange;
    private final String userId;
    private final String userName;
    private final JsonObject perms;
    private final String message;

    private AuthState(
            boolean loggedIn,
            boolean setupNeeded,
            boolean setupAllowed,
            int minLength,
            long elevatedUntil,
            boolean owner,
            boolean mustChange,
            String userId,
            String userName,
            JsonObject perms,
            String message) {
        this.loggedIn = loggedIn;
        this.setupNeeded = setupNeeded;
        this.setupAllowed = setupAllowed;
        this.minLength = minLength;
        this.elevatedUntil = elevatedUntil;
        this.owner = owner;
        this.mustChange = mustChange;
        this.userId = userId;
        this.userName = userName;
        this.perms = perms;
        this.message = message;
    }

    /** Never null: anything that isn't an object reads as logged out. */
    public static AuthState parse(JsonElement body) {
        JsonObject o = Json.obj(body);
        if (o == null) return new AuthState(false, false, false, 12, 0, true, false, "", "", new JsonObject(), "");
        boolean loggedIn = Json.bool(o, "loggedIn");
        int min = (int) Json.num(o, "minLength", 12);
        JsonObject u = Json.obj(o.get("user"));
        boolean owner = true;
        boolean mustChange = false;
        String id = "owner";
        String name = "";
        JsonObject perms = new JsonObject();
        if (u != null) {
            owner = Json.bool(u, "owner");
            mustChange = Json.bool(u, "mustChange");
            id = Json.str(u, "id");
            name = Json.str(u, "name");
            JsonObject p = Json.obj(u.get("perms"));
            if (p != null) perms = p;
            if (!owner && id.isEmpty()) id = "?";
        }
        return new AuthState(
                loggedIn,
                Json.bool(o, "setupNeeded"),
                Json.bool(o, "setupAllowed"),
                min < 1 ? 12 : min,
                Json.lng(o, "elevatedUntil", 0),
                owner,
                mustChange,
                id,
                name,
                perms,
                Json.str(o, "message"));
    }

    public boolean loggedIn() {
        return loggedIn;
    }

    public boolean setupNeeded() {
        return setupNeeded;
    }

    public boolean setupAllowed() {
        return setupAllowed;
    }

    public int minLength() {
        return minLength;
    }

    /** Server time (ms) until which this session is in sudo mode; 0 when it isn't. */
    public long elevatedUntil() {
        return elevatedUntil;
    }

    public boolean owner() {
        return owner;
    }

    public boolean mustChange() {
        return mustChange;
    }

    public String userId() {
        return userId;
    }

    public String userName() {
        return userName;
    }

    /** The server's note, e.g. why setup has to be done elsewhere. */
    public String message() {
        return message;
    }

    /** A yes/no permission. The owner has every one except streamer. */
    public boolean can(String key) {
        if (owner) return !key.equals("streamer") && contains(OWNER_FLAGS, key);
        JsonElement e = perms.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** "proxy" (none, read, change) or "console" (none, list, full). */

    /** The owner forced streamer mode on for this user. The owner can never be forced. */
    public boolean forcedStreamer() {
        return loggedIn && !owner && can("streamer");
    }

    private static boolean contains(String[] list, String key) {
        for (String s : list) if (s.equals(key)) return true;
        return false;
    }
}
