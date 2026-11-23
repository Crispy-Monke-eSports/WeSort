package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import com.porter.wesort.data.DataDirectory;
import com.porter.wesort.inventory.PhysicalJournal;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import java.util.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Private durable execution evidence. Recovery performs reads, never retransmits an item mutation. */
public final class PhysicalJournalRuntime {
    private static PhysicalJournal journal;
    private static String failure = "";
    private static boolean recoveryFence;
    private static String mirroredId = "";
    private static long mirroredRevision;
    private static long nextUpload;
    private static String lastUpload = "";
    private static String managerScope = "";
    private static JsonObject inspection, inspectionResult, inspectedSource;
    private static boolean inspectInventoryPending;

    private PhysicalJournalRuntime() {}

    private static boolean reconcileRequired;

    public static void initialize() {
        try {
            journal = new PhysicalJournal(DataDirectory.clientFolder(MeteorClient.FOLDER.toPath(), "physical-journal"));
            recoveryFence = journal.blocked();
            // An uncertain operation stays fenced until an exact read-only inspection
            // proves it safe. No mutation is ever retransmitted during recovery.
            var current = journal.current();
            reconcileRequired = current != null && current.has("interruptedPhase");
        } catch (Exception invalid) {
            failure = "Cannot validate durable physical evidence; automatic input is disabled";
            MeteorClient.LOG.error(failure);
        }
    }

    private static String managerScope() {
        var manager = Modules.get().get(StashManager.class);
        return manager == null ? "" : manager.linkScope();
    }

    public static boolean blocked() {
        return !failure.isEmpty() || recoveryFence || journal != null && journal.blocked();
    }

    public static boolean awaitingMirror() {
        if (journal == null || !journal.terminal() || mc.player == null) return false;
        String scope = managerScope();
        if (scope.isEmpty()) return false;
        var context = journal.context();
        return Stash.currentServer().equals(context.get("server").getAsString())
                && mc.player.getUUID().toString().equals(context.get("agent").getAsString())
                && (!managerScope.equals(scope) || !mirroredCurrent());
    }

    private static boolean mirroredCurrent() {
        return mirroredId.equals(journal.id()) && mirroredRevision >= journal.revision();
    }

    public static boolean unfinished() {
        return journal != null && journal.revision() != 0 && !journal.terminal();
    }

    public static String status() {
        return !failure.isEmpty()
                ? failure
                : blocked() ? "NEEDS_RECONCILIATION" : awaitingMirror() ? "WAITING_MANAGER_RECEIPT" : "READY";
    }

    private static JsonObject context() {
        var sorter = com.porter.wesort.WeSortAddon.sorter();
        if (sorter != null) return sorter.physicalJournalContext();
        var out = new JsonObject();
        out.addProperty("server", Stash.currentServer());
        out.addProperty("dimension", StashIndex.dimension());
        out.addProperty("agent", mc.player.getUUID().toString());
        out.addProperty("kind", "DIRECT");
        return out;
    }

    private static void ensure() throws Exception {
        if (journal == null || blocked()) throw new IllegalStateException(status());
        var context = context();
        var previous = journal.context();
        if (previous == null || journal.terminal()) {
            if (awaitingMirror())
                throw new IllegalStateException("Previous physical receipt is not durable in Manager yet");
            journal.start(context);
            return;
        }
        for (String key : List.of("server", "dimension", "agent", "kind", "jobId", "orderId"))
            if (!Objects.equals(previous.get(key), context.get(key)))
                throw new IllegalStateException("Another physical operation needs reconciliation");
    }

    public static void adoptPending(JsonObject pending) throws Exception {
        if (journal == null || unfinished()) return;
        var origin = pending.getAsJsonObject("origin");
        var context = new JsonObject();
        context.add("server", origin.get("server"));
        context.add("dimension", origin.get("dimension"));
        context.add("agent", origin.get("player"));
        context.addProperty("kind", "DIRECT");
        context.addProperty("adoptedPending", true);
        var previous = journal.current();
        if (previous != null) context.add("previousOperation", previous.get("id"));
        journal.start(context);
        var evidence = pending.deepCopy();
        evidence.add("before", pending.get("expected").deepCopy());
        evidence.addProperty("beforeUnknown", true);
        journal.intent(evidence);
        journal.block("Existing WeSort pending action requires fresh read-only reconciliation; never retransmit it");
        recoveryFence = true;
    }

    public static void inspectionFailed(String detail) {
        if (inspection != null) {
            block(detail);
            finishInspection(false, detail);
        }
    }

    public static void intent(JsonObject pending, JsonObject before) throws Exception {
        if (inspection != null) return; // A typed inspection issues only the native readback probe.
        ensure();
        var evidence = pending.deepCopy();
        evidence.add("before", before.deepCopy());
        journal.intent(evidence);
    }

    public static void phase(String phase, JsonObject evidence) {
        if (mc.player == null || mc.level == null || blocked()) return;
        long previousRevision = journal == null ? 0 : journal.revision();
        String previousId = journal == null ? "" : journal.id();
        try {
            ensure();
            journal.phase(phase, evidence);
        } catch (Exception failed) {
            failed("Cannot durably record physical phase");
            return;
        }
        if (previousRevision != journal.revision() || !previousId.equals(journal.id()))
            WorldTestDriver.physicalPhasePersisted(phase);
    }

    public static void verified(JsonObject frame, boolean reconciled) throws Exception {
        if (journal == null || journal.revision() == 0) return;
        if (inspection != null || reconciled) {
            var scope = journal.context();
            if (mc.player == null
                    || !Stash.currentServer().equals(scope.get("server").getAsString())
                    || !StashIndex.dimension().equals(scope.get("dimension").getAsString())
                    || !mc.player.getUUID().toString().equals(scope.get("agent").getAsString()))
                throw new IllegalStateException("Inspection scope changed");
            if (inspection != null && frame.get("menu").getAsInt() != 0) {
                if (!mc.player.containerMenu.getCarried().isEmpty()) {
                    finishInspection(
                            false, "The recorded source still has an occupied cursor; operator decision required");
                    return;
                }
                inspectedSource = frame.deepCopy();
                var sorter = com.porter.wesort.WeSortAddon.sorter();
                if (sorter != null) sorter.capturePhysicalInspection(InventorySync.inspectionSource());
                // Empty cursor was proved by the original postcondition; closing a storage menu moves no items.
                mc.player.closeContainer();
                inspectInventoryPending = true;
                return;
            }
            var playerInventory = frame.get("menu").getAsInt() == 0 ? InventorySync.physicalPlayerInventory() : null;
            String decisionId = inspection == null ? null : inspection.get("id").getAsString();
            if (journal.lastIntentEvidence() == null) {
                journal.inspectedWithoutMutation(frame, playerInventory, decisionId);
                journal.failBeforeMutation("Read-only inspection verified that no WeSort mutation was transmitted");
                // The inspection proves no mutation was sent, including after a
                // restart. The Manager must still accept this revision before
                // other physical work may acquire its lease.
                reconcileRequired = false;
                recoveryFence = false;
                finishInspection(true, "No transmitted mutation; operation failed safely");
                return;
            }
            journal.verified(inspectedSource == null ? frame : inspectedSource, true, playerInventory, decisionId);
            inspectedSource = null;
            var value = journal.current();
            String kind = value.getAsJsonObject("context").get("kind").getAsString();
            String phase = value.get("phase").getAsString();
            if (kind.equals("DIRECT") || phase.equals("COMPLETED")) {
                journal.complete();
                reconcileRequired = false;
                recoveryFence = false;
                finishInspection(true, "Exact original source and postcondition verified; no mutation replayed");
            } else {
                journal.block(
                        "Last step verified; unfinished packing/standing-box decisions still require an operator");
                recoveryFence = true;
                finishInspection(
                        false,
                        "Last exact step is verified; the complete operation remains fenced for manual recovery");
            }
            return;
        }
        if (journal.pending()) journal.verified(frame, false);
        var context = journal.context();
        if (context.get("kind").getAsString().equals("DIRECT")) journal.complete();
    }

    public static void endUnstarted() {
        if (!unfinished()) return;
        try {
            if (journal.lastIntentEvidence() == null) journal.failBeforeMutation("No physical mutation was sent");
            else block("Shulker handling ended without verifying storage");
        } catch (Exception invalid) {
            failed("Cannot persist the end of physical work");
        }
    }

    public static void complete() {
        if (journal == null || journal.revision() == 0 || journal.terminal() || blocked()) return;
        try {
            journal.complete();
        } catch (Exception failed) {
            failed("Cannot commit physical completion");
        }
    }

    public static void block(String reason) {
        if (journal == null) return;
        try {
            journal.block(reason);
            if (unfinished()) recoveryFence = true;
        } catch (Exception failed) {
            failed("Cannot persist physical reconciliation fence");
        }
    }

    /**
     * Explicit operator release after a crash-recovered operation was inspected. Called only
     * from deliberate operator actions (module off-to-on, explicit sort-start), never from
     * mirror round-trips, inspection results, or automatic restores. Recomputes the fence
     * instead of blindly clearing it, so a live failure or blocked journal stays fenced.
     */
    public static void releaseReconciled() {
        reconcileRequired = false;
        recoveryFence = !failure.isEmpty() || journal != null && journal.blocked();
    }

    public static void failed(String reason) {
        failure = reason;
        recoveryFence = true;
        try {
            if (journal != null) journal.block(reason);
        } catch (Exception ignored) {
        }
    }

    public static JsonObject upload(String scope, String server, String agent, long now) {
        if (journal == null) return null;
        if (!scope.equals(managerScope)) {
            mirroredId = "";
            mirroredRevision = 0;
            managerScope = scope;
            lastUpload = "";
        }
        String checksum = journal.checksum();
        if (checksum.isEmpty() || now < nextUpload && checksum.equals(lastUpload)) return null;
        if (journal.terminal() && mirroredCurrent() && !blocked() && inspectionResult == null) return null;
        var context = journal.context();
        if (!server.equals(context.get("server").getAsString())
                || !agent.equals(context.get("agent").getAsString())) return null;
        lastUpload = checksum;
        nextUpload = now + (blocked() || mirroredCurrent() ? 2000 : 500);
        return journal.current();
    }

    public static void mirrored(JsonObject receipt, boolean hasDecision) {
        mirroredId = receipt.get("id").getAsString();
        mirroredRevision = receipt.get("revision").getAsLong();
        if (journal != null
                && journal.id().equals(receipt.get("id").getAsString())
                && receipt.get("state").getAsString().equals("NEEDS_RECONCILIATION")) {
            recoveryFence = true;
            if (!hasDecision && inspection == null) block("Manager requires a fresh physical inspection");
        }
    }

    public static JsonObject inspectionResult() {
        return inspectionResult == null ? null : inspectionResult.deepCopy();
    }

    public static void acknowledgedInspection(String id) {
        if (inspectionResult != null && inspectionResult.get("id").getAsString().equals(id)) inspectionResult = null;
    }

    public static void inspect(JsonObject decision) {
        if (inspection != null) return;
        var value = journal == null ? null : journal.current();
        if (value == null
                || !value.get("id").equals(decision.get("operation"))
                || !value.get("revision").equals(decision.get("revision"))
                || !value.get("checksum").equals(decision.get("checksum"))) {
            inspection = decision;
            finishInspection(false, "Physical revision changed; reload before inspecting");
            return;
        }
        inspection = decision;
        recoveryFence = true;
        var evidence = journal.lastIntentEvidence();
        try {
            if (evidence == null) {
                if (!InventorySync.inspectCurrentInventory())
                    finishInspection(false, "A fresh inventory-only inspection cannot start yet");
                return;
            }
            var copy = evidence.deepCopy();
            var latestBlocks = new LinkedHashMap<String, JsonObject>();
            for (var entry : value.getAsJsonArray("events")) {
                var event = entry.getAsJsonObject();
                if (!event.get("type").getAsString().equals("INTENT")) continue;
                var body = event.getAsJsonObject("evidence");
                if (!body.has("blocks")) continue;
                for (var block : body.getAsJsonArray("blocks")) {
                    var b = block.getAsJsonObject();
                    latestBlocks.put(b.get("x") + ":" + b.get("y") + ":" + b.get("z"), b.deepCopy());
                }
            }
            var blocks = new JsonArray();
            latestBlocks.values().forEach(blocks::add);
            copy.add("blocks", blocks);
            if (!InventorySync.inspectRecorded(copy))
                finishInspection(
                        false,
                        "Load the original source/workspace and leave the agent's hands empty before inspecting");
        } catch (Exception invalid) {
            finishInspection(false, "Recorded physical postcondition cannot be inspected safely");
        }
    }

    private static void finishInspection(boolean success, String detail) {
        if (inspection == null) return;
        inspectionResult = new JsonObject();
        inspectionResult.add("id", inspection.get("id"));
        inspectionResult.addProperty("state", success ? "COMPLETED" : "FAILED");
        inspectionResult.addProperty("detail", detail);
        inspection = null;
        inspectedSource = null;
        inspectInventoryPending = false;
    }

    public static void tickInspection() {
        if (inspection != null && inspectInventoryPending && InventorySync.ready()) {
            inspectInventoryPending = false;
            if (!InventorySync.inspectCurrentInventory())
                finishInspection(false, "A final full inventory read could not start");
        }
    }

    public static boolean inspecting() {
        return inspection != null;
    }
}
