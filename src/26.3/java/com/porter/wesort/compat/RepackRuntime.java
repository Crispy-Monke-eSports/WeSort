package com.porter.wesort.compat;

import com.google.gson.*;
import com.porter.wesort.data.CanonicalJson;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.stash.Kits;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** The outer item's exact metadata and all 27 inner slots must survive place/work/break/pickup. */
public final class RepackRuntime {
    private RepackRuntime() {}

    public static String signature(ItemStack stack) {
        return signature(stack, Kits.inside(stack));
    }

    public static String signature(ItemStack outer, ItemStack[] contents) {
        if (outer.isEmpty() || outer.getCount() != 1 || contents.length != 27)
            throw new IllegalArgumentException("One physical 27-slot shulker required");
        if (outer.has(DataComponents.BLOCK_ENTITY_DATA))
            throw new IllegalArgumentException("Unmodeled block-entity metadata needs manual inspection");
        var header = outer.copy();
        header.remove(DataComponents.CONTAINER);
        var identity = new JsonArray();
        identity.add(SlotRuntime.snapshot(new ItemStack[] {header}).fingerprint());
        identity.add(SlotRuntime.snapshot(contents).fingerprint());
        return ContainerIdentity.hash(CanonicalJson.stringify(identity));
    }

    public static Map<String, Long> conservation(List<ItemStack> stacks) {
        Map<String, Long> totals = new TreeMap<>();
        for (var stack : stacks) add(totals, stack, 1, 0);
        return totals;
    }

    private static void add(Map<String, Long> totals, ItemStack stack, long copies, int depth) {
        if (stack.isEmpty()) return;
        if (depth > 8) throw new IllegalArgumentException("Physical conservation nesting limit");
        var children = ObservationRuntime.contents(stack);
        var header = stack.copyWithCount(1);
        if (header.has(DataComponents.BLOCK_ENTITY_DATA))
            throw new IllegalArgumentException("Unmodeled block-entity metadata");
        header.remove(DataComponents.CONTAINER);
        header.remove(DataComponents.BUNDLE_CONTENTS);
        long count = Math.multiplyExact(copies, stack.getCount());
        totals.merge(SlotRuntime.snapshot(new ItemStack[] {header}).fingerprint(), count, Math::addExact);
        for (var child : children) add(totals, child, count, depth + 1);
    }
}
