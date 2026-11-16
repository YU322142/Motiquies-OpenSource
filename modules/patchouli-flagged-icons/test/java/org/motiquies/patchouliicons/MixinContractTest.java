package org.motiquies.patchouliicons;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

public final class MixinContractTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
    static String code(MethodNode method) {
        method.maxStack = method.maxLocals = 0;
        var text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        return text.getText().toString();
    }
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("motiquies_patchouli_flagged_icons.mixins.json");
        var host = (OfflineMixinService) MixinService.getService();
        String name = "vazkii.patchouli.client.book.BookContentsBuilder";
        byte[] original = host.read(name);
        byte[] transformed = host.transformer().transformClassBytes(name, name, original);
        require(!Arrays.equals(original, transformed), "Mixin did not transform exact pinned Patchouli class");
        new ClassReader(transformed).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        var before = node(original);
        var after = node(transformed);
        Set<String> changed = new TreeSet<>();
        for (var method : before.methods) {
            var replacement = after.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc))
                    .findFirst().orElseThrow();
            if (!code(method).equals(code(replacement))) changed.add(method.name);
        }
        require(changed.equals(Set.of("loadEntry")), "Unexpected changed methods: " + changed);
        require(before.fields.size() == after.fields.size(), "Patchouli field layout changed");
        var load = after.methods.stream().filter(m -> m.name.equals("loadEntry")).findFirst().orElseThrow();
        int gate = -1, construction = -1, index = 0;
        for (var instruction : load.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.contains("checkFlagBeforeParsing")) gate = index;
            if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals("vazkii/patchouli/client/book/BookEntry")) construction = index;
            index++;
        }
        require(gate >= 0 && gate < construction, "Flag gate must run before BookEntry constructor");
        require(Mixins.getConfigs().isEmpty(), "Mixin configuration was not processed");
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.write(output, transformed);
        System.out.println("PASS: exact pinned Mixin weaving; only loadEntry changes; gate precedes entry parsing");
    }
}
