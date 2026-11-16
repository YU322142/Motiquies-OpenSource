"""Offline own delta producer. Outputs and dependencies must stay outside this repository."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tempfile,zipfile,copy

ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/'publication-manifest.json').is_file()),ROOT)
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def invoke(command,log):
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf8',errors='replace')
    log.write_text(result.stdout+result.stderr,encoding='utf8')
    if result.returncode:raise SystemExit('Producer command failed; see '+log.name)
    return result.stdout+result.stderr
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input-jar',type=Path,required=True)
    p.add_argument('--java-home',type=Path,required=True)
    p.add_argument('--dependencies','--sdk',dest='dependencies',type=Path,required=True)
    p.add_argument('--upstream-source',type=Path,help='HotBath only: exact original ExtraEventsRegister.java from the pinned public source commit')
    p.add_argument('--output',type=Path,required=True,help='New directory outside the entire source repository and external SDK')
    a=p.parse_args();source=a.input_jar.resolve();deps=a.dependencies.resolve();output=a.output.resolve();jdk=a.java_home.resolve()
    config=json.loads((ROOT/'producer.json').read_text(encoding='utf8'))
    lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf8'))
    if any(output==root or root in output.parents for root in [SOURCE_ROOT,deps]):raise SystemExit('Output must be outside source repository and dependency directories')
    if output.exists():raise SystemExit('Refusing to overwrite existing output')
    if sha(source)!=config['input']['sha256']:raise SystemExit('Input SHA256 mismatch; only the reviewed original input is accepted')
    cp=[]
    for entry in lock['classpath']:
        candidates=sorted(f for f in deps.rglob(entry['fileName']) if f.is_file() and f.name==entry['fileName'])
        if not candidates:raise SystemExit('Missing dependency: '+entry['fileName'])
        valid=[f for f in candidates if sha(f)==entry['sha256']]
        if not valid:raise SystemExit('Dependency SHA256 mismatch: '+entry['fileName'])
        cp.append(valid[0])
    for file,digest in config['ownSourceSha256'].items():
        if sha(ROOT/file)!=digest:raise SystemExit('Own producer source SHA256 mismatch: '+file)
    if config['mode']=='source-delta':
        if a.upstream_source is None:raise SystemExit('--upstream-source is required for the exact original GPL HotBath Java source')
        upstream=a.upstream_source.resolve()
        if sha(upstream)!=config['upstreamSource']['sha256']:raise SystemExit('Upstream source SHA256 mismatch')
    suffix='.exe' if os.name=='nt' else ''
    javac=jdk/'bin'/('javac'+suffix);java=jdk/'bin'/('java'+suffix)
    version=subprocess.run([str(javac),'-version'],capture_output=True,text=True,check=True)
    compiler=(version.stdout+version.stderr).strip()
    if compiler!=lock['javacVersion']:raise SystemExit('Unsupported javac: '+compiler)
    runtime=subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True)
    if '"21.0.10"' not in runtime.stdout+runtime.stderr:raise SystemExit('Unsupported Java runtime; JDK21.0.10 required')
    output.mkdir(parents=True);artifact=output/config['artifact']
    with tempfile.TemporaryDirectory(prefix='producer-',dir=output) as temporary:
        temp=Path(temporary);classes=temp/'classes';classes.mkdir()
        if config['mode']=='asm':
            compileCp=cp;sources=[ROOT/f for f in config['ownSourceSha256'] if f.endswith('.java')]
        else:
            text=upstream.read_bytes().decode('utf8')
            for edit in config['sourceEdits']:
                if text.count(edit['before'])!=1:raise SystemExit('Upstream source edit anchor changed')
                text=text.replace(edit['before'],edit['after'],1)
            generated=temp/'ExtraEventsRegister.java';generated.write_bytes(text.encode('utf8'))
            if sha(generated)!=config['patchedSourceSha256']:raise SystemExit('Patched source SHA256 mismatch')
            compileCp=apply_compile_access(config,[*cp,source],temp);sources=[generated]
        options=['-g','--release','21','-encoding','UTF-8','-proc:none','-classpath',os.pathsep.join(map(str,compileCp)),'-d',str(classes),*map(str,sources)]
        argfile=temp/'javac.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in options),encoding='utf8')
        invoke([str(javac),'-J-Duser.language=en','-J-Dfile.encoding=UTF-8','@'+str(argfile)],output/'compile.log')
        if config['mode']=='asm':
            runtimeCp=os.pathsep.join(map(str,[classes,*cp]))
            invoke([str(java),'-Duser.timezone=Asia/Shanghai','-cp',runtimeCp,config['mainClass'],str(source),str(artifact)],output/'producer.log')
            invoke([str(java),'-cp',runtimeCp,config['verifierClass'],str(source),str(artifact)],output/'verifier.log')
        else:
            entry=config['compiledEntry'];compiled=classes/entry
            if sha(compiled)!=config['expectedChangedEntries'][entry]:raise SystemExit('Compiled HotBath class differs from reviewed production target')
            replacements={entry:compiled.read_bytes()}
            with zipfile.ZipFile(source) as old:
                for metadata,edit in config['metadataEdits'].items():
                    content=old.read(metadata)
                    if content.count(edit['before'].encode())!=1:raise SystemExit('Original metadata version contract changed: '+metadata)
                    replacements[metadata]=content.replace(edit['before'].encode(),edit['after'].encode(),1)
                with zipfile.ZipFile(artifact,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as new:
                    for entryInfo in old.infolist():
                        info=copy.copy(entryInfo);info.date_time=(1980,1,1,0,0,0)
                        new.writestr(info,replacements.get(info.filename,old.read(info.filename)),compress_type=info.compress_type,compresslevel=9)
    differences=[];entryHashes={}
    with zipfile.ZipFile(source) as before,zipfile.ZipFile(artifact) as after:
        if before.testzip() is not None or after.testzip() is not None:raise SystemExit('Archive CRC failure')
        if len(set(after.namelist()))!=len(after.namelist()) or set(before.namelist())!=set(after.namelist()):raise SystemExit('Unexpected/duplicate archive entries')
        for entry in before.namelist():
            if before.read(entry)!=after.read(entry):differences.append(entry)
        if sorted(differences)!=sorted(config['expectedChangedEntries']):raise SystemExit('Unexpected modified entry set')
        for entry,expected in config['expectedChangedEntries'].items():
            actual=hashlib.sha256(after.read(entry)).hexdigest()
            if actual!=expected:raise SystemExit('Modified entry SHA256 mismatch: '+entry)
            entryHashes[entry]=actual
    if sha(source)!=config['input']['sha256']:raise SystemExit('Original input changed during production')
    for path,entry in zip(cp,lock['classpath']):
        if sha(path)!=entry['sha256']:raise SystemExit('Dependency changed during production')
    for file in ['LICENSE.txt','NOTICE.txt','UPSTREAM.md']:
        shutil.copyfile(ROOT/file,output/file)
    receipt={'schema':1,'artifact':artifact.name,'sha256':sha(artifact),'inputSha256':config['input']['sha256'],
             'ownProducerSources':config['ownSourceSha256'],'toolDependencies':lock['classpath'],
             'compiler':compiler,'changedEntries':differences,'changedEntrySha256':entryHashes,
             'allOtherEntryContentsUnchanged':True,'matchesRecordedProductionJarSha256':sha(artifact)==config['productionSha256'],
             'recordedProductionJarSha256':config['productionSha256'],'upstreamLicenseStatus':config['upstreamLicenseStatus'],
             'ownSourceLicense':config['ownLicense'],'binaryRedistributionAuthorized':False,
             'gameStarted':False,'runtimeAcceptance':'NOT RUN','deployed':False,'sourceOnlyPublication':True}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
