"""Compile only pinned own runtime sources; no download, game launch or QA actors."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,tempfile,zipfile

ROOT=Path(__file__).resolve().parent
SOURCE_ROOT=next((p for p in (ROOT,*ROOT.parents) if (p/"publication-manifest.json").is_file()),ROOT)
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk',type=Path,required=True,help='External legally obtained dependency JAR directory; searched recursively by locked filename and SHA256')
    p.add_argument('--dependency-dir',type=Path,action='append',default=[],help='Additional external dependency directory; repeatable')
    p.add_argument('--jdk',type=Path,required=True,help='JDK21 installation directory')
    p.add_argument('--output',type=Path,required=True,help='New output directory outside the source repository')
    a=p.parse_args();sdk=a.sdk.resolve();output=a.output.resolve()
    config=json.loads((ROOT/'module.json').read_text(encoding='utf8'))
    lock=json.loads((ROOT/'dependencies.lock.json').read_text(encoding='utf8'))
    if output==SOURCE_ROOT or SOURCE_ROOT in output.parents:
        raise SystemExit('Output must be outside the source repository')
    if any(output==root or root in output.parents for root in [sdk,*[p.resolve() for p in a.dependency_dir]]):
        raise SystemExit('Output must be outside the external SDK')
    if output.exists():raise SystemExit('Refusing to overwrite existing output directory')
    cp=[];verified=[];embedded={}
    for entry in lock['classpath']:
        matches=sorted({f for root in [sdk,*[p.resolve() for p in a.dependency_dir]] for f in root.rglob(entry['fileName'])})
        matches=[f for f in matches if f.is_file() and f.name==entry['fileName']]
        if not matches:raise SystemExit('Missing locked dependency: '+entry['fileName'])
        valid=[f for f in matches if digest(f)==entry['sha256']]
        if not valid:raise SystemExit('SHA256 mismatch: '+entry['fileName'])
        if 'embeddedPath' in entry:
            with zipfile.ZipFile(valid[0]) as archive:
                content=archive.read(entry['embeddedPath'])
            if hashlib.sha256(content).hexdigest()!=entry['embeddedSha256']:
                raise SystemExit('SHA256 mismatch in embedded dependency: '+entry['embeddedPath'])
            embedded[len(cp)]=content;cp.append(None)
        else:cp.append(valid[0])
        verified.append(entry)
    javac=a.jdk.resolve()/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    version=subprocess.run([str(javac),'-version'],capture_output=True,text=True,check=True)
    actual=(version.stdout+version.stderr).strip()
    if actual!=lock['javacVersion']:raise SystemExit('Unsupported compiler: '+actual+'; expected '+lock['javacVersion'])
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='compile-',dir=output) as temporary:
        temp=Path(temporary);classes=temp/'classes';classes.mkdir()
        for index,content in embedded.items():
            file=temp/('dependency-'+str(index)+'.jar');file.write_bytes(content);cp[index]=file
        compileClasspath=apply_compile_access(config,cp,temp)
        args=['-g','-encoding','UTF-8','--release','21','-proc:none','-classpath',os.pathsep.join(map(str,compileClasspath)),'-d',str(classes)]
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
            if digest(path)!=entry.get('embeddedSha256',entry['sha256']):raise SystemExit('Dependency changed during compilation: '+entry['fileName'])
        receipt={'module':config['id'],'artifact':artifact.name,'sha256':digest(artifact),'compiler':actual,
                 'dependencies':verified,'entries':{n:hashlib.sha256(b).hexdigest() for n,b in sorted(entries.items())},
                 'sources':{n:digest(ROOT/n) for n in config['sources']},'compileOnly':True,
                 'gameStarted':False,'actualGameAcceptance':'PENDING','behaviorTestsRun':False,'releaseEligible':False}
        (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8')
        print(json.dumps(receipt,indent=2))
def apply_compile_access(config, classpath, temporary):
    """Honor pinned NeoForge AT declarations in a temporary compiler input only."""
    import struct
    rules=config.get('compileAccess',[])
    if not rules:return classpath
    def methods_public(content, required):
        data=bytearray(content)
        if data[:4]!=b'\xca\xfe\xba\xbe':raise SystemExit('Invalid external SDK class')
        u2=lambda offset:struct.unpack_from('>H',data,offset)[0]
        u4=lambda offset:struct.unpack_from('>I',data,offset)[0]
        count=u2(8);position=10;constants={};index=1
        sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
        while index<count:
            tag=data[position];position+=1
            if tag==1:
                size=u2(position);position+=2
                constants[index]=bytes(data[position:position+size]).decode('utf8',errors='replace');position+=size
            else:
                if tag not in sizes:raise SystemExit('Unsupported constant pool tag')
                position+=sizes[tag]
                if tag in [5,6]:index+=1
            index+=1
        position+=6
        position+=2+2*u2(position)
        def skip_member(start):
            attributes=u2(start+6);start+=8
            for _ in range(attributes):start+=6+u4(start+2)
            return start
        fields=u2(position);position+=2
        for _ in range(fields):position=skip_member(position)
        methods=u2(position);position+=2;changed=set()
        for _ in range(methods):
            name=constants[u2(position+2)];descriptor=constants[u2(position+4)]
            key=name+descriptor
            if key in required:
                struct.pack_into('>H',data,position,(u2(position)&~0x0006)|0x0001);changed.add(key)
            position=skip_member(position)
        if changed!=set(required):raise SystemExit('Pinned access target missing: '+repr(set(required)-changed))
        return bytes(data)
    generated={}
    for rule in rules:
        declarations=None
        for path in classpath:
            if path.name==rule['declarationJar']:
                with zipfile.ZipFile(path) as archive:
                    declarations=archive.read(rule['declarationEntry']).decode('utf8')
                break
        if declarations is None:raise SystemExit('Missing pinned NeoForge access declaration JAR')
        canonical={line.split('#',1)[0].strip() for line in declarations.splitlines()}
        for method in rule['methods']:
            declaration='public '+rule['className']+' '+method
            if declaration not in canonical:raise SystemExit('Required official access declaration missing: '+declaration)
        entry=rule['className'].replace('.','/')+'.class'
        original=None
        for path in classpath:
            with zipfile.ZipFile(path) as archive:
                if entry in archive.namelist():original=archive.read(entry);break
        if original is None:raise SystemExit('Missing original SDK class for compile access')
        generated[entry]=methods_public(original,rule['methods'])
    transformed=temporary/'neoforge-compile-access.jar'
    with zipfile.ZipFile(transformed,'w') as archive:
        for entry,content in sorted(generated.items()):
            info=zipfile.ZipInfo(entry,(2026,11,16,0,0,0));info.create_system=0;info.external_attr=0x20
            archive.writestr(info,content)
    expected=config.get('compileAccessExpectedJarSha256')
    if expected:
        import hashlib
        if hashlib.sha256(transformed.read_bytes()).hexdigest()!=expected:
            raise SystemExit('Derived compile-access SDK SHA256 mismatch')
    return [transformed,*classpath]

if __name__=='__main__':main()
