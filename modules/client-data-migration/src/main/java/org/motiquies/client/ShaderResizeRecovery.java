package org.motiquies.client;

import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Scoped public-Iris-API workaround for the reproduced DH-water loss after resize.
 * Does not write settings, select another pack, touch world data, or patch Iris/DH. */
final class ShaderResizeRecovery {
    private static final Logger LOG=LogManager.getLogger();
    private final ShaderResizeScheduler scheduler=new ShaderResizeScheduler();
    private Method configMethod,enabledMethod,packMethod,reloadMethod;
    private boolean disabled;
    private long recoveries;

    static void register(){
        if(ModList.get().isLoaded("iris")&&ModList.get().isLoaded("distanthorizons")){
            var bridge=new ShaderResizeRecovery();
            NeoForge.EVENT_BUS.addListener(bridge::tick);
        }
    }
    private void tick(ClientTickEvent.Post event){
        if(disabled)return;
        Minecraft client=Minecraft.getInstance();
        if(client.level==null||client.player==null){scheduler.reset();return;}
        try{
            // Resolve only after normal game/renderer initialization, never during mod construction.
            if(configMethod==null){
                Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
                configMethod=iris.getMethod("getIrisConfig");
                Class<?> configType=configMethod.getReturnType();
                enabledMethod=configType.getMethod("areShadersEnabled");
                packMethod=configType.getMethod("getShaderPackName");
                reloadMethod=iris.getMethod("reload");
            }
            Object config=configMethod.invoke(null);
            Object name=config==null?null:packMethod.invoke(config);
            boolean ours=name instanceof Optional<?> selected && selected.orElse(null) instanceof String pack
                    && pack.startsWith("Nostalgia_v5.1_Motiquies-3.3.0-compat-")&&pack.endsWith(".zip");
            boolean eligible=config!=null&&Boolean.TRUE.equals(enabledMethod.invoke(config))&&ours;
            var window=client.getWindow();
            if(scheduler.take(eligible,window.getWidth(),window.getHeight(),System.nanoTime()/1_000_000L)){
                // Consume the pending gesture before calling Iris; a reload cannot arm itself.
                reloadMethod.invoke(null);
                recoveries++;
                LOG.info("Motiquies recovered Nostalgia DH rendering after stable framebuffer resize: {}x{} (recovery {})",window.getWidth(),window.getHeight(),recoveries);
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
            disabled=true;scheduler.reset();
            LOG.error("Motiquies optional shader resize recovery disabled after failure; original files and settings unchanged",failure);
        }
    }
}
