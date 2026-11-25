"""Build the Motiquies client module and run its regression tests."""
import sys
sys.dont_write_bytecode=True
import os
from build_support import ROOT,Context,parser
c=Context(parser(__doc__).parse_args())
classes=c.output/'classes';tests=c.output/'tests';temp=c.output/'temp';temp.mkdir()
c.compile((ROOT/'src/main/java').rglob('*.java'),classes,'compile-production')
cp=os.pathsep.join([str(classes),c.cp])
c.compile((ROOT/'src/test/java').rglob('*.java'),tests,'compile-tests',cp)
cp=os.pathsep.join([str(classes),str(tests),c.cp])
for source in sorted((ROOT/'src/test/java').rglob('*Test.java')):
    if source.stem=='DhCacheJunctionTest':continue
    c.run([c.java,'-Djava.io.tmpdir='+str(temp),'-cp',cp,'org.motiquies.client.'+source.stem],source.stem)
fixture=c.output/'junction-fixture';instance=fixture/'instance';victim_dir=fixture/'victim'
victim_dir.mkdir(parents=True);victim=victim_dir/'DistantHorizons.sqlite';victim.write_text('synthetic protected victim',encoding='utf-8')
link=instance/'Distant_Horizons_server_data/-1921347326_Motiquies';link.parent.mkdir(parents=True)
if os.name=='nt':
    script=c.output/'create-test-junction.ps1'
    script.write_text('param([string]$LinkPath,[string]$TargetPath)\n$ErrorActionPreference="Stop"\nNew-Item -ItemType Junction -Path $LinkPath -Target $TargetPath | Out-Null\n',encoding='utf-8')
    c.run(['powershell','-NoProfile','-File',script,'-LinkPath',link,'-TargetPath',victim_dir],'create-test-junction')
else:
    link.symlink_to(victim_dir,target_is_directory=True)
c.run([c.java,'-Djava.io.tmpdir='+str(temp),'-cp',cp,'org.motiquies.client.DhCacheJunctionTest',instance,victim],'DhCacheJunctionTest')
c.artifact('motiquies-client-migration-0.3.23.jar',classes,ROOT/'src/main/resources')
