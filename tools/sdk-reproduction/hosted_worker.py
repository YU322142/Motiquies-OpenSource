"""Separate hosted execution adapter; not the frozen local SDK guard.

Imports are inert. A real run needs an explicitly approved commit on a GitHub
hosted Windows workflow_dispatch job. Tests inject only own fake processes.
"""
from pathlib import Path
from urllib.parse import urlsplit
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
import zipfile
from hosted_sdk import (require,plain_path,safe_relative,sha256_file,check_file,
    validate_recipe,check_platform,child_environment,coordinate_path,
    processor_commands,zip_metadata,validate_export,validate_url)

JDK_URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/OpenJDK21U-jdk_x64_windows_hotspot_21.0.10_7.zip'

class ProcessFailure(ValueError):
    def __init__(self,kind,receipt):
        super().__init__(kind);self.receipt=receipt

def create_fresh_root(value):
    p=plain_path(value);require(not p.exists(),'fresh destination already exists')
    p.mkdir(parents=False,exist_ok=False);return p

def require_hosted(env,approved_commit):
    require(os.name=='nt' and env.get('RUNNER_ENVIRONMENT')=='github-hosted' and env.get('RUNNER_OS')=='Windows','only a fresh hosted Windows runner is admitted')
    require(env.get('GITHUB_EVENT_NAME')=='workflow_dispatch' and re.fullmatch('[0-9a-f]{40}',approved_commit or '') and env.get('GITHUB_SHA')==approved_commit,'explicit approved workflow commit required')
    require(env.get('SDK_FRESH_JOB') in {'fresh-a','fresh-b'},'fresh job label required')
    require(not env.get('GH_TOKEN') and not env.get('GITHUB_TOKEN'),'no repository credentials may reach SDK worker')

def deadline_fence(deadline):
    require(time.monotonic()<deadline,'global or terminal deadline exhausted')

def bounded_process(argv,cwd,seconds,cap,deadline,popen=None,capture=None,watch=None):
    """Drain both pipes incrementally; hash body, never publish its text.

    Windows Popen.terminate/kill uses its owned kernel process handle, not a
    PID lookup. No process name, reused PID or unrelated process is targeted.
    """
    require(seconds>=0 and cap>0,'invalid process budget')
    started=time.monotonic();require(started<deadline,'global deadline exhausted before process admission');end=min(deadline,started+seconds)
    p=(subprocess.Popen if popen is None else popen)(argv,cwd=cwd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=child_environment(),shell=False,creationflags=0x08000000 if os.name=='nt' else 0)
    streams=[{'bytes':0,'hash':hashlib.sha256()},{'bytes':0,'hash':hashlib.sha256()}];errors=[];observed=set()
    log_budget={'bytes':0,'overflow':False};log_lock=threading.Lock()
    def drain(stream,i):
        tail=b''
        try:
            while True:
                data=stream.read1(65536) if hasattr(stream,'read1') else stream.read(65536)
                if not data:break
                streams[i]['bytes']+=len(data);streams[i]['hash'].update(data)
                with log_lock:
                    log_budget['bytes']+=len(data)
                    if log_budget['bytes']>cap and not log_budget['overflow']:
                        log_budget['overflow']=True;errors.append('log-cap')
                if capture is not None:
                    buf=capture.setdefault(i,bytearray())
                    if len(buf)+len(data)<=65536:buf.extend(data)
                    else:errors.append('capture-cap')
                text=tail+data
                for match in re.findall(rb'https?://[^\s<>"\x00]+',text):observed.add(hashlib.sha256(match.rstrip(b'.,;)')).hexdigest())
                tail=text[-1024:]
        except BaseException:errors.append('reader-error')
        finally:
            try:stream.close()
            except BaseException:errors.append('reader-close-error')
    threads=[threading.Thread(target=drain,args=(s,i),daemon=True) for i,s in enumerate((p.stdout,p.stderr))]
    for t in threads:t.start()
    failure=None
    try:
        while True:
            if errors:failure='bounded-log-failure';break
            if watch:watch()
            if time.monotonic()>=end:failure='timeout';break
            code=p.poll()
            if time.monotonic()>=end:failure='timeout';break
            if code is not None:break
            time.sleep(.02)
    except BaseException:failure='process-watch-failure'
    finally:
        if failure and p.poll() is None:
            p.terminate()
            try:p.wait(timeout=5)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
        for t in threads:t.join(timeout=5)
        if any(t.is_alive() for t in threads) or errors:failure=failure or 'bounded-log-failure'
    terminal_code=p.poll();terminal=time.monotonic()
    if terminal>=end:failure=failure or 'terminal-timeout'
    receipt={'exitCode':terminal_code,'stdoutBytes':streams[0]['bytes'],'stderrBytes':streams[1]['bytes'],'logBytesTotal':log_budget['bytes'],'stdoutSha256':streams[0]['hash'].hexdigest(),'stderrSha256':streams[1]['hash'].hexdigest(),'elapsedSeconds':round(terminal-started,3),'observedUrlSha256':sorted(observed)}
    if failure or receipt['exitCode']!=0:raise ProcessFailure(failure or 'nonzero-exit',receipt)
    return receipt

def jdk_redirect(location):
    u=urlsplit(location)
    require(u.scheme=='https' and u.netloc=='release-assets.githubusercontent.com' and not u.fragment and bool(u.query),'unapproved JDK redirect origin')
    require(re.fullmatch('/github-production-release-asset/602574963/[0-9a-f-]{36}',u.path) is not None and not any(c in location for c in '\\\r\n\x00'),'unapproved JDK redirect repository/path')
    return location

def extract_checked(archive,destination):
    metadata=zip_metadata(archive) # Validate every name, duplicate, symlink and CRC before creating destination.
    out=create_fresh_root(destination)
    with zipfile.ZipFile(archive) as z:
        for e in metadata['entries']:
            p=out/safe_relative(e['name']);p.parent.mkdir(parents=True,exist_ok=True)
            with p.open('xb') as target,z.open(e['name']) as source:shutil.copyfileobj(source,target,65536)
            check_file(p,e)
    return out

def manifest_main(path):
    with zipfile.ZipFile(path) as z:
        body=z.read('META-INF/MANIFEST.MF')
    require(len(body)<=65536,'manifest cap exceeded')
    text=body.decode('utf-8').replace('\r\n','\n').replace('\n ','')
    main_section=text.split('\n\n',1)[0]
    values=[line.split(': ',1)[1] for line in main_section.split('\n') if line.startswith('Main-Class: ')]
    require(len(values)==1 and re.fullmatch('[A-Za-z0-9_.$]+',values[0]),'unexpected manifest main')
    return values[0]

def tree_hash(root):
    entries=[]
    for p in sorted(plain_path(root).rglob('*')):
        plain_path(p)
        if p.is_file():entries.append((p.relative_to(root).as_posix(),sha256_file(p)))
    h=hashlib.sha256(''.join(n+'\x00'+v+'\n' for n,v in sorted(entries)).encode()).hexdigest()
    return h,len(entries)

def bundle_expectations(server_jar):
    """Derive extraction expectations only from an already SHA-pinned official JAR.

    Never trust current output files as expected values. Only digest metadata is
    returned; no embedded vendor bytes or manifest text enters a public report.
    """
    result={'libraries':[]};seen=set()
    with zipfile.ZipFile(server_jar) as z:
        for key in ('libraries','versions'):
            raw=z.read('META-INF/'+key+'.list');require(len(raw)<=1024**2,'bundler table cap')
            rows=raw.decode('utf-8').splitlines();require(len(rows)<=512,'bundler row cap')
            for row in rows:
                pieces=row.split('\t');require(len(pieces)==3,'unknown bundler manifest schema')
                h,coordinate,path=pieces;safe_relative(path);require(re.fullmatch('[0-9a-f]{64}',h),'bundler digest invalid')
                require((key,path.casefold()) not in seen,'bundler duplicate path');seen.add((key,path.casefold()))
                name='META-INF/'+key+'/'+path;e=z.getinfo(name)
                with z.open(e) as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==h,'bundled bytes differ from authenticated manifest')
                pin={'name':path,'bytes':e.file_size,'sha256':h}
                if key=='libraries':result['libraries'].append(pin)
                else:require('server' not in result,'multiple bundled server versions');result['server']=pin
    require('server' in result,'bundled server absent');return result

def write_metadata_new(path,value):
    validate_export(value)
    with plain_path(path).open('x',encoding='utf-8',newline='\n') as stream:json.dump(value,stream,sort_keys=True,indent=2);stream.write('\n')

def write_result(path,report,deadline,success):
    """Only the final fixed filename is uploaded; a late seal stays failed.

    Candidate metadata is own digest metadata, never a vendor body. If the
    deadline expires during serialization/write/rename, preserve the candidate
    separately and publish a failed receipt at the fixed path, without retry.
    """
    if not success:write_metadata_new(path,report);return
    path=plain_path(path);candidate=path.with_name('result.candidate.metadata.json')
    require(not path.exists() and not candidate.exists(),'result or candidate already exists')
    published=False
    try:
        deadline_fence(deadline)
        value=dict(report,passed=True,rawTargetsMatch=True);validate_export(value)
        body=json.dumps(value,sort_keys=True,indent=2)+'\n'
        deadline_fence(deadline)
        with candidate.open('x',encoding='utf-8',newline='\n') as stream:
            stream.write(body);stream.flush();os.fsync(stream.fileno())
        deadline_fence(deadline)
        require(not path.exists(),'final result already exists')
        candidate.rename(path)
        published=True
        deadline_fence(deadline)
    except BaseException:
        if published:path.rename(path.with_name('result.unaccepted.metadata.json'))
        report.update({'passed':False,'rawTargetsMatch':False,'failure':'final-deadline-or-seal-failure'})
        write_metadata_new(path,report)
        raise
    report.update({'passed':True,'rawTargetsMatch':True})

def load_locked_recipe(here,recipe_sha,pins_sha):
    pins=here/'source-pins.json';require(sha256_file(pins)==pins_sha,'source lock changed')
    table=json.loads(pins.read_text('utf-8'))
    require(table['schema']==1 and set(table)=={'schema','files'},'source lock schema')
    for name,h in table['files'].items():require(sha256_file(here/safe_relative(name))==h,'reviewed source changed')
    body=here/'recipe.json';require(sha256_file(body)==recipe_sha,'recipe lock changed')
    r=json.loads(body.read_text('utf-8'));return r

def resource_fence(root,reserve=2):
    require(shutil.disk_usage(root).free>=reserve*1024**3,'disk reserve exhausted')

def fetch(row,target,curl,deadline,root,jdk=False):
    """One GET per admitted URL. No retry, proxy, cookie, auth or TLS bypass."""
    if jdk:require(row['url'] in {JDK_URL,JDK_URL+'.sha256.txt',JDK_URL+'.sig'},'unreviewed JDK asset route')
    validate_url(row['url'],jdk_url=row['url'] if jdk else None)
    deadline_fence(deadline)
    url=row['url'];request_id=hashlib.sha256(url.encode()).hexdigest();receipts=[]
    for hop in range(2 if jdk else 1):
        body=plain_path(target if hop==0 else target.with_suffix('.redirect-body'))
        headers=body.with_suffix(body.suffix+'.headers')
        with body.open('xb'):pass
        capture={}
        args=[str(curl),'-q','--silent','--show-error','--noproxy','*','--proto','=https','--proto-redir','=https','--max-redirs','0','--connect-timeout','20','--max-time','180','--max-filesize',str(row['bytes']),'--dump-header',str(headers),'--output',str(body),'--write-out','%{http_code}',url]
        def watch():
            resource_fence(root)
            require(body.stat().st_size<=row['bytes'],'transfer size cap')
            if headers.exists():require(headers.stat().st_size<=65536,'header cap')
        receipt=bounded_process(args,root,190,65536,deadline,capture=capture,watch=watch)
        status=int(bytes(capture.get(0,b'')).decode('ascii'));receipt.update({'requestId':request_id,'status':status,'redirects':hop});receipts.append(receipt)
        raw=headers.read_bytes();locations=re.findall(rb'(?im)^location:\s*([^\r\n]+)',raw);require(len(locations)<=1,'ambiguous redirect')
        if status in (301,302,303,307,308):
            require(jdk and hop==0 and locations,'unexpected redirect');url=jdk_redirect(locations[0].decode('ascii'));continue
        require(status==200 and not locations,'unexpected HTTP status/location')
        deadline_fence(deadline);receipt.update(check_file(body,row));deadline_fence(deadline)
        if row.get('sha1'):receipt['sha1']=row['sha1']
        if hop:require(target.stat().st_size==0,'unexpected redirect response body');target.unlink();body.rename(target)
        return receipts
    raise ValueError('redirect chain incomplete')

def verify_static_libs(recipe,side_root):
    for row in recipe['downloads']:
        if row.get('libraryPath'):check_file(side_root/'libraries'/row['libraryPath'],row)

def verify_bundle_outputs(bundle,command):
    if command['side']!='server':return
    if command['index']==1:
        for pin in bundle['libraries']:check_file(Path(command['cwd'])/'libraries'/pin['name'],pin)
    if command['index']==2:
        args=command['programArgs'];check_file(Path(args[args.index('--output')+1]),bundle['server'])

def prepare_inputs(recipe,root,curl,deadline,report):
    deadline_fence(deadline)
    raw=root/'raw';raw.mkdir();objects=root/'objects';objects.mkdir()
    for row in recipe['downloads']:
        deadline_fence(deadline)
        p=objects/row['id'];report['inputs'].extend(fetch(row,p,curl,deadline,root))
        if row.get('vanilla') in ('client','server'):shutil.copyfile(p,raw/(row['vanilla']+'.jar'))
        if row['url']==recipe['installer']['officialUrl']:shutil.copyfile(p,raw/'installer.jar')
    jdk_zip=objects/'jdk.zip';report['inputs'].extend(fetch(recipe['jdk'],jdk_zip,curl,deadline,root,jdk=True))
    for index,pin in enumerate(recipe['jdkSupport']):report['inputs'].extend(fetch(pin,objects/('jdk-support-'+str(index)),curl,deadline,root,jdk=True))
    deadline_fence(deadline);expanded=extract_checked(jdk_zip,root/'jdk-extracted');deadline_fence(deadline);jdk=expanded/recipe['jdk']['root']
    h,count=tree_hash(jdk);deadline_fence(deadline);require(h==recipe['jdk']['treeSha256'] and count==recipe['jdk']['entryCount'],'fresh official JDK tree mismatch')
    java=jdk/'bin'/'java.exe';require(sha256_file(java)==recipe['jdk']['javaSha256'],'fresh Java executable mismatch')
    require(sha256_file(jdk/'bin'/'javac.exe')==recipe['jdk']['javacSha256'],'fresh javac executable mismatch');report['jdkTreeSha256']=h
    installer=raw/'installer.jar'
    with zipfile.ZipFile(installer) as z:
        for name,h in recipe['metadataSHA256'].items():require(hashlib.sha256(z.read(name)).hexdigest()==h,'official installer metadata mismatch')
        profile=json.loads(z.read('install_profile.json'))
        require(profile['processors']==recipe['processors'] and profile['data']==recipe['profileData'],'original recipe differs from fresh installer')
        for side in ('client','server'):
            deadline_fence(deadline)
            side_root=root/side;side_root.mkdir();libs=side_root/'libraries';libs.mkdir()
            for row in recipe['downloads']:
                deadline_fence(deadline)
                if row.get('libraryPath'):
                    dest=libs/safe_relative(row['libraryPath']);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(objects/row['id'],dest);check_file(dest,row)
            for name,pin in recipe['installer']['embeddedPatchFingerprints'].items():
                data=z.read(name);require(len(data)==pin['bytes'] and hashlib.sha256(data).hexdigest()==pin['sha256'],'embedded patch mismatch')
                dest=side_root/'embedded'/safe_relative(name);dest.parent.mkdir(parents=True,exist_ok=True)
                with dest.open('xb') as target:target.write(data)
            for coordinate,main in recipe['processorMains'].items():require(manifest_main(libs/coordinate_path(coordinate))==main,'processor manifest main differs')
    deadline_fence(deadline)
    return java

def verify_mapping(recipe,command):
    side=command['side'];pin=next(x for x in recipe['downloads'] if x.get('vanilla')==side+'_mappings')
    args=command['programArgs'];check_file(Path(args[args.index('--output')+1]),pin)

def check_processor_urls(recipe,index,observed):
    urls=recipe['mappingMetadataOrigins']+[x['url'] for x in recipe['downloads'] if x.get('vanilla','').endswith('_mappings')]
    allowed={hashlib.sha256(u.encode()).hexdigest() for u in urls} if index==4 else set()
    require(set(observed)<=allowed,'unreviewed processor URL observation')

def finalize_outputs(recipe,root,here,recipe_sha,pins_sha,deadline,report):
    for pin in recipe['expectedOutputs']:
        deadline_fence(deadline);resource_fence(root)
        side='server' if '/server/' in pin['path'] or pin['path'].endswith('-server.jar') else 'client'
        path=root/side/'libraries'/pin['path'];observed=check_file(path,pin);deadline_fence(deadline)
        metadata=zip_metadata(path);deadline_fence(deadline)
        report['outputs'].append({'name':pin['path'],**observed,**metadata})
    for row in recipe['downloads']:
        deadline_fence(deadline);check_file(root/'objects'/row['id'],row);deadline_fence(deadline)
    load_locked_recipe(here,recipe_sha,pins_sha);deadline_fence(deadline)
    resource_fence(root);deadline_fence(deadline)

def run(recipe,root,here,recipe_sha,pins_sha,approved_commit):
    require_hosted(os.environ,approved_commit);validate_recipe(recipe,execution=True)
    facts={'imageOS':os.environ.get('ImageOS'),'imageVersion':os.environ.get('ImageVersion'),'curlSha256':sha256_file('C:/Windows/System32/curl.exe'),'pythonVersion':os.environ.get('SDK_PYTHON_VERSION'),'pythonSha256':sha256_file(os.sys.executable)}
    check_platform(recipe,facts)
    parent=plain_path(os.environ['RUNNER_TEMP']);require(plain_path(root).parent==parent,'build must be a new immediate RUNNER_TEMP child')
    require(shutil.disk_usage(parent).free>=recipe['limits']['minimumFreeGiB']*1024**3,'initial disk budget insufficient')
    root=create_fresh_root(root);deadline=time.monotonic()+3600
    report={'schema':1,'passed':False,'rawTargetsMatch':False,'reproductionAccepted':False,'recipeSha256':recipe_sha,'sourcePinsSha256':pins_sha,'sourceCommit':approved_commit,'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],'job':os.environ['SDK_FRESH_JOB'],'platform':facts,'networkIsolated':False,'networkObservationComplete':False,'inputs':[],'outputs':[],'steps':[],'omittedAncillaryStep0':True,'globalRuntimeValidated':False}
    success_ready=False
    try:
        java=prepare_inputs(recipe,root,Path('C:/Windows/System32/curl.exe'),deadline,report)
        bundle=bundle_expectations(root/'raw'/'server.jar')
        for command in processor_commands(recipe,root,java):
            deadline_fence(deadline);resource_fence(root);verify_static_libs(recipe,Path(command['cwd']));deadline_fence(deadline)
            step=bounded_process(command['argv'],command['cwd'],900,recipe['limits']['logBytesPerProcess'],deadline,watch=lambda:resource_fence(root))
            step.update({'side':command['side'],'index':command['index']});report['steps'].append(step)
            check_processor_urls(recipe,command['index'],step['observedUrlSha256'])
            if command['index']==4:verify_mapping(recipe,command)
            verify_bundle_outputs(bundle,command)
            verify_static_libs(recipe,Path(command['cwd']))
            deadline_fence(deadline)
        finalize_outputs(recipe,root,here,recipe_sha,pins_sha,deadline,report)
        success_ready=True
        # Acceptance still requires independent fresh-a/b review and root ruling.
    except BaseException as error:
        report['failure']='process-failed' if isinstance(error,ProcessFailure) else 'fence-or-input-failed'
        if isinstance(error,ProcessFailure):report['steps'].append(error.receipt)
        raise
    finally:write_result(root/'result.metadata.json',report,deadline,success_ready)
    return report

def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['plan','run']);parser.add_argument('--recipe-sha',required=True);parser.add_argument('--pins-sha',required=True);parser.add_argument('--approved-commit');args=parser.parse_args()
    here=Path(__file__).resolve().parent
    try:
        recipe=load_locked_recipe(here,args.recipe_sha,args.pins_sha)
        if args.mode=='plan':
            status=validate_recipe(recipe);print(json.dumps({'schema':1,'mode':'SOURCE_ONLY_PLAN','pendingVanilla':status['pendingVanillaSha256'],'sdkInputObjects':status['downloadCount'],'jdkAssets':3,'totalInitialObjects':status['downloadCount']+3,'steps':14,'outputs':7,'executionAuthorized':False,'networkIsolationClaimed':False}));return
        root=Path(os.environ['RUNNER_TEMP'])/('sdk-'+os.environ.get('SDK_FRESH_JOB','invalid'))
        run(recipe,root,here,args.recipe_sha,args.pins_sha,args.approved_commit)
    except BaseException:
        print('{"schema":1,"passed":false,"failure":"admission-or-build-failed"}')
        raise SystemExit(1)

if __name__=='__main__':main()
