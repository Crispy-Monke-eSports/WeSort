package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import com.porter.wesort.stash.StashWatch;
import java.nio.file.*;
import java.util.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Filesystem-only development control, enabled by -PworldTest=true in an isolated gameDir. */
public final class WorldTestDriver {
    private final Path control;
    private JsonObject pending;
    private String lastId = "";
    private long deadline;
    private int stable;
    private Stash stash;
    private final StashWatch lidWatch = new StashWatch();
    private long lidTick;
    private String interaction = "";
    private long openEpoch;
    private String crashPhysicalPhase;
    private static WorldTestDriver instance;
    private final Map<BlockPos, Long> blockEntities = new HashMap<>();

    private WorldTestDriver(Path control) throws Exception {
        this.control = control;
        Path last = control.resolve("last-request-id");
        if (Files.exists(last)) {
            lastId = Files.readString(last);
            if (!lastId.matches("[0-9a-f]{32}")) throw new IllegalStateException("Invalid E2E request journal");
        }
    }

    public static void initialize() {
        if (!Boolean.getBoolean("wesort.e2e")) return;
        Path root = FabricLoader.getInstance().getGameDir().resolve("wesort-e2e");
        try {
            // The harness creates this marker; never initialize a random existing game directory.
            if (!Files.readString(root.resolve("installation.txt")).equals("wesort-mc26-e2e-v1\n")) {
                throw new IllegalStateException("Invalid E2E installation marker");
            }
            instance = new WorldTestDriver(root);
            MeteorClient.EVENT_BUS.subscribe(instance);
        } catch (Exception e) {
            throw new IllegalStateException("E2E profile not initialized", e);
        }
    }

    static void physicalPhasePersisted(String phase) {
        var driver = instance;
        if (driver == null) return;
        try {
            // Every durable phase leaves a stamped marker, so a missed arming window still
            // names the last durable phase instead of timing out blindly.
            Files.writeString(driver.control.resolve("physical-crash-" + phase), phase);
        } catch (java.io.IOException ignored) {
            // Marker files are diagnostics only; the journal itself is already durable.
        }
        if (!phase.equals(driver.crashPhysicalPhase)) return;
        try {
            Files.writeString(driver.control.resolve("physical-crash-ready"), phase);
            // The isolated harness kills this process after observing the marker.
            Thread.sleep(30_000);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot announce the journal crash point", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("Harness did not terminate the armed client");
    }

    @EventHandler
    private void tick(TickEvent.Post ignored) {
        try {
            if (pending != null) {
                if (System.nanoTime() > deadline) {
                    respond(
                            false,
                            "PAUSED_NEEDS_RECONCILIATION: server observation timed out",
                            mc.player == null ? null : state());
                    return;
                }
                finishPending();
                return;
            }
            Path request = control.resolve("request.json");
            if (!Files.exists(request)) return;
            JsonObject command =
                    JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            if (command.get("id").getAsString().equals(lastId)) return;
            pending = command;
            lastId = command.get("id").getAsString();
            Path last = control.resolve("last-request-id.tmp");
            Files.writeString(last, lastId);
            Files.move(
                    last,
                    control.resolve("last-request-id"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            deadline = System.nanoTime()
                    + (command.get("action").getAsString().equals("state") ? 180_000_000_000L : 15_000_000_000L);
            stable = 0;
            if (mc.level == null || mc.player == null) return;
            switch (command.get("action").getAsString()) {
                case "state" -> respond(true, "connected", state());
                case "barrel-ready" -> {
                    BlockPos pos = position(command);
                    var block = mc.level.getBlockState(pos);
                    var open = net.minecraft.world.level.block.BarrelBlock.OPEN;
                    boolean ready = mc.level.isLoaded(pos)
                            && block.hasProperty(open)
                            && block.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
                            && !block.getValue(open)
                            && mc.level.getBlockEntity(pos)
                                    instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity;
                    respond(ready, ready ? "Closed barrel observed" : "Waiting for closed barrel: " + block, null);
                }
                case "watch-lid" -> {
                    BlockPos pos = position(command);
                    Stash.Box watched = stash == null ? null : stash.boxAt(pos);
                    var block = mc.level.getBlockState(pos);
                    var open = net.minecraft.world.level.block.BarrelBlock.OPEN;
                    if (watched == null
                            || !block.hasProperty(open)
                            || block.getValue(open) != command.get("open").getAsBoolean()) {
                        respond(false, "Waiting for native barrel state", null);
                        return;
                    }
                    var observed = new JsonObject();
                    observed.addProperty("reread", false);
                    lidWatch.tickLids(stash, ++lidTick, 0, box -> box != watched, box -> {
                        if (box == watched) observed.addProperty("reread", true);
                    });
                    respond(true, "observed native barrel lid", observed);
                }
                case "arm-physical-crash" -> {
                    crashPhysicalPhase = com.porter.wesort.inventory.PhysicalWork.Phase.valueOf(
                                    command.get("phase").getAsString())
                            .name();
                    respond(true, "armed after durable journal phase", null);
                }
                case "open" -> {
                    openEpoch = InventorySync.contentsEpoch();
                    BlockPos pos = position(command);
                    if (!mc.level.isLoaded(pos)) {
                        respond(false, "Unloaded target", null);
                        return;
                    }
                    // Test-only hook: the harness authorizes its own explicit open like production code does.
                    WorldInteractionGuard.authorizeUse(pos);
                    if (mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 25) {
                        respond(false, "Target outside interaction range", null);
                        return;
                    }
                    interaction = mc.level.getBlockState(pos) + " -> "
                            + meteordevelopment.meteorclient.utils.world.BlockUtils.interact(
                                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
                                    InteractionHand.MAIN_HAND,
                                    true);
                }
                case "overlap-open" -> {
                    BlockPos pos = position(command);
                    BlockPos other = pos.east();
                    if (!mc.level.isLoaded(pos)
                            || !mc.level.isLoaded(other)
                            || mc.player.distanceToSqr(Vec3.atCenterOf(other)) > 25) {
                        respond(false, "Ambiguity probe requires reachable fixture halves", null);
                        return;
                    }
                    for (BlockPos part : List.of(pos, other)) WorldInteractionGuard.authorizeUse(part);
                    for (BlockPos part : List.of(pos, other))
                        meteordevelopment.meteorclient.utils.world.BlockUtils.interact(
                                new BlockHitResult(Vec3.atCenterOf(part), Direction.UP, part, false),
                                InteractionHand.MAIN_HAND,
                                true);
                }
                case "navigate" -> {
                    if (NavigationRuntime.paused() || !InventorySync.ready()) {
                        respond(false, "Navigation paused", state());
                        return;
                    }
                    BlockPos target = position(command);
                    if (!mc.level.isLoaded(target)) {
                        respond(false, "Unloaded navigation target", state());
                        return;
                    }
                    com.porter.wesort.utils.BaritoneHelper.protect(List.of(
                            net.minecraft.world.level.block.Blocks.CHEST,
                            net.minecraft.world.level.block.Blocks.BARREL,
                            net.minecraft.world.level.block.Blocks.SHULKER_BOX));
                    com.porter.wesort.utils.BaritoneHelper.confine(true);
                    if (command.has("reach") && command.get("reach").getAsBoolean())
                        com.porter.wesort.utils.BaritoneHelper.moveInReach(List.of(target), 4.0);
                    else com.porter.wesort.utils.BaritoneHelper.moveNear(target, 0);
                    respond(true, "started actual Baritone goal", state());
                }
                case "nav-cancel" -> {
                    NavigationRuntime.cancel();
                    respond(true, NavigationRuntime.status(), state());
                }
                case "nav-resume" -> respond(NavigationRuntime.resume(), NavigationRuntime.status(), state());
                case "world-guard-probe" -> {
                    BlockPos pos = position(command);
                    var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    var used = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                    boolean broken = mc.gameMode.startDestroyBlock(pos, Direction.UP);
                    respond(
                            used == net.minecraft.world.InteractionResult.FAIL && !broken,
                            "Dedicated world guard refused use and break before prediction",
                            state());
                }
                case "unsafe-bot-profile" -> {
                    var settings = baritone.api.BaritoneAPI.getSettings();
                    settings.allowBreak.value = true;
                    settings.allowPlace.value = true;
                    settings.allowBreakAnyway.value = List.of(net.minecraft.world.level.block.Blocks.STONE);
                    var external = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
                    external.getCustomGoalProcess()
                            .setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(position(command)));
                    respond(true, "Submitted an external Baritone goal and unsafe profile settings", state());
                }
                case "close" -> mc.player.closeContainer();
                case "click" -> {
                    if (!InventorySync.ready()) {
                        respond(false, "Inventory not ready: " + InventorySync.reason(), state());
                        return;
                    }
                    var menu = mc.player.containerMenu;
                    if (menu.containerId != command.get("menu").getAsInt()
                            || menu.getStateId() != command.get("state").getAsInt()) {
                        respond(false, "Stale menu/state id", state());
                        return;
                    }
                    int slot = command.get("slot").getAsInt();
                    ItemStack stack = menu.getSlot(slot).getItem();
                    if (stack.getCount() != command.get("count").getAsInt()
                            || !(stack.isEmpty() ? "" : StashIndex.keyOf(stack))
                                    .equals(command.get("fingerprint").getAsString())) {
                        respond(false, "Source slot changed", state());
                        return;
                    }
                    mc.gameMode.handleContainerInput(
                            menu.containerId,
                            slot,
                            0,
                            net.minecraft.world.inventory.ContainerInput.valueOf(
                                    command.get("type").getAsString()),
                            mc.player);
                }
                case "attempt-protected" -> {
                    var menu = mc.player.containerMenu;
                    if (menu == mc.player.inventoryMenu || !InventorySync.ready()) {
                        respond(false, "Open the protected fixture first", state());
                        return;
                    }
                    long before = InventorySync.attemptedClicks();
                    var item = menu.getSlot(0).getItem().copy();
                    mc.gameMode.handleContainerInput(
                            menu.containerId, 0, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                    respond(
                            before == InventorySync.attemptedClicks()
                                    && ItemStack.matches(item, menu.getSlot(0).getItem()),
                            "protected storage refused before prediction or packet send",
                            state());
                }
                case "attempt-unsafe" -> {
                    if (!InventorySync.failed()) {
                        respond(false, "Requires an actually paused production guard", state());
                        return;
                    }
                    var menu = mc.player.containerMenu;
                    long before = InventorySync.attemptedClicks();
                    var stack = menu.getSlot(0).getItem().copy();
                    mc.gameMode.handleContainerInput(
                            menu.containerId, 0, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                    boolean blocked = InventorySync.attemptedClicks() == before
                            && ItemStack.matches(stack, menu.getSlot(0).getItem());
                    respond(blocked, "production mixin refused before local prediction or packet send", state());
                }
                case "reconcile" -> {
                    if (!InventorySync.requestReconciliation())
                        respond(false, "No recoverable inventory intent", state());
                }
                case "select" -> {
                    if (!InventorySync.ready()) {
                        respond(false, InventorySync.reason(), state());
                        return;
                    }
                    int slot = command.get("slot").getAsInt();
                    if (slot < 0 || slot > 8) {
                        respond(false, "Selection requires a real hotbar slot", state());
                        return;
                    }
                    meteordevelopment.meteorclient.utils.player.InvUtils.swap(slot, false);
                    respond(true, "selected", state());
                }
                case "place" -> {
                    if (!InventorySync.ready()) {
                        respond(false, InventorySync.reason(), state());
                        return;
                    }
                    BlockPos pos = position(command);
                    if (!mc.level.isLoaded(pos)
                            || !mc.level.getBlockState(pos).isAir()
                            || mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 25) {
                        respond(false, "Placement target is not loaded, empty and reachable", state());
                        return;
                    }
                    WorldInteractionGuard.authorizePlace(pos);
                    // The driver places through the same deferred rotation path: announce the
                    // placement so the fail-closed intent gate records it (same as production).
                    String placing = command.has("block")
                            ? command.get("block").getAsString()
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(mc.player.getMainHandItem().getItem())
                                    .toString();
                    InventorySync.expectPlacement(pos, placing);
                    meteordevelopment.meteorclient.utils.world.BlockUtils.interact(
                            new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false),
                            InteractionHand.MAIN_HAND,
                            true);
                }
                case "break" -> {
                    if (!InventorySync.ready()) {
                        respond(false, InventorySync.reason(), state());
                        return;
                    }
                    BlockPos pos = position(command);
                    if (!mc.level.isLoaded(pos) || mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 25) {
                        respond(false, "Break target not loaded/reachable", state());
                        return;
                    }
                    WorldInteractionGuard.authorizeBreak(pos);
                    meteordevelopment.meteorclient.utils.world.BlockUtils.breakBlock(pos, true);
                }
                case "forget" -> {
                    BlockPos pos = position(command);
                    if (!InventorySync.ready()
                            || !mc.level.isLoaded(pos)
                            || !mc.level.getBlockState(pos).isAir()) {
                        respond(false, "Cannot forget a source without an observed empty world position", state());
                        return;
                    }
                    if (stash != null) stash.boxes.removeIf(box -> box.has(pos));
                    respond(true, "removed observed absent source", state());
                }
                case "manager-unlink-test" -> {
                    var manager = Modules.get().get(StashManager.class);
                    if (manager.isActive()) manager.toggle();
                    ((Setting<String>) manager.settings.get("server-url")).set("");
                    respond(true, "isolated fixture returned to local sorting", state());
                }
                case "manager-pause" -> {
                    var manager = Modules.get().get(StashManager.class);
                    if (manager.isActive()) manager.toggle();
                    respond(true, "production Manager agent paused", state());
                }
                case "manager-resume" -> {
                    var manager = Modules.get().get(StashManager.class);
                    if (!manager.isActive()) manager.toggle();
                    // The live Manager must remain enabled for fail-closed guard tests,
                    // but its autonomous sorter would immediately override RCON teleports.
                    // Apply the fixture-only hold in this same client tick as activation.
                    if (command.has("inspectionHold")
                            && command.get("inspectionHold").getAsBoolean()) manager.e2eHoldAutonomousWork(true);
                    respond(true, "production Manager agent resumed", state());
                }
                case "manager-inspection-hold" -> {
                    var manager = Modules.get().get(StashManager.class);
                    manager.e2eHoldAutonomousWork(command.get("hold").getAsBoolean());
                    respond(true, "E2E inspection hold updated; dedicated Manager remains active", state());
                }
                case "manager-sorting-hold" -> {
                    var manager = Modules.get().get(StashManager.class);
                    manager.e2eHoldPhysicalSorting(command.get("hold").getAsBoolean());
                    respond(true, "E2E scan-only hold updated; Manager continues real observations", state());
                }
                case "engine-index" -> {
                    var current = com.porter.wesort.WeSortAddon.sorter().currentStash();
                    var result = state();
                    if (current != null)
                        result.add(
                                "engineIndex",
                                JsonParser.parseString(new String(
                                        StashIndex.build(current, false).json(),
                                        java.nio.charset.StandardCharsets.UTF_8)));
                    respond(true, "actual production Sorter observation cache", result);
                }
                case "kit-info", "kit-compare" -> respond(false, "kit system removed", state());
                case "sort-start", "sort-config" -> {
                    if (stash == null) {
                        respond(false, "Capture fixture containers first", null);
                        return;
                    }
                    stash.save();
                    var sorter = com.porter.wesort.WeSortAddon.sorter();
                    sorter.selectE2eFixtureStash(stash.name);
                    ((Setting<Boolean>) sorter.settings.get("sort-items")).set(true);
                    if (command.has("repack"))
                        ((Setting<Boolean>) sorter.settings.get("repack"))
                                .set(command.get("repack").getAsBoolean());
                    if (command.has("compact"))
                        ((Setting<Boolean>) sorter.settings.get("compact"))
                                .set(command.get("compact").getAsBoolean());
                    if (command.has("newBulkAt"))
                        ((Setting<Integer>) sorter.settings.get("new-bulk-at"))
                                .set(command.get("newBulkAt").getAsInt());
                    if (command.has("topUpAt"))
                        ((Setting<Integer>) sorter.settings.get("compact-top-up-at"))
                                .set(command.get("topUpAt").getAsInt());
                    if (command.has("carrySlots"))
                        ((Setting<String>) sorter.settings.get("carry-slots"))
                                .set(command.get("carrySlots").getAsString());
                    ((Setting<Boolean>) sorter.settings.get("barrels")).set(true);
                    ((Setting<Boolean>) sorter.settings.get("walk"))
                            .set(!command.has("walk") || command.get("walk").getAsBoolean());
                    if (command.has("reach"))
                        ((Setting<Double>) sorter.settings.get("reach"))
                                .set(command.get("reach").getAsDouble());
                    ((Setting<Integer>) sorter.settings.get("radius")).set(32);
                    ((Setting<Integer>) sorter.settings.get("delay")).set(1);
                    ((Setting<Integer>) sorter.settings.get("container-delay")).set(0);
                    if (command.get("action").getAsString().equals("sort-start") && !sorter.isActive()) sorter.toggle();
                    respond(true, "actual StashSorter configured", state());
                }
                case "sort-stop-when-idle" -> {
                    // End an E2E sorting phase only between physical operations. A
                    // fixture must not stop the module with an unverified click,
                    // open menu, or unfinished durable journal; those are genuine
                    // reconciliation conditions, not a reason to weaken the fence.
                    var sorter = com.porter.wesort.WeSortAddon.sorter();
                    // An empty cursor and a closed menu do not mean the sort batch
                    // has been deposited: between two verified clicks the sorter
                    // can hold the next batch in normal player inventory slots.
                    // Stopping there silently strands items outside the stash.
                    boolean carryingBatch = false;
                    for (int slot = 0; slot < 36; slot++) {
                        if (!mc.player.getInventory().getItem(slot).isEmpty()) {
                            carryingBatch = true;
                            break;
                        }
                    }
                    if (sorter.isActive()
                            && (sorter.physicalWorkInProgress()
                                    || carryingBatch
                                    || !InventorySync.ready()
                                    || PhysicalJournalRuntime.unfinished()
                                    || !PhysicalJournalRuntime.status().equals("READY")
                                    || mc.player.containerMenu != mc.player.inventoryMenu
                                    || !mc.player.containerMenu.getCarried().isEmpty())) {
                        respond(false, "Physical sorting is not yet safe to stop", state());
                        return;
                    }
                    if (sorter.isActive()) sorter.toggle();
                    respond(true, "actual StashSorter stopped between verified operations", state());
                }
                case "sort-stop" -> {
                    var sorter = com.porter.wesort.WeSortAddon.sorter();
                    if (sorter.isActive()) sorter.toggle();
                    respond(true, "actual StashSorter stopped", state());
                }
                case "sort-mark" -> {
                    BlockPos pos = position(command);
                    var current = com.porter.wesort.WeSortAddon.sorter().currentStash();
                    for (var source : new Stash[] {stash, current}) {
                        if (source == null) continue;
                        var box = source.boxAt(pos);
                        if (box == null) continue;
                        if (command.has("protected")) {
                            box.protectedStorage = command.get("protected").getAsBoolean();
                            box.stale = true;
                            box.observationSession = "";
                        }
                        if (command.has("excluded"))
                            box.excluded = command.get("excluded").getAsBoolean();
                        if (command.has("dropOff"))
                            box.dropOff = command.get("dropOff").getAsBoolean();
                        if (command.has("delivery")) {
                            box.delivery = command.get("delivery").getAsBoolean();
                            box.excluded = box.delivery;
                            if (box.delivery) source.deliveryPos = pos;
                        }
                        source.markDirty();
                        source.save();
                    }
                    respond(true, "fixture storage protection configured", state());
                }
                case "sort-explain" -> {
                    var report = new JsonObject();
                    var sorter = com.porter.wesort.WeSortAddon.sorter();
                    JsonArray probes = new JsonArray();
                    for (var slot : mc.player.containerMenu.slots) {
                        if (slot.index >= mc.player.containerMenu.slots.size() - 36
                                || slot.getItem().isEmpty()) continue;
                        var probe = sorter.explainSort(slot.getItem());
                        probes.add(probe);
                    }
                    report.add("probes", probes);
                    respond(true, "production category and destination diagnostics", report);
                }
                case "order-staging-settings" -> {
                    var pos = position(command);
                    var box = stash == null ? null : stash.boxAt(pos);
                    if (box == null || !box.known()) {
                        respond(false, "Observe the staging chest first", null);
                        return;
                    }
                    box.delivery = true;
                    box.excluded = true;
                    stash.deliveryPos = box.pos;
                    stash.markDirty();
                    stash.save();
                    var manager = Modules.get().get(StashManager.class);
                    ((Setting<Boolean>) manager.settings.get("accept-orders")).set(true);
                    respond(true, "configured actual production order staging", state());
                }
                case "stock-scan-settings" -> {
                    var sorter = com.porter.wesort.WeSortAddon.sorter();
                    ((Setting<Boolean>) sorter.settings.get("sort-items")).set(false);
                    ((Setting<Boolean>) sorter.settings.get("barrels")).set(true);
                    ((Setting<Boolean>) sorter.settings.get("walk")).set(true);
                    ((Setting<Integer>) sorter.settings.get("radius")).set(48);
                    ((Setting<Integer>) sorter.settings.get("delay")).set(1);
                    ((Setting<Integer>) sorter.settings.get("container-delay")).set(0);
                    respond(true, "configured production scan-only settings", state());
                }
                case "component-identity" -> {
                    var item = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
                    var first = new net.minecraft.nbt.CompoundTag();
                    first.putByte("value", (byte) 1);
                    var second = new net.minecraft.nbt.CompoundTag();
                    second.putInt("value", 1);
                    item.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(first));
                    String a = StashIndex.keyOf(item);
                    item.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(second));
                    String b = StashIndex.keyOf(item);
                    var nestedA = new net.minecraft.nbt.CompoundTag();
                    nestedA.putInt("a", 1);
                    nestedA.putInt("b", 2);
                    var nestedB = new net.minecraft.nbt.CompoundTag();
                    nestedB.putInt("b", 2);
                    nestedB.putInt("a", 1);
                    boolean valid = !a.equals(b)
                            && NbtFingerprint.encode(nestedA).equals(NbtFingerprint.encode(nestedB))
                            && !NbtFingerprint.encode(first).equals(NbtFingerprint.encode(second));
                    respond(
                            valid,
                            "actual item CODEC keeps NBT numeric types distinct and compound order stable",
                            state());
                }
                case "export-resources" -> {
                    MetadataExport.export(Path.of(command.get("jar").getAsString()), control.resolve("resources"));
                    respond(true, "exported live vanilla 26.3 metadata and recipes", state());
                }
                case "publish" -> publish(command);
                case "connect" -> connectManager(command);
                case "screenshot" ->
                    net.minecraft.client.Screenshot.grab(
                            mc.gameDirectory,
                            "fixture.png",
                            mc.gameRenderer.mainRenderTarget(),
                            1,
                            message -> mc.execute(() -> {
                                try {
                                    boolean saved = Files.isRegularFile(
                                            mc.gameDirectory.toPath().resolve("screenshots/fixture.png"));
                                    respond(saved, saved ? "screenshot saved" : "screenshot failed", state());
                                } catch (Exception e) {
                                    throw new IllegalStateException("Screenshot evidence failed", e);
                                }
                            }));
                case "stop" -> {
                    respond(true, "stopping", state());
                    mc.stop();
                }
                default -> respond(false, "Unsupported action", null);
            }
        } catch (Exception e) {
            if (pending != null) {
                try {
                    respond(false, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
                } catch (Exception output) {
                    throw new IllegalStateException("Cannot write E2E result", output);
                }
            } else throw new IllegalStateException("E2E driver failed", e);
        }
    }

    private void finishPending() throws Exception {
        if (mc.level == null || mc.player == null) return;
        String action = pending.get("action").getAsString();
        if (action.equals("reconcile")) {
            if (InventorySync.ready())
                respond(true, "original pending postcondition verified; no click replayed", state());
            else if (InventorySync.failed() && !InventorySync.recoveryPending())
                respond(false, InventorySync.reason(), state());
            return;
        }
        if (action.equals("place") || action.equals("break")) {
            if (InventorySync.failed()) {
                respond(false, InventorySync.reason(), state());
                return;
            }
            if (!InventorySync.ready()) return;
            BlockPos pos = position(pending);
            boolean done = action.equals("break")
                    ? mc.level.getBlockState(pos).isAir()
                    : BuiltInRegistries.BLOCK
                            .getKey(mc.level.getBlockState(pos).getBlock())
                            .toString()
                            .equals(pending.get("block").getAsString());
            if (done && InventorySync.lastBlockAck() >= InventorySync.lastBlockSequence()) {
                respond(true, "native server block acknowledgement and inventory readback verified", state());
            } else if (action.equals("break")) {
                // Re-authorize every poll iteration: the fire-and-forget permit lives 10 s and
                // the poll may run longer. Same exact position/block/job match, no weakening.
                WorldInteractionGuard.authorizeBreak(pos);
                meteordevelopment.meteorclient.utils.world.BlockUtils.breakBlock(pos, true);
            }
            return;
        }
        if (action.equals("click")) {
            if (InventorySync.failed()) respond(false, InventorySync.reason(), state());
            else if (InventorySync.ready())
                respond(true, "server inventory snapshot and FIFO barrier verified", state());
            return;
        }
        if (action.equals("overlap-open")) {
            if (InventorySync.lastBlockAck() < InventorySync.lastBlockSequence() || ++stable < 5) return;
            BlockPos pos = position(pending);
            respond(
                    !InventorySync.confirmedSource(List.of(pos, pos.east())),
                    "overlapping native opens cannot produce an attributable physical observation",
                    state());
            return;
        }
        if (action.equals("state")) {
            respond(true, "connected", state());
            return;
        }
        if (action.equals("close") && mc.player.containerMenu == mc.player.inventoryMenu) {
            respond(true, "closed", state());
            return;
        }
        if (action.equals("open") && mc.player.containerMenu != mc.player.inventoryMenu) {
            var menu = mc.player.containerMenu;
            if (!InventorySync.hasServerContents(menu.containerId, openEpoch)) return;
            int slots = menu.slots.size() - 36;
            if (slots != pending.get("slots").getAsInt()) {
                respond(false, "Unexpected container slot count", state());
                return;
            }
            // A matching server-supplied stack is required; an opened empty menu alone is not an ack.
            ItemStack first = menu.getSlot(0).getItem();
            if (!BuiltInRegistries.ITEM
                            .getKey(first.getItem())
                            .toString()
                            .equals(pending.get("item").getAsString())
                    || first.getCount() != pending.get("count").getAsInt()) {
                stable = 0;
                return;
            }
            if (++stable < 3) return;
            if (pending.has("capture") && !pending.get("capture").getAsBoolean()) {
                respond(true, "server container observed without index capture", state());
                return;
            }
            if (stash == null) stash = new Stash("e2e-fixture", Stash.currentServer());
            BlockPos pos = position(pending);
            List<BlockPos> parts = new ArrayList<>();
            parts.add(pos);
            if (pending.has("second"))
                parts.add(new BlockPos(pending.get("second").getAsInt(), pos.getY(), pos.getZ()));
            Stash.Box box = new Stash.Box(pos, parts);
            var previous = stash.boxAt(pos);
            if (previous != null) {
                box.excluded = previous.excluded;
                box.dropOff = previous.dropOff;
                box.protectedStorage = previous.protectedStorage;
                box.delivery = previous.delivery;
            }
            box.contents = new ItemStack[slots];
            for (int i = 0; i < slots; i++)
                box.contents[i] = menu.getSlot(i).getItem().copy();
            box.lastRead = System.currentTimeMillis();
            if (!ObservationRuntime.canRead(box)) return;
            ObservationRuntime.record(box);
            stash.boxes.removeIf(b -> b.pos.equals(pos));
            stash.boxes.add(box);
            respond(true, "server container observed", state());
        }
    }

    private void publish(JsonObject command) throws Exception {
        if (stash == null) stash = Stash.load("e2e-fixture");
        if (stash == null || stash.boxes.isEmpty()) {
            respond(false, "No observed containers", null);
            return;
        }
        stash.save();
        StashSorter sorter = com.porter.wesort.WeSortAddon.sorter();
        sorter.selectE2eFixtureStash(stash.name);
        connectManager(command);
    }

    @SuppressWarnings("unchecked")
    private void connectManager(JsonObject command) throws Exception {
        StashManager manager = Modules.get().get(StashManager.class);
        ((Setting<String>) manager.settings.get("server-url"))
                .set(command.get("url").getAsString());
        ((Setting<String>) manager.settings.get("api-key"))
                .set(command.get("key").getAsString());
        if (!manager.isActive()) manager.toggle();
        if (command.has("holdPhysicalSorting")
                && command.get("holdPhysicalSorting").getAsBoolean()) manager.e2eHoldPhysicalSorting(true);
        if (command.has("inspectionHold") && command.get("inspectionHold").getAsBoolean())
            manager.e2eHoldAutonomousWork(true);
        respond(true, "production signed module enabled; verify Manager separately", state());
    }

    private static boolean legacyMemberWindowClassPresent() {
        try {
            Class.forName("com.porter.wesort.modules.StashWindow");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private static BlockPos position(JsonObject o) {
        return new BlockPos(
                o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private JsonObject state() {
        JsonObject out = new JsonObject();
        var actualSorter = com.porter.wesort.WeSortAddon.sorter();
        JsonArray storage = new JsonArray();
        var actualStash = actualSorter.currentStash();
        if (actualStash != null)
            for (var box : actualStash.boxes) {
                var flags = new JsonObject();
                flags.addProperty("x", box.pos.getX());
                flags.addProperty("y", box.pos.getY());
                flags.addProperty("z", box.pos.getZ());
                flags.addProperty("excluded", box.excluded);
                flags.addProperty("protected", box.protectedStorage);
                flags.addProperty("dropOff", box.dropOff);
                storage.add(flags);
            }
        out.add("sorterStorage", storage);
        out.addProperty("sorterActive", actualSorter.isActive());
        out.add("repackProgress", actualSorter.repackProgress());
        out.addProperty("shulkerPhase", actualSorter.shulkerPhase());
        if (actualSorter.repackPosition() != null) {
            var p = actualSorter.repackPosition();
            var position = new JsonArray();
            position.add(p.getX());
            position.add(p.getY());
            position.add(p.getZ());
            out.add("repackPosition", position);
        }
        // Test-only observability: separate requested sorting, durable recovery fencing,
        // and effective permission so a journal regression can be diagnosed per phase.
        out.addProperty("sortingPermitRequired", actualSorter.requiresSortingPermit());
        out.addProperty("journalBlocked", PhysicalJournalRuntime.blocked());
        out.addProperty("journalAwaitingMirror", PhysicalJournalRuntime.awaitingMirror());
        out.addProperty("sortingPermitted", actualSorter.sortingPermitted());
        out.addProperty("server", Stash.currentServer());
        out.addProperty("player", mc.player.getName().getString());
        out.addProperty("playerUuid", mc.player.getUUID().toString());
        out.addProperty("legacyMemberWindowClassPresent", legacyMemberWindowClassPresent());
        out.addProperty("dimension", StashIndex.dimension());
        out.addProperty("x", mc.player.getX());
        out.addProperty("y", mc.player.getY());
        out.addProperty("z", mc.player.getZ());
        out.addProperty("containerId", mc.player.containerMenu.containerId);
        out.addProperty("stateId", mc.player.containerMenu.getStateId());
        out.addProperty("interaction", interaction);
        out.addProperty(
                "screen",
                mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        out.addProperty("navigation", NavigationRuntime.status());
        out.addProperty("navigationPaused", NavigationRuntime.paused());
        var settings = baritone.api.BaritoneAPI.getSettings();
        out.addProperty("allowBreak", settings.allowBreak.value);
        out.addProperty("allowBreakAnyway", settings.allowBreakAnyway.value.size());
        out.addProperty("allowPlace", settings.allowPlace.value);
        out.addProperty("allowParkour", settings.allowParkour.value);
        out.addProperty("allowWaterBucketFall", settings.allowWaterBucketFall.value);
        out.addProperty("maxFallHeightNoWater", settings.maxFallHeightNoWater.value);
        out.addProperty("pathing", com.porter.wesort.utils.BaritoneHelper.isActive());
        out.addProperty("physicalJournal", PhysicalJournalRuntime.status());
        out.addProperty("inventoryReady", InventorySync.ready());
        out.addProperty("inventoryFailure", InventorySync.reason());
        out.addProperty("cursorCount", mc.player.containerMenu.getCarried().getCount());
        out.addProperty(
                "cursorFingerprint",
                mc.player.containerMenu.getCarried().isEmpty()
                        ? ""
                        : StashIndex.keyOf(mc.player.containerMenu.getCarried()));
        out.addProperty("lastBlockAck", InventorySync.lastBlockAck());
        out.addProperty("lastBlockSequence", InventorySync.lastBlockSequence());
        out.addProperty("attemptedClicks", InventorySync.attemptedClicks());
        var denials = new JsonObject();
        for (var entry : WorldInteractionGuard.denialCounts().entrySet())
            denials.addProperty(entry.getKey(), entry.getValue());
        out.add("guardDenials", denials);
        var intents = new JsonObject();
        for (var entry : InventorySync.intentAudit().entrySet()) intents.addProperty(entry.getKey(), entry.getValue());
        out.add("intentAudit", intents);
        JsonArray slots = new JsonArray();
        for (var slot : mc.player.containerMenu.slots) {
            JsonObject value = new JsonObject();
            ItemStack stack = slot.getItem();
            value.addProperty("slot", slot.index);
            value.addProperty("count", stack.getCount());
            value.addProperty(
                    "item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            value.addProperty("fingerprint", stack.isEmpty() ? "" : StashIndex.keyOf(stack));
            slots.add(value);
        }
        out.add("slots", slots);
        out.add(
                "aggregate",
                StashIndex.aggregate(mc.player.containerMenu.slots.stream()
                        .map(s -> s.getItem())
                        .toList()));
        if (stash != null && !stash.boxes.isEmpty()) {
            out.add(
                    "index",
                    JsonParser.parseString(new String(
                            StashIndex.build(stash, false).json(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        return out;
    }

    private void respond(boolean success, String message, JsonObject state) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("id", lastId);
        out.addProperty("success", success);
        out.addProperty("message", message);
        if (state != null) out.add("state", state);
        Path tmp = control.resolve("response.tmp");
        Files.writeString(tmp, new Gson().toJson(out));
        Files.move(
                tmp,
                control.resolve("response.json"),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        pending = null;
    }

    public static void blockEntityUpdated(BlockPos pos) {
        if (instance != null) instance.blockEntities.merge(pos.immutable(), 1L, Long::sum);
    }
}
