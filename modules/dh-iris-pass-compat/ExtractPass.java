import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Test utility: retain the installed DH's selection instructions, not a Java rewrite. */
public final class ExtractPass implements Opcodes {
 public static void main(String[] args) throws Exception {
  ClassNode in=new ClassNode(); new ClassReader(Files.readAllBytes(Path.of(args[0]))).accept(in,0);
  MethodNode original=in.methods.stream().filter(m->m.name.equals("renderLodLayer")&&m.desc.equals("(Z)V")).findFirst().orElseThrow();
  ClassNode out=new ClassNode();out.version=V21;out.access=ACC_PUBLIC;out.name="NativePass";out.superName="java/lang/Object";
  MethodNode test=new MethodNode(ACC_PUBLIC|ACC_STATIC,"choose","(Ljava/lang/Object;Z)Lcom/seibel/distanthorizons/api/enums/rendering/EDhApiRenderPass;",null,null);
  boolean copying=false;int getters=0;
  for(AbstractInsnNode n=original.instructions.getFirst();n!=null;n=n.getNext()) {
   if(n instanceof MethodInsnNode call&&call.name.equals("getDeferTransparentRendering")&&call.owner.endsWith("/DhApiRenderProxy"))getters++;
   if(n instanceof FieldInsnNode f&&f.owner.endsWith("/DhApiRenderProxy")&&f.name.equals("INSTANCE"))copying=true;
   if(copying&&n instanceof FieldInsnNode f&&f.name.equals("RENDER_PARAMS"))break;
   if(copying)test.instructions.add(n.clone(labels(original)));
  }
  if(getters!=1)throw new IllegalStateException("Unexpected target count "+getters);
  test.instructions.add(new VarInsnNode(ALOAD,4));test.instructions.add(new InsnNode(ARETURN));out.methods.add(test);
  ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);out.accept(writer);
  Files.write(Path.of(args[1]),writer.toByteArray());
 }
 private static java.util.Map<LabelNode,LabelNode> map;
 private static java.util.Map<LabelNode,LabelNode> labels(MethodNode m){if(map==null){map=new java.util.HashMap<>();for(AbstractInsnNode n:m.instructions)if(n instanceof LabelNode l)map.put(l,new LabelNode());}return map;}
}
