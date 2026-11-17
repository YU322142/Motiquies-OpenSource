"""Compare two independently obtained metadata receipts; never load an SDK."""
import argparse
import json
from pathlib import Path
from hosted_sdk import require

def compare(recipe,a,b):
    require({a['job'],b['job']}=={'fresh-a','fresh-b'},'two distinct fresh job labels required')
    for key in ('recipeSha256','sourcePinsSha256','sourceCommit','runId','runAttempt'):require(a[key]==b[key],'different source/workflow run identity')
    expected={(p['path'],p['bytes'],p['sha256']) for p in recipe['expectedOutputs']}
    require(len(expected)==7,'seven reference targets required')
    for r in (a,b):
        require(r['passed'] is True and r['rawTargetsMatch'] is True,'cold job did not pass')
        rows=r['outputs'];require(len(rows)==7 and {(p['name'],p['bytes'],p['sha256']) for p in rows}==expected,'raw SDK targets differ')
    return {'schema':1,'rawTargetsMatch':True,'reproductionAccepted':False,'globalRuntimeValidated':False,'runId':a['runId'],'recipeSha256':a['recipeSha256'],'sourcePinsSha256':a['sourcePinsSha256']}

def main():
    p=argparse.ArgumentParser();p.add_argument('recipe');p.add_argument('fresh_a');p.add_argument('fresh_b');args=p.parse_args()
    try:print(json.dumps(compare(*(json.loads(Path(x).read_text('utf-8')) for x in (args.recipe,args.fresh_a,args.fresh_b))),sort_keys=True))
    except (ValueError,KeyError,OSError):raise SystemExit('digest comparison rejected')

if __name__=='__main__':main()
