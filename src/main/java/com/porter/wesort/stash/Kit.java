package com.porter.wesort.stash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * An exact Manager kit definition, slot for slot, counts included. Items match by everything
 * including durability, repair cost, names and every component. Saved kits also require the exact 27-slot layout.
 */
public final class Kit {
    public static final int SLOTS = 27;

    /**
     * An item as kits see it: the item and its complete components, with count kept separately.
     */
    public record Key(Item item, DataComponentMap components, String exact) {}

    /**
     * One kind of item in a kit: how many slots it fills, how many items in total, and the count of each slot.
     */
    public static final class Need {
        public final Key key;
        public final ItemStack sample;
        public final List<Integer> counts = new ArrayList<>();
        public int total;

        Need(Key key, ItemStack sample) {
            this.key = key;
            this.sample = sample;
        }

        public int slots() {
            return counts.size();
        }
    }

    public final String name;
    /** A transient kit made for an order: items match exactly, damage and repair cost included. Not a saved kit. */
    public final boolean orderKit;
    /**
     * Exact slot-for-slot placement (from a Manager kit template). An exact order kit is only
     * finished when the box holds the right stacks in the right slots — never a totals match.
     */
    public final boolean exact;

    private final ItemStack[] layout = new ItemStack[SLOTS];
    private final Key[] keys = new Key[SLOTS];
    private final Map<Key, Need> needs = new LinkedHashMap<>();
    private final Set<Item> items = new HashSet<>();
    private final int[] order;

    public Kit(String name, ItemStack[] stacks) {
        this(name, stacks, false, false);
    }

    public Kit(String name, ItemStack[] stacks, boolean orderKit) {
        this(name, stacks, orderKit, false);
    }

    /** An on-demand kit from a Manager template: order semantics with exact slot placement. */
    public static Kit template(String name, ItemStack[] stacks) {
        return new Kit(name, stacks, true, true);
    }

    public Kit(String name, ItemStack[] stacks, boolean orderKit, boolean exact) {
        if (stacks.length > SLOTS || !orderKit && stacks.length != SLOTS)
            throw new IllegalArgumentException("A saved WeSort kit has exactly 27 slots");
        if (!orderKit && (name == null || name.isBlank() || name.length() > 64))
            throw new IllegalArgumentException("Invalid WeSort kit name");
        this.name = name;
        this.orderKit = orderKit;
        this.exact = exact && orderKit;

        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < stacks.length && stacks[i] != null ? stacks[i] : ItemStack.EMPTY;
            layout[i] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            if (layout[i].isEmpty()) continue;
            if (layout[i].getCount() > layout[i].getMaxStackSize())
                throw new IllegalArgumentException("Kit slot exceeds its stack limit");

            ItemStack here = layout[i];
            Key key = key(here);
            keys[i] = key;
            items.add(here.getItem());
            Need need = needs.computeIfAbsent(key, k -> new Need(k, here.copyWithCount(1)));
            need.counts.add(layout[i].getCount());
            need.total += layout[i].getCount();
        }

        // One stack of each kind first, so a kit that's only part built already holds more than one kind of item
        List<Integer> first = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        Set<Key> seen = new HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            if (keys[i] == null) continue;
            if (seen.add(keys[i])) first.add(i);
            else rest.add(i);
        }
        first.addAll(rest);
        order = first.stream().mapToInt(Integer::intValue).toArray();
    }

    // Items

    /**
     * One exact item, with count normalized to one for comparing.
     */
    public static ItemStack normalized(ItemStack stack) {
        return stack.copyWithCount(1);
    }

    public static Key keyOf(ItemStack stack) {
        ItemStack copy = normalized(stack);
        return new Key(copy.getItem(), copy.getComponents(), null);
    }

    /** The key this kit matches a stack by: exact for an order kit, else {@link #keyOf}. */
    public Key key(ItemStack stack) {
        // An order kit counts by the index key: what the server sends for the same item can differ in components
        // (ViaVersion's
        // marker, the way a shulker's items are stored), so raw components are never compared to decide what is wanted.
        // Which exact variant sits in a slot is decided by the stacks really put there (see KitBuilder.fillOneExact)
        if (!orderKit) return keyOf(stack);
        String indexKey = StashIndex.keyOf(stack);
        return indexKey != null
                ? new Key(stack.getItem(), DataComponentMap.EMPTY, indexKey)
                : new Key(stack.getItem(), stack.copyWithCount(1).getComponents(), null);
    }

    /** {@link #totals} by this kit's own keys. */
    public Map<Key, Integer> totalsOf(ItemStack[] contents) {
        Map<Key, Integer> totals = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            totals.merge(key(stack), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    /**
     * Totals by kind of item. Empty and null slots are skipped.
     */
    public static Map<Key, Integer> totals(ItemStack[] contents) {
        Map<Key, Integer> totals = new HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            totals.merge(keyOf(stack), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    public static int kinds(ItemStack[] contents) {
        Set<Key> kinds = new HashSet<>();
        for (ItemStack stack : contents) if (stack != null && !stack.isEmpty()) kinds.add(keyOf(stack));
        return kinds.size();
    }

    // The layout

    public ItemStack stackAt(int slot) {
        return layout[slot].copy();
    }

    public Key keyAt(int slot) {
        return keys[slot];
    }

    public int countAt(int slot) {
        return layout[slot].isEmpty() ? 0 : layout[slot].getCount();
    }

    public int slotCount() {
        int count = 0;
        for (Key key : keys) if (key != null) count++;
        return count;
    }

    public Iterable<Need> needs() {
        return needs.values();
    }

    public Need need(Key key) {
        return needs.get(key);
    }

    /**
     * Whether the item is one of the kinds this kit holds, ignoring components. Cheap, to skip everything else.
     */
    public boolean hasItem(Item item) {
        return items.contains(item);
    }

    /**
     * Slots in the order to fill them: one of each kind first, then the rest.
     */
    public int[] fillOrder() {
        return order;
    }

    public ItemStack[] layout() {
        return java.util.Arrays.stream(layout).map(ItemStack::copy).toArray(ItemStack[]::new);
    }

    // Matching shulkers

    /**
     * Saved templates require exact slots/components/counts. Transient order manifests retain their total-based layout,
     * unless they come from a kit template (exact placement).
     */
    public boolean matchesFinished(ItemStack[] contents) {
        if (exact) return exactLayout(contents);
        if (!orderKit) return exactLayout(contents);
        Map<Key, Integer> have = totalsOf(contents);
        if (have.size() != needs.size()) return false;
        for (Need need : needs.values()) {
            Integer count = have.get(need.key);
            if (count == null || count != need.total) return false;
        }
        return true;
    }

    /**
     * Something of the kit and nothing else, no more of anything than the kit holds, and not finished.
     */
    public boolean matchesPartial(ItemStack[] contents) {
        Map<Key, Integer> have = totalsOf(contents);
        if (have.isEmpty()) return false;

        boolean complete = have.size() == needs.size();
        for (Map.Entry<Key, Integer> entry : have.entrySet()) {
            Need need = needs.get(entry.getKey());
            if (need == null || entry.getValue() > need.total) return false;
            if (entry.getValue() != need.total) complete = false;
        }
        return orderKit && !exact ? !complete : !exactLayout(contents);
    }

    /**
     * Slot for slot, count for count. Template order kits are done only with the exact placement.
     */
    public boolean exactLayout(ItemStack[] contents) {
        if (contents.length != SLOTS) return false;
        // An order kit is done when it holds the right totals, in whatever slots — unless exact.
        if (orderKit && !exact) return matchesFinished(contents);
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < contents.length && contents[i] != null ? contents[i] : ItemStack.EMPTY;
            if (keys[i] == null) {
                if (!stack.isEmpty()) return false;
            } else if (stack.isEmpty() || stack.getCount() != layout[i].getCount() || !keys[i].equals(key(stack)))
                return false;
        }
        return true;
    }

    // Naming

    public boolean sameName(String other) {
        return com.porter.wesort.data.KitTemplate.nameKey(name)
                .equals(com.porter.wesort.data.KitTemplate.nameKey(other));
    }
}
