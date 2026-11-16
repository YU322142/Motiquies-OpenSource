"""Offline overlay producers must preserve their explicitly selected toolchain."""
from pathlib import Path
import subprocess,sys,tempfile,unittest

ROOT=Path(__file__).resolve().parents[1]
ORIGINAL_MODULES=('worldedit-direction','tracks-block-tag-fix')
MODULES=ORIGINAL_MODULES+('yuushya-patchouli-safe', 'create-dragons-plus-server-resources', 'kaleidoscope-nether-server-resources','zstdnet-production-frame-fix','tom-storage-performance')

class ToolchainOutputBoundary(unittest.TestCase):
 def test_selected_jdk_output_is_refused_before_reading_inputs(self):
  with tempfile.TemporaryDirectory() as temp:
   external=Path(temp);jdk=external/'jdk';jdk.mkdir();sdk=external/'sdk';sdk.mkdir();inputs=external/'inputs';inputs.mkdir()
   for name in MODULES:
    with self.subTest(module=name):
     output=jdk/('new-output-'+name)
     module_path=ROOT/'modules'/('server-mods' if name=='tom-storage-performance' else 'server-overlays')/name/'build.py'
     args=['--sdk',str(sdk),'--jdk',str(jdk)] if name=='tom-storage-performance' else ['--input-jar',str(inputs/'absent.jar'),'--dependencies',str(sdk),'--java-home',str(jdk)]
     result=subprocess.run([sys.executable,'-B',str(module_path),*args,'--output',str(output)],capture_output=True,text=True,timeout=20)
     self.assertNotEqual(result.returncode,0)
     expected=('Output overlaps source repository or protected input root' if name=='tom-storage-performance' else 'outside selected JDK' if name=='zstdnet-production-frame-fix' else 'outside JDK' if name in ORIGINAL_MODULES else 'outside source repository, SDK and JDK')
     self.assertIn(expected,result.stdout+result.stderr)
     self.assertFalse(output.exists())
     self.assertEqual(list(jdk.iterdir()),[])

if __name__=='__main__':unittest.main()
