#!/usr/bin/env python3
"""Offline fetch/checksum validation: simulate cold download, cache hit and corruption.
Run after obtaining the pinned .deps/p6a archives. Never edits that shared cache.
"""
import os
import pathlib
import shutil
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='p6e-fetch-') as directory:
    fixture = pathlib.Path(directory)
    scripts = fixture / 'stream/scripts'
    scripts.mkdir(parents=True)
    shutil.copyfile(root / 'stream/scripts/fetch-sources.sh', scripts / 'fetch-sources.sh')
    mock = fixture / 'bin'
    mock.mkdir()
    curl = mock / 'curl'
    curl.write_text('''#!/usr/bin/env python3
import os, pathlib, shutil, sys
args = sys.argv[1:]
url = args[-1]
name = ('common-c/enet.tar.gz' if '/enet/' in url else
        'common-c/nanors.tar.gz' if '/nanors/' in url else 'common-c.tar.gz')
shutil.copyfile(pathlib.Path(os.environ['P6E_FETCH_ARCHIVES']) / name, args[args.index('--output') + 1])
with open(os.environ['P6E_FETCH_CALLS'], 'a') as log: log.write(name + '\\n')
''')
    curl.chmod(0o755)
    calls = fixture / 'calls'
    env = dict(os.environ, PATH=str(mock) + os.pathsep + os.environ['PATH'],
               P6E_FETCH_ARCHIVES=str(root / '.deps/p6a'), P6E_FETCH_CALLS=str(calls))
    command = ['bash', str(scripts / 'fetch-sources.sh')]
    def fetch():
        return subprocess.run(command, env=env, capture_output=True, text=True)
    cold = fetch()
    assert cold.returncode == 0, cold.stdout + cold.stderr
    assert len(calls.read_text().splitlines()) == 3
    assert not list((fixture / '.deps/p6a').rglob('*.part'))
    cached = fetch()
    assert cached.returncode == 0, cached.stdout + cached.stderr
    assert len(calls.read_text().splitlines()) == 3, 'Cache hit unexpectedly downloaded'
    (fixture / '.deps/p6a/common-c/enet.tar.gz').write_bytes(b'corrupt cache')
    corrupt = fetch()
    assert corrupt.returncode != 0 and 'FAILED' in corrupt.stdout + corrupt.stderr
    (fixture / '.deps/p6a/common-c/enet.tar.gz').unlink()
    # A download that returns corrupt bytes must not be promoted from .part.
    bad = fixture / 'bad-archives/common-c'
    bad.mkdir(parents=True)
    (bad / 'enet.tar.gz').write_bytes(b'corrupt download')
    env['P6E_FETCH_ARCHIVES'] = str(bad.parent)
    corrupt = fetch()
    assert corrupt.returncode != 0 and 'FAILED' in corrupt.stdout + corrupt.stderr
    assert not (fixture / '.deps/p6a/common-c/enet.tar.gz').exists()
print('PASS: cold fetch, verified cache hit, corrupt cache rejection, corrupt download rejection')
