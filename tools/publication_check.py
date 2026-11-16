"""Check a reviewed source snapshot before publication; never refresh its lock implicitly."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,re,stat

BINARY={'.jar','.class','.zip','.7z','.tar','.gz','.exe','.dll','.so','.pyc','.png','.ogg','.mp3','.sqlite','.db'}
PRIVATE_NAMES={'password.txt','accounts.json','launcher_accounts.json','id_rsa','id_ed25519','.env'}
CREDENTIAL=re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b|-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----')
MACHINE=re.compile(r'(?i)\b[A-Z]:/(?:Users|ProgramData)/')

def inventory(root):
 root=Path(root);result={}
 for path in sorted(root.rglob('*')):
  rel=path.relative_to(root)
  if '.git' in rel.parts:continue
  if path.is_symlink() or getattr(path.lstat(),'st_file_attributes',0)&0x400:raise ValueError('Link/reparse point refused: '+rel.as_posix())
  if path.is_file() and rel.as_posix()!='publication-manifest.json':result[rel.as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
 return result

def verify(root,expected):
 root=Path(root);errors=[];folded={}
 for name,digest in expected.items():
  path=PurePosixPath(name)
  if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name or not path.parts:errors.append('unsafe manifest path: '+name);continue
  if name.casefold() in folded:errors.append('case collision: '+name)
  folded[name.casefold()]=name
  if not re.fullmatch(r'[0-9a-f]{64}',digest):errors.append('invalid digest: '+name)
 try:actual=inventory(root)
 except ValueError as e:return errors+[str(e)]
 for name in sorted(actual.keys()-expected.keys()):errors.append('unreviewed file: '+name)
 for name in sorted(expected.keys()-actual.keys()):errors.append('missing file: '+name)
 for name,digest in actual.items():
  if name in expected and expected[name]!=digest:errors.append('changed source: '+name)
  path=root/name
  if path.suffix.lower() in BINARY:errors.append('binary/generated asset refused: '+name);continue
  if path.name.lower() in PRIVATE_NAMES or path.suffix.lower()=='.log':errors.append('private/runtime file refused: '+name)
  raw=path.read_bytes()
  if len(raw)>4*1024*1024:errors.append('oversize source needs explicit review: '+name);continue
  try:text=raw.decode('utf-8-sig')
  except UnicodeDecodeError:errors.append('non-text file needs explicit review: '+name);continue
  if CREDENTIAL.search(text):errors.append('credential pattern: '+name)
  normalized=text.replace('\\','/')
  while '//' in normalized:normalized=normalized.replace('//','/')
  if MACHINE.search(normalized):errors.append('machine path: '+name)
 return errors

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);args=parser.parse_args()
 manifest=json.loads((args.root/'publication-manifest.json').read_text(encoding='utf8'));errors=verify(args.root,manifest['files'])
 print(json.dumps({'checkedFiles':len(manifest['files']),'errors':errors,'serverReproductionComplete':manifest['serverReproductionComplete']},ensure_ascii=False,indent=2))
 return 1 if errors else 0

if __name__=='__main__':raise SystemExit(main())
