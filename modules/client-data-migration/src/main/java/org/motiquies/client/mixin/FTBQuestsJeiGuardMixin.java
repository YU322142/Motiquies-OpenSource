package org.motiquies.client.mixin;

import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Do not resolve the optional quest client class when FTB Quests is absent. */
@Pseudo
@Mixin(targets="dev.ftb.mods.ftbxmodcompat.ftbquests.jei.FTBQuestsJEIIntegration",remap=false)
public abstract class FTBQuestsJeiGuardMixin {
    @Inject(method="onRuntimeAvailable",at=@At("HEAD"),cancellable=true,remap=false)
    private void motiquies$guardAbsentQuestPlugin(CallbackInfo ci){
        if(!ModList.get().isLoaded("ftbquests"))ci.cancel();
    }
}
