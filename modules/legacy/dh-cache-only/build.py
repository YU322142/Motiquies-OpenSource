"""Reproduce a historical DH bytecode overlay offline; never launches or deploys DH."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tempfile,zipfile

ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/"publication-manifest.json").is_file()),ROOT)
def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input-jar',type=Path,required=True,help='Legally obtained original unmodified DH3.2.0-b1.21.1 JAR')
    p.add_argument('--java-home',type=Path,required=True,help='JDK21 installation; javac and java are required')
    p.add_argument('--dependencies','--sdk',dest='dependencies',type=Path,required=True,help='External ASM9.8 dependency directory, searched recursively')
    p.add_argument('--output',type=Path,required=True,help='New external output directory, never the original JAR')
    a=p.parse_args();source=a.input_jar.resolve();deps=a.dependencies.resolve();output=a.output.resolve();jdk=a.java_home.resolve()
    config=json.loads((ROOT/'producer.json').read_text(encoding='utf8'))
    lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf8'))
    if output.exists():raise SystemExit('Refusing to overwrite an existing output directory')
    if any(output==root or root in output.parents for root in [SOURCE_ROOT,deps]):raise SystemExit('Output must be outside source repository and dependency directories')
    if sha(source)!=config['input']['sha256']:raise SystemExit('Input SHA256 mismatch: only the exact original DH JAR is accepted')
    classpath=[]
    for entry in lock['classpath']:
        candidates=sorted(f for f in deps.rglob(entry['fileName']) if f.is_file() and f.name==entry['fileName'])
        if not candidates:raise SystemExit('Missing dependency: '+entry['fileName'])
        valid=[f for f in candidates if sha(f)==entry['sha256']]
        if not valid:raise SystemExit('Dependency SHA256 mismatch: '+entry['fileName'])
        classpath.append(valid[0])
    sourceFile=ROOT/'PatchCacheOnly.java'
    if sha(sourceFile)!=config['producerSourceSha256']:raise SystemExit('Producer source SHA256 mismatch; review and update producer.json explicitly')
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:raise SystemExit('Invalid input archive')
        for entry,digest in config['preservedUpstreamNotices'].items():
            if hashlib.sha256(archive.read(entry)).hexdigest()!=digest:raise SystemExit('Original upstream notice changed: '+entry)
    suffix='.exe' if os.name=='nt' else ''
    javac=jdk/'bin'/('javac'+suffix);java=jdk/'bin'/('java'+suffix)
    v=subprocess.run([str(javac),'-version'],capture_output=True,text=True,check=True)
    compiler=(v.stdout+v.stderr).strip()
    if compiler!=lock['javacVersion']:raise SystemExit('Unsupported compiler: '+compiler)
    runtime=subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True)
    runtimeVersion=(runtime.stdout+runtime.stderr).splitlines()[0]
    if '"21.0.10"' not in runtimeVersion:raise SystemExit('Unsupported Java runtime: '+runtimeVersion)
    output.mkdir(parents=True)
    artifact=output/config['artifact']
    with tempfile.TemporaryDirectory(prefix='producer-',dir=output) as temporary:
        classes=Path(temporary)/'classes';classes.mkdir()
        cp=os.pathsep.join(map(str,classpath))
        command=[str(javac),'--release','21','-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(classes),str(sourceFile)]
        compiled=subprocess.run(command,capture_output=True,text=True,encoding='utf8',errors='replace')
        (output/'compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf8')
        if compiled.returncode:raise SystemExit('Producer compilation failed; see compile.log')
        changed=subprocess.run([str(java),'-cp',os.pathsep.join([str(classes),cp]),'PatchCacheOnly',str(source),str(artifact)],capture_output=True,text=True,encoding='utf8',errors='replace')
        (output/'producer.log').write_text(changed.stdout+changed.stderr,encoding='utf8')
        if changed.returncode:raise SystemExit('Producer failed; see producer.log')
    differences=[];classHashes={}
    with zipfile.ZipFile(source) as before,zipfile.ZipFile(artifact) as after:
        if after.testzip() is not None or before.namelist()!=after.namelist():raise SystemExit('Archive entry set/order or CRC changed unexpectedly')
        for entry in before.namelist():
            aBytes=before.read(entry);bBytes=after.read(entry)
            if aBytes!=bBytes:differences.append(entry)
        if sorted(differences)!=sorted(config['expectedChangedClassSha256']):raise SystemExit('Unexpected modified archive entries: '+repr(differences))
        for entry,expected in config['expectedChangedClassSha256'].items():
            actual=hashlib.sha256(after.read(entry)).hexdigest()
            if actual!=expected:raise SystemExit('Historical patched class SHA256 mismatch: '+entry)
            classHashes[entry]=actual
        for entry,digest in config['preservedUpstreamNotices'].items():
            if hashlib.sha256(after.read(entry)).hexdigest()!=digest:raise SystemExit('Upstream notice not preserved: '+entry)
    if sha(source)!=config['input']['sha256']:raise SystemExit('Original DH input changed during build')
    for path,entry in zip(classpath,lock['classpath']):
        if sha(path)!=entry['sha256']:raise SystemExit('ASM dependency changed during build')
    # Output-side modification/source notices accompany the otherwise exact historical archive.
    for name in ['MODIFICATION-NOTICE.txt','CORRESPONDING-SOURCE.md','LICENSE.txt','LICENSE.LESSER.txt','LICENSE-LOGOS.txt']:
        shutil.copyfile(ROOT/name,output/name)
    receipt={'schema':1,'artifact':artifact.name,'artifactSha256':sha(artifact),'inputSha256':config['input']['sha256'],
             'producerSourceSha256':config['producerSourceSha256'],'compiler':compiler,'javaRuntime':runtimeVersion,
             'toolDependencies':lock['classpath'],'changedEntries':differences,'changedClassSha256':classHashes,
             'matchesObservedHistoricalArtifact':sha(artifact)==config['observedHistoricalSha256'],
             'observedHistoricalSha256':config['observedHistoricalSha256'],'allOtherEntryContentsUnchanged':True,
             'upstreamNoticesPreserved':True,'historicalOverlayOnly':True,'target330OfficialNativePlanChanged':False,
             'runtimeFlag':'motiquies.dh.cacheOnly','deployedFlagState':'UNKNOWN: not inspected',
             'gameOrDHStarted':False,'deployed':False,'distributionReviewComplete':False,
             'correspondingSourceStatus':'Original revision route recorded; full recursive upstream source rebuild/availability not independently verified'}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8')
    print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
