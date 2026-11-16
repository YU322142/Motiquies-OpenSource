"""GPL MapSyncer exact-source overlay and all 17 original regression tests; no game."""
import sys
sys.dont_write_bytecode=True
from pathlib import Path
import os,json,zipfile
from build_support import ROOT,Context,parser,digest,deterministic_archive,overlay_receipt,inside
from build_mapsync import build_sources
p=parser(__doc__)
p.add_argument('--base',required=True,type=Path,help='Original beta2 JAR; exact hash in dependency lock')
p.add_argument('--upstream-root',required=True,type=Path,help='External original source checkout at locked beta2 commit')
args=p.parse_args();upstream=args.upstream_root.resolve(strict=True)
c=Context(args)
if inside(c.output,upstream):raise ValueError('Output must be outside read-only upstream source')
source_lock=json.loads((ROOT/'upstream-source.lock.json').read_text(encoding='utf-8'))
def check_sources():
    for entry in source_lock['sourceInputs']:
        path=(upstream/entry['path']).resolve(strict=True)
        if not inside(path,upstream) or digest(path.read_bytes())!=entry['sha256']:
            raise ValueError('Locked upstream source mismatch: '+entry['path'])
check_sources()
classes=c.output/'classes'
c.compile(build_sources(upstream,c.output),classes,'compile-production')
with zipfile.ZipFile(args.base) as archive:
    if len(archive.namelist())!=len(set(archive.namelist())):raise ValueError('Duplicate base entry')
    entries={name:archive.read(name) for name in archive.namelist()}
    if any(name.endswith(('.SF','.RSA','.DSA')) for name in entries):raise ValueError('Signed input is unsupported')
original={name:digest(data) for name,data in entries.items()}
entries.update({f.relative_to(classes).as_posix():f.read_bytes() for f in classes.rglob('*.class')})
entries.update({f.relative_to(ROOT/'resources').as_posix():f.read_bytes() for f in (ROOT/'resources').rglob('*') if f.is_file()})
entries['META-INF/neoforge.mods.toml']+=b'\n[[mixins]]\nconfig="motiquies.mapsync.saved-regions.mixins.json"\n'
for name in ('LICENSE-GPL-3.0.txt','LICENSE-UPSTREAM-QOS.txt','NOTICE.md'):
    entries[name]=(ROOT/name).read_bytes()
artifact=c.output/'mapsyncer-motiquies-hybrid-source-neoforge-1.21.1.jar'
deterministic_archive(artifact,entries)
for name,value in original.items():
    if name.startswith('com/mapsyncer/client/') and digest(entries[name])!=value:
        raise ValueError('Original client class changed')
tests=c.output/'tests';testcp=os.pathsep.join([str(classes),str(artifact),c.cp])
c.compile((ROOT/'tests').glob('*.java'),tests,'compile-tests',testcp)
cp=os.pathsep.join([str(tests),str(classes),str(artifact),c.cp]);temp=c.output/'temp';temp.mkdir()
for source in sorted((ROOT/'tests').glob('*Test.java')):
    c.run([c.java,'-Xmx512m','-XX:ActiveProcessorCount=2','-Djava.awt.headless=true','-Djava.io.tmpdir='+str(temp),
           '-cp',cp,'com.mapsyncer.server.'+source.stem,c.output/'fixtures'/source.stem],source.stem)
c.run([sys.executable,ROOT/'verify_artifact.py','--base',args.base,'--artifact',artifact,'--java-home',args.java_home,
       '--output',c.output/'artifact-audit.json'],'verify-artifact')
check_sources()
overlay_receipt(c,artifact,{'upstreamSourceLock':source_lock,'originalBaseSha256':digest(args.base.read_bytes()),
                          'changedOriginalEntries':[name for name in sorted(original) if digest(entries[name])!=original[name]],
                          'addedEntries':sorted(set(entries)-set(original)),'all17OriginalTestsPassed':True})
