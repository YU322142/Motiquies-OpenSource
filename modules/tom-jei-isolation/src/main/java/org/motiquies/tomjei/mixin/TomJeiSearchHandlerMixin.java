package org.motiquies.tomjei.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the Tom-owned JEI synchronization adapter is disabled; settings and other handlers survive. */
@Mixin(targets = "com.tom.storagemod.jei.JEIHandler$3", remap = false)
public class TomJeiSearchHandlerMixin {
    @Inject(method = "setSearch(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true,
            require = 1, expect = 1, allow = 1, remap = false)
    private void motiquies$independentSearch(String query, CallbackInfo callback) {
        callback.cancel();
    }
}
