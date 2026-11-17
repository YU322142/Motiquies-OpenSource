"""Own SDK recipe adapter; no import-time IO, downloads or process execution."""
from pathlib import Path
from urllib.parse import urlsplit
import hashlib
import json
import os
import re
import stat
import zipfile

JAVA_ENV={'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH','JAVA_HOME'}
HOSTS={'libraries.minecraft.net','maven.neoforged.net','piston-data.mojang.com'}
ORDER={'client':[3,4,5,6,8,9],'server':[1,2,3,4,5,7,8,9]}

def require(condition,message):
    if not condition:raise ValueError(message)

def sha256_file(path):
    with plain_path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def safe_relative(value):
    require(isinstance(value,str) and value and not any(c in value for c in '\\:\x00\r\n'),'unsafe relative path')
    require(all(p and p not in ('.','..') and p.rstrip(' .')==p for p in value.split('/')),'unsafe path segments')
    require(all(p.split('.')[0].upper() not in {'CON','PRN','AUX','NUL',*[f'COM{i}' for i in range(1,10)],*[f'LPT{i}' for i in range(1,10)]} for p in value.split('/')),'reserved Windows path')
    return value

def plain_path(value):
    # Inspect lexical ancestors first, including dangling links, before resolve.
    require('..' not in Path(value).parts,'parent path segments rejected')
    p=Path(os.path.abspath(os.fspath(value)))
    for ancestor in reversed((p,*p.parents)):
        try:s=ancestor.lstat()
        except FileNotFoundError:continue
        require(not stat.S_ISLNK(s.st_mode) and not getattr(s,'st_file_attributes',0)&0x400,'reparse/symlink path rejected')
    return p

def coordinate_path(coordinate):
    parts=coordinate.split('@');require(len(parts)<=2,'bad Maven extension')
    extension=parts[1] if len(parts)==2 else 'jar';pieces=parts[0].split(':')
    require(len(pieces) in (3,4),'bad Maven coordinate')
    require(all(re.fullmatch(r'[A-Za-z0-9_.+-]+',p) and p not in ('.','..') for p in pieces+[extension]),'unsafe Maven coordinate')
    group,artifact,version=pieces[:3];classifier='-'+pieces[3] if len(pieces)==4 else ''
    return safe_relative(f'{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}{classifier}.{extension}')

def child_environment(parent=None):
    allowed={'SYSTEMROOT','WINDIR','COMSPEC','PATH','TEMP','TMP','USERPROFILE','HOMEDRIVE','HOMEPATH','PATHEXT','NUMBER_OF_PROCESSORS','PROCESSOR_ARCHITECTURE','OS'}
    return {k:v for k,v in (os.environ if parent is None else parent).items() if k.upper() in allowed}

def validate_url(url,jdk_url=None):
    u=urlsplit(url)
    require(u.scheme=='https' and u.netloc==u.hostname and not u.username and not u.password and not u.query and not u.fragment,'canonical HTTPS URL required')
    require(u.hostname in HOSTS or url==jdk_url,'unknown download origin')
    require(not any(c in url for c in '\\\x00\r\n') and '..' not in u.path.split('/'),'unsafe URL')
    return url

def check_file(path,pin):
    p=plain_path(path);before=p.stat();require(p.is_file(),'regular file required')
    require(before.st_size==pin['bytes'],'file size changed')
    h=sha256_file(p);require(h==pin['sha256'],'file SHA256 changed')
    if pin.get('sha1'):
        with p.open('rb') as stream:require(hashlib.file_digest(stream,'sha1').hexdigest()==pin['sha1'],'file SHA1 changed')
    after=p.stat();require((before.st_ino,before.st_size,before.st_mtime_ns)==(after.st_ino,after.st_size,after.st_mtime_ns),'file changed during read')
    return {'bytes':before.st_size,'sha256':h}

def validate_recipe(recipe,execution=False):
    r=recipe;require(r['schema']==1 and r['scope']=='sdk-parent-derivation-only','unknown recipe scope')
    require(r['sideOrder']==ORDER and len(r['processors'])==10,'original processor side order changed')
    require(len(r['expectedOutputs'])==7 and len({x['path'] for x in r['expectedOutputs']})==7,'seven distinct outputs required')
    require(r['limits']=={'processorSeconds':900,'totalSeconds':3600,'jobMinutes':120,'heapGiB':2,'cpu':2,'minimumFreeGiB':12,'reserveGiB':2,'logBytesPerProcess':8388608},'resource policy changed')
    seen=set();paths={};pending=[]
    for x in r['downloads']:
        validate_url(x['url']);require(x['url'] not in seen,'duplicate URL');seen.add(x['url'])
        require(hashlib.sha256(x['url'].encode()).hexdigest()==x['id'],'request identity mismatch')
        require(type(x['bytes']) is int and 0<x['bytes']<=512*1024**2,'input size invalid')
        if x['sha256'] is None:
            require(x.get('vanilla') in {'client','server','client_mappings','server_mappings'} and x['url'].startswith('https://piston-data.mojang.com/v1/objects/'),'unreviewed hash omission')
            pending.append(x['vanilla'])
        else:require(re.fullmatch('[0-9a-f]{64}',x['sha256']) is not None,'invalid SHA256')
        if x.get('libraryPath'):
            path=safe_relative(x['libraryPath']);require(path.casefold() not in paths,'library path collision');paths[path.casefold()]=x
            require(not path.startswith('net/minecraft/'),'generated SDK input forbidden')
    require(len([x for x in r['downloads'] if x.get('vanilla')])==4,'four vanilla objects required')
    for x in r['expectedOutputs']:safe_relative(x['path'])
    if execution:
        require(not pending,'vanilla measured SHA256 not frozen')
        require(len(r['jdkSupport'])==2 and len(r['downloads'])+3==136,'complete fixed input coverage changed')
    return {'pendingVanillaSha256':sorted(pending),'downloadCount':len(seen),'rawBytes':sum(x['bytes'] for x in r['downloads'])}

def processor_commands(recipe,root,java):
    commands=[];root=Path(root)
    for side in ('client','server'):
        side_root=root/side;libs=side_root/'libraries'
        data={'SIDE':side,'ROOT':str(side_root),'LIBRARY_DIR':str(libs),'MINECRAFT_JAR':str(root/'raw'/(side+'.jar')),'INSTALLER':str(root/'raw'/'installer.jar')}
        for key,value in recipe['profileData'].items():
            raw=value[side]
            if raw.startswith('[') and raw.endswith(']'):data[key]=str(libs/coordinate_path(raw[1:-1]))
            elif raw.startswith('/'):
                data[key]=str(side_root/'embedded'/safe_relative(raw[1:]))
            elif raw.startswith("'") and raw.endswith("'"):data[key]=raw[1:-1]
            else:raise ValueError('unknown profile data encoding')
        def expand(arg):
            if arg.startswith('[') and arg.endswith(']'):return str(libs/coordinate_path(arg[1:-1]))
            value=re.sub(r'\{([^}]+)\}',lambda m:data.get(m[1],m[0]),arg)
            require('{' not in value and '}' not in value,'unresolved processor template')
            return value
        for index in recipe['sideOrder'][side]:
            p=recipe['processors'][index];require(side in p.get('sides',['client','server']),'wrong processor side')
            main=recipe['processorMains'][p['jar']]
            cp=';'.join(str(libs/coordinate_path(c)) for c in p['classpath'])
            args=[expand(a) for a in p['args']]
            commands.append({'side':side,'index':index,'main':main,'cwd':str(side_root),'programArgs':args,'argv':[str(java),'-Xmx2G','-XX:ActiveProcessorCount=2','-cp',cp,main,*args]})
    return commands

def check_platform(recipe,facts):
    require(facts==recipe['platform'],'hosted platform differs from reviewed facts')
    return True

def zip_metadata(path):
    entries=[];names=set()
    with zipfile.ZipFile(plain_path(path)) as z:
        for e in z.infolist():
            n=e.filename.rstrip('/');safe_relative(n)
            require(n.casefold() not in names,'duplicate ZIP entry');names.add(n.casefold())
            require(not stat.S_ISLNK(e.external_attr>>16),'ZIP symlink rejected')
            if e.is_dir():continue
            h=hashlib.sha256();size=0
            with z.open(e) as stream:
                while chunk:=stream.read(65536):h.update(chunk);size+=len(chunk)
            require(size==e.file_size,'ZIP entry size mismatch') # zipfile verifies CRC while reading.
            entries.append({'name':n,'bytes':size,'sha256':h.hexdigest(),'crc32':f'{e.CRC:08x}','classEntry':n.endswith('.class')})
    entries.sort(key=lambda x:x['name'])
    return {'entryCount':len(entries),'classCount':sum(e['classEntry'] for e in entries),'entries':entries}

EXPORT_KEYS={'logBytesTotal','sourceCommit','schema','passed','inputs','outputs','steps','bytes','sha256','sha1','entryCount','classCount','entries','name','crc32','classEntry','side','index','exitCode','stdoutBytes','stderrBytes','stdoutSha256','stderrSha256','elapsedSeconds','requestId','status','redirects','runId','runAttempt','job','recipeSha256','sourcePinsSha256','rawTargetsMatch','reproductionAccepted','networkIsolated','networkObservationComplete','observedUrlSha256','expectedRequestSha256','failure','kind','jdkTreeSha256','platform','imageOS','imageVersion','curlSha256','pythonVersion','pythonSha256','omittedAncillaryStep0','globalRuntimeValidated'}
def validate_export(value):
    if isinstance(value,dict):
        require(not set(value)-EXPORT_KEYS,'unapproved metadata export field')
        for v in value.values():validate_export(v)
    elif isinstance(value,list):
        for v in value:validate_export(v)
    elif isinstance(value,str):
        require(len(value)<=256 and not re.search(r'[A-Za-z]:[\\/]|https?://|\x00|\r|\n',value),'unsafe metadata string')
    else:require(value is None or type(value) in (int,float,bool),'unsupported metadata value')
