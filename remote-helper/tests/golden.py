"""Shared offline JSON golden comparison; UPDATE_FIXTURES=1 explicitly refreshes samples."""
import json
import os
from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / 'fixtures'


def golden(name, actual):
    path = FIXTURES / 'sh' / (name + '.json')
    if os.environ.get('UPDATE_FIXTURES') == '1':
        path.write_text(json.dumps(actual, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n')
    expected = json.loads(path.read_text())
    assert actual == expected, (f'{path}: helper output changed; review protocol and use UPDATE_FIXTURES=1\n'
        f'actual:   {json.dumps(actual, ensure_ascii=False, sort_keys=True)}\nexpected: {json.dumps(expected, ensure_ascii=False, sort_keys=True)}')


def rdp_golden(name, actual):
    # These values are deliberately randomized/account-specific; validate before substituting.
    actual = dict(actual)
    if actual.get('port') is not None:
        assert 20000 <= actual['port'] < 60000
        actual['port'] = 45891
    if actual.get('username') is not None:
        assert actual['username'] == 'miffan-' + str(os.getuid())
        actual['username'] = 'miffan-1001'
    if actual.get('certificate_sha256') is not None:
        assert len(actual['certificate_sha256']) == 64
        actual['certificate_sha256'] = 'ab' * 32
    golden(name, actual)
