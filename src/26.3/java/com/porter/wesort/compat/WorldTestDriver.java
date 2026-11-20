package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import com.porter.wesort.modules.StashManager;
import com.porter.wesort.modules.StashSorter;
import com.porter.wesort.stash.Stash;
import com.porter.wesort.stash.StashIndex;
import java.nio.file.*;
import java.util.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
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
    private String interaction = "";
    private long openEpoch, signEpoch;
    private net.minecraft.world.level.block.entity.SignTextSlot signSlot;
    private boolean signSent;
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
        if (driver == null || !phase.equals(driver.crashPhysicalPhase)) return;
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
                case "sign" -> {
                    if (!InventorySync.ready()) {
                        respond(false, InventorySync.reason(), state());
                        return;
                    }
                    BlockPos pos = position(command);
                    if (!mc.level.isLoaded(pos) || mc.player.distanceToSqr(Vec3.atCenterOf(pos)) > 25) {
                        respond(false, "Sign not loaded/reachable", state());
                        return;
                    }
                    signSlot = null;
                    signSent = false;
                    meteordevelopment.meteorclient.utils.world.BlockUtils.interact(
                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false),
                            InteractionHand.MAIN_HAND,
                            true);
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
                    respond(true, "production Manager agent resumed", state());
                }
                case "engine-index" -> {
                    var current = Modules.get().get(StashSorter.class).currentStash();
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
                    var sorter = Modules.get().get(StashSorter.class);
                    ((Setting<String>) sorter.settings.get("stash-name")).set(stash.name);
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
                    ((Setting<StashSorter.Mode>) sorter.settings.get("mode"))
                            .set(StashSorter.Mode.valueOf(
                                    command.has("mode") ? command.get("mode").getAsString() : "Signs"));
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
                case "sort-stop" -> {
                    var sorter = Modules.get().get(StashSorter.class);
                    if (sorter.isActive()) sorter.toggle();
                    respond(true, "actual StashSorter stopped", state());
                }
                case "sort-mark" -> {
                    BlockPos pos = position(command);
                    var current = Modules.get().get(StashSorter.class).currentStash();
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
                    var sorter = Modules.get().get(StashSorter.class);
                    JsonArray probes = new JsonArray();
                    for (var slot : mc.player.containerMenu.slots) {
                        if (slot.index >= mc.player.containerMenu.slots.size() - 36
                                || slot.getItem().isEmpty()) continue;
                        var probe = sorter.explainSort(slot.getItem());
                        if (command.has("labels")) {
                            JsonObject scores = new JsonObject();
                            for (var label : command.getAsJsonArray("labels"))
                                scores.addProperty(
                                        label.getAsString(),
                                        com.porter.wesort.stash.Categories.signScore(
                                                label.getAsString(), slot.getItem()));
                            probe.add("scores", scores);
                        }
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
                    var sorter = Modules.get().get(StashSorter.class);
                    ((Setting<Boolean>) sorter.settings.get("sort-items")).set(false);
                    ((Setting<Boolean>) sorter.settings.get("barrels")).set(true);
                    ((Setting<Boolean>) sorter.settings.get("walk")).set(true);
                    ((Setting<Integer>) sorter.settings.get("radius")).set(48);
                    ((Setting<Integer>) sorter.settings.get("delay")).set(1);
                    ((Setting<Integer>) sorter.settings.get("container-delay")).set(0);
                    respond(true, "configured production scan-only settings", state());
                }
                case "window-capture" -> {
                    var window = com.porter.wesort.modules.StashWindow.get();
                    if (window == null) {
                        window = new com.porter.wesort.modules.StashWindow();
                        Modules.get().add(window);
                    }
                    if (!window.isActive()) window.toggle();
                    respond(true, "enabled actual passive Stash Window capture", state());
                }
                case "window-open" ->
                    com.porter.wesort.modules.StashWindow.get().openNow();
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
        if (action.equals("window-open")) {
            if (!(mc.gui.screen() instanceof com.porter.wesort.manager.gui.StashManagerScreen)) return;
            var hub = com.porter.wesort.modules.StashWindow.get().hub();
            var store = hub.store();
            if (store == null || !store.loaded()) return;
            var entries = store.snapshot();
            hub.prepare(entries, 200_000_000L);
            var built = hub.build(entries);
            if (built == null || built.index().containersKnown() != entries.size()) return;
            if (++stable < 20) return; // Let the real GUI build and render from the captured index.
            var output = state();
            output.add("windowItems", new Gson().toJsonTree(built.index().items()));
            output.add("windowShulkers", new Gson().toJsonTree(built.index().shulkers()));
            respond(true, "actual Stash Window rendered passive 26.3 scan", output);
            return;
        }
        if (action.equals("sign")) {
            BlockPos pos = position(pending);
            if (signSlot == null) return;
            List<String> lines = new ArrayList<>();
            pending.getAsJsonArray("lines").forEach(line -> lines.add(line.getAsString()));
            if (!signSent) {
                WorldInteractionGuard.authorizeSign(pos);
                signEpoch = blockEntities.getOrDefault(pos, 0L);
                mc.player.connection.send(
                        new net.minecraft.network.protocol.game.ServerboundSignUpdatePacket(pos, lines, signSlot));
                signSent = true;
                return;
            }
            if (blockEntities.getOrDefault(pos, 0L) <= signEpoch) return;
            if (!(mc.level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign))
                return;
            List<String> actual = sign.getText(signSlot).getMessages(false).stream()
                    .map(net.minecraft.network.chat.Component::getString)
                    .toList();
            if (actual.equals(lines)) respond(true, "authoritative sign block-entity update verified", state());
            return;
        }
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
            } else if (action.equals("break"))
                meteordevelopment.meteorclient.utils.world.BlockUtils.breakBlock(pos, true);
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
            var labels = com.porter.wesort.stash.Containers.read(stash, box);
            if (labels != null) box.labels.addAll(labels.labels());
            if (!ObservationRuntime.canRead(box)) return;
            ObservationRuntime.record(box);
            stash.boxes.removeIf(b -> b.pos.equals(pos));
            stash.boxes.add(box);
            respond(true, "server container observed", state());
        }
    }

    @SuppressWarnings("unchecked")
    private void publish(JsonObject command) throws Exception {
        if (stash == null) stash = Stash.load("e2e-fixture");
        if (stash == null || stash.boxes.isEmpty()) {
            respond(false, "No observed containers", null);
            return;
        }
        stash.save();
        StashSorter sorter = Modules.get().get(StashSorter.class);
        ((Setting<String>) sorter.settings.get("stash-name")).set(stash.name);
        StashManager manager = Modules.get().get(StashManager.class);
        ((Setting<String>) manager.settings.get("server-url"))
                .set(command.get("url").getAsString());
        ((Setting<String>) manager.settings.get("api-key"))
                .set(command.get("key").getAsString());
        if (!manager.isActive()) manager.toggle();
        respond(true, "production signed module enabled; verify Manager separately", state());
    }

    private static BlockPos position(JsonObject o) {
        return new BlockPos(
                o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
    }

    private JsonObject state() {
        JsonObject out = new JsonObject();
        var actualSorter = Modules.get().get(StashSorter.class);
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
        out.addProperty("sortingPermitted", Modules.get().get(StashSorter.class).sortingPermitted());
        out.addProperty("server", Stash.currentServer());
        out.addProperty("player", mc.player.getName().getString());
        out.addProperty("playerUuid", mc.player.getUUID().toString());
        out.addProperty("legacyMemberWindowRegistered", com.porter.wesort.modules.StashWindow.get() != null);
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

    @EventHandler
    private void screen(OpenScreenEvent event) {
        if (pending == null || !pending.get("action").getAsString().equals("sign")) return;
        if (!(event.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen editor)) return;
        var sign = SignEditors.signOf(editor);
        if (!sign.getBlockPos().equals(position(pending))) return;
        signSlot = sign.getSlotPlayerIsFacing(mc.player);
        event.cancel(); // Same one-packet editor path as StashLabeller; no later vanilla empty update.
    }

    public static void blockEntityUpdated(BlockPos pos) {
        if (instance != null) instance.blockEntities.merge(pos.immutable(), 1L, Long::sum);
    }
}
