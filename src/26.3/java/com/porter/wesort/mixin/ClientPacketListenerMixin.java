package com.porter.wesort.mixin;

import com.porter.wesort.utils.SnapshotSearch;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla 26.3 resolves a name-only player head (a ResolvableProfile.Dynamic) on a worker thread, and
 * LocalPlayerResolver looks the name up in the tab list through getPlayerInfoIgnoreCase, which walks the
 * plain HashMap that the render thread changes on every player info packet. The resulting
 * ConcurrentModificationException fails the future, the render thread rethrows it when it reads the
 * future while drawing the head, and Minecraft.run() ends the game ("Unreported exception thrown!").
 * Off the render thread the lookup now walks a copy, retried when the copy itself races. The render
 * thread keeps the vanilla code (nothing changes the map under it). 1.21.4 has neither the resolver nor
 * this method, so this guard is used only in builds that expose this method.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void wesort$blockEntityObservation(
            net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.porter.wesort.compat.WorldTestDriver.blockEntityUpdated(packet.getPos());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void wesort$inventoryContent(
            net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.porter.wesort.compat.InventorySync.contents(packet);
    }

    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void wesort$inventoryBarrier(
            net.minecraft.network.protocol.game.ClientboundAwardStatsPacket packet,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.porter.wesort.compat.InventorySync.stats();
    }

    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void wesort$blockAck(
            net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket packet,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.porter.wesort.compat.InventorySync.blockAck(packet.sequence());
    }

    @Shadow
    @Final
    private Map<UUID, PlayerInfo> playerInfoMap;

    @Inject(method = "getPlayerInfoIgnoreCase", at = @At("HEAD"), cancellable = true, require = 1)
    private void wesort$safeLookup(String name, CallbackInfoReturnable<PlayerInfo> cir) {
        if (Minecraft.getInstance().isSameThread()) return;
        cir.setReturnValue(SnapshotSearch.find(
                playerInfoMap.values(), info -> info.getProfile().name().equalsIgnoreCase(name)));
    }
}
