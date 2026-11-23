package com.porter.wesort.inventory;

import com.google.gson.*;
import com.porter.wesort.data.CanonicalJson;
import com.porter.wesort.data.ContainerIdentity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.LongSupplier;

/** Durable intent/receipt chain. Coordinates are private evidence, never an audit-log message. */
public final class PhysicalJournal {
    public static final String FORMAT = "wesort-physical-journal:1";
    public static final int MAX_BYTES = 4 * 1024 * 1024, MAX_EVENTS = 4096;
    private final Path folder, active;
    private final LongSupplier clock;
    private JsonObject value, durable;

    public PhysicalJournal(Path folder) throws IOException {
        this(folder, System::currentTimeMillis);
    }

    public PhysicalJournal(Path folder, LongSupplier clock) throws IOException {
        this.folder = folder;
        this.active = folder.resolve("active.json");
        this.clock = clock;
        if (Files.isSymbolicLink(folder) || Files.isSymbolicLink(active))
            throw new IOException("Symlink physical journal");
        Files.createDirectories(folder);
        mode(folder, "rwx------");
        if (Files.exists(active)) {
            value = read(active);
            durable = value.deepCopy();
            if (!terminal() && !blocked()) {
                value.addProperty("interruptedPhase", value.get("phase").getAsString());
                value.addProperty("state", "NEEDS_RECONCILIATION");
                event("RESTART", new JsonObject());
            }
        }
    }

    public synchronized JsonObject current() {
        return value == null ? null : value.deepCopy();
    }

    public synchronized String id() {
        return value == null ? "" : value.get("id").getAsString();
    }

    public synchronized long revision() {
        return value == null ? 0 : value.get("revision").getAsLong();
    }

    public synchronized String checksum() {
        return value == null ? "" : value.get("checksum").getAsString();
    }

    public synchronized JsonObject context() {
        return value == null ? null : value.getAsJsonObject("context").deepCopy();
    }

    public synchronized boolean blocked() {
        return value != null && value.get("state").getAsString().equals("NEEDS_RECONCILIATION");
    }

    public synchronized boolean terminal() {
        return value != null
                && List.of("COMPLETED", "FAILED").contains(value.get("state").getAsString());
    }

    public synchronized boolean pending() {
        return value != null && lastIntent() > value.get("verifiedThrough").getAsLong();
    }

    public synchronized void start(JsonObject context) throws IOException {
        if (value != null && !terminal()) throw new IOException("Unresolved physical operation");
        for (String key : List.of("server", "dimension", "agent", "kind")) {
            String text = context.get(key).getAsString();
            if (text.isBlank() || text.length() > 256)
                throw new IllegalArgumentException("Invalid physical journal scope");
        }
        UUID.fromString(context.get("agent").getAsString());
        if (context.toString().length() > 128_000) throw new IllegalArgumentException("Physical context too large");
        if (value != null) {
            Path archive = folder.resolve("completed");
            Files.createDirectories(archive);
            mode(archive, "rwx------");
            Path file = archive.resolve(value.get("id").getAsString() + ".json");
            if (Files.exists(file)) {
                if (!read(file).equals(value)) throw new IOException("Conflicting immutable physical history");
            } else write(file, value);
        }
        value = new JsonObject();
        value.addProperty("format", FORMAT);
        value.addProperty("id", UUID.randomUUID().toString());
        value.add("context", context.deepCopy());
        value.addProperty("state", "PLANNED");
        value.addProperty("phase", "SELECT");
        value.addProperty("createdAt", clock.getAsLong());
        value.addProperty("revision", 0);
        value.addProperty("verifiedThrough", 0);
        value.add("events", new JsonArray());
        event("PLANNED", new JsonObject());
    }

    public synchronized void phase(String phase, JsonObject evidence) throws IOException {
        requireActive();
        if (blocked() || terminal()) return;
        PhysicalWork.Phase.valueOf(phase);
        if (phase.equals(value.get("phase").getAsString())) return;
        value.addProperty("phase", phase);
        value.addProperty("state", List.of("VERIFY", "STORE").contains(phase) ? "VERIFYING" : "IN_PROGRESS");
        event("PHASE", evidence);
    }
    /** Must complete before the caller transmits the corresponding packet. */
    public synchronized void intent(JsonObject evidence) throws IOException {
        requireActive();
        if (blocked() || terminal()) throw new IOException("Physical operation is fenced");
        if (!evidence.has("before") || !evidence.has("expected") || !evidence.has("origin"))
            throw new IllegalArgumentException("Physical intent needs exact postcondition and source scope");
        var after = expected(evidence.getAsJsonObject("expected"));
        var before = expected(evidence.getAsJsonObject("before"));
        var intent = evidence.deepCopy();
        intent.add("predictedDiff", diff(before, after));
        value.addProperty("state", "IN_PROGRESS");
        event("INTENT", intent);
    }

    public synchronized JsonObject lastIntentEvidence() {
        if (value == null) return null;
        var events = value.getAsJsonArray("events");
        for (int i = events.size() - 1; i >= 0; i--) {
            var event = events.get(i).getAsJsonObject();
            if (event.get("type").getAsString().equals("INTENT"))
                return event.getAsJsonObject("evidence").deepCopy();
        }
        return null;
    }
    /** Native FIFO barrier already bound the fresh frame to the exact source. No packet is retried here. */
    public synchronized void verified(JsonObject frame, boolean sourceReconciled) throws IOException {
        verified(frame, sourceReconciled, null, null);
    }

    public synchronized void verified(
            JsonObject frame, boolean sourceReconciled, JsonObject playerInventory, String decisionId)
            throws IOException {
        requireActive();
        if (!pending() && !sourceReconciled) return;
        JsonObject intent = lastIntentEvidence();
        if (intent == null) return;
        var wanted = expected(intent.getAsJsonObject("expected"));
        var actual = expected(frame);
        if (sourceReconciled)
            wanted = new InventoryReadback.Expected(
                    actual.menu(), wanted.slots(), wanted.cursor(), wanted.alternatives());
        if (!wanted.matches(actual)) throw new IOException("Physical receipt differs from the recorded postcondition");
        if (blocked() && !sourceReconciled) throw new IOException("Explicit source reconciliation required");
        long verifiedThrough = lastIntent();
        var evidence = new JsonObject();
        evidence.add("frame", frame.deepCopy());
        evidence.addProperty("sourceReconciled", sourceReconciled);
        evidence.addProperty("verifiedThrough", verifiedThrough);
        evidence.add("inventoryDiff", diff(expected(intent.getAsJsonObject("before")), actual));
        if (playerInventory != null) evidence.add("playerInventory", playerInventory.deepCopy());
        if (decisionId != null)
            evidence.addProperty("decisionId", UUID.fromString(decisionId).toString());
        validateInspectionEvidence(evidence);
        value.addProperty("verifiedThrough", verifiedThrough);
        value.addProperty("state", "VERIFYING");
        event(sourceReconciled ? "RECONCILED" : "VERIFIED", evidence);
    }

    public synchronized void inspectedWithoutMutation(JsonObject frame, JsonObject playerInventory, String decisionId)
            throws IOException {
        requireActive();
        if (lastIntent() != 0) throw new IOException("Recorded physical action requires its exact postcondition");
        expected(frame);
        var evidence = new JsonObject();
        evidence.add("frame", frame.deepCopy());
        if (playerInventory != null) evidence.add("playerInventory", playerInventory.deepCopy());
        if (decisionId != null)
            evidence.addProperty("decisionId", UUID.fromString(decisionId).toString());
        validateInspectionEvidence(evidence);
        event("INSPECTED_NO_MUTATION", evidence);
    }

    public synchronized void complete() throws IOException {
        requireActive();
        if (terminal()) return;
        if (blocked() || pending()) throw new IOException("Unverified physical work cannot complete");
        value.addProperty("state", "COMPLETED");
        value.addProperty("phase", "COMPLETED");
        event("COMPLETED", new JsonObject());
    }

    public synchronized void block(String reason) throws IOException {
        if (value == null || terminal() || blocked()) return;
        value.addProperty("state", "NEEDS_RECONCILIATION");
        var evidence = new JsonObject();
        evidence.addProperty("reason", bounded(reason));
        event("BLOCKED", evidence);
    }

    public synchronized void failBeforeMutation(String reason) throws IOException {
        requireActive();
        if (lastIntent() != 0) throw new IOException("Physical work with transmitted intent must be reconciled");
        value.addProperty("state", "FAILED");
        var evidence = new JsonObject();
        evidence.addProperty("reason", bounded(reason));
        event("FAILED", evidence);
    }

    public synchronized List<JsonObject> records() throws IOException {
        List<JsonObject> out = new ArrayList<>();
        Path history = folder.resolve("completed");
        if (Files.isSymbolicLink(history)) throw new IOException("Symlink physical history");
        if (Files.isDirectory(history))
            try (var files = Files.list(history)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
                        .sorted()
                        .toList()) out.add(read(file));
            }
        if (value != null) out.add(value.deepCopy());
        return List.copyOf(out);
    }

    private void requireActive() {
        if (value == null) throw new IllegalStateException("No physical operation");
    }

    private long lastIntent() {
        if (value == null) return 0;
        var events = value.getAsJsonArray("events");
        for (int i = events.size() - 1; i >= 0; i--) {
            var row = events.get(i).getAsJsonObject();
            if (row.get("type").getAsString().equals("INTENT"))
                return row.get("seq").getAsLong();
        }
        return 0;
    }

    private static String bounded(String reason) {
        return reason == null ? "" : reason.substring(0, Math.min(1000, reason.length()));
    }

    private void event(String type, JsonObject evidence) throws IOException {
        var events = value.getAsJsonArray("events");
        if (events.size() >= MAX_EVENTS) {
            value = durable == null ? null : durable.deepCopy();
            throw new IOException("Physical journal event bound reached; do not transmit more actions");
        }
        var row = new JsonObject();
        long revision = Math.addExact(value.get("revision").getAsLong(), 1);
        row.addProperty("seq", revision);
        row.addProperty("at", clock.getAsLong());
        row.addProperty("type", type);
        row.addProperty("phase", value.get("phase").getAsString());
        row.addProperty(
                "previous",
                events.isEmpty()
                        ? ""
                        : events.get(events.size() - 1)
                                .getAsJsonObject()
                                .get("hash")
                                .getAsString());
        row.add("evidence", evidence.deepCopy());
        row.addProperty("hash", ContainerIdentity.hash(CanonicalJson.stringify(row)));
        events.add(row);
        value.addProperty("revision", revision);
        value.addProperty("updatedAt", clock.getAsLong());
        value.remove("checksum");
        value.addProperty("checksum", ContainerIdentity.hash(CanonicalJson.stringify(value)));
        try {
            write(active, value);
            durable = value.deepCopy();
        } catch (IOException failed) {
            value = durable == null ? null : durable.deepCopy();
            throw failed;
        }
    }

    public static JsonObject validate(JsonObject input) {
        var value = input.deepCopy();
        String checksum = value.remove("checksum").getAsString();
        if (!checksum.equals(ContainerIdentity.hash(CanonicalJson.stringify(value))))
            throw new IllegalArgumentException("Physical journal checksum mismatch");
        value.addProperty("checksum", checksum);
        if (!FORMAT.equals(value.get("format").getAsString()))
            throw new IllegalArgumentException("Foreign physical journal format");
        UUID.fromString(value.get("id").getAsString());
        var context = value.getAsJsonObject("context");
        UUID.fromString(context.get("agent").getAsString());
        for (String key : List.of("server", "dimension", "agent", "kind"))
            if (context.get(key).getAsString().isBlank()) throw new IllegalArgumentException("Missing journal scope");
        if (!List.of("PLANNED", "IN_PROGRESS", "VERIFYING", "COMPLETED", "NEEDS_RECONCILIATION", "FAILED")
                .contains(value.get("state").getAsString()))
            throw new IllegalArgumentException("Unknown physical state");
        PhysicalWork.Phase.valueOf(value.get("phase").getAsString());
        var events = value.getAsJsonArray("events");
        if (events.isEmpty() || events.size() > MAX_EVENTS)
            throw new IllegalArgumentException("Invalid physical event count");
        long sequence = 0, verified = 0, pending = 0;
        JsonObject intent = null;
        String previous = "", state = null, phase = null, interruptedPhase = null;
        for (var entry : events) {
            var row = entry.getAsJsonObject().deepCopy();
            String hash = row.remove("hash").getAsString();
            if (exactLong(row, "seq") != ++sequence
                    || !previous.equals(row.get("previous").getAsString())
                    || !hash.equals(ContainerIdentity.hash(CanonicalJson.stringify(row))))
                throw new IllegalArgumentException("Broken physical evidence chain");
            previous = hash;
            String type = row.get("type").getAsString();
            var evidence = row.getAsJsonObject("evidence");
            if (evidence == null) throw new IllegalArgumentException("Missing physical evidence");
            String nextPhase = row.get("phase").getAsString();
            PhysicalWork.Phase.valueOf(nextPhase);
            boolean fenced =
                    List.of("NEEDS_RECONCILIATION", "COMPLETED", "FAILED").contains(state == null ? "" : state);
            if (sequence == 1 && !type.equals("PLANNED"))
                throw new IllegalArgumentException("Physical journal must begin with a plan");
            if (sequence > 1 && !type.equals("PHASE") && !type.equals("COMPLETED") && !nextPhase.equals(phase))
                throw new IllegalArgumentException("Unexpected physical phase change");
            switch (type) {
                case "PLANNED" -> {
                    if (sequence != 1 || !nextPhase.equals("SELECT"))
                        throw new IllegalArgumentException("Unexpected physical plan");
                    state = "PLANNED";
                }
                case "PHASE" -> {
                    if (fenced || nextPhase.equals(phase))
                        throw new IllegalArgumentException("Unexpected physical phase event");
                    state = List.of("VERIFY", "STORE").contains(nextPhase) ? "VERIFYING" : "IN_PROGRESS";
                }
                case "INTENT" -> {
                    if (fenced || !evidence.has("origin"))
                        throw new IllegalArgumentException("Unexpected physical intent");
                    state = "IN_PROGRESS";
                }
                case "VERIFIED" -> {
                    if (fenced) throw new IllegalArgumentException("Fenced physical receipt");
                    state = "VERIFYING";
                }
                case "RECONCILED" -> state = "VERIFYING";
                case "INSPECTED_NO_MUTATION" -> {
                    if (pending != 0) throw new IllegalArgumentException("Physical intent requires reconciliation");
                    expected(evidence.getAsJsonObject("frame"));
                }
                case "RESTART", "BLOCKED" -> {
                    if (fenced) throw new IllegalArgumentException("Unexpected physical fence");
                    if (type.equals("RESTART")) interruptedPhase = phase;
                    state = "NEEDS_RECONCILIATION";
                }
                case "COMPLETED" -> {
                    if (fenced || pending > verified || !nextPhase.equals("COMPLETED"))
                        throw new IllegalArgumentException("Unverified completed journal");
                    state = "COMPLETED";
                }
                case "FAILED" -> {
                    if (pending != 0)
                        throw new IllegalArgumentException("Physical intent cannot fail without reconciliation");
                    state = "FAILED";
                }
                default -> throw new IllegalArgumentException("Unknown physical event");
            }
            phase = nextPhase;
            if (type.equals("RECONCILED") || type.equals("INSPECTED_NO_MUTATION")) validateInspectionEvidence(evidence);
            if (type.equals("INTENT")) {
                pending = sequence;
                intent = evidence;
                expected(intent.getAsJsonObject("before"));
                expected(intent.getAsJsonObject("expected"));
            }
            if (type.equals("VERIFIED") || type.equals("RECONCILED")) {
                if (intent == null
                        || pending < verified
                        || type.equals("VERIFIED") && pending == verified
                        || exactLong(evidence, "verifiedThrough") != pending)
                    throw new IllegalArgumentException("Unbound physical receipt");
                var wanted = expected(intent.getAsJsonObject("expected"));
                var actual = expected(evidence.getAsJsonObject("frame"));
                if (type.equals("RECONCILED"))
                    wanted = new InventoryReadback.Expected(
                            actual.menu(), wanted.slots(), wanted.cursor(), wanted.alternatives());
                if (!wanted.matches(actual)) throw new IllegalArgumentException("Invalid recorded postcondition");
                verified = pending;
            }
        }
        if (exactLong(value, "revision") != sequence || exactLong(value, "verifiedThrough") != verified)
            throw new IllegalArgumentException("Invalid journal revision");
        if (!state.equals(value.get("state").getAsString())
                || !phase.equals(value.get("phase").getAsString())
                || !Objects.equals(
                        interruptedPhase,
                        value.has("interruptedPhase")
                                ? value.get("interruptedPhase").getAsString()
                                : null))
            throw new IllegalArgumentException("Physical journal header disagrees with evidence");
        return value;
    }

    private static long exactLong(JsonObject object, String field) {
        try {
            var element = object.get(field);
            if (element == null
                    || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Invalid journal number");
            return element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid journal number", invalid);
        }
    }

    private static void validateInspectionEvidence(JsonObject evidence) {
        if (!evidence.has("decisionId")) return;
        UUID.fromString(evidence.get("decisionId").getAsString());
        var inventory = evidence.getAsJsonObject("playerInventory");
        int size = inventory.get("size").getAsBigDecimal().intValueExact();
        var reader = new com.porter.wesort.data.PhysicalSlots.Reader(inventory.getAsJsonArray("slotVariants"));
        var actual = reader.snapshot(size, inventory.getAsJsonArray("slots"));
        if (size != 46
                || !actual.fingerprint().equals(inventory.get("fingerprint").getAsString())) {
            throw new IllegalArgumentException("Invalid full player inventory inspection");
        }
    }

    private static JsonObject diff(InventoryReadback.Expected before, InventoryReadback.Expected after) {
        var out = new JsonObject();
        var slots = new JsonArray();
        if (before.slots().size() == after.slots().size())
            for (int slot = 0; slot < before.slots().size(); slot++) {
                if (before.slots().get(slot).equals(after.slots().get(slot))) continue;
                var row = new JsonObject();
                row.addProperty("slot", slot);
                row.addProperty("before", before.slots().get(slot));
                row.addProperty("after", after.slots().get(slot));
                slots.add(row);
            }
        out.add("slots", slots);
        out.addProperty("cursorBefore", before.cursor());
        out.addProperty("cursorAfter", after.cursor());
        return out;
    }

    private static InventoryReadback.Expected expected(JsonObject frame) {
        List<String> slots = new ArrayList<>();
        for (var value : frame.getAsJsonArray("slots")) {
            String fingerprint = value.getAsString();
            if (fingerprint.length() > 64_000) throw new IllegalArgumentException("Oversized physical fingerprint");
            slots.add(fingerprint);
        }
        if (slots.isEmpty() || slots.size() > 256)
            throw new IllegalArgumentException("Invalid physical inventory frame");
        Map<Integer, Set<String>> alternatives = new HashMap<>();
        if (frame.has("alternatives"))
            for (var entry : frame.getAsJsonObject("alternatives").entrySet()) {
                Set<String> choices = new HashSet<>();
                entry.getValue().getAsJsonArray().forEach(v -> choices.add(v.getAsString()));
                alternatives.put(Integer.parseInt(entry.getKey()), choices);
            }
        return new InventoryReadback.Expected(
                frame.get("menu").getAsInt(), slots, frame.get("cursor").getAsString(), alternatives);
    }

    private static JsonObject read(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || Files.size(file) > MAX_BYTES)
            throw new IOException("Invalid physical journal file");
        try {
            return validate(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot validate physical evidence; file left unchanged", invalid);
        }
    }

    private static void write(Path target, JsonObject value) throws IOException {
        byte[] data = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IOException("Physical journal exceeds byte bound");
        Path temp = Files.createTempFile(target.getParent(), "wesort-physical-", ".tmp");
        try {
            mode(temp, "rw-------");
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void mode(Path file, String mode) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException ignored) {
        }
    }
}
