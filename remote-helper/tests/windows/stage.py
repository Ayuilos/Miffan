#!/usr/bin/env python3
"""Stage only P7b script/public fixtures over SFTP, then run offline PS 5.1 tests."""
import json
from pathlib import Path
import subprocess
from remote import DEFAULT_CONFIG, run

ROOT = Path(__file__).resolve().parents[3]
FILES = {
    **{'fixtures/' + path.name: path for path in (ROOT / 'remote-helper/fixtures/ps1').glob('*.json')},
    'miffan.ps1': ROOT / 'app/build/generated/remoteHelper/remote/miffan.ps1',
    **{name: Path(__file__).with_name(name) for name in ('Test-Helper.ps1', 'certificate.pem', 'certificate.sha256')},
}


def main():
    subprocess.run([str(ROOT / "gradlew"), ":app:assembleRemoteHelper", "-q"], cwd=ROOT, check=True)
    result = run("$dir=Join-Path $env:LOCALAPPDATA 'Miffan\\p7b-tests'; $null=New-Item -ItemType Directory -Path $dir -Force; $null=New-Item -ItemType Directory -Path (Join-Path $dir 'fixtures') -Force; [Console]::Out.WriteLine($dir)")
    if result['exit_code']:
        raise SystemExit(json.dumps(result, ensure_ascii=False))
    remote_dir = '/' + result['stdout'].strip().replace('\\', '/').replace('//', '/')
    config = dict(line.split('=', 1) for line in DEFAULT_CONFIG.read_text().splitlines() if line and not line.startswith('#'))
    batch = ''.join(f'put "{path}" "{remote_dir}/{name}"\n' for name, path in FILES.items())
    subprocess.run(['sftp', '-oBatchMode=yes', '-oStrictHostKeyChecking=yes', '-P', config['port'], '-i', config['key'], '-b', '-', config['user']+'@'+config['host']], input=batch.encode(), check=True, timeout=60)
    script = r"""
$dir=Join-Path $env:LOCALAPPDATA 'Miffan\p7b-tests'
$tokens=$null;$errors=$null
$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $dir 'miffan.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){$errors | ForEach-Object {[Console]::Out.WriteLine($_.ToString())};exit 1}
& (Join-Path $dir 'Test-Helper.ps1') -HelperPath (Join-Path $dir 'miffan.ps1') -FixturePath (Join-Path $dir 'fixtures')
"""
    result = run(script)
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(result['exit_code'])

if __name__ == '__main__':
    main()
