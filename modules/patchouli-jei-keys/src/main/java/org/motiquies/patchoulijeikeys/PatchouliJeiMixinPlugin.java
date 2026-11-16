package org.motiquies.patchoulijeikeys;

import java.util.*;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** Optional JEI remains optional: do not resolve its dependent target when JEI is absent. */
public final class PatchouliJeiMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target,String mixin) {
        var mods=LoadingModList.get();
        if(mods==null)throw new IllegalStateException("NeoForge loading mod inventory unavailable for Patchouli JEI key compatibility");
        return mods.getModFileById("jei")!=null;
    }
    @Override public void acceptTargets(Set<String> mine,Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
}
