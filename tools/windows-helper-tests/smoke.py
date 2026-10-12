#!/usr/bin/env python3
"""P7b smoke tests on the SPEC test machine. Never runs firewall apply. Start/enforce are prechecked to be no-ops.
No clipboard write here. --display opts into the approved read-only one-shot task.
"""
import argparse
import json
from pathlib import Path
import time
from remote import run

PREFIX = "$helper=Join-Path $env:LOCALAPPDATA 'Miffan\\p7b-tests\\miffan.ps1'; "



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--display', action='store_true')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    commands = [('version', ''), ('probe', ''), ('sunshine probe', ''), ('sunshine start', ''),
                ('sunshine enforce-encryption', ''), ('sunshine firewall check', '')]
    if args.display:
        commands.append(('probe display', ''))
    assert all(cmd != 'sunshine firewall apply' for cmd, _ in commands)
    evidence = []
    for cmd, stdin in commands:
        start = time.monotonic()
        precheck = ''
        if cmd in ('sunshine start', 'sunshine enforce-encryption'):
            precheck = ". $helper; $c=Get-SunshineContext; if($c.service.State -ne 'Running' -or (Get-ConfigInteger $c.values 'lan_encryption_mode' 0) -ne 2 -or (Get-ConfigInteger $c.values 'wan_encryption_mode' 1) -ne 2){throw 'Unexpected state; no-op smoke cancelled'}; "
        result = run(PREFIX+precheck+'\n& $helper '+cmd, stdin)
        result.update(command=cmd, seconds=round(time.monotonic()-start, 3))
        evidence.append(result)
        if args.out:
            args.out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2)+'\n')
        if result['exit_code'] != 0 or result['stderr'] or len(result['stdout'].splitlines()) != 1:
            raise SystemExit(json.dumps(result, ensure_ascii=False))
        decoded = json.loads(result['stdout'])
        if isinstance(decoded, dict) and 'success' in decoded:
            assert decoded['success'], decoded
        if cmd in ('sunshine start', 'sunshine enforce-encryption'):
            assert decoded['success'] and decoded['changed'] is False and decoded['backup'] is None
        if cmd == 'sunshine firewall check':
            assert decoded['firewall_model_ok'] and decoded['udp_rule_present'] and decoded['unsafe_rules'] == []
        if cmd == 'probe':
            assert decoded['os'] == 'windows' and decoded['displays'] is None
        print(f'PASS {cmd} ({result["seconds"]}s)')
    print(json.dumps({'success': True, 'commands': len(evidence)}))

if __name__ == '__main__':
    main()
