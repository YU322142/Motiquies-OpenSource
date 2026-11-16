"""Public standard build entry; preserves build_b10.py as the original named entry."""
import sys
sys.dont_write_bytecode = True
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().with_name('build_b10.py')), run_name='__main__')
