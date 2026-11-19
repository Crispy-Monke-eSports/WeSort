package com.porter.wesort.mixin;

import com.porter.wesort.compat.InventorySync;
import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = Rotations.class, remap = false)
public abstract class RotationInputMixin {
    @ModifyVariable(method = "rotate(DDIZLjava/lang/Runnable;)V", at = @At("HEAD"), argsOnly = true)
    private static Runnable wesort$preserveAutomationScope(Runnable callback) {
        return callback != null && InventorySync.calledFromWeSort()
                ? InventorySync.scopedAutomation(callback)
                : callback;
    }
}
