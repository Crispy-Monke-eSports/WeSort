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

    /**
     * BlockUtils.place/interact defer the real interact into the rotation callback via these
     * overloads. Without the same scope wrap, the callback runs unattributed: no beginBlockUse,
     * no own-packet intent, yet the permit check still passes and the packet leaves. Every
     * Runnable-bearing overload must preserve the automation scope.
     */
    @ModifyVariable(method = "rotate(DDILjava/lang/Runnable;)V", at = @At("HEAD"), argsOnly = true)
    private static Runnable wesort$preserveAutomationScopePriority(Runnable callback) {
        return callback != null && InventorySync.calledFromWeSort()
                ? InventorySync.scopedAutomation(callback)
                : callback;
    }

    @ModifyVariable(method = "rotate(DDLjava/lang/Runnable;)V", at = @At("HEAD"), argsOnly = true)
    private static Runnable wesort$preserveAutomationScopeSimple(Runnable callback) {
        return callback != null && InventorySync.calledFromWeSort()
                ? InventorySync.scopedAutomation(callback)
                : callback;
    }
}
