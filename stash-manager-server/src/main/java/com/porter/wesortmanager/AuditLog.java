package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.LongSupplier;

final class AuditLog {
    static final int MAX = 2000;
    static final int MAX_DETAIL = 300;

    private final Path file;
    private final LongSupplier clock;
    private final ArrayDeque<JsonObject> entries = new ArrayDeque<>(); // oldest first

    AuditLog(Path dataDir) {
        this(dataDir, System::currentTimeMillis);
    }

    AuditLog(Path dataDir, LongSupplier clock) {
        this.file = dataDir == null ? null : dataDir.resolve("audit.json");
        this.clock = clock;
    }

    synchronized void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("entries");
            for (JsonElement e : list) if (e.isJsonObject()) entries.addLast(e.getAsJsonObject());
            while (entries.size() > MAX) entries.pollFirst();
            AtomicFiles.ownerOnly(file);
        } catch (IOException | RuntimeException e) {
            System.err.println("Skipping " + file.getFileName() + " (the audit log starts empty): " + e);
        }
    }

    /** Adds an entry. who may be null (a login attempt for an unknown name). detail is cut to 300 characters. */
    void add(Users.Principal who, String action, String detail, String address) {
        add(who == null ? null : who.id(), who == null ? null : who.name(), action, detail, address);
    }

    synchronized void add(String userId, String userName, String action, String detail, String address) {
        JsonObject o = new JsonObject();
        o.addProperty("at", clock.getAsLong());
        o.addProperty("userId", userId);
        o.addProperty("user", userName);
        o.addProperty("action", action);
        o.addProperty("detail", detail == null ? null : Http.truncate(clean(detail), MAX_DETAIL));
        o.addProperty("address", address);
        entries.addLast(o);
        while (entries.size() > MAX) entries.pollFirst();
        save();
    }

    /** Control characters become spaces, so an entry is always one line. */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(c -> sb.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return sb.toString();
    }

    /** The newest entries first, at most limit. */
    synchronized JsonArray newest(int limit) {
        JsonArray out = new JsonArray();
        for (Iterator<JsonObject> it = entries.descendingIterator(); it.hasNext() && out.size() < limit; ) out.add(it.next().deepCopy());
        return out;
    }

    synchronized int size() {
        return entries.size();
    }

    private void save() {
        if (file == null) return;
        JsonArray list = new JsonArray();
        entries.forEach(list::add);
        JsonObject root = new JsonObject();
        root.add("entries", list);
        try {
            AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            System.err.println("Could not save " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
