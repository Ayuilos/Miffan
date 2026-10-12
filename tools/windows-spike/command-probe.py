#!/usr/bin/env python3
"""Read-only exec/stdin/UTF-8/exit-code and SFTP path probes; no shell changes."""
import json
from pathlib import Path
import subprocess
from ssh_ps import command

ssh = ['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
       '-i', str(Path.home() / '.ssh/id_ed25519_new'), 'ekk@192.168.31.61']
prefix = "$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false); $OutputEncoding=[Console]::OutputEncoding; "
cases = [
    ('cmd-pwd', 'pwd', b''),
    ('cmd-printf', 'printf %s "$HOME"', b''),
    ('cmd-cd', 'cd', b''),
    ('encoded-output-exit', command('Write-Output "中文测试"; exit 7'), b''),
    ('encoded-stdin-exit', command('$v=[Console]::In.ReadToEnd(); Write-Output ("stdin:"+$v); exit 9'), '中文测试\n'.encode()),
    # Added after the initial run exposed CP936 stdin decoding. Not yet verified remotely.
    ('encoded-utf8-stdin-exit', command('[Console]::InputEncoding=New-Object System.Text.UTF8Encoding($false); $v=[Console]::In.ReadToEnd(); Write-Output ("stdin:"+$v); exit 9'), '中文测试\n'.encode()),
    ('stdin-script-exit', 'powershell.exe -NoLogo -NoProfile -NonInteractive -Command -',
     (prefix+'Write-Output "中文测试"\nexit 11\n').encode()),
    ('shell-registry', command("Get-ItemProperty 'HKLM:\\SOFTWARE\\OpenSSH' | Select-Object DefaultShell,DefaultShellCommandOption | ConvertTo-Json"), b''),
    ('wsl-status', command('wsl.exe --status'), b''),
]
for name, remote, data in cases:
    try:
        p = subprocess.run(ssh+[remote], input=data, capture_output=True, timeout=30)
        print(json.dumps(dict(case=name, exit=p.returncode, stdout=p.stdout.decode('utf-8', errors='replace'),
                             stdout_hex=p.stdout.hex(), stderr=p.stderr.decode('utf-8',errors='replace')[:1000]), ensure_ascii=False))
    except subprocess.TimeoutExpired:
        print(json.dumps(dict(case=name, timeout=True)))
for target in ['C:/Users/Ekk', '/C:/Users/Ekk']:
    p = subprocess.run(['sftp','-b','-','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),
                        'ekk@192.168.31.61'], input=('pwd\ncd '+target+'\npwd\n').encode(), capture_output=True,timeout=20)
    print(json.dumps(dict(case='sftp-'+target,exit=p.returncode,stdout=p.stdout.decode(),stderr=p.stderr.decode())))
