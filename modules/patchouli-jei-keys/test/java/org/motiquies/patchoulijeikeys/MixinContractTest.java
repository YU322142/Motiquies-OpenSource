package org.motiquies.patchoulijeikeys;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import net.neoforged.fml.loading.LoadingModList;

public final class MixinContractTest {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static ClassNode node(byte[] bytes){var n=new ClassNode();new ClassReader(bytes).accept(n,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return n;}
    private static String code(MethodNode method){method.maxStack=method.maxLocals=0;var text=new Textifier();method.accept(new TraceMethodVisitor(text));return text.getText().toString();}
    public static void main(String[] args)throws Exception{
        boolean absent=args.length>1&&args[1].equals("absent");
        LoadingModList.jeiPresent=!absent;
        MixinBootstrap.init();MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("motiquies_patchouli_jei_keys.mixins.json");
        var host=(OfflineMixinService)MixinService.getService();
        String name="vazkii.patchouli.client.jei.PatchouliJeiPlugin";
        byte[] original=host.read(name),transformed=host.transformer().transformClassBytes(name,name,original);
        if(absent){require(Arrays.equals(original,transformed),"Absent JEI must leave its dependent target unchanged");System.out.println("PASS: JEI absent; optional target untouched and JEI dependency resolution refused by test host");return;}
        require(!Arrays.equals(original,transformed),"Exact pinned target did not transform");
        new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0),false),0);
        var before=node(original);var after=node(transformed);Set<String> changed=new TreeSet<>();
        for(var method:before.methods){
            var replacement=after.methods.stream().filter(m->m.name.equals(method.name)&&m.desc.equals(method.desc)).findFirst().orElseThrow();
            if(!code(method).equals(code(replacement)))changed.add(method.name);
        }
        require(changed.equals(Set.of("handleRecipeKeybind")),"Unexpected changed original methods: "+changed);
        require(before.fields.size()==after.fields.size(),"Original field count changed");
        for(var field:before.fields)require(after.fields.stream().anyMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&f.access==field.access&&Objects.equals(f.value,field.value)),"Original field changed: "+field.name);
        require(before.interfaces.equals(after.interfaces),"Original plugin interface contract changed");
        for(var method:after.methods)if(before.methods.stream().noneMatch(m->m.name.equals(method.name)&&m.desc.equals(method.desc)))
            require(method.name.equals("onRuntimeUnavailable")||method.name.contains("motiquies$currentJeiKeyMappings"),"Unexpected added method: "+method.name);
        Path output=Path.of(args[0]).resolve(name.replace('.','/')+".class");Files.createDirectories(output.getParent());Files.write(output,transformed);
        require(Mixins.getConfigs().isEmpty(),"Mixin configuration unprocessed");
        System.out.println("PASS: exact Mixin weaving; only original key handler changes; availability/subtypes/UID/eager warning unchanged; added unavailable cleanup hook");
    }
}
