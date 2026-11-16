import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Change only useItems' nullable pull result. No world, item, registry or other method edits. */
public final class WandTomsPatch {
    static final String OWNER="dev/polaris_light/constructionwand/containers/handlers/HandlerAdvWirelessTerminal";
    static final String TERM="com/tom/storagemod/block/entity/StorageTerminalBlockEntity";
    static final String STORED="com/tom/storagemod/inventory/StoredItemStack";
    static final String PLAYER="net/minecraft/world/entity/player/Player";
    static final String STACK="net/minecraft/world/item/ItemStack";
    static final String TRACE="dev/polaris_light/constructionwand/containers/ContainerTrace";
    static ClassNode read(byte[] bytes){var n=new ClassNode();new ClassReader(bytes).accept(n,ClassReader.EXPAND_FRAMES);return n;}
    static byte[] write(ClassNode n){var w=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(w);return w.toByteArray();}
    static String trace(MethodNode m){var t=new Textifier();m.accept(new TraceMethodVisitor(t));var s=new StringWriter();t.print(new PrintWriter(s));return s.toString();}
    static byte[] patch(byte[] bytes){
        ClassNode n=read(bytes);if(!n.name.equals(OWNER))throw new IllegalArgumentException("Unexpected target");
        MethodNode method=n.methods.stream().filter(m->m.name.equals("useItems")).findFirst().orElseThrow();
        MethodInsnNode quantity=null;int count=0;
        for(var insn:method.instructions){
            if(insn instanceof MethodInsnNode m&&m.owner.equals(STORED)&&m.name.equals("getQuantity")&&m.desc.equals("()J")){
                AbstractInsnNode prev=m.getPrevious();while(prev!=null&&prev.getOpcode()<0)prev=prev.getPrevious();
                if(!(prev instanceof MethodInsnNode pull)||!pull.owner.equals(TERM)||!pull.name.equals("pullStack"))throw new IllegalStateException("Unexpected original pull result layout");
                quantity=m;count++;
            }
        }
        if(count!=1)throw new IllegalStateException("Expected one quantity read");
        Object[] locals={OWNER,PLAYER,TRACE,STACK,STACK,Opcodes.INTEGER,TERM};
        var nonnull=new LabelNode();var done=new LabelNode();var guard=new InsnList();
        guard.add(new InsnNode(Opcodes.DUP));guard.add(new JumpInsnNode(Opcodes.IFNONNULL,nonnull));
        guard.add(new InsnNode(Opcodes.POP));guard.add(new InsnNode(Opcodes.LCONST_0));guard.add(new JumpInsnNode(Opcodes.GOTO,done));
        guard.add(nonnull);guard.add(new FrameNode(Opcodes.F_NEW,locals.length,locals,1,new Object[]{STORED}));
        method.instructions.insertBefore(quantity,guard);
        var join=new InsnList();join.add(done);join.add(new FrameNode(Opcodes.F_NEW,locals.length,locals,1,new Object[]{Opcodes.LONG}));method.instructions.insert(quantity,join);
        byte[] result=write(n);var reloaded=read(result);int unchanged=0;
        for(MethodNode original:read(bytes).methods){
            if(original.name.equals("useItems"))continue;
            MethodNode after=reloaded.methods.stream().filter(m->m.name.equals(original.name)&&m.desc.equals(original.desc)).findFirst().orElseThrow();
            if(!trace(original).equals(trace(after)))throw new IllegalStateException("Unrelated method changed: "+original.name);
            unchanged++;
        }
        System.out.println("Unrelated methods preserved: "+unchanged);return result;
    }
    static void harness(byte[] bytes,Path out)throws Exception{
        ClassNode n=read(bytes);n.interfaces.clear();n.fields.clear();n.signature=null;
        n.methods.removeIf(m->!List.of("<init>","useItems","resolveTerminal").contains(m.name));
        var resolve=n.methods.stream().filter(m->m.name.equals("resolveTerminal")).findFirst().orElseThrow();
        resolve.instructions.clear();resolve.tryCatchBlocks.clear();resolve.localVariables=null;
        resolve.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,"org/motiquies/qa/WandHarnessState","terminal","L"+TERM+";"));resolve.instructions.add(new InsnNode(Opcodes.ARETURN));
        Path target=out.resolve(OWNER+".class");Files.createDirectories(target.getParent());Files.write(target,write(n));
    }
    public static void main(String[] args)throws Exception{
        byte[] original=Files.readAllBytes(Path.of(args[1]));
        if(args[0].equals("red")){harness(original,Path.of(args[2]));return;}
        if(!args[0].equals("green"))throw new IllegalArgumentException();
        byte[] changed=patch(original);Files.write(Path.of(args[2]),changed);harness(changed,Path.of(args[3]));
    }
}
