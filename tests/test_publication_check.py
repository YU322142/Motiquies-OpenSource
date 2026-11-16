import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC=importlib.util.spec_from_file_location('publication_check',Path(__file__).parents[1]/'tools/publication_check.py')
CHECK=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(CHECK)

class PublicationChecks(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  (self.root/'README.md').write_text('Example first-party source\n',encoding='utf8')
 def snapshot(self):return {'README.md':hashlib.sha256((self.root/'README.md').read_bytes()).hexdigest()}
 def test_clean_locked_source(self):self.assertEqual(CHECK.verify(self.root,self.snapshot()),[])
 def test_unreviewed_file_and_changed_source_rejected(self):
  locked=self.snapshot();(self.root/'README.md').write_text('changed',encoding='utf8');(self.root/'extra.py').write_text('print(1)',encoding='utf8')
  errors=CHECK.verify(self.root,locked);self.assertTrue(any('changed' in e for e in errors));self.assertTrue(any('unreviewed' in e for e in errors))
 def test_binary_even_if_hash_locked_is_rejected(self):
  (self.root/'private.jar').write_bytes(b'not a distributable source file');locked=self.snapshot();locked['private.jar']=hashlib.sha256((self.root/'private.jar').read_bytes()).hexdigest()
  self.assertTrue(any('binary' in e for e in CHECK.verify(self.root,locked)))
 def test_secret_and_machine_path_rejected(self):
  (self.root/'README.md').write_text('ghp_'+'a'*30+'\n'+'D:'+'/ProgramData/Codex/private\n',encoding='utf8')
  errors=CHECK.verify(self.root,self.snapshot());self.assertTrue(any('credential' in e for e in errors));self.assertTrue(any('machine path' in e for e in errors))
 def test_traversal_and_case_collision_rejected(self):
  locked=self.snapshot();locked['../outside.py']='0'*64;locked['readme.md']=locked['README.md']
  errors=CHECK.verify(self.root,locked);self.assertTrue(any('unsafe' in e for e in errors));self.assertTrue(any('collision' in e for e in errors))

if __name__=='__main__':unittest.main()
