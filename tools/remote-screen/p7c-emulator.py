#!/usr/bin/env python3
"""P7c Registry acceptance only on emulator-5560. Keys transferred by FD, removed in finally.
Use --read-only for initial transport checks. --owned takes controller-verified PID/HWND/title.
Notify Claude before each of the two clipboard writes and any foreground operation.
"""
import argparse
import json
import shlex
import subprocess
import time
from pathlib import Path
from importlib.util import spec_from_file_location, module_from_spec

spec = spec_from_file_location('p7b2', Path(__file__).with_name('p7b2-emulator.py'))
p7b2 = module_from_spec(spec); spec.loader.exec_module(p7b2)
ADB, APP, ROOT = p7b2.ADB, p7b2.APP, p7b2.ROOT
OUT = ROOT / 'app/build/test-output/p7c'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['install', 'run', 'audit'])
    parser.add_argument('--read-only', action='store_true')
    parser.add_argument('--owned', type=Path)
    parser.add_argument('--clipboard-notified', action='store_true')
    parser.add_argument('--helper-clipboard-notified', action='store_true')
    args = parser.parse_args()
    if 'RikkaHub_API_35_16K' not in subprocess.check_output(ADB+['emu','avd','name'], text=True):
        raise RuntimeError('Wrong AVD')
    if args.mode == 'audit':
        command = ['am','instrument','-w','-r','-e','disableAnalytics','true','-e','p7c','true','-e','class',
                   'me.ayuilos.miffan.data.repository.WindowsComputerAcceptanceTest#cleanupAudit',
                   APP+'.test/androidx.test.runner.AndroidJUnitRunner']
        result = subprocess.run(ADB+['shell',shlex.join(command)], capture_output=True,text=True,timeout=60,check=True)
        OUT.mkdir(parents=True,exist_ok=True);(OUT/'audit.txt').write_text(result.stdout)
        print(result.stdout)
        assert 'OK (' in result.stdout and 'FAILURES' not in result.stdout
        return
    if args.mode == 'install':
        p7b2_args = [str(Path(__file__).with_name('p7b2-emulator.py')), 'install']
        subprocess.run(['python3', *p7b2_args], check=True)
        return
    if not args.read_only and not (args.owned and args.clipboard_notified and args.helper_clipboard_notified):
        parser.error('Verify ownership and notify Claude about both clipboard writes before input test')
    config = dict(line.split('=',1) for line in (p7b2.PRIVATE/'p7-test.properties').read_text().splitlines() if line and not line.startswith('#'))
    params = {'p7c':'true','readOnly':str(args.read_only).lower(),'user':config['user'],
              'sshPin':'SHA256:yy3faXP7edMJa4kPE+XEXeXjKMFBxWb66r+qT6Oh4MY',
              'clipboardNotified':str(args.clipboard_notified).lower(), 'helperClipboardNotified':str(args.helper_clipboard_notified).lower()}
    if args.owned:
        owned = json.loads(args.owned.read_text())
        assert owned['marker'].startswith('p7c-owned-') and owned['pid'] > 0 and owned['windowId'] > 0
        params.update({key:str(owned[key]) for key in ('pid','windowId','marker')})
    forwarding = proc = None
    try:
        with open(config['key'],'rb') as key_fd:
            p7b2.shell('run-as', APP, 'sh', '-c', 'umask 077; mkdir -p cache; cat > cache/p7c-test-key', stdin=key_fd)
        forwarding = subprocess.Popen(['ssh','-N','-T','-oBatchMode=yes','-oStrictHostKeyChecking=yes','-oExitOnForwardFailure=yes',
            '-i',config['key'],'-p',config['port'],'-L','127.0.0.1:58022:127.0.0.1:22',config['user']+'@'+config['host']], stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(1)
        if forwarding.poll() is not None:
            raise RuntimeError('Test forwarding failed')
        cmd = ['am','instrument','-w','-r','-e','disableAnalytics','true','-e','class',
               'me.ayuilos.miffan.data.repository.WindowsComputerAcceptanceTest#registryOnOwnedNotepad']
        for key,value in params.items():
            cmd += ['-e',key,value]
        cmd += [APP+'.test/androidx.test.runner.AndroidJUnitRunner']
        proc = subprocess.Popen(ADB+['shell',shlex.join(cmd)], stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        output = proc.communicate(timeout=180)[0]
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/('readonly.txt' if args.read_only else 'input.txt')).write_text(output)
        print(output)
        if proc.returncode or 'OK (' not in output or 'FAILURES' in output:
            raise RuntimeError('Registry acceptance failed')
        for name in (['p7c-readonly.txt'] if args.read_only else ['p7c-registry.txt','p7c-registry.png']):
            subprocess.run(ADB+['pull',f'/sdcard/Android/data/{APP}/files/{name}',str(OUT/name)],check=True)
    finally:
        if proc and proc.poll() is None:
            proc.terminate();proc.wait(timeout=15)
        if forwarding:
            forwarding.terminate();forwarding.wait(timeout=15)
        p7b2.shell('run-as',APP,'rm','-f','cache/p7c-test-key')

if __name__ == '__main__':
    main()
