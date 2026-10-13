#!/usr/bin/env python3
"""Explicit P7b2 acceptance controller. Only emulator-5560; never firewall apply.
Private key is passed as a file descriptor to adb stdin, never inspected or logged.
Notify Claude before stream modes, and before the single clipboard write in input mode.
"""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import shlex
import socket
import ssl
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = Path('/Users/chenxiansheng/miffan/.deps/windows-spike')
APP = 'me.ayuilos.miffan.app.debug'
SERIAL = 'emulator-5560'
PIN = '8edb025280591442b733c675de6dafb1b011b0f69abddd1f18ed178b14bf512d'
ADB = [str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'), '-s', SERIAL]
OUT = ROOT / 'app/build/test-output/p7b2'


def shell(*args, **kwargs):
    return subprocess.run(ADB + ['shell', shlex.join(args)], check=True, **kwargs)


def instrument(method, params):
    cmd = ['am', 'instrument', '-w', '-r', '-e', 'disableAnalytics', 'true', '-e', 'p7b2', 'true',
           '-e', 'class', 'me.ayuilos.miffan.data.repository.WindowsScreenAcceptanceTest#' + method]
    for key, value in params.items():
        cmd += ['-e', key, value]
    cmd += [APP + '.test/androidx.test.runner.AndroidJUnitRunner']
    return subprocess.Popen(ADB + ['shell', shlex.join(cmd)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def admin(port, path, data=None):
    # Password stays in memory and HTTP Authorization; neither PIN nor password enters argv/output.
    creds = json.loads((PRIVATE / 'admin.json').read_text())
    conn = http.client.HTTPSConnection('127.0.0.1', port, timeout=10, context=ssl._create_unverified_context())
    conn.connect()
    if hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest() != PIN:
        conn.close()
        raise RuntimeError('Sunshine admin certificate mismatch')
    headers = {'Content-Type': 'application/json', 'Authorization': 'Basic ' + base64.b64encode(
        (creds['username'] + ':' + creds['password']).encode()).decode()}
    conn.request('GET' if data is None else 'POST', path, None if data is None else json.dumps(data), headers)
    response = conn.getresponse()
    body = response.read()
    conn.close()
    if response.status != 200:
        raise RuntimeError('Admin HTTP failure: ' + str(response.status))
    return json.loads(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['install', 'helper', 'pair', 'screen', 'input', 'cleanup'])
    parser.add_argument('--stream-notified', action='store_true')
    parser.add_argument('--clipboard-notified', action='store_true')
    parser.add_argument('--foreground-confirmed', action='store_true')
    args = parser.parse_args()
    if args.mode in ('screen', 'input') and not args.stream_notified:
        parser.error('Notify Claude first, then explicitly pass --stream-notified')
    if args.mode == 'input' and not (args.clipboard_notified and args.foreground_confirmed):
        parser.error('Notify Claude about the clipboard and obtain foreground confirmation first')
    avd = subprocess.check_output(ADB + ['emu', 'avd', 'name'], text=True)
    if 'RikkaHub_API_35_16K' not in avd:
        raise RuntimeError('Wrong AVD; no test operation performed')
    OUT.mkdir(parents=True, exist_ok=True)
    if args.mode == 'install':
        apk = ROOT / 'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
        if not apk.exists():
            apk = ROOT / 'app/build/outputs/apk/debug/app-universal-debug.apk'
        subprocess.run(ADB + ['install', '-r', str(apk)], check=True)
        subprocess.run(ADB + ['install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')], check=True)
        shell('cmd', 'package', 'compile', '-m', 'speed', '-f', APP)
        return
    values = dict(line.split('=', 1) for line in (PRIVATE / 'p7-test.properties').read_text().splitlines()
                  if line and not line.startswith('#'))
    method = {'helper': 'installAndPair', 'pair': 'installAndPair', 'screen': 'receiveScreen', 'input': 'inputToOwnedNotepad', 'cleanup': 'cleanup'}[args.mode]
    params = {'streamNotified': str(args.stream_notified).lower(), 'clipboardNotified': str(args.clipboard_notified).lower(),
              'foregroundConfirmed': str(args.foreground_confirmed).lower()}
    forwarding = None
    proc = None
    secret_pin = None
    try:
        if args.mode in ('pair', 'helper'):
            # Direct FD transfer: no read(), read_text(), shell substitution or output of key bytes.
            with open(values['key'], 'rb') as key_fd:
                shell('run-as', APP, 'sh', '-c', 'umask 077; mkdir -p cache; cat > cache/p7b2-test-key', stdin=key_fd)
            params.update(host='10.0.2.2', port='58022', user=values['user'], sshPin='SHA256:yy3faXP7edMJa4kPE+XEXeXjKMFBxWb66r+qT6Oh4MY')
            params['helperOnly'] = str(args.mode == 'helper').lower()
            sock = socket.socket(); sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]; sock.close()
        if args.mode in ('helper', 'pair', 'screen', 'input'):
            forwards = ['-L', '127.0.0.1:58022:127.0.0.1:22']
            if args.mode == 'pair':
                forwards += ['-L', f'127.0.0.1:{port}:127.0.0.1:47990']
            forwarding = subprocess.Popen(['ssh', '-N', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'ExitOnForwardFailure=yes', '-i', values['key'], '-p', values['port'],
                *forwards, values['user'] + '@' + values['host']],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1)
            if forwarding.poll() is not None:
                raise RuntimeError('Test SSH forwarding failed; check loopback port 58022')
        proc = instrument(method, params)
        if args.mode == 'pair':
            for _ in range(120):
                if proc.poll() is not None:
                    break
                pin_result = subprocess.run(ADB + ['shell', 'run-as', APP, 'cat', 'cache/p7b2-pin'], capture_output=True, text=True)
                if pin_result.returncode == 0 and len(pin_result.stdout.strip()) == 4:
                    secret_pin = pin_result.stdout.strip()
                    own = [item for item in admin(port, '/api/pin').get('pairings', []) if item.get('name') == 'miffan-p7b2-emulator']
                    if len(own) == 1:
                        if not admin(port, '/api/pin', {'pairing_id': own[0]['id'], 'pin': secret_pin, 'name': 'miffan-p7b2-emulator'}).get('status'):
                            raise RuntimeError('Test pairing approval refused')
                        break
                time.sleep(1)
        output = proc.communicate(timeout=180)[0]
        if secret_pin:
            output = output.replace(secret_pin, '<redacted>')
        (OUT / (args.mode + '.txt')).write_text(output)
        print(output)
        if proc.returncode or 'OK (' not in output or 'FAILURES' in output:
            raise RuntimeError('Instrumentation failed; see test-output')
        stem = {'helper': 'p7b2-helper', 'pair': 'p7b2-pair', 'screen': 'p7b2-screen', 'input': 'p7b2-input'}.get(args.mode)
        if stem:
            for suffix in (['txt'] if args.mode in ('helper', 'pair') else ['txt', 'png']):
                subprocess.run(ADB + ['pull', f'/sdcard/Android/data/{APP}/files/{stem}.{suffix}', str(OUT / (stem + '.' + suffix))], check=True)
    finally:
        if proc and proc.poll() is None:
            proc.terminate(); proc.wait(timeout=15)
        if forwarding:
            forwarding.terminate(); forwarding.wait(timeout=15)
        if args.mode in ('pair', 'helper'):
            shell('run-as', APP, 'rm', '-f', 'cache/p7b2-test-key', 'cache/p7b2-pin')


if __name__ == '__main__':
    main()
