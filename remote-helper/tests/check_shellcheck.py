#!/usr/bin/env python3
"""Reject new warning/error diagnostics; known ones are matched by kind, so edits that move lines still pass."""
from collections import Counter
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
found = Counter(f"SC{d['code']} {d['level']}" for d in actual)
allowed = Counter(json.loads(Path(__file__).with_name('shellcheck-baseline.json').read_text()))
new = found - allowed
if new:
    print(json.dumps([d for d in actual if f"SC{d['code']} {d['level']}" in new], indent=1))
    raise SystemExit(f'New ShellCheck diagnostics: {dict(new)}')
fixed = allowed - found
if fixed:
    print(f'Note: {dict(fixed)} no longer reported; lower the counts in shellcheck-baseline.json')
print(f'PASS: ShellCheck, {len(actual)} known warnings, no new warnings/errors')
