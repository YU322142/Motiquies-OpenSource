package org.motiquies.patchoulijeikeys.mixin;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.item.ItemStack;
import org.motiquies.patchoulijeikeys.JeiBookKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="vazkii.patchouli.client.jei.PatchouliJeiPlugin",remap=false)
public abstract class PatchouliJeiKeysMixin implements IModPlugin {
    @Shadow(remap=false) private static IJeiRuntime jeiRuntime;

    @Inject(method="handleRecipeKeybind(IILnet/minecraft/world/item/ItemStack;)Z",at=@At("HEAD"),cancellable=true,require=1)
    private static void motiquies$currentJeiKeyMappings(int keyCode,int scanCode,ItemStack hovered,CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(JeiBookKeys.handle(jeiRuntime,keyCode,scanCode,hovered));
    }

    /** Override JEI's default no-op: an obsolete runtime must not handle later book input. */
    @Override public void onRuntimeUnavailable() { jeiRuntime=null; }
}
