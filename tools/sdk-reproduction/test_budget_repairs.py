"""Adversarial own clocks, streams and IO; no real child/network/vendor data."""
from pathlib import Path
import io
import json
import tempfile
import unittest
from unittest.mock import patch
import hosted_worker as w

HERE=Path(__file__).parent
class Clock:
    def __init__(self):self.now=0
    def __call__(self):return self.now

class FakeProcess:
    def __init__(self,out=b'own',err=b'',clock=None):
        self.stdout=io.BytesIO(out);self.stderr=io.BytesIO(err);self.clock=clock;self.code=0
    def poll(self):return self.code
    def terminate(self):self.code=-1
    def kill(self):self.code=-2
    def wait(self,timeout=None):return self.code

class BudgetRepairs(unittest.TestCase):
    def test_exit0_after_monitor_expiry_rejected(self):
        clock=Clock();p=FakeProcess()
        def monitor():clock.now=200
        with patch.object(w.time,'monotonic',clock),self.assertRaises(ValueError):w.bounded_process(['own'],HERE,10,100,100,popen=lambda *a,**k:p,watch=monitor)

    def test_expiry_while_draining_exit0_rejected(self):
        clock=Clock();p=FakeProcess()
        class OwnDrainThread:
            def __init__(self,target,args,daemon):self.target=target;self.args=args
            def start(self):self.target(*self.args)
            def join(self,timeout=None):clock.now=200
            def is_alive(self):return False
        with patch.object(w.time,'monotonic',clock),patch.object(w.threading,'Thread',OwnDrainThread),self.assertRaises(ValueError):w.bounded_process(['own'],HERE,10,100,100,popen=lambda *a,**k:p)

    def test_combined_stream_budget_overflow_rejected(self):
        p=FakeProcess(b'123456',b'abcdef')
        with self.assertRaises(ValueError):w.bounded_process(['own'],HERE,10,8,w.time.monotonic()+30,popen=lambda *a,**k:p)

    def test_exact_combined_budget_accepted(self):
        p=FakeProcess(b'1234',b'abcd');r=w.bounded_process(['own'],HERE,10,8,w.time.monotonic()+30,popen=lambda *a,**k:p)
        self.assertEqual(r['stdoutBytes']+r['stderrBytes'],8)

    def test_final_output_verification_expiry_rejected(self):
        self.assertTrue(callable(getattr(w,'finalize_outputs',None)),'missing real final-verification deadline fence')
        clock=Clock();recipe={'expectedOutputs':[{'path':'own.jar','bytes':3,'sha256':'0'*64}],'downloads':[]};report={'outputs':[]}
        def metadata(path):clock.now=101;return {'entryCount':0,'classCount':0,'entries':[]}
        with patch.object(w.time,'monotonic',clock),patch.object(w,'check_file',return_value={'bytes':3,'sha256':'0'*64}),patch.object(w,'zip_metadata',side_effect=metadata),patch.object(w,'load_locked_recipe'),patch.object(w,'resource_fence'),self.assertRaises(ValueError):w.finalize_outputs(recipe,HERE,HERE,'a'*64,'b'*64,100,report)
        self.assertFalse(report.get('passed',False))

    def test_final_metadata_expiry_keeps_uploaded_result_failed(self):
        self.assertTrue(callable(getattr(w,'write_result',None)),'missing final metadata deadline seal')
        with tempfile.TemporaryDirectory(dir=HERE/'repairs-v2') as t:
            path=Path(t)/'result.metadata.json';clock=Clock();report={'schema':1,'passed':False,'rawTargetsMatch':False}
            original=json.dumps
            def serialize(*args,**kwargs):
                text=original(*args,**kwargs);clock.now=101;return text
            with patch.object(w.time,'monotonic',clock),patch.object(w.json,'dumps',side_effect=serialize),self.assertRaises(ValueError):w.write_result(path,report,100,True)
            self.assertFalse(json.loads(path.read_text('utf-8'))['passed'])
    def test_result_expiry_after_publish_is_retained_unaccepted(self):
        with tempfile.TemporaryDirectory(dir=HERE/'repairs-v2') as t:
            path=Path(t)/'result.metadata.json';clock=Clock();report={'schema':1,'passed':False,'rawTargetsMatch':False};original=Path.rename
            def rename(source,target):
                value=original(source,target)
                if Path(target)==path:clock.now=101
                return value
            with patch.object(w.time,'monotonic',clock),patch.object(Path,'rename',new=rename),self.assertRaises(ValueError):w.write_result(path,report,100,True)
            self.assertFalse(json.loads(path.read_text('utf-8'))['passed'])
            self.assertTrue((path.parent/'result.unaccepted.metadata.json').exists())
    def test_timely_result_seal_is_success(self):
        with tempfile.TemporaryDirectory(dir=HERE/'repairs-v2') as t:
            path=Path(t)/'result.metadata.json';report={'schema':1,'passed':False,'rawTargetsMatch':False}
            with patch.object(w.time,'monotonic',return_value=0):w.write_result(path,report,100,True)
            self.assertTrue(json.loads(path.read_text('utf-8'))['passed']);self.assertTrue(report['rawTargetsMatch'])
    def test_existing_result_is_preserved(self):
        with tempfile.TemporaryDirectory(dir=HERE/'repairs-v2') as t:
            path=Path(t)/'result.metadata.json';path.write_bytes(b'own prior evidence')
            with self.assertRaises(ValueError):w.write_result(path,{'schema':1,'passed':False},100,True)
            self.assertEqual(path.read_bytes(),b'own prior evidence')
    def test_actual_run_entry_final_verification_expiry_persists_failure(self):
        # Only the real run/finalization wiring is exercised; all hosted/tool IO
        # callbacks are own fixtures, with zero processor/JVM invocation.
        from types import SimpleNamespace
        recipe=json.loads((HERE/'recipe.json').read_text('utf-8'));clock=Clock()
        with tempfile.TemporaryDirectory(dir=HERE/'repairs-v2') as t:
            parent=Path(t);root=parent/'sdk-fresh-a'
            env={'RUNNER_TEMP':str(parent),'ImageOS':'win22','ImageVersion':recipe['platform']['imageVersion'],'SDK_PYTHON_VERSION':recipe['platform']['pythonVersion'],'GITHUB_RUN_ID':'own1','GITHUB_RUN_ATTEMPT':'1','SDK_FRESH_JOB':'fresh-a'}
            def metadata(path):clock.now=3601;return {'entryCount':0,'classCount':0,'entries':[]}
            with patch.dict(w.os.environ,env),patch.object(w.time,'monotonic',clock),patch.object(w,'require_hosted'),patch.object(w,'check_platform'),patch.object(w,'sha256_file',return_value='0'*64),patch.object(w.shutil,'disk_usage',return_value=SimpleNamespace(free=13*1024**3)),patch.object(w,'prepare_inputs',return_value=parent/'own-java'),patch.object(w,'bundle_expectations',return_value={}),patch.object(w,'processor_commands',return_value=[]),patch.object(w,'check_file',return_value={'bytes':3,'sha256':'0'*64}),patch.object(w,'zip_metadata',side_effect=metadata),self.assertRaises(ValueError):w.run(recipe,root,HERE,'a'*64,'b'*64,'c'*40)
            result=json.loads((root/'result.metadata.json').read_text('utf-8'))
            self.assertFalse(result['passed']);self.assertFalse(result['rawTargetsMatch']);self.assertFalse(result['reproductionAccepted'])

if __name__=='__main__':unittest.main(verbosity=2)
