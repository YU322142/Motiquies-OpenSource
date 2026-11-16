import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A server-only overlay, inactive unless its explicit JVM flag is set. */
public final class PatchCacheOnly implements Opcodes {
    static final String CORE="com/seibel/distanthorizons/core/";
    static InsnList enabled(LabelNode fallback) {
        InsnList code=new InsnList();
        code.add(new LdcInsnNode("motiquies.dh.cacheOnly"));
        code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Boolean","getBoolean","(Ljava/lang/String;)Z",false));
        code.add(new JumpInsnNode(IFEQ,fallback));return code;
    }
    static byte[] patch(byte[] bytes,boolean session) {
        ClassNode cls=new ClassNode();new ClassReader(bytes).accept(cls,0);int changes=0;
        for(MethodNode method:cls.methods) {
            if(session && method.name.equals("getValue") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/Object;")) {
                LabelNode fallback=new LabelNode();InsnList code=enabled(fallback);
                code.add(new VarInsnNode(ALOAD,1));
                code.add(new FieldInsnNode(GETSTATIC,CORE+"config/Config$Common$WorldGenerator","enableDistantGeneration","L"+CORE+"config/types/ConfigEntry;"));
                code.add(new MethodInsnNode(INVOKEVIRTUAL,CORE+"config/types/ConfigEntry","getChatCommandName","()Ljava/lang/String;",false));
                code.add(new MethodInsnNode(INVOKEVIRTUAL,"java/lang/String","equals","(Ljava/lang/Object;)Z",false));
                code.add(new JumpInsnNode(IFEQ,fallback));
                code.add(new FieldInsnNode(GETSTATIC,"java/lang/Boolean","TRUE","Ljava/lang/Boolean;"));
                code.add(new InsnNode(ARETURN));code.add(fallback);code.add(new FrameNode(F_SAME,0,null,0,null));
                method.instructions.insert(code);changes++;
            }
            String descriptor="(L"+CORE+"multiplayer/server/ServerPlayerState;L"+CORE+"network/messages/fullData/FullDataSourceRequestMessage;L"+CORE+"multiplayer/server/ServerPlayerState$RateLimiterSet;)V";
            if(!session && method.name.equals("queueWorldGenForRequestMessage") && method.desc.equals(descriptor)) {
                LabelNode fallback=new LabelNode();InsnList code=enabled(fallback);
                for(int i=0;i<4;i++)code.add(new VarInsnNode(ALOAD,i));
                code.add(new MethodInsnNode(INVOKEVIRTUAL,cls.name,"queueLodSyncForRequestMessage",descriptor,false));
                code.add(new InsnNode(RETURN));code.add(fallback);code.add(new FrameNode(F_SAME,0,null,0,null));
                method.instructions.insert(code);changes++;
            }
        }
        if(changes!=1)throw new IllegalStateException("Unexpected DH class layout: "+cls.name+" "+changes);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);cls.accept(writer);return writer.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        int changed=0;
        try(JarFile input=new JarFile(args[0]);JarOutputStream output=new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            var entries=input.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry=entries.nextElement();byte[] bytes=input.getInputStream(entry).readAllBytes();
                boolean session=entry.getName().equals(CORE+"multiplayer/config/SessionConfig.class");
                if(session || entry.getName().equals(CORE+"multiplayer/server/FullDataSourceRequestHandler.class")) {bytes=patch(bytes,session);changed++;}
                JarEntry copy=new JarEntry(entry.getName());copy.setTime(entry.getTime());output.putNextEntry(copy);output.write(bytes);output.closeEntry();
            }
        }
        if(changed!=2)throw new IllegalStateException("Expected exactly two changed classes");
        System.out.println("DH_CACHE_ONLY_OVERLAY_READY");
    }
}
