#!/usr/bin/env python3
"""Offline helper acceptance: fake user unit/package/network tools; never connects to a host."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[2] / 'app/src/main/assets/remote/miffan.sh'

STUB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
root = pathlib.Path(os.environ['P6C_FIXTURE'])
settings = json.loads((root / 'settings.json').read_text())
with (root / 'commands.log').open('a') as log: log.write(name + ' ' + ' '.join(sys.argv[1:]) + '\n')
if name == 'sunshine': sys.exit(99)  # Must never be called, including --version.
if name == 'systemctl':
    if 'list-units' in sys.argv or 'list-unit-files' in sys.argv:
        print('app-sunshine-custom.service enabled')
    elif 'is-active' in sys.argv:
        print('inactive'); sys.exit(3)
    elif '--property=ExecStart' in sys.argv:
        print('{ path=/usr/bin/sunshine ; argv[]=/usr/bin/sunshine ; }')
    elif '--property=WorkingDirectory' in sys.argv:
        print(str(root))
    elif 'restart' in sys.argv:
        sys.exit(0)
    else: sys.exit(1)
elif name == 'ss':
    count = root / 'ss-count'
    n = int(count.read_text()) + 1 if count.exists() else 1
    count.write_text(str(n))
    if settings.get('busy') or settings.get('race') and n > 1 or settings.get('restart_race') and n > 2:
        print('UNCONN 0 0 0.0.0.0:47998 0.0.0.0:* users:(("sunshine",pid=42,fd=7))')
    if settings.get('ss_error'): sys.exit(1)
elif name == 'ip':
    print(json.dumps([{'ifname': 'lo', 'addr_info': [{'local': '127.0.0.1'}]},
        {'ifname': 'enp1s0', 'addr_info': [{'local': '192.168.31.61'}, {'local': '203.0.113.2'}]},
        {'ifname': 'tailscale0', 'addr_info': [{'local': '100.64.0.5'}]},
        {'ifname': 'docker0', 'addr_info': [{'local': '172.17.0.1'}]},
        {'ifname': 'br-abcd', 'addr_info': [{'local': '172.18.0.1'}]}]))
elif name == 'openssl':
    sys.stdout.buffer.write(b'fixture-public-certificate-DER')
elif name in ('pacman', 'dpkg-query', 'rpm', 'flatpak'):
    if name != settings.get('package', 'pacman'): sys.exit(1)
    print({'pacman': 'sunshine 2026.1008.155609-1', 'dpkg-query': '2026.1008-debian',
        'rpm': '2026.1008-rpm', 'flatpak': 'Sunshine\n  Version: 2026.1008-flatpak'}[name])
'''

class SunshineHelperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='miffan-p6c-')
        self.root = Path(self.temp.name)
        (self.root / 'bin').mkdir()
        self.config = self.root / 'config/sunshine/sunshine.conf'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('# personal settings\nencoder = nvenc\nlan_encryption_mode = 0\nwan_encryption_mode = 1\nlan_encryption_mode = 1\n')
        for command in ('systemctl', 'ss', 'ip', 'sunshine', 'pacman', 'dpkg-query', 'rpm', 'flatpak', 'openssl'):
            path = self.root / 'bin' / command
            path.write_text(STUB); path.chmod(0o755)
        self.env = dict(os.environ, P6C_FIXTURE=str(self.root), XDG_CONFIG_HOME=str(self.root / 'config'),
            PATH=str(self.root / 'bin') + ':' + os.environ['PATH'])
    def tearDown(self): self.temp.cleanup()
    def call(self, mode='probe', **settings):
        (self.root / 'settings.json').write_text(json.dumps(settings))
        result = subprocess.run(['/bin/sh', str(HELPER), 'sunshine', mode], env=self.env, capture_output=True, text=True, timeout=15)
        self.assertNotIn('\nsunshine ', '\n' + (self.root / 'commands.log').read_text())
        return result.returncode, json.loads(result.stdout)
    def test_probe_is_read_only_and_reports_filtered_addresses(self):
        before = self.config.read_bytes()
        code, probe = self.call()
        self.assertEqual(0, code)
        self.assertTrue(probe['installed']); self.assertFalse(probe['running']); self.assertFalse(probe['active_stream'])
        self.assertEqual('2026.1008.155609-1', probe['version'])
        self.assertEqual(['192.168.31.61', '100.64.0.5'], probe['candidates'])
        self.assertEqual(1, probe['lan_encryption_mode']); self.assertEqual(1, probe['wan_encryption_mode'])
        self.assertEqual(before, self.config.read_bytes())
        self.assertFalse(list(self.config.parent.glob('*.bak-*')))
        self.assertNotIn('restart', (self.root / 'commands.log').read_text())
    def test_versions_use_package_manager_fallbacks(self):
        for package, expected in [('dpkg-query', '2026.1008-debian'), ('rpm', '2026.1008-rpm'), ('flatpak', '2026.1008-flatpak'), ('none', None)]:
            _, probe = self.call(package=package)
            self.assertEqual(expected, probe['version'])
    def test_active_stream_rejects_changes_and_restart(self):
        before = self.config.read_bytes()
        _, probe = self.call(busy=True); self.assertTrue(probe['active_stream'])
        code, result = self.call('enforce-encryption', busy=True)
        self.assertEqual(1, code); self.assertFalse(result['success'])
        self.assertEqual(before, self.config.read_bytes())
        self.assertNotIn('restart', (self.root / 'commands.log').read_text())
    def test_explicit_enforcement_backs_up_preserves_other_settings_and_discovers_unit(self):
        before = self.config.read_bytes()
        code, result = self.call('enforce-encryption')
        self.assertEqual(0, code); self.assertTrue(result['success'])
        backups = list(self.config.parent.glob('sunshine.conf.bak-miffan-*'))
        self.assertEqual(1, len(backups)); self.assertEqual(before, backups[0].read_bytes())
        content = self.config.read_text()
        self.assertIn('encoder = nvenc', content); self.assertIn('# personal settings', content)
        self.assertEqual(1, content.count('lan_encryption_mode'))
        self.assertIn('lan_encryption_mode = 2', content); self.assertIn('wan_encryption_mode = 2', content)
        self.assertIn('restart app-sunshine-custom.service', (self.root / 'commands.log').read_text())
    def test_a_new_stream_after_config_save_prevents_restart(self):
        code, result = self.call('enforce-encryption', restart_race=True)
        self.assertEqual(1, code); self.assertFalse(result['success'])
        self.assertIn('lan_encryption_mode = 2', self.config.read_text())
        self.assertNotIn('restart', (self.root / 'commands.log').read_text())
    def test_activity_race_and_failed_inspection_do_not_replace_config(self):
        before = self.config.read_bytes()
        for settings in (dict(race=True), dict(ss_error=True)):
            count = self.root / 'ss-count'
            if count.exists(): count.unlink()
            code, result = self.call('enforce-encryption', **settings)
            self.assertEqual(1, code); self.assertFalse(result['success'])
            self.assertEqual(before, self.config.read_bytes())
            self.assertNotIn('restart', (self.root / 'commands.log').read_text())

if __name__ == '__main__': unittest.main()
