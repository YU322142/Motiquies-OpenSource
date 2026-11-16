package org.motiquies.compat.mixin;

import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "snownee.jade.addon.vanilla.LecternProvider", remap = false)
public class JadeLecternMixin {
    @Inject(method = "streamData(Lsnownee/jade/api/BlockAccessor;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void motiquies$createLectern(BlockAccessor accessor, CallbackInfoReturnable<ItemStack> callback) {
        var entity = accessor.getBlockEntity();
        if (entity != null && entity.getClass().getName().equals(
                "com.simibubi.create.content.redstone.link.controller.LecternControllerBlockEntity")) {
            // A controller lectern has no vanilla book inventory. Keep all other Jade providers.
            callback.setReturnValue(ItemStack.EMPTY);
        }
    }
}
