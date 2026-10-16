#!/usr/bin/env python3
"""Reject new warning/error diagnostics while preserving the byte-identical migration baseline."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
result = subprocess.run([sys.argv[1], '-S', 'warning', '--format=json', 'remote-helper/src/miffan.sh'],
                        cwd=root, capture_output=True, text=True, check=False)
if result.returncode not in (0, 1):
    raise SystemExit(result.stderr or f'ShellCheck failed: {result.returncode}')
actual = json.loads(result.stdout)
expected = json.loads(Path(__file__).with_name('shellcheck-baseline.json').read_text())
if actual != expected:
    print(result.stdout)
    raise SystemExit('ShellCheck diagnostics differ from the three pre-migration SC2155 warnings; review all changes')
print(f'PASS: ShellCheck v0.10.0, {len(actual)} existing warnings, no new warnings/errors')
