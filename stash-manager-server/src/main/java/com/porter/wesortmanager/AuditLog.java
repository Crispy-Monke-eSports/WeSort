package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
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

    /**
     * A corrupt audit file is an operational error, not an empty history. Reading into
     * a temporary deque also keeps the previous view intact if a reload fails.
     */
    synchronized void load() {
        if (file == null) return;
        if (Files.isSymbolicLink(file))
            throw new IllegalStateException("Audit log is a symbolic link: " + file.getFileName());
        if (!Files.exists(file)) return;
        try {
            JsonArray list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonArray("entries");
            if (list == null) throw new IllegalArgumentException("Missing audit entries");
            ArrayDeque<JsonObject> loaded = new ArrayDeque<>();
            for (JsonElement e : list) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("Invalid audit entry");
                JsonObject item = e.getAsJsonObject();
                if (!item.has("at")
                        || !item.get("at").isJsonPrimitive()
                        || !item.getAsJsonPrimitive("at").isNumber()
                        || !item.has("action")
                        || !item.get("action").isJsonPrimitive()
                        || !item.getAsJsonPrimitive("action").isString())
                    throw new IllegalArgumentException("Audit entry missing timestamp or action");
                loaded.addLast(item.deepCopy());
            }
            while (loaded.size() > MAX) loaded.pollFirst();
            AtomicFiles.ownerOnly(file);
            entries.clear();
            entries.addAll(loaded);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot load " + file.getFileName() + "; refusing to discard audit history", e);
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
        // The disk write must succeed before the new entry becomes visible to
        // readers. A failed write must not create an in-memory-only audit event.
        ArrayDeque<JsonObject> updated = new ArrayDeque<>(entries);
        updated.addLast(o);
        while (updated.size() > MAX) updated.pollFirst();
        save(updated);
        entries.clear();
        entries.addAll(updated);
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
        for (Iterator<JsonObject> it = entries.descendingIterator(); it.hasNext() && out.size() < limit; )
            out.add(it.next().deepCopy());
        return out;
    }

    synchronized int size() {
        return entries.size();
    }

    private void save(ArrayDeque<JsonObject> updated) {
        if (file == null) return;
        JsonArray list = new JsonArray();
        updated.forEach(list::add);
        JsonObject root = new JsonObject();
        root.add("entries", list);
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Audit log must not be a symbolic link");
            AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot persist " + file.getFileName(), e);
        }
    }
}
