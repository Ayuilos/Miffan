#!/usr/bin/env python3
"""P7b test transport: fixed UTF-8 bootstrap; scripts/data on stdin, never secrets in argv.
Requires an existing trusted SSH host key. Does not read the private key's contents.
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess

DEFAULT_CONFIG = Path('/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties')
BOOTSTRAP = r"""
$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
$utf8=New-Object Text.UTF8Encoding($false,$true)
[Console]::InputEncoding=$utf8; [Console]::OutputEncoding=$utf8; $OutputEncoding=$utf8
$r=ConvertFrom-Json ([Console]::In.ReadLine())
$global:LASTEXITCODE=0
& ([ScriptBlock]::Create([string]$r.script))
exit $global:LASTEXITCODE
"""

def run(script, stdin='', config=DEFAULT_CONFIG, timeout=90):
    values = dict(line.split('=', 1) for line in config.read_text().splitlines() if line and not line.startswith('#'))
    encoded = base64.b64encode(BOOTSTRAP.encode('utf-16le')).decode('ascii')
    result = subprocess.run([
        'ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8',
        '-p', values['port'], '-i', values['key'], values['user']+'@'+values['host'],
        'powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand '+encoded,
    ], input=(json.dumps({'script': script}, ensure_ascii=False)+'\n'+stdin).encode('utf-8'), capture_output=True, timeout=timeout)
    return {'exit_code': result.returncode, 'stdout': result.stdout.decode('utf-8'), 'stderr': result.stderr.decode('utf-8')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('script', type=Path, help='Trusted, non-secret test script')
    parser.add_argument('--stdin-file', type=Path)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    parser.add_argument('--timeout', type=int, default=90)
    args = parser.parse_args()
    try:
        result = run(args.script.read_text(encoding='utf-8-sig'), args.stdin_file.read_text() if args.stdin_file else '', args.config, args.timeout)
    except subprocess.TimeoutExpired:
        parser.exit(124, 'SSH timed out; inspect remote state before retrying.\n')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(result['exit_code'])

if __name__ == '__main__':
    main()
