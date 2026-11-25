package com.porter.wesort.stash;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.stash.Stash.Box;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Read-only, in-memory exact definitions received with assigned Manager execution jobs. */
public final class Kits {
    private Kits() {}

    /**
     * A shulker that is a kit, or part of one. {@code mixed} is two or more kinds of item inside.
     */
    public record Match(Kit kit, boolean finished, boolean mixed) {
        /**
         * Safe to treat as a kit anywhere, not only in its own chest: finished, or clearly part built.
         */
        public boolean claims() {
            return finished || mixed;
        }
    }

    private record StackKey(Item item, DataComponentMap components) {}

    private static final List<Kit> kits = new ArrayList<>();
    private static final Map<StackKey, Match> matches = new HashMap<>();
    private static final Match NONE = new Match(null, false, false);
    private static String loadedFor;
    private static int version;

    // The list

    /**
     * Bumps whenever the list changes or is loaded, so callers can tell something may have changed.
     */
    public static int version() {
        return version;
    }

    public static List<Kit> all() {
        ensureLoaded();
        return Collections.unmodifiableList(kits);
    }

    public static Kit byName(String name) {
        ensureLoaded();
        if (assigned != null && assigned.sameName(name)) return assigned;
        for (int i = kits.size() - 1; i >= 0; i--) if (kits.get(i).sameName(name)) return kits.get(i);
        return null;
    }

    /**
     * Forgets the loaded list, on leaving the world.
     */
    public static void clear() {
        kits.clear();
        release();
        cacheScope = "";
        loadedFor = null;
        changed();
    }

    private static void changed() {
        version++;
        matches.clear();
    }

    private static Kit assigned;
    private static String destination = "", assignmentScope = "", cacheScope = "";
    private static long assignmentUntil;

    private static String currentScope() {
        var manager = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(com.porter.wesort.modules.StashManager.class);
        return manager == null ? "" : manager.linkScope();
    }

    public static boolean isShared(Kit kit) {
        return kits.contains(kit);
    }

    public static boolean buildable(Kit kit) {
        return kit == assigned
                && assignmentUntil > System.nanoTime()
                && !assignmentScope.isEmpty()
                && assignmentScope.equals(currentScope());
    }

    public static Kit assigned() {
        return assigned;
    }

    public static void assign(com.porter.wesort.data.KitTemplate template, String scope, String target, long until) {
        ensureLoaded();
        var incoming = com.porter.wesort.compat.KitRuntime.load(template);
        if (!scope.equals(cacheScope)) {
            kits.clear();
            cacheScope = scope;
        }
        kits.removeIf(known -> known.sameName(template.name())
                && com.porter.wesort.compat.KitRuntime.export(known)
                        .fingerprint()
                        .equals(template.fingerprint()));
        // Retain observed historical definitions for protection, while execution pins this exact immutable object.
        assigned = incoming;
        destination = target;
        assignmentScope = scope;
        assignmentUntil = until;
        kits.add(incoming);
        changed();
    }

    public static void renew(long until) {
        assignmentUntil = until;
    }

    public static void revoke() {
        assignmentUntil = 0;
    }

    public static void release() {
        assigned = null;
        destination = "";
        assignmentScope = "";
        assignmentUntil = 0;
        changed();
    }

    public static String sourceId(Box box) {
        return new com.porter.wesort.data.ContainerIdentity(
                        Stash.currentServer(),
                        StashIndex.dimension(),
                        box.parts.stream()
                                .map(p -> new com.porter.wesort.data.ContainerIdentity.Position(
                                        p.getX(), p.getY(), p.getZ()))
                                .toList())
                .id();
    }

    // Finding kits

    /**
     * The kit a shulker is, or part of, going by what's inside. Null if it isn't one, or is named (named shulkers are
     * left as they are).
     */
    public static Match match(ItemStack shulker) {
        if (shulker == null
                || shulker.isEmpty()
                || !Utils.isShulker(shulker.getItem())
                || com.porter.wesort.compat.ShulkerIdentityRuntime.hasForeignName(shulker)) return null;
        ensureLoaded();
        if (kits.isEmpty()) return null;

        StackKey lookup = new StackKey(shulker.getItem(), shulker.getComponents());
        Match cached = matches.get(lookup);
        if (cached != null) return cached == NONE ? null : cached;

        Match found = compute(shulker);
        matches.put(new StackKey(shulker.getItem(), shulker.copy().getComponents()), found == null ? NONE : found);
        return found;
    }

    private static Match compute(ItemStack shulker) {
        ItemStack[] inside = inside(shulker);
        boolean any = false;
        for (ItemStack s : inside) if (s != null && !s.isEmpty()) any = true;
        if (!any) return null;

        boolean mixed = Kit.kinds(inside) >= 2;
        if (assigned != null && assigned.matchesFinished(inside)) return new Match(assigned, true, mixed);
        for (Kit kit : kits) if (kit.matchesFinished(inside)) return new Match(kit, true, mixed);
        if (assigned != null && assigned.matchesPartial(inside)) return new Match(assigned, false, mixed);
        for (Kit kit : kits) if (kit.matchesPartial(inside)) return new Match(kit, false, mixed);
        return null;
    }

    /**
     * A finished kit, or one clearly part built, of any saved kit.
     */
    public static boolean claims(ItemStack shulker) {
        Match match = match(shulker);
        return match != null && match.claims();
    }

    public static ItemStack[] inside(ItemStack shulker) {
        ItemStack[] contents = new ItemStack[Kit.SLOTS];
        Utils.getItemsInContainerItem(shulker, contents);

        // Shulkers that came through ViaVersion keep their items in block_entity_data. A shulker always has a (default,
        // empty) container component, so the reader never looks there: it is asked again with the container taken off
        if (shulker.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA) && allEmpty(contents)) {
            ItemStack[] other = legacyContents(shulker);
            if (other != null) return other;
        }
        return contents;
    }

    // The legacy read is worked out once per stack and tick
    private static final java.util.IdentityHashMap<ItemStack, ItemStack[]> LEGACY = new java.util.IdentityHashMap<>();
    private static int legacyTick = -1;

    private static ItemStack[] legacyContents(ItemStack shulker) {
        int tick = net.minecraft.client.Minecraft.getInstance().player == null
                ? -1
                : net.minecraft.client.Minecraft.getInstance().player.tickCount;
        if (tick != legacyTick) {
            LEGACY.clear();
            legacyTick = tick;
        }
        if (LEGACY.containsKey(shulker)) return LEGACY.get(shulker);

        ItemStack[] result = null;
        // Only data that has an item list is worth the read
        if (com.porter.wesort.compat.LegacyItems.hasItemList(shulker)) {
            try {
                ItemStack copy = shulker.copy();
                copy.remove(net.minecraft.core.component.DataComponents.CONTAINER);
                ItemStack[] other = new ItemStack[Kit.SLOTS];
                Utils.getItemsInContainerItem(copy, other);
                if (!allEmpty(other)) result = other;
            } catch (RuntimeException e) {
                // Not readable: treated as the container says
            }
        }
        LEGACY.put(shulker, result);
        return result;
    }

    private static boolean allEmpty(ItemStack[] contents) {
        for (ItemStack s : contents) if (s != null && !s.isEmpty()) return false;
        return true;
    }

    // Chests

    /**
     * A kit staging container is identified by the Manager's assigned physical
     * source, never by arbitrary text on a nearby Minecraft sign.
     */
    public static boolean isKitBox(Box box) {
        return assigned != null && sourceId(box).equals(destination);
    }

    /** Only the current signed Manager assignment may designate a kit home. */
    public static List<Box> homes(Kit kit, Stash stash) {
        if (stash == null || kit == null || kit != assigned || !buildable(kit)) return List.of();
        List<Box> found = new ArrayList<>();
        for (Box box : com.porter.wesort.compat.ObservationRuntime.boxes(stash))
            if (box.sortable() && !box.dropOff && sourceId(box).equals(destination)) found.add(box);
        return found;
    }

    /**
     * Finished copies of a kit sitting in the stash, named or not.
     */
    public static int built(Kit kit, Stash stash) {
        if (stash == null) return 0;
        int count = 0;
        for (Box box : com.porter.wesort.compat.ObservationRuntime.boxes(stash)) {
            if (!box.known() || box.foreign || !com.porter.wesort.compat.ObservationRuntime.current(box)) continue;
            for (ItemStack stack : box.contents) {
                if (!stack.isEmpty() && Utils.isShulker(stack.getItem()) && kit.matchesFinished(inside(stack))) count++;
            }
        }
        return count;
    }

    /** No local kit library is discovered or read. Only a signed, assigned Manager job installs execution data. */
    private static void ensureLoaded() {
        if (mc.level == null) return;
        String server = Stash.currentServer();
        if (!server.equals(loadedFor)) {
            clear();
            loadedFor = server;
        }
    }
}
