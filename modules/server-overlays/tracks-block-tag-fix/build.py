"""Offline scoped producer; no download, game launch or deployment."""
from pathlib import Path
import argparse,copy,hashlib,json,os,shutil,subprocess,tempfile,zipfile
ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/'publication-manifest.json').exists() or (p/'CANDIDATE-ROOT').exists()),ROOT)
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def tree_sha(root):
    entries=[f.relative_to(root).as_posix()+'\0'+sha(f)+'\n' for f in sorted(root.rglob('*'),key=lambda f:f.relative_to(root).as_posix()) if f.is_file()]
    return hashlib.sha256(''.join(entries).encode()).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--input-jar',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--java-home',type=Path);p.add_argument('--dependencies','--sdk',dest='deps',type=Path)
    a=p.parse_args();source=a.input_jar.resolve();out=a.output.resolve();cfg=json.loads((ROOT/'producer.json').read_text(encoding='utf8'));lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf8'))
    if out==SOURCE_ROOT or SOURCE_ROOT in out.parents:raise SystemExit('Output must be outside source repository')
    if a.deps and (out==a.deps.resolve() or a.deps.resolve() in out.parents):raise SystemExit('Output must be outside dependencies')
    if a.java_home and (out==a.java_home.resolve() or a.java_home.resolve() in out.parents):raise SystemExit('Output must be outside JDK')
    if out.exists():raise SystemExit('Output already exists')
    if sha(source)!=cfg['inputSha256']:raise SystemExit('Input SHA256 mismatch')
    deps=[]
    for item in lock['classpath']:
        matches=sorted(f for f in a.deps.rglob(item['fileName']) if f.is_file() and f.name==item['fileName']) if a.deps else []
        valid=[f for f in matches if sha(f)==item['sha256']]
        if not valid:raise SystemExit('Dependency SHA256 mismatch: '+item['fileName'])
        deps.append(valid[0])
    for name,digest in cfg['sourceSha256'].items():
        if sha(ROOT/name)!=digest:raise SystemExit('Source SHA256 mismatch')
    if cfg['kind']=='worldedit':
        if not a.java_home:raise SystemExit('--java-home required')
        exe='.exe' if os.name=='nt' else '';javac=a.java_home/'bin'/('javac'+exe);jar=a.java_home/'bin'/('jar'+exe)
        v=subprocess.run([str(javac),'-version'],capture_output=True,text=True,check=True)
        if (v.stdout+v.stderr).strip()!='javac 21.0.10':raise SystemExit('Expected javac21.0.10')
        if tree_sha(a.java_home.resolve())!=lock['jdkTreeSha256']:raise SystemExit('JDK tree SHA256 mismatch')
    out.mkdir(parents=True);artifact=out/cfg['artifact'];stages=[]
    if cfg['kind']=='worldedit':
        with tempfile.TemporaryDirectory(dir=out) as temp:
            classes=Path(temp)/'classes';classes.mkdir();options=['--release','21','-g','-encoding','UTF-8','-proc:none','-cp',os.pathsep.join(map(str,[source,*deps])),'-d',str(classes),str(ROOT/'source/NeoForgeTransmogrifier.java')]
            args=Path(temp)/'compile.args';args.write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in options),encoding='utf8')
            r=subprocess.run([str(javac),'@'+str(args)],capture_output=True,text=True,encoding='utf8',errors='replace');(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf8')
            if r.returncode:raise SystemExit('Compilation failed')
            actual=sorted(f.relative_to(classes).as_posix() for f in classes.rglob('*.class'))
            if actual!=sorted(cfg['expectedChangedEntries']):raise SystemExit('Unexpected compiled classes')
            shutil.copyfile(source,artifact)
            cmd=[str(jar),'--update','--file',str(artifact),'--date=2024-10-16T16:53:06Z']
            for entry in cfg['compiledEntryOrder']:cmd+=['-C',str(classes),entry]
            subprocess.run(cmd,check=True);subprocess.run([str(jar),'--validate','--file',str(artifact)],check=True)
    else:
        intermediate=out/'tracks-namespace-stage.jar'
        for index,(before,after) in enumerate([(source,intermediate),(intermediate,artifact)]):
            changed=[]
            with zipfile.ZipFile(before) as old,zipfile.ZipFile(after,'w') as new:
                for info in old.infolist():
                    data=old.read(info.filename);updated=data
                    if index==0 and info.filename.endswith('.json'):updated=data.replace(b'Tracks:',b'tracks:')
                    if index==1 and info.filename in cfg['tagEntries']:
                        if json.loads(data)!=cfg['originalTag']:raise SystemExit('Unexpected tag input')
                        updated=(json.dumps({'values':['tracks:track_mount']},indent=2)+'\n').encode()
                    if updated!=data:changed.append(info.filename)
                    new.writestr(copy.copy(info),updated)
            if sorted(changed)!=sorted(cfg['stageChangedEntries'][index]):raise SystemExit('Unexpected stage delta')
            stages.append({'sha256':sha(after),'changedEntries':changed})
            if sha(after)!=cfg['stageSha256'][index]:raise SystemExit('Historical stage SHA mismatch')
    changes=[]
    with zipfile.ZipFile(source) as old,zipfile.ZipFile(artifact) as new:
        if old.namelist()!=new.namelist() and set(old.namelist())!=set(new.namelist()):raise SystemExit('Entry set changed')
        if new.testzip() or len(set(new.namelist()))!=len(new.namelist()):raise SystemExit('CRC/duplicate error')
        for entry in old.namelist():
            if old.read(entry)!=new.read(entry):changes.append(entry)
        if sorted(changes)!=sorted(cfg['expectedChangedEntries']):raise SystemExit('Unexpected entry changes')
        for entry,digest in cfg['expectedChangedEntries'].items():
            if hashlib.sha256(new.read(entry)).hexdigest()!=digest:raise SystemExit('Patched entry mismatch')
    if sha(source)!=cfg['inputSha256']:raise SystemExit('Input changed')
    for f,item in zip(deps,lock['classpath']):
        if sha(f)!=item['sha256']:raise SystemExit('Dependency changed')
    for name in ['LICENSE.txt','NOTICE.md','UPSTREAM.md']:shutil.copyfile(ROOT/name,out/name)
    receipt={'artifact':artifact.name,'sha256':sha(artifact),'productionSha256':cfg['productionSha256'],'wholeJarMatchesProduction':sha(artifact)==cfg['productionSha256'],'changedEntries':changes,'allOtherEntryContentsUnchanged':True,'stages':stages,'inputs':cfg['inputSha256'],'dependencies':lock['classpath'],'gameStarted':False,'binaryPublicationCleared':False,'runtimeAcceptance':'NOT RUN'}
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8');print(json.dumps(receipt))
if __name__=='__main__':main()
