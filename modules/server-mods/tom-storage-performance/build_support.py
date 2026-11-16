"""MIT source-only offline build support, copyright2026 Motiquies maintainers."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def digest(data):return hashlib.sha256(data).hexdigest()
def read_json(path):return json.loads(path.read_text(encoding='utf8'))
def source_root():
 for p in (ROOT,*ROOT.parents):
  if (p/'publication-manifest.json').is_file() or (p/'SOURCE_ROOT').is_file():return p.resolve()
 return ROOT.resolve()
def within(path,root):return path==root or root in path.parents
def parse_cli(description):
 p=argparse.ArgumentParser(description=description,allow_abbrev=False)
 p.add_argument('--output',type=Path);p.add_argument('--sdk-root','--sdk',dest='sdk',type=Path)
 p.add_argument('--api-input-root','--api-root','--api',dest='api',type=Path)
 p.add_argument('--compiler-input-root','--compiler-root',dest='compiler',type=Path)
 p.add_argument('--java-home','--jdk',dest='jdk',type=Path)
 p.add_argument('--class-build',type=Path,help='Existing class-only build used only by the optional pure regression runner')
 p.add_argument('--data-root',action='append',type=Path,default=[]);p.add_argument('--protected-root',action='append',type=Path,default=[])
 p.add_argument('--check-source',action='store_true')
 a=p.parse_args()
 # This guard intentionally precedes every lock/source/SDK/JDK content read.
 if a.output is not None:
  out=a.output.resolve()
  roots=[source_root(),ROOT.resolve(),*[v.resolve() for v in [a.sdk,a.api,a.compiler,a.jdk,a.class_build,*a.data_root,*a.protected_root] if v is not None]]
  if any(within(out,r) or within(r,out) for r in roots):raise ValueError('Output overlaps source repository or protected input root')
  if out.exists():raise ValueError('Output must be a new directory; existing evidence is never overwritten')
  a.output=out
 elif not a.check_source:p.error('--output is required')
 return a
def verify_source():
 tools=read_json(ROOT/'TOOL-INPUTS.lock.json')
 for e in tools['files']:
  if sha(ROOT/e['path'])!=e['sha256']:raise ValueError('Tool/metadata SHA256 mismatch: '+e['path'])
 origin=read_json(ROOT/'SOURCE-ORIGINS.json')
 expected={e['path'] for e in origin['files']};actual={p.relative_to(ROOT).as_posix() for p in (ROOT/'src/main/java').rglob('*.java')}
 if len(expected)!=182 or actual!=expected:raise ValueError('Complete182 runtime source set required')
 for e in origin['files']:
  if sha(ROOT/e['path'])!=e['sha256']:raise ValueError('Runtime source SHA256 mismatch: '+e['path'])
 for e in read_json(ROOT/'TEST-ORIGINS.json')['files']:
  if sha(ROOT/e['path'])!=e['sha256']:raise ValueError('Original regression source SHA256 mismatch')
 if sha(ROOT/'HISTORICAL-515-ENTRY-HASHES.json')!='4dab36afb92edf6d92e7731179f5566963d8212a1fd2ee9038ae926785e87400':raise ValueError('Historical strict515 table changed')
 return origin
def sdk_file(root,path,layout='library'):
 if Path(path).is_absolute() or '..' in Path(path).parts:raise ValueError('Invalid relative SDK path')
 root=root.resolve();candidates=[root/path]
 if layout=='library':candidates += [root/'libraries'/path,root/'.minecraft/libraries'/path,root/'original/.minecraft/libraries'/path]
 else:candidates += [root/'mods'/path,root/'game/mods'/path]
 for p in candidates:
  p=p.resolve()
  if within(p,root) and p.is_file():return p
 raise ValueError('Missing external SDK input: '+path)
def classpath(a):
 for name in ['sdk','api','compiler']:
  if getattr(a,name) is None:raise ValueError('Missing external input root: '+name)
 lock=read_json(ROOT/'dependencies.lock.json');paths=[]
 # Resolve the full set before hashing/compiling so missing real APIs fail before output creation.
 for e in lock['classpath']:
  if e['root']=='sdk':path=sdk_file(a.sdk,e['path'],e['sdkLayout'])
  else:
   path=(getattr(a,e['root']).resolve()/e['path']).resolve()
   if not within(path,getattr(a,e['root']).resolve()) or not path.is_file():raise ValueError('Missing external '+e['root']+' input: '+e['path'])
  paths.append(path)
 if len(paths)!=143 or len(set(paths))!=143:raise ValueError('Complete143 distinct compiler inputs required')
 for e,path in zip(lock['classpath'],paths):
  if any(t in path.name.lower() for t in ['toms_storage','toms-storage']) or path.name=='guava-20.0.jar':raise ValueError('Own old Tom API or Guava20 shadowing forbidden')
  if sha(path)!=e['sha256']:raise ValueError('External input SHA256 mismatch: '+e['path'])
 return lock,paths
def jdk_tools(a):
 if a.jdk is None:raise ValueError('--java-home/--jdk is required')
 profile=read_json(ROOT/'toolchain.lock.json');root=a.jdk.resolve()
 entries=[]
 for p in sorted(root.rglob('*'),key=lambda p:p.relative_to(root).as_posix()):
  if p.is_file():entries.append(p.relative_to(root).as_posix()+'\0'+sha(p)+'\n')
 if digest(''.join(entries).encode())!=profile['jdkTreeSha256']:raise ValueError('JDK tree SHA256 mismatch')
 suffix='.exe' if os.name=='nt' else '';javac=root/'bin'/('javac'+suffix);java=root/'bin'/('java'+suffix)
 if sha(javac)!=profile['javacSha256']:raise ValueError('javac SHA256 mismatch')
 version=subprocess.run([str(javac),'-version'],capture_output=True,check=True)
 if (version.stdout+version.stderr).decode('utf8').strip()!=profile['javacVersion']:raise ValueError('javac version mismatch')
 return javac,java,profile
def assert_unchanged(lock,paths):
 for e,p in zip(lock['classpath'],paths):
  if sha(p)!=e['sha256']:raise ValueError('External input changed during build')
 verify_source()
def write_json(path,data):path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf8')
def compile_sources(javac,sources,cp,out,classes):
 options=['--release','21','-encoding','UTF-8','-proc:none','-g','-implicit:none','-sourcepath','','-Xmaxerrs','1000','-cp',os.pathsep.join(map(str,cp)),'-d',str(classes),*map(str,sources)]
 arg=out/'javac.args';arg.write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in options),encoding='utf8')
 env=os.environ.copy()
 for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
 run=subprocess.run([str(javac),'-J-Duser.language=en','-J-Duser.country=US','@'+str(arg)],cwd=out,env=env,capture_output=True)
 (out/'javac.log').write_bytes(run.stdout+run.stderr)
 if run.returncode:raise ValueError('Complete source compilation failed; no mod archive produced')
 return options
