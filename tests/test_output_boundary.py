"""Builders must refuse repository-sibling output before resolving any SDK input."""
from pathlib import Path
import subprocess,sys,tempfile,unittest,uuid

ROOT=Path(__file__).resolve().parents[1]
MODULES=['common-compat','tom-jei-isolation','restaurant-workpoint']+['server-guards/'+x for x in ('cctweaked-startup-shutdown-guard','create-carriage-orientation-guard','create-chute-unload-guard','deferred-content-protection','heightmap-384-to-544-compat','kaleidoscope-cookery-scarecrow-compat','waypoint-fire-equivalence')]+['legacy/dh-cache-only']

class RepositoryOutputBoundary(unittest.TestCase):
 def test_all_affected_builders_refuse_repository_sibling_output(self):
  with tempfile.TemporaryDirectory() as tmp:
   external=Path(tmp);sdk=external/'sdk';sdk.mkdir();jdk=external/'jdk'
   for name in MODULES:
    with self.subTest(module=name):
     output=ROOT/('boundary-test-'+uuid.uuid4().hex)
     args=['--sdk',str(sdk),'--jdk',str(jdk)] if name!='legacy/dh-cache-only' else ['--input-jar',str(external/'absent.jar'),'--dependencies',str(sdk),'--java-home',str(jdk)]
     result=subprocess.run([sys.executable,'-B',str(ROOT/'modules'/name/'build.py'),*args,'--output',str(output)],capture_output=True,text=True,timeout=20)
     self.assertNotEqual(result.returncode,0);self.assertIn('repository',result.stdout+result.stderr)
     self.assertFalse(output.exists(),'Invalid output must never be created')

if __name__=='__main__':unittest.main()
