package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.stash.Kits;
import com.porter.wesort.stash.StashIndex;
import java.util.*;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

/** Native 26.3 component snapshots, with explicit sparse slot locations and no movable-box UUID. */
public final class SlotRuntime {
    private SlotRuntime() {}

    public static PhysicalSlots.Snapshot snapshot(ItemStack[] contents) {
        return new PhysicalSlots.Snapshot(contents.length, slots(Arrays.asList(contents), 0));
    }

    private static List<PhysicalSlots.Slot> slots(List<ItemStack> stacks, int depth) {
        if (depth > PhysicalSlots.MAX_DEPTH)
            throw new IllegalArgumentException("Container nesting exceeds verified limit");
        List<PhysicalSlots.Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (!stack.isEmpty()) slots.add(new PhysicalSlots.Slot(slot, stack.getCount(), item(stack, depth)));
        }
        return slots;
    }

    private static PhysicalSlots.Item item(ItemStack stack, int depth) {
        var ops = mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        String patch = NbtFingerprint.encode(DataComponentPatch.CODEC
                .encodeStart(ops, stack.getComponentsPatch())
                .getOrThrow());
        var container = stack.get(DataComponents.CONTAINER);
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        boolean shulker = Utils.isShulker(stack.getItem());
        if (container != null && bundle != null && container.size() > 0 && !bundle.isEmpty())
            throw new IllegalArgumentException("Ambiguous simultaneous container components require manual inspection");
        PhysicalSlots.Kind kind = PhysicalSlots.Kind.ITEM;
        List<ItemStack> contents = List.of();
        int size = 0;
        if (shulker) {
            kind = PhysicalSlots.Kind.SHULKER;
            contents = Arrays.asList(Kits.inside(stack));
            size = 27;
        } else if (bundle != null) {
            kind = PhysicalSlots.Kind.BUNDLE;
            contents = bundle.itemCopies().toList();
            size = contents.size();
        } else if (container != null) {
            kind = PhysicalSlots.Kind.CONTAINER;
            contents = container.itemCopies().toList();
            size = contents.size();
        }
        return new PhysicalSlots.Item(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                StashIndex.keyOf(stack),
                stack.getMaxStackSize(),
                patch,
                kind,
                size,
                contents.isEmpty() ? List.of() : slots(contents, depth + 1),
                shulker ? StashIndex.groupKey(stack) : "");
    }
}
