#!/usr/bin/env python3
"""Run only emulator-5560; pass ignored local credentials without echoing them."""
import argparse
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--desktop', choices=['kde', 'gnome'])
parser.add_argument('--x', type=int)
parser.add_argument('--y', type=int)
parser.add_argument('--text', default='Miffan P5A 中文输入')
args = parser.parse_args()
properties = {}
for line in (root / '.deps/rdp-test.properties').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        properties[key.strip()] = value.strip()
desktop = args.desktop or properties['active']
if desktop != properties['active']:
    raise SystemExit('Requested desktop is not active; start it and update local test properties first.')
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk'))
adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-5560']
for apk in ('rdp/build/outputs/apk/androidTest/debug/rdp-debug-androidTest.apk',):
    subprocess.run(adb + ['install', '-r', str(root / apk)], check=True)
command = adb + ['shell', 'am', 'instrument', '-w', '-r']
# Only forward the selected server; the other server's credentials stay on the host.
for key in ('host', 'active', f'{desktop}.port', f'{desktop}.username', f'{desktop}.password', f'{desktop}.security'):
    command += ['-e', key, properties[key]]
for key, value in [('input.x', args.x), ('input.y', args.y), ('input.text', args.text)]:
    if value is not None:
        command += ['-e', key, str(value)]
command += ['me.rerere.rdp.test/androidx.test.runner.AndroidJUnitRunner']
# adb concatenates arguments into a remote shell command. Quote each argument there.
import shlex
command = adb + ['shell', shlex.join(command[len(adb)+1:])]
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
# Redact even unexpected runner output before storing or displaying it.
output = result.stdout
for key, value in properties.items():
    if 'password' in key or 'username' in key:
        output = output.replace(value, '<redacted>')
log = root / 'rdp/build/test-output'
log.mkdir(parents=True, exist_ok=True)
(log / f'{desktop}-instrumentation.txt').write_text(output)
print(output)
(log / desktop).mkdir(exist_ok=True)
subprocess.run(adb + ['pull', '/sdcard/Android/data/me.rerere.rdp.test/files/rdp-test', str(log / desktop)], check=False)
if result.returncode or 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (' not in output:
    raise SystemExit(1)
