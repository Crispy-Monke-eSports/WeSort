package com.porter.wesort.mixin;

import com.porter.wesort.compat.InventorySync;
import com.porter.wesort.compat.WorldInteractionGuard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class BlockInputMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void wesort$beforeBlockUse(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (!WorldInteractionGuard.allowUse(hit) || (InventorySync.calledFromWeSort() && !InventorySync.allowInput())) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        InventorySync.beginBlockUse(hit);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void wesort$afterBlockUse(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        InventorySync.endBlockUse();
    }

    @Inject(
            method = {"startDestroyBlock", "continueDestroyBlock"},
            at = @At("HEAD"),
            cancellable = true)
    private void wesort$beforeBlockBreak(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldInteractionGuard.allowBreak(pos) || (InventorySync.calledFromWeSort() && !InventorySync.allowInput())) {
            cir.setReturnValue(false);
            return;
        }
        wesort$lastBreakTarget = pos == null ? null : pos.immutable();
    }

    private static BlockPos wesort$lastBreakTarget;

    // stopDestroyBlock carries no position: refuse it unless the break it finishes was allowed.
    // The STOP_DESTROY_BLOCK packet is independently checked in InventorySync.
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void wesort$beforeBlockBreakStop(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        BlockPos pos = wesort$lastBreakTarget;
        if (pos != null
                && (!WorldInteractionGuard.allowBreakStop(pos)
                        || (InventorySync.calledFromWeSort() && !InventorySync.allowInput()))) ci.cancel();
    }
}
