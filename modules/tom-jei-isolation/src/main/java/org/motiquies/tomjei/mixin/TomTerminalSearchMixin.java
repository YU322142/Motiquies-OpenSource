// First-party Motiquies compatibility source; existing module license applies.
package org.motiquies.tomjei.mixin;

import net.minecraft.client.gui.components.EditBox;
import org.motiquies.tomjei.TomSearchFocusGuard;
import org.motiquies.tomjei.TomJeiFocusKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.tom.storagemod.screen.AbstractStorageTerminalScreen", remap = false)
public abstract class TomTerminalSearchMixin {
    @Shadow(remap = false) protected EditBox searchField;

    @Redirect(method = "onPacket()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;setFocused(Z)V", remap = false),
            require = 1, expect = 1, allow = 1, remap = false)
    private void motiquies$respectJeiFocus(EditBox box, boolean focused) {
        TomSearchFocusGuard.apply(box, focused, TomJeiRuntimeAccess.motiquies$getRuntime());
    }

    /** Preserve popup/Escape/Tab priority; let the original JEI Post handler perform its one focus action. */
    @Inject(method = "keyPressed(III)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/EditBox;keyPressed(III)Z", remap = false),
            cancellable = true, require = 1, expect = 1, allow = 1, remap = false)
    private void motiquies$yieldJeiFocus(int key,int scan,int modifiers,CallbackInfoReturnable<Boolean> callback) {
        if (TomJeiFocusKey.shouldYield(searchField,key,scan,TomJeiRuntimeAccess.motiquies$getRuntime())) {
            callback.setReturnValue(false);
        }
    }

}
