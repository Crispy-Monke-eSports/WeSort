package com.porter.wesort.compat;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import java.util.Optional;

public final class IndexComponents {
    private IndexComponents() {}
    public static DataComponentPatch patch(ItemStack stack) {
        // Filled shulkers have a separate content group key. All other component variants remain distinct.
        return Utils.isShulker(stack.getItem()) ? stack.getComponentsPatch().forget(type ->
            type == DataComponents.CONTAINER || type == DataComponents.BLOCK_ENTITY_DATA) : stack.getComponentsPatch();
    }
    public static Optional<String> encode(RegistryOps<Tag> ops, DataComponentPatch patch) {
        return Optional.of(NbtFingerprint.encode(DataComponentPatch.CODEC.encodeStart(ops, patch).getOrThrow()));
    }
}
