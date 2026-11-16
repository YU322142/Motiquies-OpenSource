"""Offline from-source DH/Iris candidate build, original boundary tests and synthetic ZIP tests."""
import sys
sys.dont_write_bytecode = True
from build_support import ROOT, Context, parser

c = Context(parser(__doc__).parse_args())
def harness(mode, production=False):
    command = [sys.executable,ROOT/'test_b10.py',mode,'--sdk-root',c.sdk,'--java-home',c.java.parent.parent,
               '--output',c.output/('boundary-'+mode+('-production' if production else ''))]
    if production:
        command += ['--production-classes',c.output/'classes']
    c.run(command,'boundary-'+mode+('-production' if production else ''))
harness('red')
harness('green')
classes = c.output/'classes'
c.compile((ROOT/'src').rglob('*.java'),classes,'compile-production')
harness('green',True)
c.run([sys.executable,ROOT/'test_local_adapter.py','--output',c.output/'adapter-tests'],'adapter-tests')
c.artifact('motiquies-shader-compat-b10-0.0.1.jar',classes,ROOT/'resources','LICENSE-Motiquies.txt')
