"""Synthetic IO/process boundaries. No real child process, HTTP or vendor input."""
from pathlib import Path
import copy
import hashlib
import io
import json
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile
import hosted_worker as w

HERE=Path(__file__).parent

class FakeProcess:
    def __init__(self,out=b'own output',code=0):
        self.stdout=io.BytesIO(out);self.stderr=io.BytesIO(b'');self.code=code;self.stopped=False
    def poll(self):return self.code
    def wait(self,timeout=None):
        if self.code is None:raise AssertionError('unbounded/failing wait')
        return self.code
    def terminate(self):self.stopped=True;self.code=-1
    def kill(self):self.stopped=True;self.code=-2
    def communicate(self,*a,**k):raise AssertionError('unbounded communicate forbidden')

class WorkerContracts(unittest.TestCase):
    def api(self,n):
        self.assertTrue(callable(getattr(w,n,None)),'missing implementation: '+n)
        return getattr(w,n)
    def test_bounded_process_hashes_without_exporting_body(self):
        f=self.api('bounded_process');p=FakeProcess()
        r=f(['own-fixture'],HERE,10,100,time.monotonic()+20,popen=lambda *a,**k:p)
        self.assertEqual(r['stdoutSha256'],hashlib.sha256(b'own output').hexdigest())
        self.assertEqual(r['exitCode'],0);self.assertNotIn('own output',json.dumps(r))
    def test_overflow_fails_closed_and_stops_owned_process(self):
        f=self.api('bounded_process');p=FakeProcess(b'123456789',None)
        with self.assertRaises(ValueError):f(['own'],HERE,10,4,time.monotonic()+20,popen=lambda *a,**k:p)
        self.assertTrue(p.stopped)
    def test_timeout_stops_owned_process(self):
        f=self.api('bounded_process');p=FakeProcess(code=None)
        with self.assertRaises(ValueError):f(['own'],HERE,0,100,time.monotonic()+20,popen=lambda *a,**k:p)
        self.assertTrue(p.stopped)
    def test_nonzero_rejects_and_retains_digest(self):
        f=self.api('bounded_process');p=FakeProcess(code=1)
        try:f(['own'],HERE,1,100,time.monotonic()+20,popen=lambda *a,**k:p)
        except ValueError as e:self.assertEqual(e.receipt['exitCode'],1)
        else:self.fail('nonzero accepted')
    def test_fresh_root_rejects_existing_even_empty(self):
        f=self.api('create_fresh_root')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            with self.assertRaises(ValueError):f(Path(t))
            p=Path(t)/'new';self.assertEqual(f(p),p);self.assertTrue(p.is_dir())
    def test_jdk_signed_redirect_is_narrow(self):
        f=self.api('jdk_redirect');url='https://release-assets.githubusercontent.com/github-production-release-asset/602574963/11111111-1111-1111-1111-111111111111?sp=r&sig=own'
        self.assertEqual(f(url),url)
        for bad in (url.replace('602574963','123'),url.replace('release-assets.githubusercontent.com','evil.test'),url.replace('https:','http:'),url.split('?')[0]):
            with self.subTest(bad=bad),self.assertRaises(ValueError):f(bad)
    def test_zip_extract_never_writes_before_full_name_validation(self):
        f=self.api('extract_checked')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own.zip';out=Path(t)/'new'
            with zipfile.ZipFile(p,'w') as z:z.writestr('good',b'own');z.writestr('../bad',b'own')
            with self.assertRaises(ValueError):f(p,out)
            self.assertFalse(out.exists())
    def test_official_manifest_main_continuations(self):
        f=self.api('manifest_main')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own.jar'
            with zipfile.ZipFile(p,'w') as z:z.writestr('META-INF/MANIFEST.MF',b'Manifest-Version: 1.0\r\nMain-Class: own.Long\r\n Name\r\n\r\n')
            self.assertEqual(f(p),'own.LongName')
    def test_live_entry_refuses_local_before_process(self):
        f=self.api('require_hosted')
        with self.assertRaises(ValueError):f({},'0'*40)
    def test_active_vanilla_freeze_not_pending(self):
        import hosted_sdk as h
        r=json.loads((HERE/'recipe.json').read_text('utf-8'))
        self.assertEqual(h.validate_recipe(r,execution=True)['pendingVanillaSha256'],[])
        self.assertEqual(r['vanillaSha256Origin']['rootFreezeSha256'],'54585c98ca4ee075e1bcaa146caf123e7e7304457ad2bf2520b5ecd433ec69e2')
    def test_bundle_manifest_binds_exact_embedded_bytes(self):
        f=self.api('bundle_expectations')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own.jar';data=b'own bundled library';digest=hashlib.sha256(data).hexdigest()
            with zipfile.ZipFile(p,'w') as z:
                z.writestr('META-INF/libraries.list',f'{digest}\town:a:1\town/a/1/a-1.jar\n')
                z.writestr('META-INF/libraries/own/a/1/a-1.jar',data)
                z.writestr('META-INF/versions.list',f'{digest}\town\town/server.jar\n')
                z.writestr('META-INF/versions/own/server.jar',data)
            result=f(p);self.assertEqual(result['libraries'][0]['sha256'],digest)
            self.assertEqual(result['server']['bytes'],len(data))
    def test_bundle_manifest_wrong_embedded_digest_rejected(self):
        f=self.api('bundle_expectations')
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t:
            p=Path(t)/'own.jar'
            with zipfile.ZipFile(p,'w') as z:
                z.writestr('META-INF/libraries.list','0'*64+'\town:a:1\town/a.jar\n')
                z.writestr('META-INF/libraries/own/a.jar',b'own')
                z.writestr('META-INF/versions.list','')
            with self.assertRaises(ValueError):f(p)
    def test_expired_global_deadline_never_launches(self):
        f=self.api('bounded_process')
        with self.assertRaises(ValueError):f(['own'],HERE,10,100,0,popen=lambda *a,**k:self.fail('process launched after deadline'))
    def test_unknown_processor_url_observation_fails(self):
        f=self.api('check_processor_urls');r=json.loads((HERE/'recipe.json').read_text('utf-8'))
        good=hashlib.sha256(r['mappingMetadataOrigins'][0].encode()).hexdigest()
        f(r,4,[good])
        for index,values in ((4,['0'*64]),(3,[good])):
            with self.assertRaises(ValueError):f(r,index,values)
    def test_unknown_initial_get_refused_before_transport(self):
        f=self.api('fetch')
        row={'url':'https://evil.test/a','bytes':3,'sha256':hashlib.sha256(b'own').hexdigest()}
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t,patch.object(w,'bounded_process',side_effect=AssertionError('unexpected transport')):
            with self.assertRaises(ValueError):f(row,Path(t)/'new',Path('own-curl'),time.monotonic()+10,Path(t))
    def test_download_metadata_records_actual_hash(self):
        f=self.api('fetch');data=b'own';row={'url':'https://libraries.minecraft.net/own.jar','bytes':3,'sha256':hashlib.sha256(data).hexdigest()}
        calls=[]
        def fake(argv,cwd,*args,capture=None,watch=None,**kw):
            calls.append(argv);Path(argv[argv.index('--output')+1]).write_bytes(data);Path(argv[argv.index('--dump-header')+1]).write_bytes(b'HTTP/1.1 200 OK\r\n\r\n');capture[0]=bytearray(b'200')
            return {'exitCode':0}
        with tempfile.TemporaryDirectory(dir=HERE/'reports') as t,patch.object(w,'bounded_process',side_effect=fake):
            result=f(row,Path(t)/'new',Path('own-curl'),time.monotonic()+10,Path(t))
            self.assertEqual(len(calls),1);self.assertEqual(result[0]['sha256'],row['sha256']);self.assertEqual(result[0]['bytes'],3)

if __name__=='__main__':unittest.main(verbosity=2)
