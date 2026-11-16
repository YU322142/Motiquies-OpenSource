"""Offline overlay producers must preserve their explicitly selected toolchain."""
from pathlib import Path
import subprocess,sys,tempfile,unittest

ROOT=Path(__file__).resolve().parents[1]
MODULES=('worldedit-direction','tracks-block-tag-fix')

class ToolchainOutputBoundary(unittest.TestCase):
 def test_selected_jdk_output_is_refused_before_reading_inputs(self):
  with tempfile.TemporaryDirectory() as temp:
   external=Path(temp);jdk=external/'jdk';jdk.mkdir();sdk=external/'sdk';sdk.mkdir()
   for name in MODULES:
    with self.subTest(module=name):
     output=jdk/('new-output-'+name)
     result=subprocess.run([sys.executable,'-B',str(ROOT/'modules/server-overlays'/name/'build.py'),
       '--input-jar',str(external/'absent.jar'),'--dependencies',str(sdk),'--java-home',str(jdk),
       '--output',str(output)],capture_output=True,text=True,timeout=20)
     self.assertNotEqual(result.returncode,0)
     self.assertIn('outside JDK',result.stdout+result.stderr)
     self.assertFalse(output.exists())
     self.assertEqual(list(jdk.iterdir()),[])

if __name__=='__main__':unittest.main()
