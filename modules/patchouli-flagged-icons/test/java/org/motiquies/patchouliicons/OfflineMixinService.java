// Adapted from existing Motiquies Tom/JEI offline test host; production code is never started.
package org.motiquies.patchouliicons;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** Test-only Mixin host: transforms byte arrays, never initializes or launches Minecraft. */
@SuppressWarnings("deprecation")
public final class OfflineMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider,
        ITransformerProvider, IClassTracker {
    public String getName() { return "Motiquies offline bytecode contract host"; }
    public boolean isValid() { return true; }
    public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
    public IClassProvider getClassProvider() { return this; }
    public IClassBytecodeProvider getBytecodeProvider() { return this; }
    public ITransformerProvider getTransformerProvider() { return this; }
    public IClassTracker getClassTracker() { return this; }
    public IMixinAuditTrail getAuditTrail() { return null; }
    public Collection<String> getPlatformAgents() { return List.of(); }
    public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("offline-test"); }
    public InputStream getResourceAsStream(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
    public URL[] getClassPath() { return new URL[0]; }
    public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
    public ClassNode getClassNode(String name) throws IOException, ClassNotFoundException { return getClassNode(name, false, 0); }
    public ClassNode getClassNode(String name, boolean transform) throws IOException, ClassNotFoundException { return getClassNode(name, transform, 0); }
    public ClassNode getClassNode(String name, boolean transform, int flags) throws IOException, ClassNotFoundException {
        byte[] bytes = read(name);
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }
    public byte[] read(String name) throws IOException, ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        String archive = resource.startsWith("vazkii/patchouli/") ? System.getProperty("patchouli.original.jar")
                : resource.startsWith("net/minecraft/") ? System.getProperty("patchouli.minecraft.jar")
                : null;
        if (archive != null) {
            try (var zip = new ZipFile(archive)) {
                var entry = zip.getEntry(resource);
                if (entry == null) throw new ClassNotFoundException(name);
                return zip.getInputStream(entry).readAllBytes();
            }
        }
        try (var stream = getResourceAsStream(resource)) {
            if (stream == null) throw new ClassNotFoundException(name);
            return stream.readAllBytes();
        }
    }
    public Collection<ITransformer> getTransformers() { return List.of(); }
    public Collection<ITransformer> getDelegatedTransformers() { return List.of(); }
    public void addTransformerExclusion(String name) {}
    public void registerInvalidClass(String name) {}
    public boolean isClassLoaded(String name) { return false; }
    public String getClassRestrictions(String name) { return ""; }
    public IMixinTransformer transformer() { return getInternal(IMixinTransformerFactory.class).createTransformer(); }
}
