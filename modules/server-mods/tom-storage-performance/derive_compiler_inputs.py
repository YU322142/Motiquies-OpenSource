"""Derive external compiler-only SDK children from exact official/original access rules; never patch runtime output."""
import copy,sys,zipfile
sys.dont_write_bytecode=True
from build_support import ROOT,parse_cli,verify_source,read_json,sdk_file,sha,digest,write_json
from classfile import patch
def main():
 a=parse_cli(__doc__);verify_source()
 if a.check_source:print('Source/tool pins verified');return
 if a.sdk is None:raise ValueError('--sdk/--sdk-root is required')
 p=read_json(ROOT/'compiler-profile/profile.json');official=sdk_file(a.sdk,p['officialAtOuterPath']);tom=ROOT/p['originalTomAtPath']
 if sha(official)!=p['officialAtOuterSha256'] or sha(tom)!=p['originalTomAtSha256']:raise ValueError('Official/original rule outer SHA256 mismatch')
 with zipfile.ZipFile(official) as z:rules=z.read(p['officialRuleEntry'])
 if digest(rules)!=p['officialRuleEntrySha256']:raise ValueError('Official rule entry SHA256 mismatch')
 literal={s.split('#')[0].strip() for s in rules.decode().splitlines()}
 if not all(s.split('#')[0].strip() in literal for s in p['exactOfficialRules']):raise ValueError('Exact official access rule missing')
 if tom.read_text(encoding='utf8').splitlines()!=p['exactOriginalTomRules']:raise ValueError('Original Tom access rules changed')
 names=[];fields={}
 for line in p['exactOfficialRules']:
  parts=line.split('#')[0].split()
  if len(parts)==2:names.append(parts[1].replace('.','/'))
  else:fields.setdefault(parts[1].replace('.','/'),{})[parts[2]]=parts[0]=='public-f'
 for line in p['exactOriginalTomRules']:
  if line.strip():access,name,field=line.split();fields.setdefault(name.replace('.','/'),{})[field]=access=='public-f'
 parents=[]
 for e in p['parents']:
  parent=sdk_file(a.sdk,e['path'])
  if sha(parent)!=e['sha256']:raise ValueError('SDK parent SHA256 mismatch')
  parents.append(parent)
 a.output.mkdir(parents=True);results=[]
 for e,parent in zip(p['parents'],parents):
  child=a.output/e['childPath'];changes=[]
  with zipfile.ZipFile(parent) as src,zipfile.ZipFile(child,'w') as dst:
   for info in src.infolist():
    raw=src.read(info);data,flags=patch(raw,info.filename[:-6],names,fields) if info.filename.endswith('.class') else (raw,[])
    if flags:changes.append({'entry':info.filename,'parentEntrySha256':digest(raw),'childEntrySha256':digest(data),'accessFlagChanges':flags})
    dst.writestr(copy.copy(info),data)
  with zipfile.ZipFile(parent) as src,zipfile.ZipFile(child) as dst:
   if dst.testzip() is not None or src.namelist()!=dst.namelist():raise ValueError('Derived archive integrity failure')
   if sorted(n for n in src.namelist() if src.read(n)!=dst.read(n))!=sorted(x['entry'] for x in changes):raise ValueError('Undocumented derived entry change')
  if sha(parent)!=e['sha256'] or sha(child)!=e['expectedChildSha256']:raise ValueError('Parent/derived child SHA256 mismatch')
  results.append({'parentPath':e['path'],'parentSha256':e['sha256'],'childPath':e['childPath'],'childSha256':sha(child),'changedEntries':changes,'allOtherEntryPayloadsUnchanged':True,'onlyDocumentedAccessWordsChanged':True})
 verify_source()
 receipt={'officialOuterSha256':p['officialAtOuterSha256'],'officialRuleEntrySha256':p['officialRuleEntrySha256'],'exactOfficialRules':p['exactOfficialRules'],'exactOriginalTomRules':p['exactOriginalTomRules'],'originalTomAtSha256':p['originalTomAtSha256'],'inputs':results,'methodAndNonAccessBytesUnchangedByRestorationWitness':True,'sdkOriginalsUnchanged':True,'runtimeModOutputPatched':False,'scope':'External compiler SDK children only; no redistribution permission is implied'}
 write_json(a.output/'receipt.json',receipt);print('Derived2 compiler children using exact official/original rules; SDK unchanged')
if __name__=='__main__':
 try:main()
 except (ValueError,OSError) as error:raise SystemExit(str(error))
