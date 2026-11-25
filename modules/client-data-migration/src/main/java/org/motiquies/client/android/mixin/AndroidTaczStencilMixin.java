package org.motiquies.client.android.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.tacz.guns.util.RenderHelper", remap = false)
public abstract class AndroidTaczStencilMixin {
    @Inject(method = "initializeItemEntityStencil()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void motiquies$skipTaczStencil(CallbackInfo callback) {
        callback.cancel();
    }
}
