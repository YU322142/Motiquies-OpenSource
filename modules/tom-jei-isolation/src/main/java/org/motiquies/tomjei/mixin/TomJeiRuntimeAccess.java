package org.motiquies.tomjei.mixin;

import mezz.jei.api.runtime.IJeiRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Uses the runtime already owned by Tom's JEI plugin; adds no second lifecycle listener. */
@Mixin(targets = "com.tom.storagemod.jei.JEIHandler", remap = false)
public interface TomJeiRuntimeAccess {
    @Accessor(value = "jeiRuntime", remap = false)
    static IJeiRuntime motiquies$getRuntime() {
        throw new AssertionError("TomJeiRuntimeAccess mixin was not applied");
    }
}
