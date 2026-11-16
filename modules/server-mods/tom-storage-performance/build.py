"""Compile all182 MIT runtime Java files only. No resources, mod JAR, download or game launch."""
from pathlib import Path
import sys
sys.dont_write_bytecode=True
from build_support import ROOT,parse_cli,verify_source,classpath,jdk_tools,compile_sources,assert_unchanged,read_json,digest,write_json,sha
from classfile import without_debug
def main():
 a=parse_cli(__doc__);origin=verify_source()
 if a.check_source:print('PASS:182 runtime Java and3 original pure regressions verified; no resources required');return 0
 lock,cp=classpath(a);javac,_,toolchain=jdk_tools(a)
 reference=read_json(ROOT/'HISTORICAL-515-ENTRY-HASHES.json');expected={n:h for n,h in reference.items() if n.endswith('.class')}
 diag=read_json(ROOT/'REFERENCE-CLASS-DIAGNOSTICS.json')['debugFreeClassSha256']
 if len(reference)!=515 or len(expected)!=290 or set(diag)!=set(expected):raise ValueError('Complete unchanged historical reference profiles required')
 a.output.mkdir(parents=True);classes=a.output/'classes';classes.mkdir()
 sources=sorted((ROOT/'src/main/java').rglob('*.java'));assert len(sources)==182
 options=compile_sources(javac,sources,cp,a.output,classes)
 payloads={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
 actual={n:digest(b) for n,b in sorted(payloads.items())};missing=sorted(set(expected)-set(actual));added=sorted(set(actual)-set(expected))
 changed=[n for n in sorted(set(expected)&set(actual)) if actual[n]!=expected[n]]
 normalized={n:digest(without_debug(b)) for n,b in payloads.items()};nondebug=[n for n in sorted(set(expected)&set(actual)) if normalized[n]!=diag[n]]
 debug_only=[n for n in changed if n not in nondebug]
 assert_unchanged(lock,cp)
 result={'runtimeJavaSources':182,'generatedClasses':len(actual),'externalCompilerInputs':143,'independentCompleteCompileSucceeded':True,'ownOldTomJarAsApi':False,'dependencyStubsUsed':False,'annotationProcessorsDisabled':True,'missingClasses':missing,'addedClasses':added,'rawChangedClasses':changed,'rawClassByteExactCount':len(set(expected)&set(actual))-len(changed),'rawAll290ClassesExact':not missing and not added and not changed,'debugOnlyChangedClasses':debug_only,'nonDebugChangedClasses':nondebug,'all290NonDebugClassBytesEqualReference':not missing and not added and not nondebug,'debugNormalizationIsDiagnosticOnly':True,'historical515TableSha256':sha(ROOT/'HISTORICAL-515-ENTRY-HASHES.json'),'historical515RawExact':False,'runtimeResourcesProvided':False,'fullModPackaged':False,'gameplayAcceptance':'NOT RUN','pendingExactPngRights':3,'all94PngAndOtherRuntimeResourcesOmitted':True,'jdk':toolchain,'sourceOriginsSha256':sha(ROOT/'SOURCE-ORIGINS.json'),'toolLockSha256':sha(ROOT/'TOOL-INPUTS.lock.json'),'dependencyLockSha256':sha(ROOT/'dependencies.lock.json'),'sourceAndInputsVerifiedBeforeAndAfter':True,'compilerOptions':options[:options.index('-cp')],'networkDuringBuild':False,'classPayloadsCopiedFromReference':False}
 write_json(a.output/'class-hashes.json',actual);write_json(a.output/'class-comparison.json',result);write_json(a.output/'receipt.json',result)
 print('Compiled182 sources, '+str(len(actual))+' classes; raw exact='+str(result['rawClassByteExactCount'])+', debug-only='+str(len(debug_only))+', non-debug differences='+str(len(nondebug))+'; no mod JAR')
 if missing or added or nondebug:raise ValueError('Class payload/reference validation failed; no archive produced')
 return 0
if __name__=='__main__':
 try:raise SystemExit(main())
 except (ValueError,OSError) as error:raise SystemExit(str(error))
