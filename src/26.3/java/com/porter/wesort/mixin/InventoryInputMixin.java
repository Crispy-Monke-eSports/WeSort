package com.porter.wesort.mixin;

import com.porter.wesort.compat.InventorySync;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class InventoryInputMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void wesort$blockUncertainAutomation(int container, int slot, int button, ContainerInput input, Player player, CallbackInfo ci) {
        // Cancel before vanilla changes the local inventory. Manual user reconciliation remains possible.
        if (InventorySync.calledFromWeSort() && !InventorySync.allowInput()) ci.cancel();
    }
}
