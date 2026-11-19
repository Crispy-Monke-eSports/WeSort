package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * Player helpers that differ between Minecraft versions, for Minecraft 26.3.
 */
public final class Players {
    private Players() {}

    /**
     * The hotbar slot the player has selected.
     */
    public static int selectedSlot() {
        return mc.player.getInventory().getSelectedSlot();
    }

    public static boolean isSpear(ItemStack stack) {
        return stack.is(ItemTags.SPEARS);
    }
}
