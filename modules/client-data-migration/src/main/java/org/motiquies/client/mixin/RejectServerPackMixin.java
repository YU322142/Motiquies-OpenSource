package org.motiquies.client.mixin;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerData.class)
abstract class RejectServerPackMixin {
    @Inject(method = "getResourcePackStatus", at = @At("HEAD"), cancellable = true)
    private void motiquies$rejectServerPacks(CallbackInfoReturnable<ServerData.ServerPackStatus> callback) {
        callback.setReturnValue(ServerData.ServerPackStatus.DISABLED);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void motiquies$saveRejectedServerPacks(CallbackInfoReturnable<CompoundTag> callback) {
        callback.getReturnValue().putBoolean("acceptTextures", false);
    }
}
