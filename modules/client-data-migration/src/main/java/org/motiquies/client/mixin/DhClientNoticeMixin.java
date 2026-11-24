package org.motiquies.client.mixin;

import org.motiquies.client.DhLoadNoticeGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DhClientNoticeMixin {
    @Inject(method = "showChatMessageNextFrame", at = @At("HEAD"), cancellable = true, require = 0)
    private void motiquies$coalesceRepeatedLevelFailure(String message, CallbackInfo callback) {
        if (!DhLoadNoticeGate.shouldEnqueue(message)) callback.cancel();
    }
}
