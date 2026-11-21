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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class BlockInputMixin {
    @Shadow
    public abstract boolean isDestroying();

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void wesort$beforeBlockUse(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        boolean worldAllowed = WorldInteractionGuard.allowUse(hit);
        InventorySync.tracePlacePreflight(hit, worldAllowed);
        if (!worldAllowed || (InventorySync.calledFromWeSort() && !InventorySync.allowInput())) {
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
        if (!WorldInteractionGuard.allowBreak(pos)
                || (InventorySync.calledFromWeSort() && !InventorySync.allowInput())) {
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
        // Vanilla sends nothing when it isn't destroying: evaluate nothing, count
        // nothing, and drop any retained target instead of re-checking a stale
        // break against an expired permit on every idle tick.
        if (!isDestroying()) {
            wesort$lastBreakTarget = null;
            return;
        }
        BlockPos pos = wesort$lastBreakTarget;
        if (pos == null) return;
        if (!WorldInteractionGuard.allowBreakStop(pos)
                || (InventorySync.calledFromWeSort() && !InventorySync.allowInput())) {
            ci.cancel();
            return;
        }
        // Never retain a stale break target after vanilla has been allowed to
        // finish its STOP. The outgoing packet remains independently guarded.
        wesort$lastBreakTarget = null;
    }
}
