"""Compile only pinned own runtime sources; no download, game launch or QA actors."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,tempfile,zipfile

ROOT=Path(__file__).resolve().parent
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk',type=Path,required=True,help='External legally obtained dependency JAR directory; searched recursively by locked filename and SHA256')
    p.add_argument('--jdk',type=Path,required=True,help='JDK21 installation directory')
    p.add_argument('--output',type=Path,required=True,help='New output directory outside this source module')
    a=p.parse_args();sdk=a.sdk.resolve();output=a.output.resolve()
    config=json.loads((ROOT/'module.json').read_text(encoding='utf8'))
    lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf8'))
    if output==ROOT or ROOT in output.parents:
        raise SystemExit('Output must be outside the source module')
    if output==sdk or sdk in output.parents:
        raise SystemExit('Output must be outside the external SDK')
    if output.exists():raise SystemExit('Refusing to overwrite existing output directory')
    cp=[];verified=[]
    for entry in lock['classpath']:
        matches=sorted(sdk.rglob(entry['fileName']))
        matches=[f for f in matches if f.is_file() and f.name==entry['fileName']]
        if not matches:raise SystemExit('Missing locked dependency: '+entry['fileName'])
        valid=[f for f in matches if digest(f)==entry['sha256']]
        if not valid:raise SystemExit('SHA256 mismatch: '+entry['fileName'])
        cp.append(valid[0]);verified.append({'fileName':entry['fileName'],'sha256':entry['sha256']})
    javac=a.jdk.resolve()/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    version=subprocess.run([str(javac),'-version'],capture_output=True,text=True,check=True)
    actual=(version.stdout+version.stderr).strip()
    if actual!=lock['javacVersion']:raise SystemExit('Unsupported compiler: '+actual+'; expected '+lock['javacVersion'])
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='compile-',dir=output) as temporary:
        temp=Path(temporary);classes=temp/'classes';classes.mkdir()
        args=['-encoding','UTF-8','--release','21','-proc:none','-classpath',os.pathsep.join(map(str,cp)),'-d',str(classes)]
        args.extend(str(ROOT/path) for path in config['sources'])
        argfile=temp/'javac.args'
        argfile.write_text('\n'.join('"'+arg.replace('\\','/')+'"' for arg in args),encoding='utf8')
        result=subprocess.run([str(javac),'@'+str(argfile)],capture_output=True,text=True,encoding='utf8',errors='replace')
        (output/'compile.log').write_text(result.stdout+result.stderr,encoding='utf8')
        if result.returncode:raise SystemExit('Compilation failed; see compile.log')
        actualClasses=sorted(f.relative_to(classes).as_posix() for f in classes.rglob('*.class'))
        if actualClasses!=sorted(config['classes']):raise SystemExit('Unexpected compiled class set: '+repr(actualClasses))
        entries={name:(classes/name).read_bytes() for name in actualClasses}
        entries.update({name:(ROOT/'src/main/resources'/name).read_bytes() for name in config['resources']})
        entries.update({name:(ROOT/name).read_bytes() for name in config['packageNotices']})
        entries['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\r\n\r\n'
        artifact=output/config['artifact']
        with zipfile.ZipFile(artifact,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            for name,content in sorted(entries.items()):
                info=zipfile.ZipInfo(name,(2026,11,16,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                info.create_system=0;info.external_attr=0x20
                z.writestr(info,content,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
        with zipfile.ZipFile(artifact) as z:
            if z.testzip() is not None or sorted(z.namelist())!=sorted(entries):raise SystemExit('Archive integrity/entry audit failed')
        for path,entry in zip(cp,lock['classpath']):
            if digest(path)!=entry['sha256']:raise SystemExit('Dependency changed during compilation: '+entry['fileName'])
        receipt={'module':config['id'],'artifact':artifact.name,'sha256':digest(artifact),'compiler':actual,
                 'dependencies':verified,'entries':{n:hashlib.sha256(b).hexdigest() for n,b in sorted(entries.items())},
                 'sources':{n:digest(ROOT/n) for n in config['sources']},'compileOnly':True,
                 'gameStarted':False,'actualGameAcceptance':'PENDING','behaviorTestsRun':False,'releaseEligible':False}
        (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8')
        print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
