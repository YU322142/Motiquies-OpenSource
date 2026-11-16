"""Mutation targets: sync after selection, cache a prior pipeline, sync deferred calls,
or forget shader-off/null-pipeline transitions. Native selection instructions are real;
Iris pipeline and proxy are boundary fixtures. No Minecraft or Mixin bootstrap runs.
"""
from pathlib import Path
import subprocess,os,zipfile,sys,shutil
sys.dont_write_bytecode = True
from build_support import ROOT, Context, parser
argument_parser=parser(__doc__)
argument_parser.add_argument('mode',choices=('red','green'))
argument_parser.add_argument('--production-classes',type=Path)
arguments=argument_parser.parse_args()
context=Context(arguments)
P=ROOT;B=context.output;J=context.java.parent
libs=context.deps
asm=[next(x for x in libs if x.name==n) for n in ('asm-9.8.jar','asm-tree-9.8.jar')]
mixin=next(x for x in libs if x.name.startswith('sponge-mixin-'))

def run(a):return subprocess.run(list(map(str,a)),capture_output=True,text=True,encoding='utf-8',errors='replace')
def compile(src,dest,cp):
 dest.mkdir(exist_ok=True,parents=True);r=run([context.javac,'--release','21','-proc:none','-cp',os.pathsep.join(map(str,cp)),'-d',dest,*src]);assert r.returncode==0,r.stdout+r.stderr
compile([P/'ExtractPass.java'],B/'tools',asm)
dh=context.sdk/context.lock['targets']['dh']
raw=zipfile.ZipFile(dh).read('com/seibel/distanthorizons/core/api/internal/ClientApi.class');(B/'ClientApi.class').write_bytes(raw)
H=B/'harness';H.mkdir(exist_ok=True)
r=run([context.java,'-cp',os.pathsep.join(map(str,[B/'tools',*asm])),'ExtractPass',B/'ClientApi.class',H/'NativePass.class']);assert r.returncode==0,r.stdout+r.stderr
stubs={
'com/seibel/distanthorizons/api/enums/rendering/EDhApiRenderPass.java':'package com.seibel.distanthorizons.api.enums.rendering; public enum EDhApiRenderPass {OPAQUE,TRANSPARENT,OPAQUE_AND_TRANSPARENT}',
'com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderProxy.java':'package com.seibel.distanthorizons.api.interfaces.render;public interface IDhApiRenderProxy {void setDeferTransparentRendering(boolean b);boolean getDeferTransparentRendering();}',
'com/seibel/distanthorizons/core/render/DhApiRenderProxy.java':'package com.seibel.distanthorizons.core.render; public class DhApiRenderProxy implements com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy {public static final DhApiRenderProxy INSTANCE=new DhApiRenderProxy(); public boolean defer;public void setDeferTransparentRendering(boolean b){defer=b;}public boolean getDeferTransparentRendering(){return defer;}}',
'com/seibel/distanthorizons/api/DhApi.java':'package com.seibel.distanthorizons.api; public class DhApi {public static class Delayed {public static com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy renderProxy=com.seibel.distanthorizons.core.render.DhApiRenderProxy.INSTANCE;}}',
'net/irisshaders/iris/compat/dh/DHCompatInternal.java':'package net.irisshaders.iris.compat.dh; public class DHCompatInternal {public boolean shouldOverride; public DHCompatInternal(boolean b){shouldOverride=b;}}',
'net/irisshaders/iris/compat/dh/DHCompat.java':'package net.irisshaders.iris.compat.dh; public class DHCompat {public Object value; public DHCompat(Object o){value=o;}public Object getInstance(){return value;}}',
'net/irisshaders/iris/pipeline/WorldRenderingPipeline.java':'package net.irisshaders.iris.pipeline; public interface WorldRenderingPipeline {net.irisshaders.iris.compat.dh.DHCompat getDHCompat();}',
'net/irisshaders/iris/pipeline/PipelineManager.java':'package net.irisshaders.iris.pipeline; public class PipelineManager {public WorldRenderingPipeline current;public java.util.Optional<WorldRenderingPipeline> getPipeline(){return java.util.Optional.ofNullable(current);}}',
'net/irisshaders/iris/Iris.java':'package net.irisshaders.iris; public class Iris {public static boolean pack;public static final net.irisshaders.iris.pipeline.PipelineManager MANAGER=new net.irisshaders.iris.pipeline.PipelineManager();public static boolean isPackInUseQuick(){return pack;}public static net.irisshaders.iris.pipeline.PipelineManager getPipelineManager(){return MANAGER;}}',
'B10Harness.java':'''import net.irisshaders.iris.Iris;import net.irisshaders.iris.compat.dh.*;import com.seibel.distanthorizons.core.render.DhApiRenderProxy;import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
public class B10Harness {
 static boolean green;static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void sync(boolean deferred)throws Exception {if(green){var c=Class.forName("org.motiquies.shadercompat.mixin.DhPassSelectionMixin");var m=c.getDeclaredMethod("motiquies$beforePassSelection",boolean.class,org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);m.setAccessible(true);m.invoke(c.getConstructor().newInstance(),deferred,new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("renderLodLayer",false));}}
 static EDhApiRenderPass select(boolean deferred)throws Exception {sync(deferred);return NativePass.choose(null,deferred);}
 static void pipeline(boolean override){Iris.MANAGER.current=()->new DHCompat(new DHCompatInternal(override));}
 public static void main(String[] a)throws Exception {green=a[0].equals("green");Iris.pack=true;pipeline(true);DhApiRenderProxy.INSTANCE.defer=false;
 check(select(false)==EDhApiRenderPass.OPAQUE,"shader enable must select OPAQUE on first normal call; stale combined pass is B10");
 check(select(true)==EDhApiRenderPass.TRANSPARENT,"deferred call must select TRANSPARENT");
 pipeline(false);check(select(false)==EDhApiRenderPass.OPAQUE_AND_TRANSPARENT,"current incompatible pipeline must reset stale defer");
 pipeline(true);check(select(false)==EDhApiRenderPass.OPAQUE,"new current pipeline must override without cached instance");
 Iris.pack=false;check(select(false)==EDhApiRenderPass.OPAQUE_AND_TRANSPARENT,"shader disabled must reset");
 Iris.pack=true;Iris.MANAGER.current=null;check(select(false)==EDhApiRenderPass.OPAQUE_AND_TRANSPARENT,"absent pipeline must reset");
 Iris.MANAGER.current=()->null;check(select(false)==EDhApiRenderPass.OPAQUE_AND_TRANSPARENT,"null DH compat must reset");
 pipeline(true);DhApiRenderProxy.INSTANCE.defer=false;check(select(true)==EDhApiRenderPass.OPAQUE_AND_TRANSPARENT,"deferred call must not change the normal call's lifecycle state");
 System.out.println("PASS original DH pass-selection bytecode + compiled candidate: first-enable, deferred, pipeline switch, incompatible, disabled, absent/null, normal-only synchronization");}
}'''}
sources=[]
for n,s in stubs.items():
 f=B/'harness-src'/n;f.parent.mkdir(exist_ok=True,parents=True);f.write_text(s,encoding='utf-8');sources.append(f)
compile(sources,H,[H,mixin])
mode=arguments.mode
if mode=='green':compile(list((P/'src/org/motiquies/shadercompat/mixin').glob('*.java')),H,[H,mixin])
if arguments.production_classes:
 for f in arguments.production_classes.rglob('*.class'):
  dest=H/f.relative_to(arguments.production_classes);dest.parent.mkdir(exist_ok=True,parents=True);shutil.copyfile(f,dest)
r=run([context.java,'-Xverify:all','-cp',os.pathsep.join(map(str,[H,mixin])),'B10Harness',mode]);(B/f'b10-{mode}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr)
if mode=='red':assert r.returncode!=0 and 'stale combined pass is B10' in r.stderr
else:assert r.returncode==0

context.check_inputs()
