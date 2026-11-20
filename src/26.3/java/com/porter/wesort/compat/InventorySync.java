package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.google.gson.*;
import com.porter.wesort.data.DataDirectory;
import com.porter.wesort.inventory.InventoryReadback;
import com.porter.wesort.stash.Stash;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** A fail-closed inventory fence. Neither a delay nor local prediction acknowledges an action. */
public final class InventorySync {
    private static final InventorySync INSTANCE = new InventorySync();
    private static final InventoryReadback gate = new InventoryReadback();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static Path journal;
    private static JsonObject origin, actionBefore;
    private static boolean recoveryOpening;
    private static BlockPos lastInteracted;
    private static boolean internal, reconciling, rotatedInput;
    private static String reported = "";
    private static long reconcileDeadline;
    private static int lastBlockAck = -1, lastBlockSequence = -1;
    private static long clicks, contentsReceivedAt;
    private static InventoryReadback.Expected serverContents;

    private record ReadOpen(
            BlockPos pos,
            int sequence,
            int previousMenu,
            long after,
            long deadline,
            Object level,
            boolean ambiguous,
            boolean recovery) {}

    private static ReadOpen readOpen;
    private static BlockPos readSource;
    private static int readMenu;
    private static Object readLevel;
    private static net.minecraft.world.level.block.state.BlockState readBlock;
    private static final Map<Integer, BlockExpected> blocks = new LinkedHashMap<>();
    private static BlockUse blockUse;
    private static BlockPos expectSpot;
    private static String expectBlock;
    private static boolean recoveringWorld, recoveringReceipt;
    private static boolean reopenRequested;

    private record BlockExpected(int sequence, BlockPos pos, String block, long deadline) {}

    private record BlockUse(
            BlockPos clicked, String beforeClicked, BlockPos adjacent, String beforeAdjacent, List<String> inventory) {}

    private InventorySync() {}

    public static void initialize() {
        try {
            Path folder = DataDirectory.clientFolder(MeteorClient.FOLDER.toPath(), "inventory-sync");
            Files.createDirectories(folder);
            privateMode(folder, "rwx------");
            journal = folder.resolve("pending.json");
            if (Files.exists(journal)) {
                if (Files.size(journal) > 4 * 1024 * 1024)
                    throw new IllegalStateException("Oversized pending inventory journal");
                JsonObject saved =
                        JsonParser.parseString(Files.readString(journal)).getAsJsonObject();
                if (saved.get("schema").getAsInt() != 1)
                    throw new IllegalStateException("Unsupported pending inventory schema");
                origin = saved.getAsJsonObject("origin");
                var expected = readExpected(saved.getAsJsonObject("expected"));
                for (String key : List.of("server", "dimension", "player"))
                    origin.get(key).getAsString();
                for (var value : saved.getAsJsonArray("blocks")) {
                    JsonObject b = value.getAsJsonObject();
                    BlockExpected block = new BlockExpected(
                            b.get("sequence").getAsInt(),
                            new BlockPos(
                                    b.get("x").getAsInt(),
                                    b.get("y").getAsInt(),
                                    b.get("z").getAsInt()),
                            b.get("block").getAsString(),
                            0);
                    blocks.put(block.sequence, block);
                }
                gate.restorePending(expected);
                PhysicalJournalRuntime.adoptPending(saved);
            }
        } catch (Exception e) {
            origin = null;
            blocks.clear();
            gate.fail("Cannot validate the isolated pending inventory journal");
        }
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        reportFailure();
    }

    public static boolean ready() {
        return gate.ready() && blocks.isEmpty();
    }

    public static boolean allowInput() {
        if (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror()) return false;
        var sorter = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(com.porter.wesort.modules.StashSorter.class);
        if (sorter != null && !sorter.sortingPermitted()) return false;
        if (sorter != null
                && readSource != null
                && mc.player != null
                && readLevel == mc.level
                && mc.player.containerMenu.containerId == readMenu
                && (mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu
                        || mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu)) {
            var current = sorter.currentStash();
            var source = current == null ? null : current.boxAt(readSource);
            if (source != null
                    && (source.protectedStorage
                            || source.excluded && !source.delivery
                            || source.delivery && sorter.requiresSortingPermit())) return false;
        }
        if (ready() || gate.acceptingBatch()) {
            if (mc.player != null)
                actionBefore = frame(new InventoryReadback.Expected(
                        mc.player.containerMenu.containerId,
                        fingerprints(mc.player.containerMenu.slots.stream()
                                .map(s -> s.getItem())
                                .toList()),
                        fingerprint(mc.player.containerMenu.getCarried())));
            return true;
        }
        if (!gate.failed()) {
            gate.fail("New automated input attempted before server readback");
            reportFailure();
        }
        return false;
    }

    public static boolean allowClick(int slot, ContainerInput input) {
        if (!allowInput()) return false;
        var sorter = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(com.porter.wesort.modules.StashSorter.class);
        if (sorter == null || mc.player == null || mc.level == null) return true;
        var menu = mc.player.containerMenu;
        if (!sorter.hasPhysicalOrder()
                || !(menu instanceof net.minecraft.world.inventory.ChestMenu
                        || menu instanceof net.minecraft.world.inventory.ShulkerBoxMenu)) return true;
        if (readSource == null
                || readLevel != mc.level
                || menu.containerId != readMenu
                || !mc.level.isLoaded(readSource)
                || !mc.level.getBlockState(readSource).equals(readBlock)) return false;
        var parts = new ArrayList<BlockPos>();
        parts.add(readSource);
        if (readBlock.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && readBlock.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            var other =
                    readSource.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(readBlock));
            if (!mc.level.isLoaded(other)) return false;
            parts.add(other);
        }
        String id = ObservationRuntime.sourceId(parts);
        int storageSlots = menu.slots.size() - 36;
        if (slot >= 0 && slot < storageSlots)
            return sorter.orderMayTakeFrom(id, slot, menu.getSlot(slot).getItem());
        if (input == ContainerInput.QUICK_MOVE) return sorter.orderMayDepositAutomatically(id);
        return true;
    }

    static BlockPos inspectionSource() {
        return readSource;
    }

    public static boolean failed() {
        return gate.failed();
    }

    public static String reason() {
        return gate.reason();
    }

    public static int lastBlockAck() {
        return lastBlockAck;
    }

    public static int lastBlockSequence() {
        return lastBlockSequence;
    }

    public static boolean recoveryPending() {
        return reconciling;
    }

    public static long attemptedClicks() {
        return clicks;
    }

    public static long contentsReceivedAt() {
        return contentsReceivedAt;
    }

    public static long contentsEpoch() {
        return gate.observedEpoch();
    }

    public static boolean hasServerContents(int menu, long after) {
        return gate.observedMenuSince(menu, after);
    }

    /** Meteor may run rotation callbacks after the originating sorter tick has returned. */
    public static Runnable scopedAutomation(Runnable callback) {
        return () -> {
            boolean previous = rotatedInput;
            rotatedInput = true;
            try {
                callback.run();
            } finally {
                rotatedInput = previous;
            }
        };
    }

    /** This observes actual WeSort call paths, including InvUtils batches; manual vanilla clicks are unaffected. */
    public static boolean calledFromWeSort() {
        if (internal) return false;
        return rotatedInput
                || WALKER.walk(frames ->
                        frames.anyMatch(frame -> frame.getClassName().startsWith("com.porter.wesort.")
                                && !frame.getClassName().startsWith(InventorySync.class.getName())
                                && !frame.getClassName().startsWith("com.porter.wesort.mixin.")));
    }

    /**
     * Announce a placement the bot is about to send: the following own UseItemOn against this
     * empty cell is a durable physical mutation, so its intent (with the placed block) is recorded
     * before the packet leaves. Set immediately before the interact/place call; consumed by the
     * next own UseItemOn against the same cell, cleared otherwise, so a stale slot can never
     * invent an intent.
     */
    public static void expectPlacement(BlockPos spot, String block) {
        expectSpot = spot == null ? null : spot.immutable();
        expectBlock = block;
    }

    public static void clearPlacementExpectation() {
        expectSpot = null;
        expectBlock = null;
    }

    public static void beginBlockUse(net.minecraft.world.phys.BlockHitResult hit) {
        if (mc.player == null || mc.level == null || !calledFromWeSort()) return;
        BlockPos clicked = hit.getBlockPos().immutable(), adjacent = clicked.relative(hit.getDirection());
        blockUse = new BlockUse(
                clicked,
                blockId(clicked),
                adjacent,
                blockId(adjacent),
                fingerprints(mc.player.inventoryMenu.slots.stream()
                        .map(slot -> slot.getItem())
                        .toList()));
    }

    public static void endBlockUse() {
        blockUse = null;
    }

    private static String blockId(BlockPos pos) {
        return BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(pos).getBlock())
                .toString();
    }

    @EventHandler
    private void sent(PacketEvent.Send event) {
        if (mc.player == null || mc.level == null) return;
        if ((event.packet instanceof ServerboundUseItemOnPacket use && !WorldInteractionGuard.allowUse(use.hitResult()))
                || (event.packet instanceof ServerboundPlayerActionPacket action
                        && switch (action.getAction()) {
                            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK ->
                                !WorldInteractionGuard.allowBreak(action.getPos());
                            default -> false;
                        })
                // Sign text travels in its own packet: it needs a live SIGN permit like any mutation.
                || (event.packet instanceof ServerboundSignUpdatePacket sign
                        && !WorldInteractionGuard.allowSign(sign.pos()))) {
            event.cancel();
            return;
        }
        if (internal) {
            if (recoveryOpening && event.packet instanceof ServerboundUseItemOnPacket use) {
                lastBlockSequence = use.sequence();
                readSource = null;
                readOpen = new ReadOpen(
                        use.hitResult().getBlockPos().immutable(),
                        use.sequence(),
                        mc.player.containerMenu.containerId,
                        contentsEpoch(),
                        System.nanoTime() + 8_000_000_000L,
                        mc.level,
                        readOpen != null,
                        true);
            }
            return;
        }
        boolean ours = calledFromWeSort();
        boolean allowed = ready() || gate.acceptingBatch();
        if (ours
                && !allowed
                && (event.packet instanceof ServerboundContainerClickPacket
                        || event.packet instanceof ServerboundUseItemOnPacket
                        || event.packet instanceof ServerboundPlayerActionPacket)) {
            event.cancel();
            if (!gate.failed()) {
                gate.fail("New automated packet attempted before server readback");
                reportFailure();
            }
            return;
        }
        boolean mutation = event.packet instanceof ServerboundContainerClickPacket && ours;
        Map<Integer, Set<String>> alternatives = Map.of();
        if (event.packet instanceof ServerboundUseItemOnPacket use) {
            lastInteracted = use.hitResult().getBlockPos().immutable();
            if (ours) lastBlockSequence = use.sequence();
            if (ours && blockUse != null) {
                BlockPos changed = !blockId(blockUse.clicked).equals(blockUse.beforeClicked)
                        ? blockUse.clicked
                        : (!blockId(blockUse.adjacent).equals(blockUse.beforeAdjacent) ? blockUse.adjacent : null);
                mutation |= !blockUse.inventory.equals(fingerprints(mc.player.inventoryMenu.slots.stream()
                        .map(slot -> slot.getItem())
                        .toList()));
                if (changed != null) {
                    blocks.put(
                            use.sequence(),
                            new BlockExpected(
                                    use.sequence(), changed, blockId(changed), System.nanoTime() + 8_000_000_000L));
                    mutation = true;
                }
                // An announced placement against this exact cell: durable intent with the
                // placed block before the packet leaves (W-061 journal ordering). The slot
                // clears on the first own UseItemOn whether it matches or not.
                BlockPos want = expectSpot;
                String placing = expectBlock;
                expectSpot = null;
                expectBlock = null;
                if (want != null
                        && placing != null
                        && !placing.isEmpty()
                        && use.hitResult().getBlockPos().relative(use.hitResult().getDirection()).equals(want)) {
                    blocks.put(
                            use.sequence(),
                            new BlockExpected(use.sequence(), want, placing, System.nanoTime() + 8_000_000_000L));
                    var hand = mc.player.getMainHandItem();
                    String outcome;
                    if (hand.getCount() <= 1) outcome = "empty";
                    else {
                        var rest = hand.copy();
                        rest.setCount(rest.getCount() - 1);
                        outcome = fingerprint(rest);
                    }
                    alternatives = Map.of(36 + mc.player.getInventory().getSelectedSlot(), Set.of(outcome));
                    mutation = true;
                }
            }
        }
        if (event.packet instanceof ServerboundPlayerActionPacket action && ours) {
            lastBlockSequence = action.getSequence();
            if (action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                    || (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                            && mc.level.getBlockState(action.getPos()).isAir())) {
                blocks.put(
                        action.getSequence(),
                        new BlockExpected(
                                action.getSequence(),
                                action.getPos().immutable(),
                                blockId(action.getPos()),
                                System.nanoTime() + 8_000_000_000L));
                mutation = true;
                // A successful server break may wear the selected pickaxe once (or not, e.g. Unbreaking).
                // Only this exact derived tool outcome is admitted; every other item/component stays exact.
                var tool = mc.player.getMainHandItem();
                if (tool.is(net.minecraft.tags.ItemTags.PICKAXES)
                        && tool.isDamageableItem()
                        && mc.player.containerMenu == mc.player.inventoryMenu) {
                    var worn = tool.copy();
                    worn.setDamageValue(tool.getDamageValue() + 1);
                    String result = worn.getDamageValue() >= worn.getMaxDamage()
                            ? fingerprint(ItemStack.EMPTY)
                            : fingerprint(worn);
                    alternatives = Map.of(36 + mc.player.getInventory().getSelectedSlot(), Set.of(result));
                }
            }
        }
        if (!mutation && event.packet instanceof ServerboundUseItemOnPacket use) {
            BlockPos pos = use.hitResult().getBlockPos().immutable();
            var block = mc.level.getBlockState(pos).getBlock();
            if (block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                boolean ambiguous = readOpen != null;
                readSource = null;
                readOpen = new ReadOpen(
                        pos,
                        use.sequence(),
                        mc.player.containerMenu.containerId,
                        contentsEpoch(),
                        System.nanoTime() + 8_000_000_000L,
                        mc.level,
                        ambiguous,
                        false);
            }
        }
        if (!mutation) return;
        if (PhysicalJournalRuntime.blocked() || PhysicalJournalRuntime.awaitingMirror()) {
            event.cancel();
            gate.fail("Physical journal withheld an automated mutation");
            reportFailure();
            return;
        }
        var menu = mc.player.containerMenu;
        try {
            if (event.packet instanceof ServerboundContainerClickPacket click
                    && click.containerId() != menu.containerId)
                throw new IllegalStateException("Inventory menu changed before send");
            gate.predict(new InventoryReadback.Expected(
                    menu.containerId,
                    fingerprints(menu.slots.stream().map(slot -> slot.getItem()).toList()),
                    fingerprint(menu.getCarried()),
                    alternatives));
            origin = new JsonObject();
            origin.addProperty("server", Stash.currentServer());
            origin.addProperty("dimension", dimension());
            origin.addProperty("player", mc.player.getUUID().toString());
            if (menu.containerId != 0 && lastInteracted != null) {
                JsonArray pos = new JsonArray();
                pos.add(lastInteracted.getX());
                pos.add(lastInteracted.getY());
                pos.add(lastInteracted.getZ());
                origin.add("source", pos);
            }
            persist(); // Durable before the actual packet leaves the client. Never persist an API key.
            ObservationRuntime.invalidate(lastInteracted);
            if (event.packet instanceof ServerboundContainerClickPacket) clicks++;
        } catch (Exception e) {
            event.cancel();
            gate.fail("Cannot durably record the inventory action before send");
            reportFailure();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void tick(TickEvent.Post event) {
        PhysicalJournalRuntime.tickInspection();
        if (reconciling) {
            if (System.nanoTime() > reconcileDeadline) {
                reconciling = false;
                PhysicalJournalRuntime.inspectionFailed("Inspection timed out; load the original source and workspace");
                reportFailure();
            } else if (sourceMatches()) {
                var expected = gate.reconcile(mc.player.containerMenu.containerId, System.nanoTime());
                reconciling = false;
                if (expected != null) {
                    recoveringWorld = true;
                    sendReadback(expected);
                }
            } else if (!reopenRequested && openRecoverySource()) reopenRequested = true;
        }
        if (gate.failed()) return;
        for (var block : blocks.values())
            if (!recoveringWorld && System.nanoTime() >= block.deadline) {
                gate.fail("Block acknowledgement timed out; do not replay the placement or break");
                reportFailure();
                return;
            }
        gate.expire(System.nanoTime());
        if (gate.failed()) {
            reportFailure();
            return;
        }
        if (!gate.ready() && (mc.level == null || mc.player == null)) {
            gate.fail("Disconnected before server inventory readback");
            reportFailure();
            return;
        }
        var expected = gate.fence(System.nanoTime());
        if (expected != null) sendReadback(expected);
    }

    @EventHandler
    private void left(GameLeftEvent event) {
        lastBlockAck = lastBlockSequence = -1;
        serverContents = null;
        readOpen = null;
        readSource = null;
        readLevel = null;
        if (!ready()) {
            gate.fail("Connection ended before inventory verification; do not replay the action");
            reportFailure();
        }
    }

    private static void sendReadback(InventoryReadback.Expected expected) {
        internal = true;
        try {
            // Verified against pinned vanilla 26.3: QUICK_MOVE(-1) returns before touching slots
            // or the cursor in every game mode. A stale state id forces broadcastFullState.
            // REQUEST_STATS is an ordered server response after that readback, not an item mutation.
            mc.player.connection.send(new ServerboundContainerClickPacket(
                    expected.menu(),
                    -1,
                    (short) -1,
                    (byte) 0,
                    ContainerInput.QUICK_MOVE,
                    Int2ObjectMaps.emptyMap(),
                    HashedStack.EMPTY));
            mc.player.connection.send(
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        } finally {
            internal = false;
        }
    }

    /** Read-only full verification for passive pickups. A local inventory stack alone is not a receipt. */
    public static void verifyInventory() {
        if (!ready() || mc.player == null || mc.level == null || mc.player.containerMenu != mc.player.inventoryMenu)
            return;
        try {
            var menu = mc.player.inventoryMenu;
            gate.predict(new InventoryReadback.Expected(
                    menu.containerId,
                    fingerprints(menu.slots.stream().map(slot -> slot.getItem()).toList()),
                    fingerprint(menu.getCarried())));
            actionBefore = frame(gate.expected());
            origin = new JsonObject();
            origin.addProperty("server", Stash.currentServer());
            origin.addProperty("dimension", dimension());
            origin.addProperty("player", mc.player.getUUID().toString());
            persist();
        } catch (Exception failure) {
            gate.fail("Cannot persist passive pickup verification");
            reportFailure();
        }
    }

    public static boolean confirmedMenu() {
        if (!ready() || mc.player == null || serverContents == null) return false;
        var menu = mc.player.containerMenu;
        return serverContents.equals(new InventoryReadback.Expected(
                menu.containerId,
                fingerprints(menu.slots.stream().map(slot -> slot.getItem()).toList()),
                fingerprint(menu.getCarried())));
    }
    /** An ordered native ACK binds the full menu to one unambiguous storage interaction. */
    public static boolean confirmedSource(java.util.List<BlockPos> parts) {
        return confirmedMenu()
                && readSource != null
                && readLevel == mc.level
                && mc.player.containerMenu.containerId == readMenu
                && parts.contains(readSource)
                && mc.level.getBlockState(readSource).equals(readBlock);
    }

    public static void contents(ClientboundContainerSetContentPacket packet) {
        if (mc.level == null) return;
        contentsReceivedAt = System.currentTimeMillis();
        serverContents = new InventoryReadback.Expected(
                packet.containerId(), fingerprints(packet.items()), fingerprint(packet.carriedItem()));
        gate.observe(serverContents);
    }

    public static void stats() {
        gate.barrier();
        if (gate.failed()) {
            if (recoveringWorld)
                PhysicalJournalRuntime.inspectionFailed(
                        "The fresh server frame does not match the recorded postcondition");
            reportFailure();
            return;
        }
        if (gate.ready() && recoveringWorld) {
            if (!worldMatches()) {
                gate.fail("World block does not match the pending physical action");
                reportFailure();
                return;
            }
            blocks.clear();
            recoveringWorld = false;
            recoveringReceipt = true;
        }
        complete();
    }

    private static void complete() {
        if (ready() && journal != null) {
            try {
                if (origin != null && serverContents != null)
                    PhysicalJournalRuntime.verified(frame(serverContents), recoveringReceipt);
                Files.deleteIfExists(journal);
                forceDirectory(journal.getParent());
                origin = null;
                recoveringReceipt = false;
            } catch (Exception e) {
                gate.fail("Cannot commit the verified inventory readback");
                PhysicalJournalRuntime.inspectionFailed("The fresh readback could not be signed off");
                reportFailure();
            }
        }
    }

    public static void blockAck(int sequence) {
        lastBlockAck = Math.max(lastBlockAck, sequence);
        if (readOpen != null && sequence >= readOpen.sequence) {
            var pending = readOpen;
            readOpen = null;
            if (sequence == pending.sequence
                    && !pending.ambiguous
                    && mc.level == pending.level
                    && System.nanoTime() <= pending.deadline
                    && mc.player != null
                    && mc.player.containerMenu.containerId != 0
                    && mc.player.containerMenu.containerId != pending.previousMenu
                    && hasServerContents(mc.player.containerMenu.containerId, pending.after)
                    && (pending.recovery ? rawConfirmedMenu() : confirmedMenu())) {
                readSource = pending.pos;
                readMenu = mc.player.containerMenu.containerId;
                readLevel = mc.level;
                readBlock = mc.level.getBlockState(readSource);
            }
        }
        if (gate.failed()) return;
        for (var block : List.copyOf(blocks.values()))
            if (block.sequence <= sequence) {
                if (!mc.level.isLoaded(block.pos) || !blockId(block.pos).equals(block.block)) {
                    gate.fail("Server rejected or changed the predicted block operation");
                    reportFailure();
                    return;
                }
                blocks.remove(block.sequence);
            }
        complete();
    }

    /** Explicitly requested by the player, then waits for the affected source to be reopened. No click is retried. */
    public static boolean requestReconciliation() {
        if (!gate.failed() || gate.expected() == null || origin == null) return false;
        reconciling = true;
        reopenRequested = false;
        reconcileDeadline = System.nanoTime() + 30_000_000_000L;
        return true;
    }

    /** Explicit recovery may only reopen the exact source, empty-handed, as a read operation. */
    private static boolean openRecoverySource() {
        if (mc.player == null
                || mc.level == null
                || origin == null
                || !origin.has("source")
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()
                || mc.options.keyShift.isDown()) return false;
        if (!mc.player.getMainHandItem().isEmpty()) {
            int empty = -1;
            for (int slot = 0; slot < 9; slot++)
                if (mc.player.getInventory().getItem(slot).isEmpty()) {
                    empty = slot;
                    break;
                }
            if (empty < 0) return false;
            meteordevelopment.meteorclient.utils.player.InvUtils.swap(empty, false);
        }
        if (!mc.player.getOffhandItem().isEmpty()) return false;
        if (!origin.get("server").getAsString().equals(Stash.currentServer())
                || !origin.get("dimension").getAsString().equals(dimension())
                || !origin.get("player")
                        .getAsString()
                        .equals(mc.player.getUUID().toString())) return false;
        JsonArray p = origin.getAsJsonArray("source");
        BlockPos pos =
                new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
        if (!mc.level.isLoaded(pos) || mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 25)
            return false;
        var block = mc.level.getBlockState(pos).getBlock();
        if (!(block instanceof net.minecraft.world.level.block.ChestBlock
                || block instanceof net.minecraft.world.level.block.BarrelBlock
                || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                || block instanceof net.minecraft.world.level.block.CraftingTableBlock
                || block instanceof net.minecraft.world.level.block.AnvilBlock)) return false;
        internal = true;
        recoveryOpening = true;
        try {
            lastInteracted = pos;
            // W-061: recovery reopens the exact container being recovered, nothing else.
            WorldInteractionGuard.authorizeUse(pos);
            meteordevelopment.meteorclient.utils.world.BlockUtils.interact(
                    new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false),
                    net.minecraft.world.InteractionHand.MAIN_HAND,
                    false);
            return true;
        } finally {
            internal = false;
            recoveryOpening = false;
        }
    }

    private static boolean sourceMatches() {
        if (mc.player == null || mc.level == null || origin == null) return false;
        if (!worldMatches()) return false;
        if (!origin.get("server").getAsString().equals(Stash.currentServer())
                || !origin.get("dimension").getAsString().equals(dimension())
                || !origin.get("player")
                        .getAsString()
                        .equals(mc.player.getUUID().toString())) return false;
        if (gate.expected().menu() == 0) return mc.player.containerMenu == mc.player.inventoryMenu;
        if (mc.player.containerMenu == mc.player.inventoryMenu) return false;
        if (origin.has("source")) {
            JsonArray pos = origin.getAsJsonArray("source");
            var source = new BlockPos(
                    pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            return source.equals(lastInteracted)
                    && source.equals(readSource)
                    && readLevel == mc.level
                    && mc.player.containerMenu.containerId == readMenu
                    && mc.level.getBlockState(source).equals(readBlock);
        }
        return mc.player.containerMenu.containerId == gate.expected().menu();
    }

    private static String dimension() {
        return com.porter.wesort.stash.StashIndex.dimension();
    }

    private static boolean worldMatches() {
        if (mc.level == null) return false;
        return blocks.values().stream()
                .allMatch(block ->
                        mc.level.isLoaded(block.pos) && blockId(block.pos).equals(block.block));
    }

    private static void persist() throws Exception {
        if (journal == null) throw new IllegalStateException("No inventory journal");
        JsonObject out = new JsonObject();
        out.addProperty("schema", 1);
        out.add("origin", origin);
        var expected = gate.expected();
        JsonObject value = new JsonObject();
        value.addProperty("menu", expected.menu());
        JsonArray slots = new JsonArray();
        expected.slots().forEach(slots::add);
        value.add("slots", slots);
        value.addProperty("cursor", expected.cursor());
        JsonObject alternatives = new JsonObject();
        expected.alternatives().forEach((slot, outcomes) -> {
            JsonArray allowed = new JsonArray();
            outcomes.forEach(allowed::add);
            alternatives.add(String.valueOf(slot), allowed);
        });
        value.add("alternatives", alternatives);
        out.add("expected", value);
        JsonArray world = new JsonArray();
        for (var block : blocks.values()) {
            JsonObject b = new JsonObject();
            b.addProperty("sequence", block.sequence);
            b.addProperty("x", block.pos.getX());
            b.addProperty("y", block.pos.getY());
            b.addProperty("z", block.pos.getZ());
            b.addProperty("block", block.block);
            world.add(b);
        }
        out.add("blocks", world);
        byte[] data = out.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 4 * 1024 * 1024) throw new IllegalStateException("Inventory action exceeds journal bound");
        Path tmp = Files.createTempFile(journal.getParent(), "pending-", ".tmp");
        try {
            privateMode(tmp, "rw-------");
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(tmp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(journal.getParent());
            PhysicalJournalRuntime.intent(out, actionBefore == null ? value : actionBefore);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static JsonObject frame(InventoryReadback.Expected expected) {
        var value = new JsonObject();
        value.addProperty("menu", expected.menu());
        var slots = new JsonArray();
        expected.slots().forEach(slots::add);
        value.add("slots", slots);
        value.addProperty("cursor", expected.cursor());
        var choices = new JsonObject();
        expected.alternatives().forEach((slot, outcomes) -> {
            var values = new JsonArray();
            outcomes.forEach(values::add);
            choices.add(String.valueOf(slot), values);
        });
        value.add("alternatives", choices);
        return value;
    }

    private static boolean rawConfirmedMenu() {
        if (mc.player == null || serverContents == null) return false;
        var menu = mc.player.containerMenu;
        return serverContents.equals(new InventoryReadback.Expected(
                menu.containerId,
                fingerprints(menu.slots.stream().map(s -> s.getItem()).toList()),
                fingerprint(menu.getCarried())));
    }

    public static JsonObject physicalPlayerInventory() {
        if (mc.player == null || mc.player.containerMenu != mc.player.inventoryMenu || !confirmedMenu())
            throw new IllegalStateException("Fresh full player inventory required");
        var snapshot = SlotRuntime.snapshot(
                mc.player.inventoryMenu.slots.stream().map(s -> s.getItem()).toArray(ItemStack[]::new));
        var writer = new com.porter.wesort.data.PhysicalSlots.Writer();
        var out = new JsonObject();
        out.addProperty("size", snapshot.size());
        out.addProperty("fingerprint", snapshot.fingerprint());
        out.add("slots", writer.slots(snapshot.slots()));
        out.add("slotVariants", writer.variants());
        return out;
    }

    public static boolean inspectCurrentInventory() {
        if (mc.player == null
                || mc.level == null
                || mc.player.containerMenu != mc.player.inventoryMenu
                || !mc.player.containerMenu.getCarried().isEmpty()) return false;
        try {
            var expected = new InventoryReadback.Expected(
                    0,
                    fingerprints(mc.player.inventoryMenu.slots.stream()
                            .map(s -> s.getItem())
                            .toList()),
                    fingerprint(mc.player.inventoryMenu.getCarried()));
            origin = new JsonObject();
            origin.addProperty("server", Stash.currentServer());
            origin.addProperty("dimension", dimension());
            origin.addProperty("player", mc.player.getUUID().toString());
            gate.restorePending(expected);
            blocks.clear();
            return requestReconciliation();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean inspectRecorded(JsonObject evidence) {
        if (mc.player == null || mc.level == null) return false;
        var scope = evidence.getAsJsonObject("origin");
        if (!scope.get("server").getAsString().equals(Stash.currentServer())
                || !scope.get("dimension").getAsString().equals(dimension())
                || !scope.get("player").getAsString().equals(mc.player.getUUID().toString())) return false;
        if (scope.has("source")) {
            var p = scope.getAsJsonArray("source");
            var at = new BlockPos(
                    p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
            if (!mc.level.isLoaded(at)) return false;
            var block = mc.level.getBlockState(at).getBlock();
            if (!(block instanceof net.minecraft.world.level.block.ChestBlock
                    || block instanceof net.minecraft.world.level.block.BarrelBlock
                    || block instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) return false;
        }
        var world = new LinkedHashMap<Integer, BlockExpected>();
        for (var value : evidence.getAsJsonArray("blocks")) {
            var b = value.getAsJsonObject();
            var at = new BlockPos(
                    b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt());
            if (!mc.level.isLoaded(at) || !blockId(at).equals(b.get("block").getAsString())) return false;
            int sequence = world.size() + 1;
            world.put(sequence, new BlockExpected(sequence, at, b.get("block").getAsString(), 0));
        }
        origin = scope.deepCopy();
        blocks.clear();
        blocks.putAll(world);
        gate.restorePending(readExpected(evidence.getAsJsonObject("expected")));
        return requestReconciliation();
    }

    private static InventoryReadback.Expected readExpected(JsonObject value) {
        JsonArray values = value.getAsJsonArray("slots");
        if (values.size() > 256) throw new IllegalArgumentException("Inventory too large");
        List<String> slots = new ArrayList<>();
        values.forEach(item -> slots.add(item.getAsString()));
        Map<Integer, Set<String>> alternatives = new HashMap<>();
        if (value.has("alternatives"))
            for (var entry : value.getAsJsonObject("alternatives").entrySet()) {
                Set<String> allowed = new HashSet<>();
                entry.getValue().getAsJsonArray().forEach(item -> allowed.add(item.getAsString()));
                alternatives.put(Integer.parseInt(entry.getKey()), allowed);
            }
        return new InventoryReadback.Expected(
                value.get("menu").getAsInt(), slots, value.get("cursor").getAsString(), alternatives);
    }

    private static void forceDirectory(Path folder) throws Exception {
        try (FileChannel directory = FileChannel.open(folder, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private static void privateMode(Path path, String mode) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException ignored) {
            /* The installation root still owns access on non-POSIX platforms. */
        }
    }

    private static void reportFailure() {
        if (gate.failed()) PhysicalJournalRuntime.block(gate.reason());
        if (!gate.reason().isEmpty() && !gate.reason().equals(reported)) {
            reported = gate.reason();
            MeteorClient.LOG.error("WeSort inventory automation paused: {}", reported);
        }
    }

    private static List<String> fingerprints(List<ItemStack> stacks) {
        return stacks.stream().map(InventorySync::fingerprint).toList();
    }

    private static String fingerprint(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return NbtFingerprint.encode(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
    }
}
