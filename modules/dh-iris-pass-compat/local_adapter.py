"""Generic, offline local ZIP adaptation engine. Contains no shader assets or recipe.
Users supply their own input archive and a separately reviewed recipe. Never download,
install, overwrite a source/output, or claim that a recipe has redistribution permission.
"""
from pathlib import Path,PurePosixPath
import hashlib,json,sys,zipfile,os,tempfile
sha=lambda b:hashlib.sha256(b).hexdigest()
def require(ok,reason):
 if not ok:raise ValueError(reason)
def safe(name):
 p=PurePosixPath(name)
 require(bool(name) and not p.is_absolute() and '..' not in p.parts and '\\' not in name and ':' not in name,'unsafe entry')
def adapt(source,recipe,output):
 source=Path(source).resolve();recipe=Path(recipe).resolve();output=Path(output).resolve()
 require(source!=output and recipe!=output,'source/recipe output alias')
 require(not output.exists(),'output exists')
 r=json.loads(recipe.read_text(encoding='utf-8'));require(r['schema']==1,'unsupported schema')
 require(sha(source.read_bytes())==r['input_sha256'],'input SHA256 mismatch')
 with zipfile.ZipFile(source) as z:
  names=z.namelist();require(len(set(names))==len(names),'duplicate input entry')
  for name in names:safe(name)
  require(sum(i.file_size for i in z.infolist())<=100*1024*1024,'archive too large')
  contents={n:z.read(n) for n in names}
 original=contents.copy()
 for name,spec in r['files'].items():
  safe(name);parts=[]
  for op in spec['ops']:
   if op[0] in ('copy','copy_lf'):
    require(len(op)==4 and op[1] in original,'unknown copy source')
    data=original[op[1]];a,n=op[2:]
    if op[0]=='copy_lf':data=data.replace(b'\r\n',b'\n')
    require(type(a) is int and type(n) is int and 0<=a<=len(data) and 0<=n<=len(data)-a,'copy range invalid')
    parts.append(data[a:a+n])
   elif op[0]=='literal':require(len(op)==2 and isinstance(op[1],str),'invalid literal');parts.append(op[1].encode('utf-8'))
   else:raise ValueError('unknown operation')
  data=b''.join(parts);require(sha(data)==spec['sha256'],'adapted file SHA256 mismatch');contents[name]=data
 require(set(contents)==set(r['entries']),'entry set mismatch')
 for name,data in contents.items():require(sha(data)==r['entries'][name],'entry SHA256 mismatch: '+name)
 output.parent.mkdir(parents=True,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix='.local-adapter-',suffix='.tmp',dir=output.parent);os.close(fd)
 try:
  with zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
   for name in sorted(contents):
    info=zipfile.ZipInfo(name,(2026,11,15,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,contents[name])
  with zipfile.ZipFile(tmp) as z:
   require(z.testzip() is None,'ZIP verification failed')
   for name in z.namelist():require(sha(z.read(name))==r['entries'][name],'written entry SHA256 mismatch')
  # Exclusive create also prevents another process's new output being overwritten.
  with open(output,'xb') as target,open(tmp,'rb') as src:
   while block:=src.read(1024*1024):target.write(block)
 finally:Path(tmp).unlink(missing_ok=True)
 return {'output':str(output),'sha256':sha(output.read_bytes()),'entries':len(contents),'source_preserved':sha(source.read_bytes())==r['input_sha256'],'status':'local private result; redistribution permission not supplied'}
if __name__=='__main__':
 try:print(json.dumps(adapt(*sys.argv[1:]),indent=2))
 except (ValueError,KeyError,TypeError,OSError,zipfile.BadZipFile) as e:print(str(e),file=sys.stderr);sys.exit(2)
