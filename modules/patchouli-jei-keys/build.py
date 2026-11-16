"""Offline from-source compilation and original JEI lifecycle/Mixin contracts."""
import sys
sys.dont_write_bytecode = True
import os
from build_support import ROOT, Context, parser

p = parser(__doc__)
p.add_argument('--baseline', action='store_true', help='Require the original unhandled-key failure; do not package')
args = p.parse_args()
c = Context(args)
classes = c.output/'classes'
if not args.baseline:
    c.compile((ROOT/'src/main/java').rglob('*.java'), classes, 'compile-production')
tests = c.output/'tests'
files = list((ROOT/'test').rglob('*.java'))
if args.baseline:
    files = [p for p in files if p.name in ('BookKeyContractTest.java','ItemStack.java','AccessorKeyMapping.java')]
c.compile(files, tests, 'compile-tests',os.pathsep.join([str(classes),c.cp]))
transformed = c.output/'transformed'
if not args.baseline:
    services = tests/'META-INF/services'
    services.mkdir(parents=True)
    (services/'org.spongepowered.asm.service.IMixinService').write_text('org.motiquies.patchoulijeikeys.OfflineMixinService\n')
    (services/'org.spongepowered.asm.service.IGlobalPropertyService').write_text('org.motiquies.patchoulijeikeys.OfflineProperties\n')
    cp = os.pathsep.join([str(tests),str(classes),str(ROOT/'src/main/resources'),c.cp])
    base=[c.java,'-Dpatchouli.original.jar='+str(c.sdk/c.lock['targets']['patchouli']),
          '-Dpatchouli.minecraft.jar='+str(c.sdk/c.lock['targets']['minecraft'])]
    c.run(base+['-cp',cp,'org.motiquies.patchoulijeikeys.MixinContractTest',transformed],'mixin-weaving')
    c.run(base+['-Djei.absent=true','-cp',cp,'org.motiquies.patchoulijeikeys.MixinContractTest',transformed,'absent'],'absent-jei-weaving')
    c.run([c.java,'-cp',os.pathsep.join([str(tests),str(classes),c.cp]),'org.motiquies.patchoulijeikeys.OptionalJeiContractTest'],'optional-jei-contracts')
cp = os.pathsep.join([str(tests),str(transformed),str(classes),c.cp])
c.run([c.java,'-cp',cp,'org.motiquies.patchoulijeikeys.BookKeyContractTest'],
      'baseline-contracts' if args.baseline else 'candidate-contracts',
      expected_failure='Runtime-ready recipe lookup was not handled after early missing key mappings' if args.baseline else None)
if args.baseline:
    c.check_inputs()
    print('PASS: expected original runtime-ready shortcut failure reproduced; no candidate packaged')
else:
    c.artifact('motiquies-patchouli-jei-keys-1.0.0.jar',classes,ROOT/'src/main/resources')
