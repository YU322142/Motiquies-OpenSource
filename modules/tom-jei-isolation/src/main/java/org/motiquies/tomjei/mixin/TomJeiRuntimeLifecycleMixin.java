package org.motiquies.tomjei.mixin;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Completes the existing Tom plugin lifecycle; registers no additional JEI plugin/listener. */
@Mixin(targets="com.tom.storagemod.jei.JEIHandler",remap=false)
public abstract class TomJeiRuntimeLifecycleMixin implements IModPlugin {
    @Shadow(remap=false) private static IJeiRuntime jeiRuntime;

    @Override public void onRuntimeUnavailable() { jeiRuntime=null; }
}
