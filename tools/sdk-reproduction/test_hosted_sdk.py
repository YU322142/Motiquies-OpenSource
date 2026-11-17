"""Own synthetic fixtures only; never download or execute Java."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
import hosted_sdk as h

HERE=Path(__file__).parent

class PureContracts(unittest.TestCase):
    def api(self,name):
        self.assertTrue(callable(getattr(h,name,None)), 'missing implementation: '+name)
        return getattr(h,name)

    def test_safe_relative(self):
        safe=self.api('safe_relative')
        self.assertEqual(safe('a/b.jar'),'a/b.jar')
        for value in ('../x','/a','C:/x','a/../x','a\\x','a//x','a:stream','a/.','a\x00b'):
            with self.subTest(value=value),self.assertRaises(ValueError):safe(value)

    def test_coordinate(self):
        f=self.api('coordinate_path')
        self.assertEqual(f('a.b:c:1:all@zip'),'a/b/c/1/c-1-all.zip')
        for v in ('../a:b:1','a:b','a:b:1:2:3','a:b:../1'):
            with self.subTest(v=v),self.assertRaises(ValueError):f(v)

    def test_clean_child_environment_does_not_mutate_parent(self):
        f=self.api('child_environment');original={x:'injected' for x in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH','JAVA_HOME')};original['PATH']='system'
        clean=f(original)
        self.assertEqual(original['JDK_JAVAC_OPTIONS'],'injected')
        self.assertEqual(clean,{'PATH':'system'})

    def test_pending_vanilla_blocks_execution(self):
        f=self.api('validate_recipe');recipe=json.loads((HERE/'recipe.pending.json').read_text('utf-8'))
        result=f(recipe,execution=False)
        self.assertEqual(len(result['pendingVanillaSha256']),4)
        with self.assertRaisesRegex(ValueError,'vanilla'):f(recipe,execution=True)

    def test_profile_step_order_and_argv(self):
        f=self.api('processor_commands');r=json.loads((HERE/'recipe.pending.json').read_text('utf-8'))
        commands=f(r,Path('X:/fresh'),Path('X:/fresh/jdk/bin/java.exe'))
        self.assertEqual([(c['side'],c['index']) for c in commands],[(s,i) for s in ('client','server') for i in r['sideOrder'][s]])
        self.assertEqual(sum(c['index']==4 for c in commands),2)
        for c in commands:
            self.assertEqual(c['argv'][1:3],['-Xmx2G','-XX:ActiveProcessorCount=2'])
            self.assertEqual(c['programArgs'][0],r['processors'][c['index']]['args'][0])
            self.assertFalse(any('{' in a or '}' in a for a in c['argv']))
        cp=commands[0]['argv'][4].split(';')
        self.assertEqual(len(cp),19)
        self.assertIn('installertools-2.1.2.jar',cp[0])
        self.assertIn('commons-collections-3.2.2.jar',cp[-1])

    def test_unresolved_template_rejected(self):
        f=self.api('processor_commands');r=json.loads((HERE/'recipe.pending.json').read_text('utf-8'));r['processors'][3]['args'].append('{UNKNOWN}')
        with self.assertRaises(ValueError):f(r,Path('X:/fresh'),Path('X:/java.exe'))

    def test_no_unknown_download_route(self):
        f=self.api('validate_url')
        self.assertEqual(f('https://libraries.minecraft.net/a.jar'),'https://libraries.minecraft.net/a.jar')
        for u in ('http://libraries.minecraft.net/a','https://evil.test/a','https://x@libraries.minecraft.net/a','https://libraries.minecraft.net/a?secret=1','https://libraries.minecraft.net/a#x','https://libraries.minecraft.net/../a'):
            with self.subTest(u=u),self.assertRaises(ValueError):f(u)

    def test_platform_requires_exact_prior_observation(self):
        f=self.api('check_platform');r=json.loads((HERE/'recipe.pending.json').read_text('utf-8'));facts=copy.deepcopy(r['platform'])
        self.assertTrue(f(r,facts))
        for key in ('imageVersion','curlSha256','pythonSha256','imageOS'):
            changed=copy.deepcopy(facts);changed[key]='unexpected'
            with self.subTest(key=key),self.assertRaises(ValueError):f(r,changed)

    def test_zip_metadata_reads_crc_and_hash_without_payload(self):
        f=self.api('zip_metadata')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own.zip'
            with zipfile.ZipFile(p,'w') as z:z.writestr('own/Fake.class',b'own synthetic bytes');z.writestr('notes.txt',b'own')
            metadata=f(p)
            self.assertEqual(metadata['entryCount'],2)
            self.assertEqual(next(e['sha256'] for e in metadata['entries'] if e['name']=='own/Fake.class'),hashlib.sha256(b'own synthetic bytes').hexdigest())
            self.assertNotIn('synthetic',json.dumps(metadata))

    def test_zip_duplicate_and_traversal_refused(self):
        f=self.api('zip_metadata')
        for names in (['../escape'],['a','a'],['a','A']):
            with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
                p=Path(t)/'own.zip'
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore',UserWarning)
                    with zipfile.ZipFile(p,'w') as z:
                        for n in names:z.writestr(n,b'own')
                with self.assertRaises(ValueError):f(p)

    def test_sha_and_size_both_required(self):
        f=self.api('check_file')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own';p.write_bytes(b'own');pin={'bytes':3,'sha256':hashlib.sha256(b'own').hexdigest()}
            self.assertEqual(f(p,pin)['bytes'],3)
            for change in ({'bytes':4},{'sha256':'0'*64}):
                with self.assertRaises(ValueError):f(p,dict(pin,**change))

    def test_reparse_ancestor_refused(self):
        f=self.api('plain_path')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            root=Path(t);target=root/'target';target.mkdir();link=root/'link'
            try:
                if os.name=='nt':
                    import _winapi
                    _winapi.CreateJunction(str(target),str(link))
                else:link.symlink_to(target,target_is_directory=True)
            except OSError:self.skipTest('host does not permit synthetic symlink')
            with self.assertRaises(ValueError):f(link/'missing'/'new')

    def test_parent_segment_never_resolved_before_check(self):
        f=self.api('plain_path')
        with self.assertRaises(ValueError):f(HERE/'missing'/'..'/'recipe.json')

    def test_metadata_export_allowlist(self):
        f=self.api('validate_export')
        f({'schema':1,'passed':False,'inputs':[{'bytes':3,'sha256':'0'*64}]})
        for value in ({'stdout':'vendor text'},{'payload':'x'},{'absolutePath':'D:/private/file'},{'token':'x'}):
            with self.subTest(value=value),self.assertRaises(ValueError):f(value)

if __name__=='__main__':unittest.main(verbosity=2)
