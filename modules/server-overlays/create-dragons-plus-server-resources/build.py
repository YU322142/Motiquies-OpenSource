"""Offline historical resource transformation; original inputs remain external."""
from pathlib import Path
import argparse,collections,datetime,hashlib,json,os,re,shutil,subprocess,sys,tempfile,zipfile,zlib
ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/'publication-manifest.json').is_file() or (p/'CANDIDATE-ROOT').is_file()),ROOT)
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def tree_sha(root):
 entries=[p.relative_to(root).as_posix()+'\0'+sha(p)+'\n' for p in sorted(root.rglob('*'),key=lambda p:p.relative_to(root).as_posix()) if p.is_file()]
 return hashlib.sha256(''.join(entries).encode()).hexdigest()
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--input-jar',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
 parser.add_argument('--java-home',type=Path);parser.add_argument('--sdk','--dependencies',dest='sdk',type=Path)
 a=parser.parse_args();source=a.input_jar.resolve();output=a.output.resolve()
 cfg=json.loads((ROOT/'producer.json').read_text(encoding='utf8'));tools=json.loads((ROOT/'toolchain.lock.json').read_text(encoding='utf8'))
 for protected in [SOURCE_ROOT,*([a.sdk.resolve()] if a.sdk else []),*([a.java_home.resolve()] if a.java_home else [])]:
  if output==protected or protected in output.parents:raise SystemExit('Output must be outside source repository, SDK and JDK')
 if output.exists():raise SystemExit('Output directory already exists')
 if sha(source)!=cfg['inputSha256']:raise SystemExit('Input SHA256 mismatch')
 for name,digest in cfg['sourceSha256'].items():
  if sha(ROOT/name)!=digest:raise SystemExit('Source SHA256 mismatch: '+name)
 if list(sys.version_info[:3])!=tools['pythonVersion'] or zlib.ZLIB_RUNTIME_VERSION!=tools['zlibRuntime']:raise SystemExit('Python/zlib version mismatch')
 if sha(Path(sys.executable))!=tools['pythonExecutableSha256'] or sha(Path(zipfile.__file__))!=tools['zipfileModuleSha256']:raise SystemExit('Python tool SHA256 mismatch')
 dll=Path(sys.executable).parent/tools['pythonRuntimeDll']
 if sha(dll)!=tools['pythonRuntimeDllSha256']:raise SystemExit('Python runtime SHA256 mismatch')
 if cfg['kind']=='yuushya':
  if not a.java_home:raise SystemExit('--java-home required for the JDK jar archive tool')
  if tree_sha(a.java_home.resolve())!=tools['jdkTreeSha256']:raise SystemExit('JDK tree SHA256 mismatch')
  jar=a.java_home/'bin'/('jar.exe' if os.name=='nt' else 'jar')
 # Validate the complete archive before creating output or invoking the JDK tool.
 with zipfile.ZipFile(source) as original:
  if original.testzip() or len(original.namelist())!=len(set(original.namelist())):raise SystemExit('Input archive CRC/duplicate error')
 output.mkdir(parents=True);artifact=output/cfg['artifact'];counts=collections.Counter()
 if cfg['kind']=='yuushya':
  with tempfile.TemporaryDirectory(dir=output) as work:
   patch=Path(work)/'patch';patch.mkdir()
   with zipfile.ZipFile(source) as original:
    for entry,formatting in cfg['formatting'].items():
     data=original.read(entry).decode('utf8')
     def replace(match):
      key=match.group(2)
      if key not in cfg['mapping']:return match.group(0)
      counts[key]+=1
      return match.group(1)+cfg['mapping'][key]+match.group(3)
     data=re.sub(r'(?m)^([ \t]*"(?:icon|item)"[ \t]*:[ \t]*")([^"\r\n]+)("[^\r\n]*)',replace,data)
     # Numeric line-ending/ZIP timestamp metadata reproduces the retained historical patch.
     # All prose and JSON content come from the verified external input, never this repository.
     lf=set(formatting['lfLines']);lines=data.splitlines()
     if len(lines)!=formatting['lineCount']:raise SystemExit('Unexpected guidebook line count')
     payload=''.join(line+('\n' if i in lf else '\r\n') for i,line in enumerate(lines,1)).encode('utf8')
     if hashlib.sha256(payload).hexdigest()!=cfg['changedEntries'][entry]:raise SystemExit('Generated guidebook entry SHA256 mismatch')
     dest=patch/entry;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(payload)
     timestamp=datetime.datetime(*formatting['zipTimestamp'],tzinfo=datetime.timezone.utc).timestamp();os.utime(dest,(timestamp,timestamp))
   if dict(counts)!=cfg['expectedFieldCounts']:raise SystemExit('Guidebook field-count mismatch')
   shutil.copyfile(source,artifact)
   command=[str(jar),'-J-Duser.timezone=UTC','--update','--file',str(artifact)]
   for entry in cfg['patchOrder']:command+=['-C',str(patch),entry]
   subprocess.run(command,check=True);subprocess.run([str(jar),'--validate','--file',str(artifact)],check=True)
 else:
  removed=[]
  with zipfile.ZipFile(source) as original,zipfile.ZipFile(artifact,'w') as target:
   for info in original.infolist():
    if info.filename in cfg['removedEntries']:removed.append(info.filename);continue
    payload=original.read(info.filename)
    if cfg['kind']=='kaleidoscope-nether' and info.filename==cfg['globalList']:
     value=json.loads(payload)
     if not isinstance(value,dict) or not isinstance(value.get('entries'),list):raise SystemExit('Unexpected global loot modifier shape')
     value['entries']=[entry for entry in value['entries'] if not (isinstance(entry,str) and entry.startswith(cfg['excludedPrefix']))]
     payload=(json.dumps(value,ensure_ascii=False,indent=2,sort_keys=True)+'\n').encode('utf8')
    target.writestr(info,payload)
  if sorted(removed)!=sorted(cfg['removedEntries']):raise SystemExit('Removed entry set mismatch')
 with zipfile.ZipFile(source) as original,zipfile.ZipFile(artifact) as patched:
  if patched.testzip() or len(patched.namelist())!=len(set(patched.namelist())):raise SystemExit('Output CRC/duplicate error')
  if set(patched.namelist())!=set(original.namelist())-set(cfg['removedEntries']):raise SystemExit('Output entry set mismatch')
  changed=[];classes=0
  for entry in patched.namelist():
   before=original.read(entry);after=patched.read(entry)
   if entry.endswith('.class'):
    classes+=1
    if before!=after:raise SystemExit('Class bytes changed')
   if before!=after:changed.append(entry)
  if set(changed)!=set(cfg['changedEntries']):raise SystemExit('Changed entry set mismatch')
  for entry,digest in cfg['changedEntries'].items():
   if hashlib.sha256(patched.read(entry)).hexdigest()!=digest:raise SystemExit('Changed entry SHA256 mismatch')
 if sha(artifact)!=cfg['outputSha256']:raise SystemExit('Whole output SHA256 mismatch')
 if sha(source)!=cfg['inputSha256']:raise SystemExit('Input changed during build')
 for name,digest in cfg['sourceSha256'].items():
  if sha(ROOT/name)!=digest:raise SystemExit('Source changed during build')
 for name in ['LICENSE.txt','NOTICE.md','UPSTREAM.md']:shutil.copyfile(ROOT/name,output/name)
 shutil.copytree(ROOT/'licenses',output/'licenses')
 receipt={'artifact':artifact.name,'inputSha256':cfg['inputSha256'],'outputSha256':sha(artifact),'wholeOutputMatchesProductionReference':True,'removedEntries':cfg['removedEntries'],'changedEntryContents':changed,'classCount':classes,'allClassesUnchanged':True,'allOtherEntryContentsUnchanged':True,'fieldCounts':dict(counts),'sourceSha256':cfg['sourceSha256'],'toolchain':tools,'runtimeQa':'NOT RUN','gameStarted':False,'networkDuringBuild':False,'binaryPublicationCleared':False}
 (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8');print(json.dumps(receipt))
if __name__=='__main__':main()
