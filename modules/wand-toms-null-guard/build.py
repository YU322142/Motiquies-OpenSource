"""Public standard entry to the complete original bytecode red/green producer."""
import sys
sys.dont_write_bytecode=True
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('build_and_test.py')),run_name='__main__')
