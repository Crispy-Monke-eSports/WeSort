package com.porter.wesort.compat;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import java.util.Optional;
/** Preserve baseline item identities. */
public final class IndexComponents {
    private IndexComponents() {}
    public static DataComponentPatch patch(ItemStack stack) {
        return stack.getComponentsPatch().forget(t -> t == DataComponents.CONTAINER || t == DataComponents.BUNDLE_CONTENTS
            || t == DataComponents.CUSTOM_DATA || (Utils.isShulker(stack.getItem()) && t == DataComponents.BLOCK_ENTITY_DATA));
    }
    public static Optional<String> encode(RegistryOps<Tag> ops, DataComponentPatch patch) {
        return DataComponentPatch.CODEC.encodeStart(ops, patch).result().map(Object::toString);
    }
}
