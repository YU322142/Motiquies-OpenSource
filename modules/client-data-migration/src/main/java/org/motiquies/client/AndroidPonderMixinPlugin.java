package org.motiquies.client;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class AndroidPonderMixinPlugin implements IMixinConfigPlugin {
    static boolean shouldSkipStencil(String nativeDirectory, String openGlLibrary, String enabled) {
        if ("false".equalsIgnoreCase(enabled) || nativeDirectory == null || openGlLibrary == null) {
            return false;
        }
        boolean android = nativeDirectory.startsWith("/data/app/") || nativeDirectory.startsWith("/data/user/");
        String library = openGlLibrary.substring(openGlLibrary.lastIndexOf('/') + 1);
        return android && library.equals("libng_gl4es.so");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return shouldSkipStencil(System.getenv("POJAV_NATIVEDIR"), System.getProperty("org.lwjgl.opengl.libname"),
                System.getProperty("motiquies.android.stencilWorkaround"));
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        System.getLogger("MotiquiesAndroidCompat").log(System.Logger.Level.INFO,
                "Applied the Android Krypton stencil workaround to " + targetClassName);
    }
}
