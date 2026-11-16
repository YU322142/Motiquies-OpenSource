"""Optional reviewed original pure helper regressions; never menu/game/bootstrap tests."""
import os,subprocess,sys
sys.dont_write_bytecode=True
from build_support import ROOT,parse_cli,verify_source,classpath,jdk_tools,compile_sources,assert_unchanged,read_json,sha,write_json
def main():
 a=parse_cli(__doc__);verify_source()
 if a.check_source:print('Original pure test sources verified');return
 if a.class_build is None:raise ValueError('--class-build must name a complete source-only build directory')
 lock,cp=classpath(a);javac,java,toolchain=jdk_tools(a)
 prior=read_json(a.class_build/'receipt.json');hashes=read_json(a.class_build/'class-hashes.json');classes=a.class_build/'classes'
 if prior['sourceOriginsSha256']!=sha(ROOT/'SOURCE-ORIGINS.json') or prior['toolLockSha256']!=sha(ROOT/'TOOL-INPUTS.lock.json'):raise ValueError('Class build source/tool context mismatch')
 actual={p.relative_to(classes).as_posix() for p in classes.rglob('*') if p.is_file()}
 if len(hashes)!=290 or actual!=set(hashes):raise ValueError('Complete290 class build required')
 for n,digest in hashes.items():
  if sha(classes/n)!=digest:raise ValueError('Class build payload changed')
 tests=read_json(ROOT/'TEST-ORIGINS.json')['files'];assert len(tests)==3
 a.output.mkdir(parents=True);testclasses=a.output/'classes';testclasses.mkdir()
 compile_sources(javac,[ROOT/e['path'] for e in tests],[classes,*cp],a.output,testclasses)
 env=os.environ.copy()
 for name in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(name,None)
 rows=[]
 for e in tests:
  main=e['path'].split('tests/original/',1)[1][:-5].replace('/','.')
  run=subprocess.run([str(java),'-ea','-cp',os.pathsep.join(map(str,[testclasses,classes,*cp])),main],cwd=a.output,env=env,capture_output=True,timeout=30)
  log=a.output/(main.rsplit('.',1)[-1]+'.log');log.write_bytes(run.stdout+run.stderr)
  rows.append({'main':main,'exitCode':run.returncode,'status':'PASS' if run.returncode==0 else 'FAIL','testSourceSha256':e['sha256'],'logSha256':sha(log)})
 assert_unchanged(lock,cp)
 receipt={'pureOriginalTests':rows,'allPassed':all(r['exitCode']==0 for r in rows),'sourceAndInputsVerified':True,'runtimeOutputClassesModified':False,'menuOrGameBootstrapRun':False,'gameplayAcceptance':'NOT RUN','fullModPackaged':False,'historical515RawExact':False,'scope':'Generic counted planner, inventory de-dup selector and null-Level worker/delegate test branches only'}
 write_json(a.output/'receipt.json',receipt)
 if not receipt['allPassed']:raise ValueError('An original pure regression failed')
 print('PASS:3 original pure helper regressions; gameplay acceptance NOT RUN')
if __name__=='__main__':
 try:main()
 except (ValueError,OSError) as error:raise SystemExit(str(error))
