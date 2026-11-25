package org.motiquies.client.android.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.createmod.catnip.platform.NeoForgeClientHooksHelper", remap = false)
public abstract class AndroidPonderStencilMixin {
    @Inject(method = "enableStencilBuffer(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void motiquies$skipPonderStencil(RenderTarget target, CallbackInfo callback) {
        callback.cancel();
    }
}
