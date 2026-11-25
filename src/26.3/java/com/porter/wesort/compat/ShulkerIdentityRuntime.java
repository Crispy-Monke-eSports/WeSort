package com.porter.wesort.compat;

import com.porter.wesort.data.ShulkerUuid;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The internal literal UUID name is identity, not a user label or a kit type. */
public final class ShulkerIdentityRuntime {
    private ShulkerIdentityRuntime() {}

    public static UUID uuid(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && name.equals(Component.literal(name.getString()))
                ? ShulkerUuid.parse(name.getString())
                : null;
    }

    public static boolean hasForeignName(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME) && uuid(stack) == null;
    }
}
