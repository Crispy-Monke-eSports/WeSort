package com.porter.wesort.data;

import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Identifies an inventory at a location, never an eternal identity for a movable box. */
public record ContainerIdentity(String server, String dimension, List<Position> parts) {
    public record Position(int x, int y, int z) implements Comparable<Position> {
        public int compareTo(Position other) {
            int n = Integer.compare(x, other.x); if (n == 0) n = Integer.compare(y, other.y);
            return n == 0 ? Integer.compare(z, other.z) : n;
        }
    }
    public ContainerIdentity {
        if (server == null || server.isBlank() || server.length() > 256 || dimension == null
            || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid world identity");
        if (parts == null || parts.isEmpty() || parts.size() > 2 || new HashSet<>(parts).size() != parts.size())
            throw new IllegalArgumentException("A container requires one or two distinct parts");
        parts = parts.stream().sorted().toList();
        if (parts.size() == 2) {
            var a = parts.getFirst(); var b = parts.getLast();
            if (a.y != b.y || Math.abs((long) a.x - b.x) + Math.abs((long) a.z - b.z) != 1)
                throw new IllegalArgumentException("Double chest parts must be horizontally adjacent");
        }
    }
    public String id() {
        JsonArray value = new JsonArray(); value.add(server); value.add(dimension);
        for (var p : parts) { JsonArray pos = new JsonArray(); pos.add(p.x); pos.add(p.y); pos.add(p.z); value.add(pos); }
        return "c:" + hash(value.toString());
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
