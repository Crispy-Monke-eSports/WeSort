package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import com.porter.wesort.data.DataDirectory;
import com.porter.wesort.inventory.PhysicalJournal;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import java.nio.file.Files;
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
    private static boolean closeForeignScreenPending;
    private static long closeForeignScreenDeadline, inspectDeadline;
    private static String failureKey = "", failureCategory = "", escalation = "", escalationKey = "";
    private static int failureCount;
    private static final int ESCALATION_THRESHOLD = 3;
    private static long mirrorWaitSince, lastMirrorWarning;
    private static final long MIRROR_ESCALATION_MS = 5 * 60 * 1000;
    private static final long MIRROR_WARNING_EVERY_MS = 60 * 1000;
    private static String workspaceDebt;

    /** Thrown when a new physical order is gated on a missing durable workspace checkpoint. */
    private static final class WorkspaceDebt extends IllegalStateException {
        WorkspaceDebt(String message) {
            super(message);
        }
    }

    private PhysicalJournalRuntime() {}

    public static void initialize() {
        try {
            journal = new PhysicalJournal(DataDirectory.clientFolder(MeteorClient.FOLDER.toPath(), "physical-journal"));
            recoveryFence = journal.blocked();
            // An uncertain operation stays fenced until an exact read-only inspection
            // proves it safe. No mutation is ever retransmitted during recovery.
            workspaceDebt = readWorkspaceDebt();
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
        return !failure.isEmpty() || recoveryFence || workspaceDebtActive() || journal != null && journal.blocked();
    }

    /**
     * True when the only fence is workspace debt on an otherwise healed journal: no failure,
     * no recovery fence, journal not blocked. Recovery-rebuilding paths (healRepackState,
     * setup) may run under exactly this fence, since rebuilding is what lifts it — anything
     * else must still respect blocked().
     */
    public static boolean debtGated() {
        if (!workspaceDebtActive()) return false;
        if (!failure.isEmpty() || recoveryFence) return false;
        return journal == null || !journal.blocked();
    }

    public static boolean awaitingMirror() {
        if (journal == null || !journal.terminal() || mc.player == null) {
            mirrorWaitSince = 0;
            return false;
        }
        String scope = managerScope();
        if (scope.isEmpty()) {
            mirrorWaitSince = 0;
            return false;
        }
        var context = journal.context();
        boolean waiting = Stash.currentServer().equals(context.get("server").getAsString())
                && mc.player.getUUID().toString().equals(context.get("agent").getAsString())
                && (!managerScope.equals(scope) || !mirroredCurrent());
        if (waiting) {
            if (mirrorWaitSince == 0) mirrorWaitSince = System.currentTimeMillis();
        } else mirrorWaitSince = 0;
        return waiting;
    }

    /** Distinct heartbeat once a terminal receipt has waited minutes for its manager mirror. */
    public static String mirrorWaitWarning() {
        if (mirrorWaitSince == 0 || !awaitingMirror()) return null;
        long waiting = System.currentTimeMillis() - mirrorWaitSince;
        if (waiting < MIRROR_ESCALATION_MS || journal == null) return null;
        return "WAITING_MANAGER_RECEIPT since " + (waiting / 1000) + "s: journal " + journal.id() + " rev "
                + journal.revision() + " is terminal but the manager receipt is missing; the fence is kept"
                + " and the completed receipt keeps uploading. The link/lease heals automatically once"
                + " the manager is reachable again; no manual step can clear a terminal receipt.";
    }

    /** Debug hook while the fence is kept: only a genuinely failed readback can reopen recovery. */
    public static boolean requestManualReconciliation() {
        return InventorySync.requestReconciliation();
    }

    private static boolean mirroredCurrent() {
        return mirroredId.equals(journal.id()) && mirroredRevision >= journal.revision();
    }

    private static long recoveryProof;

    /** Advances only after a new exact reconciliation reaches a durable terminal state. */
    public static long recoveryProof() {
        return recoveryProof;
    }

    public static boolean unfinished() {
        return journal != null && journal.revision() != 0 && !journal.terminal();
    }

    public static String status() {
        if (!failure.isEmpty()) return failure;
        if (!escalation.isEmpty()) return escalation;
        // The gated reason stays available via workspaceDebt(), but the status token itself
        // must stay exact: harnesses and dashboards match on it, and a suffixed string reads
        // as a new state. blocked() above already fences everything while debt is active.
        return blocked() ? "AUTO_RECOVERING" : awaitingMirror() ? "WAITING_MANAGER_RECEIPT" : "READY";
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
        if (workspaceDebtActive()) throw new WorkspaceDebt("Next physical order gated: " + workspaceDebt);
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
                throw new IllegalStateException("Another physical operation is being recovered automatically");
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
        journal.block("Existing WeSort pending action requires automatic read-only recovery; never retransmit it");
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
        if (mc.player == null || mc.level == null || blocked() || workspaceDebtActive()) return;
        long previousRevision = journal == null ? 0 : journal.revision();
        String previousId = journal == null ? "" : journal.id();
        try {
            ensure();
            journal.phase(phase, evidence);
        } catch (WorkspaceDebt gated) {
            // The next order is gated, not corrupt: stay quiet and let the
            // intent path surface the actionable reason.
            MeteorClient.LOG.warn("WeSort {}", gated.getMessage());
            return;
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
                throw new IllegalStateException(
                        "INSPECT_SCOPE: server, dimension, or agent changed since the recorded operation");
            if (inspection != null && frame.get("menu").getAsInt() != 0) {
                if (!mc.player.containerMenu.getCarried().isEmpty()) {
                    finishInspection(
                            false,
                            "INSPECT_CURSOR: recorded source still has an occupied cursor; automatic verification will retry");
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
                recoveryFence = false;
                recoveryProof++;
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
                recoveryFence = false;
                recoveryProof++;
                finishInspection(true, "Exact original source and postcondition verified; no mutation replayed");
            } else {
                // The last native mutation was verified, but the higher-level
                // repack may still have a world shulker to collect. Only release
                // the journal when the sorter has durable recovery state and
                // will finish that state before the next order. Completing first
                // and re-fencing afterwards cannot work: block() ignores terminal
                // journals, so the operation would be reported COMPLETED with no
                // orphan list while recovery actually failed.
                var sorter = com.porter.wesort.WeSortAddon.sorter();
                boolean checkpoint = sorter != null && sorter.pendingPhysicalRecovery();
                if (checkpoint) {
                    journal.complete();
                    recoveryFence = false;
                    recoveryProof++;
                    finishInspection(true, "Exact physical step verified; sorter will resume its saved recovery");
                } else {
                    journal.block("Automatic shulker recovery requires a durable workspace checkpoint");
                    recoveryFence = true;
                    finishInspection(false, "Cannot safely recover without a saved physical workspace");
                }
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
            if (journal.lastIntentEvidence() == null) {
                journal.failBeforeMutation("No physical mutation was sent");
                // Proven: no packet left the client, so there is nothing to
                // reconcile. A terminal journal never fences input on its own,
                // so the redundant in-memory fence can go unless an inspection
                // is mid-flight.
                if (journal.terminal() && !journal.blocked() && inspection == null) recoveryFence = false;
            } else block("Shulker handling ended without verifying storage");
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
            failed("Cannot persist automatic physical recovery fence");
        }
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
            if (journal.terminal()) {
                // The step is already proven locally (COMPLETED/FAILED). A
                // stale manager receipt must not re-fence a terminal journal;
                // treat it as reconciled and wait for the manager to catch up.
                MeteorClient.LOG.warn(
                        "WeSort ignoring stale NEEDS_RECONCILIATION receipt for terminal journal {}", journal.id());
                return;
            }
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

    /**
     * Drops a pending inspection and its result without acting on it, e.g. when the link
     * scope changes: evidence gathered for the old scope must never verify the new one.
     */
    public static void dropInspection() {
        inspection = null;
        inspectionResult = null;
        inspectedSource = null;
        inspectInventoryPending = false;
    }

    /** The next upload goes out at once instead of waiting for the throttle. */
    public static void requestUpload() {
        nextUpload = 0;
    }

    public static void inspect(JsonObject decision) {
        if (inspection != null) return;
        String key = decisionKey(decision);
        if (!escalation.isEmpty() && escalationKey.equals(key)) {
            // A permanently-unmeetable precondition already failed N times for
            // this exact decision: do not reopen containers or move anything,
            // just re-answer the cached human-readable alert.
            inspection = decision;
            finishInspection(false, escalation);
            return;
        }
        if (!escalation.isEmpty()) clearEscalation();
        var value = journal == null ? null : journal.current();
        if (value == null
                || !value.get("id").equals(decision.get("operation"))
                || !value.get("revision").equals(decision.get("revision"))
                || !value.get("checksum").equals(decision.get("checksum"))) {
            inspection = decision;
            finishInspection(
                    false,
                    "INSPECT_STALE: decision targets op " + decision.get("operation") + " rev "
                            + decision.get("revision") + " but the journal holds "
                            + (value == null
                                    ? "no operation"
                                    : "op " + value.get("id") + " rev " + value.get("revision"))
                            + "; reload before inspecting");
            return;
        }
        inspection = decision;
        recoveryFence = true;
        inspectDeadline = System.currentTimeMillis() + 60_000;
        closeForeignScreenPending = false;
        try {
            var evidence = journal.lastIntentEvidence();
            boolean menuZero = evidence == null
                    || evidence.getAsJsonObject("expected").get("menu").getAsInt() == 0;
            if (menuZero && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                // The fenced bot opened this screen itself and nothing else
                // will close it. Closing a storage screen moves no items.
                if (!mc.player.containerMenu.getCarried().isEmpty()) {
                    finishInspection(
                            false,
                            "INSPECT_CURSOR: foreign " + InventorySync.screenName()
                                    + " screen open with an occupied cursor; cannot auto-close");
                    return;
                }
                mc.player.closeContainer();
                closeForeignScreenPending = true;
                closeForeignScreenDeadline = System.currentTimeMillis() + 15_000;
                return;
            }
            startInspectionProbe();
        } catch (Exception invalid) {
            finishInspection(false, "INSPECT_EVIDENCE: recorded physical postcondition cannot be inspected safely");
        }
    }

    private static void startInspectionProbe() {
        var evidence = journal.lastIntentEvidence();
        try {
            if (evidence == null) {
                String refusal = InventorySync.currentInventoryRefusal();
                if (refusal != null) {
                    finishInspection(false, refusal);
                    return;
                }
                if (!InventorySync.inspectCurrentInventory())
                    finishInspection(false, "INSPECT_NOT_READY: a fresh inventory-only inspection cannot start yet");
                return;
            }
            var copy = evidence.deepCopy();
            var latestBlocks = new LinkedHashMap<String, JsonObject>();
            var value = journal.current();
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
            String refusal = InventorySync.recordedInspectionRefusal(copy);
            if (refusal != null) {
                finishInspection(false, refusal);
                return;
            }
            if (!InventorySync.inspectRecorded(copy))
                finishInspection(false, "INSPECT_NOT_READY: recorded-source inspection cannot start yet");
        } catch (Exception invalid) {
            finishInspection(false, "INSPECT_EVIDENCE: recorded physical postcondition cannot be inspected safely");
        }
    }

    private static void finishInspection(boolean success, String detail) {
        if (inspection == null) return;
        if (success) {
            clearEscalation();
            // An exact read-only verification is the durable proof the workspace debt was
            // waiting for: the world provably matches, so the gate has served its purpose.
            clearWorkspaceDebt();
        } else if (!detail.equals(escalation)) {
            String key = decisionKey(inspection);
            String category = categoryOf(detail);
            if (key.equals(failureKey) && category.equals(failureCategory)) failureCount++;
            else {
                failureKey = key;
                failureCategory = category;
                failureCount = 1;
            }
            if (failureCount >= ESCALATION_THRESHOLD) detail = escalate(category, key, detail);
        }
        inspectionResult = new JsonObject();
        inspectionResult.add("id", inspection.get("id"));
        inspectionResult.addProperty("state", success ? "COMPLETED" : "FAILED");
        inspectionResult.addProperty("detail", detail);
        inspection = null;
        inspectedSource = null;
        inspectInventoryPending = false;
        closeForeignScreenPending = false;
    }

    private static String decisionKey(JsonObject decision) {
        return keyPart(decision, "operation") + "|" + keyPart(decision, "revision") + "|"
                + keyPart(decision, "checksum");
    }

    private static String keyPart(JsonObject decision, String field) {
        var element = decision.get(field);
        return element == null || element.isJsonNull() ? "?" : element.getAsString();
    }

    private static String categoryOf(String detail) {
        int cut = detail.indexOf(':');
        String head = cut < 0 ? detail : detail.substring(0, cut);
        return head.isEmpty() ? "UNKNOWN" : head;
    }

    private static boolean permanentCategory(String category) {
        return category.equals("SOURCE_GONE") || category.equals("TOO_FAR");
    }

    private static String escalate(String category, String key, String detail) {
        escalationKey = key;
        if (permanentCategory(category))
            escalation = category + " x" + failureCount + " for op " + key
                    + ": precondition cannot be met, waiting for the world to change: " + detail;
        else
            escalation = category + " x" + failureCount + " for op " + key
                    + ": repeated inspection failure, automatic recovery keeps retrying with backoff: " + detail;
        return escalation;
    }

    /** Clears the human-readable escalation once the world changes or a fresh decision resolves it. */
    public static void clearEscalation() {
        escalation = "";
        escalationKey = "";
        failureKey = "";
        failureCategory = "";
        failureCount = 0;
    }

    public static String escalation() {
        return escalation.isEmpty() ? null : escalation;
    }

    private static java.nio.file.Path debtFile() {
        return DataDirectory.clientFolder(MeteorClient.FOLDER.toPath(), "physical-journal")
                .resolve("workspace-debt.json");
    }

    /** The next physical order is gated until a durable workspace checkpoint exists, or null. */
    public static String workspaceDebt() {
        return workspaceDebt;
    }

    public static boolean workspaceDebtActive() {
        if (workspaceDebt == null) return false;
        var sorter = com.porter.wesort.WeSortAddon.sorter();
        if (sorter != null && sorter.pendingPhysicalRecovery()) {
            // Durable recovery state is back: the gate lifts by itself.
            clearWorkspaceDebt();
            return false;
        }
        return true;
    }

    private static void noteWorkspaceDebt(String reason) {
        workspaceDebt = reason;
        try {
            var value = new JsonObject();
            value.addProperty("since", System.currentTimeMillis());
            value.addProperty("reason", reason);
            value.addProperty("operation", journal == null ? "" : journal.id());
            Files.writeString(debtFile(), value.toString());
        } catch (Exception ignored) {
            // The in-memory gate still holds; the file is only restart survival.
        }
    }

    /** Operator override once the world shulker has been collected by hand. */
    public static void clearWorkspaceDebt() {
        workspaceDebt = null;
        try {
            Files.deleteIfExists(debtFile());
        } catch (Exception ignored) {
        }
    }

    /**
     * Restart escape for orphaned debt: the gate persisted to disk, but nothing restorable
     * exists anymore — no saved step, no in-memory work, no inspection in flight, and the
     * journal is terminally complete. Gating forever helps nothing: the verified step is
     * durable, the leftovers (if any) are found by orphan recovery, and every new order
     * re-verifies from scratch. Lifts loudly exactly once instead of wedging the bot.
     */
    public static boolean clearOrphanedDebt() {
        if (!workspaceDebtActive() || inspection != null) return false;
        if (journal != null && !journal.terminal()) return false;
        var sorter = com.porter.wesort.WeSortAddon.sorter();
        if (sorter != null && sorter.pendingPhysicalRecovery()) return false;
        MeteorClient.LOG.warn(
                "WeSort workspace debt has nothing left to recover (journal terminal, no saved step); lifting the gate, next work re-verifies: {}",
                workspaceDebt);
        clearWorkspaceDebt();
        return true;
    }

    private static String readWorkspaceDebt() {
        try {
            java.nio.file.Path file = debtFile();
            if (!Files.exists(file)) return null;
            var value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String reason = value.has("reason") ? value.get("reason").getAsString() : "";
            return reason.isEmpty() ? null : reason;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void tickMirrorWait() {
        if (!awaitingMirror() || journal == null) return;
        if (System.currentTimeMillis() - mirrorWaitSince < MIRROR_ESCALATION_MS) return;
        if (System.currentTimeMillis() - lastMirrorWarning < MIRROR_WARNING_EVERY_MS) return;
        lastMirrorWarning = System.currentTimeMillis();
        String warning = mirrorWaitWarning();
        if (warning != null) MeteorClient.LOG.warn("WeSort {}", warning);
    }

    public static void tickInspection() {
        tickMirrorWait();
        if (closeForeignScreenPending) {
            if (inspection == null) {
                closeForeignScreenPending = false;
                return;
            }
            if (mc.player == null || mc.level == null) {
                closeForeignScreenPending = false;
                finishInspection(
                        false, "INSPECT_NOT_READY: player or world vanished while waiting for the player inventory");
                return;
            }
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                if (System.currentTimeMillis() > closeForeignScreenDeadline) {
                    closeForeignScreenPending = false;
                    finishInspection(
                            false,
                            "INSPECT_MENU: foreign " + InventorySync.screenName()
                                    + " screen would not close; menu-0 inspection needs the player inventory");
                }
                return;
            }
            closeForeignScreenPending = false;
            try {
                startInspectionProbe();
            } catch (Exception invalid) {
                finishInspection(false, "INSPECT_EVIDENCE: recorded physical postcondition cannot be inspected safely");
            }
            return;
        }
        if (inspection != null && System.currentTimeMillis() > inspectDeadline) {
            finishInspection(false, "INSPECT_TIMEOUT: " + InventorySync.reconcileDetail());
            return;
        }
        if (inspection != null && inspectInventoryPending && InventorySync.ready()) {
            inspectInventoryPending = false;
            if (!InventorySync.inspectCurrentInventory())
                finishInspection(false, "INSPECT_NOT_READY: a final full inventory read could not start");
        }
    }

    public static boolean inspecting() {
        return inspection != null;
    }
}
