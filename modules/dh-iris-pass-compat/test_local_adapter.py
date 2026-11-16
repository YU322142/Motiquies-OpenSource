"""Breaks caught: missing input/output pins, out-of-bounds copy, unsafe paths,
overwrite of user files, incomplete output verification. Fixtures contain only our text.
"""
from pathlib import Path
import tempfile,zipfile,hashlib,json,subprocess,sys,unittest,argparse
P=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
class AdapterTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=OUTPUT);self.addCleanup(self.tmp.cleanup);self.p=Path(self.tmp.name)
  self.source=self.p/'mine.zip';self.output=self.p/'private.zip';self.recipe=self.p/'recipe.json'
  with zipfile.ZipFile(self.source,'w') as z:z.writestr('shaders/a.glsl',b'original local text\n');z.writestr('LICENSE',b'user provided license\n')
  self.r={'schema':1,'input_sha256':sha(self.source.read_bytes()),'files':{'shaders/a.glsl':{'sha256':sha(b'original local text\n// ours\n'),'ops':[['copy','shaders/a.glsl',0,20],['literal','// ours\n']]}},'entries':{'shaders/a.glsl':sha(b'original local text\n// ours\n'),'LICENSE':sha(b'user provided license\n')}}
 def call(self):
  self.recipe.write_text(json.dumps(self.r),encoding='utf-8')
  return subprocess.run([sys.executable,str(P/'local_adapter.py'),str(self.source),str(self.recipe),str(self.output)],capture_output=True,text=True)
 def test_exact_local_output_preserves_source_and_license(self):
  before=self.source.read_bytes();r=self.call();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
  with zipfile.ZipFile(self.output) as z:self.assertEqual(z.read('shaders/a.glsl'),b'original local text\n// ours\n');self.assertEqual(z.read('LICENSE'),b'user provided license\n')
  self.assertEqual(self.source.read_bytes(),before)
 def test_wrong_input_pin_leaves_no_output(self):
  self.r['input_sha256']='0'*64;r=self.call();self.assertNotEqual(r.returncode,0);self.assertFalse(self.output.exists())
 def test_wrong_output_pin_leaves_no_output(self):
  self.r['entries']['LICENSE']='0'*64;r=self.call();self.assertNotEqual(r.returncode,0);self.assertFalse(self.output.exists())
 def test_invalid_copy_leaves_no_output(self):
  self.r['files']['shaders/a.glsl']['ops'][0][3]=200;r=self.call();self.assertNotEqual(r.returncode,0);self.assertFalse(self.output.exists())
 def test_path_traversal_leaves_no_output(self):
  self.r['files']['../a']=self.r['files'].pop('shaders/a.glsl');r=self.call();self.assertNotEqual(r.returncode,0);self.assertFalse(self.output.exists())
 def test_existing_output_is_preserved(self):
  self.output.write_bytes(b'keep');r=self.call();self.assertNotEqual(r.returncode,0);self.assertEqual(self.output.read_bytes(),b'keep')
 def test_source_output_alias_is_preserved(self):
  before=self.source.read_bytes();self.output=self.source;r=self.call();self.assertNotEqual(r.returncode,0);self.assertEqual(self.source.read_bytes(),before)
 def test_normalized_copy_uses_only_local_input(self):
  with zipfile.ZipFile(self.source,'w') as z:z.writestr('shaders/a.glsl',b'original local text\r\n');z.writestr('LICENSE',b'user provided license\n')
  self.r['input_sha256']=sha(self.source.read_bytes());self.r['files']['shaders/a.glsl']['ops'][0][0]='copy_lf'
  r=self.call();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
  with zipfile.ZipFile(self.output) as z:self.assertEqual(z.read('shaders/a.glsl'),b'original local text\n// ours\n')
if __name__=='__main__':
 parser=argparse.ArgumentParser()
 parser.add_argument('--output',required=True,type=Path)
 args,remaining=parser.parse_known_args()
 OUTPUT=args.output.resolve()
 if OUTPUT==P or P in OUTPUT.parents or OUTPUT==P.parents[1] or P.parents[1] in OUTPUT.parents:
  parser.error('output must be outside the source repository')
 OUTPUT.mkdir(parents=True,exist_ok=True)
 unittest.main(argv=[sys.argv[0],*remaining])
