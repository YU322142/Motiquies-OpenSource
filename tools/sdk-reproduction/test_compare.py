"""Own digest-only receipts, no vendor payload or SDK."""
import copy
import json
from pathlib import Path
import unittest
import compare_runs as c

class CompareContracts(unittest.TestCase):
    def rows(self):
        r=json.loads((Path(__file__).parent/'recipe.json').read_text('utf-8'))
        a={'sourceCommit':'c'*40,'schema':1,'job':'fresh-a','runId':'123','runAttempt':'1','passed':True,'rawTargetsMatch':True,'recipeSha256':'a'*64,'sourcePinsSha256':'b'*64,'outputs':[{'name':x['path'],'bytes':x['bytes'],'sha256':x['sha256']} for x in r['expectedOutputs']]}
        b=copy.deepcopy(a);b['job']='fresh-b';return r,a,b
    def call(self,r,a,b):
        self.assertTrue(callable(getattr(c,'compare',None)),'missing comparison implementation')
        return c.compare(r,a,b)
    def test_two_independent_labels_match_all_seven(self):
        r,a,b=self.rows();v=self.call(r,a,b);self.assertTrue(v['rawTargetsMatch']);self.assertFalse(v['globalRuntimeValidated'])
    def test_same_label_not_two_cold_builds(self):
        r,a,b=self.rows();b['job']='fresh-a'
        with self.assertRaises(ValueError):self.call(r,a,b)
    def test_missing_different_or_extra_target_fails(self):
        for change in ('missing','digest','extra'):
            r,a,b=self.rows()
            if change=='missing':b['outputs'].pop()
            elif change=='digest':b['outputs'][0]['sha256']='0'*64
            else:b['outputs'].append(copy.deepcopy(b['outputs'][0]))
            with self.subTest(change=change),self.assertRaises(ValueError):self.call(r,a,b)
    def test_recipe_and_workflow_run_binding_required(self):
        for key in ('recipeSha256','sourcePinsSha256','sourceCommit','runId','runAttempt'):
            r,a,b=self.rows();b[key]='different'
            with self.subTest(key=key),self.assertRaises(ValueError):self.call(r,a,b)

if __name__=='__main__':unittest.main(verbosity=2)
