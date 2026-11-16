# SPDX-License-Identifier: MIT
# Copyright2026 Motiquies maintainers (independent offline wrapper only).
"""Build the reviewed Zstd frame source candidate offline; never install or launch a mod."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/'publication-manifest.json').is_file()),ROOT)
def inside(path,root):return path==root or root in path.parents
def sha(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def tree_sha(root):
 entries=[p.relative_to(root).as_posix()+'\0'+sha(p)+'\n' for p in sorted(root.rglob('*'),key=lambda p:p.relative_to(root).as_posix()) if p.is_file()]
 return hashlib.sha256(''.join(entries).encode('utf-8')).hexdigest()
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--input-jar','--input',dest='input',type=Path,required=True)
 parser.add_argument('--output',type=Path,required=True)
 parser.add_argument('--dependencies','--sdk-root','--sdk',dest='dependencies',type=Path,action='append',default=[])
 parser.add_argument('--java-home','--jdk',dest='jdks',type=Path,action='append',default=[])
 parser.add_argument('--data-root','--data',dest='data',type=Path,action='append',default=[])
 parser.add_argument('--compiler-root','--compiler-input-root','--compiler',dest='compiler',type=Path,action='append',default=[])
 args=parser.parse_args();output=args.output.resolve();source=args.input.resolve()
 # This guard deliberately precedes input, lock, source or compiler content reads.
 protected=[(SOURCE_ROOT,'source repository'),(source.parent,'input data directory')]
 protected += [(p.resolve(),'selected SDK/dependencies') for p in args.dependencies]
 protected += [(p.resolve(),'selected JDK') for p in args.jdks]
 protected += [(p.resolve(),'selected data') for p in args.data]
 protected += [(p.resolve(),'selected compiler input') for p in args.compiler]
 for root,label in protected:
  if inside(output,root):raise SystemExit('Output must be outside '+label)
 if args.output.is_symlink() or output.exists():raise SystemExit('Output already exists or is a link')
 if not args.dependencies or not args.jdks:raise SystemExit('--dependencies/--sdk and --java-home/--jdk are required')
 if len({p.resolve() for p in args.dependencies})!=1 or len({p.resolve() for p in args.jdks})!=1:raise SystemExit('Conflicting input roots')
 dependencies=args.dependencies[-1].resolve();jdk=args.jdks[-1].resolve()
 lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf-8'))
 for name,digest in lock['sourceSha256'].items():
  if sha(ROOT/name)!=digest:raise SystemExit('Source SHA256 mismatch: '+name)
 if sha(source)!=lock['inputSha256']:raise SystemExit('Input SHA256 mismatch')
 cp=[]
 for item in lock['classpath']:
  matches=sorted(p for p in dependencies.rglob(item['fileName']) if p.is_file() and p.name==item['fileName'])
  if not matches or any(sha(p)!=item['sha256'] for p in matches):raise SystemExit('Dependency SHA256 mismatch: '+item['fileName'])
  selected=matches[0].resolve()
  if inside(output,selected.parent):raise SystemExit('Output must be outside resolved dependency storage')
  cp.append(selected)
 for name,digest in lock['jdkExecutableSha256'].items():
  if sha(jdk/'bin'/name)!=digest:raise SystemExit('JDK executable SHA256 mismatch')
 if tree_sha(jdk)!=lock['jdkTreeSha256']:raise SystemExit('JDK tree SHA256 mismatch')
 output.mkdir(parents=True);classes=output/'tool-classes';classes.mkdir()
 def run(command,label):
  result=subprocess.run(list(map(str,command)),cwd=output,capture_output=True,timeout=90)
  (output/(label+'.stdout')).write_bytes(result.stdout);(output/(label+'.stderr')).write_bytes(result.stderr)
  (output/(label+'.command.json')).write_text(json.dumps({'command':list(map(str,command)),'exitCode':result.returncode},indent=2)+'\n',encoding='utf-8')
  if result.returncode:raise SystemExit(label+' failed; inspect retained logs')
 sources=[ROOT/name for name in lock['javaSources']]
 run([jdk/'bin/javac.exe','--release','21','-encoding','UTF-8','-proc:none','-g','-implicit:none','-sourcepath','','-cp',os.pathsep.join(map(str,cp)),'-d',classes,*sources],'compile')
 actual={p.relative_to(classes).as_posix():sha(p) for p in classes.rglob('*.class')}
 if actual!=lock['expectedToolClassSha256']:raise SystemExit('Compiled tool class mismatch')
 (classes/'LICENSES').mkdir();shutil.copyfile(ROOT/'src/main/resources/LICENSES/ZstdNet-MIT.txt',classes/'LICENSES/ZstdNet-MIT.txt')
 raw=output/'source-tool-output.jar'
 # Only our reviewed tool/helper and four API JARs are on this classpath.
 run([jdk/'bin/java.exe','-Xverify:all','-Duser.language=en','-Duser.country=US','-cp',os.pathsep.join(map(str,[classes,*cp])),'io.github.framecompat.ZstdNetFramePatch',source,raw],'patch')
 artifact=output/'zstdnet-production-frame-fix-source-candidate.jar';payloads={}
 with zipfile.ZipFile(raw) as old,zipfile.ZipFile(artifact,'x',compression=zipfile.ZIP_STORED) as new:
  names=old.namelist()
  if len(names)!=len(set(names)) or old.testzip():raise SystemExit('Invalid source-tool archive')
  for name in sorted(names):
   if name.startswith('/') or '..' in name.split('/'):raise SystemExit('Unsafe archive path')
   data=old.read(name);info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.create_system=3;info.external_attr=(0o40755 if name.endswith('/') else 0o100644)<<16;new.writestr(info,data)
   if not name.endswith('/'):payloads[name]=hashlib.sha256(data).hexdigest()
 with zipfile.ZipFile(artifact) as archive:
  if archive.testzip():raise SystemExit('Output CRC failure')
 if sha(artifact)!=lock['expectedCandidateSha256']:raise SystemExit('Deterministic candidate SHA256 mismatch')
 if sha(source)!=lock['inputSha256']:raise SystemExit('Input changed during build')
 for item,path in zip(lock['classpath'],cp):
  if sha(path)!=item['sha256']:raise SystemExit('Dependency changed during build')
 for name,digest in lock['sourceSha256'].items():
  if sha(ROOT/name)!=digest:raise SystemExit('Source changed during build')
 if tree_sha(jdk)!=lock['jdkTreeSha256']:raise SystemExit('JDK changed during build')
 receipt={'module':'server-overlays/zstdnet-production-frame-fix','artifact':artifact.name,'sha256':sha(artifact),'inputSha256':sha(source),'builderSha256':sha(ROOT/'build.py'),'lockSha256':sha(ROOT/'dependencies.lock.json'),'sourceHashes':lock['sourceSha256'],'classpath':lock['classpath'],'jdkTreeSha256':lock['jdkTreeSha256'],'rawToolOutputSha256':sha(raw),'entryHashes':payloads,'toolClassHashes':actual,'container':'NEW sorted entries/1980/STORE; all payloads preserved','historicalProductionSha256':lock['historicalProductionSha256'],'wholeHistoricalJarMatches':False,'historicalAllRawPayloadsMatch':False,'runtimeClassEvidence':{'total':56,'rawExact':55,'nondebugCanonicalExact':56,'helperRawDebugMismatchPreserved':True},'oldLocalNoticeVsNewMitAndToolNoticeMismatchPreserved':True,'officialApiArtifactDownloadCrosscheck':'NOT EXECUTED; see source README','gameOrInputModOrNativeExecuted':False,'annotationProcessorsEnabled':False,'binaryPublicationApproved':False,'oldProducerRecovered':False}
 (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8');print(json.dumps({'artifact':artifact.name,'sha256':receipt['sha256'],'historicalExact':False,'gameStarted':False}))
if __name__=='__main__':main()
