package com.porter.wesort.data;

import com.google.gson.JsonObject;
import java.util.UUID;

/**
 * W-037: a registered delivery destination for internal distribution or member withdrawal from the same guild
 * network. A point is a chest or a player/project meeting place at server + dimension + coordinates, with an access
 * class and a status that decides whether it may be selected at all. Coordinates are secret: only a permissioned
 * operator may read them, and an internal or project destination never becomes a member's private stash — selecting a
 * point never changes who owns the rest of the stock.
 */
public record DeliveryPoint(
        String id,
        String server,
        String dimension,
        int x,
        int y,
        int z,
        Type type,
        Access access,
        String member,
        Status status,
        long revision) {
    public enum Type {
        CHEST,
        PLAYER_MEETING
    }

    public enum Access {
        MEMBER,
        PROJECT,
        INTERNAL
    }
    /** PENDING: registered but not yet reachable/verified; VERIFIED: selectable; PAUSED: changed/broken, blocked. */
    public enum Status {
        PENDING,
        VERIFIED,
        PAUSED
    }

    public DeliveryPoint {
        if (id == null) id = UUID.randomUUID().toString();
        UUID.fromString(id);
        if (server == null || server.isBlank() || server.length() > 256)
            throw new IllegalArgumentException("A delivery point needs a server");
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("Invalid dimension");
        for (int coordinate : new int[] {x, y, z})
            if (Math.abs((long) coordinate) > 30_000_000)
                throw new IllegalArgumentException("Delivery point outside world limits");
        if (access == Access.MEMBER && (member == null || member.isBlank() || member.length() > 64))
            throw new IllegalArgumentException("A member delivery point needs a member");
        if (access != Access.MEMBER && member != null)
            throw new IllegalArgumentException("Only a member delivery point names a member");
        if (revision < 1) throw new IllegalArgumentException("Invalid delivery point revision");
    }

    /** A new point from an owner request: a fresh id, PENDING until an agent verifies reachability, revision 1. */
    public static DeliveryPoint create(JsonObject o) {
        return new DeliveryPoint(
                null,
                str(o, "server"),
                str(o, "dimension"),
                integer(o, "x"),
                integer(o, "y"),
                integer(o, "z"),
                enumerated(o, "type", Type.class),
                enumerated(o, "access", Access.class),
                memberId(o),
                o.has("status") ? enumerated(o, "status", Status.class) : Status.PENDING,
                1);
    }

    public static DeliveryPoint read(JsonObject o) {
        return new DeliveryPoint(
                o.get("id").getAsString(),
                o.get("server").getAsString(),
                o.get("dimension").getAsString(),
                o.get("x").getAsInt(),
                o.get("y").getAsInt(),
                o.get("z").getAsInt(),
                Type.valueOf(o.get("type").getAsString()),
                Access.valueOf(o.get("access").getAsString()),
                o.has("member") && !o.get("member").isJsonNull()
                        ? o.get("member").getAsString()
                        : null,
                Status.valueOf(o.get("status").getAsString()),
                o.get("revision").getAsLong());
    }

    /** An optimistic update: present fields replace the current value and the revision increments. */
    public DeliveryPoint updated(JsonObject request) {
        var proposed = new DeliveryPoint(
                id,
                request.has("server") ? str(request, "server") : server,
                request.has("dimension") ? str(request, "dimension") : dimension,
                request.has("x") ? integer(request, "x") : x,
                request.has("y") ? integer(request, "y") : y,
                request.has("z") ? integer(request, "z") : z,
                request.has("type") ? enumerated(request, "type", Type.class) : type,
                request.has("access") ? enumerated(request, "access", Access.class) : access,
                request.has("member") ? memberId(request) : member,
                request.has("status") ? enumerated(request, "status", Status.class) : status,
                Math.addExact(revision, 1));
        boolean changed = !server.equals(proposed.server())
                || !dimension.equals(proposed.dimension())
                || x != proposed.x()
                || y != proposed.y()
                || z != proposed.z()
                || type != proposed.type()
                || access != proposed.access()
                || !java.util.Objects.equals(member, proposed.member());
        if (!changed || proposed.status() != Status.VERIFIED) return proposed;
        // Verification describes a particular physical target and recipient scope.
        // Do not carry it forward even if the update explicitly submits VERIFIED.
        return new DeliveryPoint(
                id,
                proposed.server(),
                proposed.dimension(),
                proposed.x(),
                proposed.y(),
                proposed.z(),
                proposed.type(),
                proposed.access(),
                proposed.member(),
                Status.PENDING,
                proposed.revision());
    }

    public JsonObject json() {
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("server", server);
        out.addProperty("dimension", dimension);
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("type", type.name());
        out.addProperty("access", access.name());
        out.addProperty("member", member);
        out.addProperty("status", status.name());
        out.addProperty("revision", revision);
        return out;
    }

    /** The same point without coordinates or dimension, for members who may select but never read a secret destination. */
    public JsonObject jsonRedacted() {
        var out = json();
        out.remove("x");
        out.remove("y");
        out.remove("z");
        out.remove("dimension");
        return out;
    }

    private static String str(JsonObject o, String field) {
        var e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(field + " is required");
        return e.getAsString();
    }

    private static int integer(JsonObject o, String field) {
        var e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(field + " is required");
        try {
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be an exact integer");
        }
    }

    private static <T extends Enum<T>> T enumerated(JsonObject o, String field, Class<T> type) {
        try {
            return Enum.valueOf(type, str(o, field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static String memberId(JsonObject o) {
        var e = o.get("member");
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive()
                || !e.getAsJsonPrimitive().isString()
                || !e.getAsString().matches("u-[0-9a-f]{12}"))
            throw new IllegalArgumentException("member must be an immutable Member account ID");
        return e.getAsString();
    }
}
