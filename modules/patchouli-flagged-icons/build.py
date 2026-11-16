"""Offline from-source compilation and original parser/Mixin contracts."""
import sys
sys.dont_write_bytecode = True
import os
from build_support import ROOT, Context, parser

p = parser(__doc__)
p.add_argument('--baseline', action='store_true', help='Require the original disabled-entry failure; do not package')
args = p.parse_args()
c = Context(args)
tests = c.output/'tests'
c.compile((ROOT/'test').rglob('*.java'), tests, 'compile-tests')
classes = c.output/'classes'
if not args.baseline:
    c.compile((ROOT/'src/main/java').rglob('*.java'), classes, 'compile-production')
    services = tests/'META-INF/services'
    services.mkdir(parents=True)
    (services/'org.spongepowered.asm.service.IMixinService').write_text('org.motiquies.patchouliicons.OfflineMixinService\n')
    (services/'org.spongepowered.asm.service.IGlobalPropertyService').write_text('org.motiquies.patchouliicons.OfflineProperties\n')
    cp = os.pathsep.join([str(tests),str(classes),str(ROOT/'src/main/resources'),c.cp])
    c.run([c.java,'-Dpatchouli.original.jar='+str(c.sdk/c.lock['targets']['patchouli']),
           '-Dpatchouli.minecraft.jar='+str(c.sdk/c.lock['targets']['minecraft']),'-cp',cp,
           'org.motiquies.patchouliicons.MixinContractTest',c.output/'transformed/BookContentsBuilder.class'],'mixin-weaving')
cp = os.pathsep.join([str(tests),str(classes),c.cp])
c.run([c.java,'-cp',cp,'org.motiquies.patchouliicons.IconContractTest','baseline' if args.baseline else 'candidate'],
      'baseline-contracts' if args.baseline else 'candidate-contracts',
      expected_failure='Disabled optional entry parsed absent item before its flag was applied' if args.baseline else None)
if args.baseline:
    c.check_inputs()
    print('PASS: expected original disabled-entry baseline failure reproduced; no candidate packaged')
else:
    c.artifact('motiquies-patchouli-flagged-icons-1.0.0.jar',classes,ROOT/'src/main/resources')
