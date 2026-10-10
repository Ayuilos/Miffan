#!/usr/bin/env python3
"""Offline helper acceptance: fake user unit/package/network tools; never connects to a host."""
import json
import os
import plistlib
import sys
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
if name == 'uname': print(settings.get('platform', 'Linux')); sys.exit(0)
if name == 'sunshine': sys.exit(99)  # Must never be called, including --version.
if name == 'systemctl':
    if 'list-units' in sys.argv or 'list-unit-files' in sys.argv:
        print('app-sunshine-custom.service enabled')
    elif 'is-active' in sys.argv:
        print('active' if settings.get('running') else 'inactive'); sys.exit(0 if settings.get('running') else 3)
    elif '--property=ExecStart' in sys.argv:
        print('{ path=/usr/bin/sunshine ; argv[]=/usr/bin/sunshine ; }')
    elif '--property=WorkingDirectory' in sys.argv:
        print(str(root))
    elif 'restart' in sys.argv or 'start' in sys.argv:
        sys.exit(0)
    else: sys.exit(1)
elif name == 'ss':
    count = root / 'ss-count'
    n = int(count.read_text()) + 1 if count.exists() else 1
    count.write_text(str(n))
    if settings.get('busy') or settings.get('race') and n > 1 or settings.get('restart_race') and n > 2:
        print('UNCONN 0 0 0.0.0.0:47998 0.0.0.0:* users:(("sunshine",pid=42,fd=7))')
    if settings.get('ss_error'): sys.exit(1)
elif name == 'ps':
    if settings.get('ps_error'): sys.exit(1)
    app = root / 'Applications/Sunshine.app/Contents/MacOS/sunshine'
    running = settings.get('running') and (not (root / 'quit').exists() or settings.get('quit_timeout'))
    if '-p' in sys.argv:
        print(str(app) + (' ' + settings['config_arg'] if settings.get('config_arg') else '') + (' lan_encryption_mode=0' if settings.get('override') else ''))
    else:
        print(str(os.getuid() + 1) + ' 99 /Applications/Sunshine.app/Contents/MacOS/sunshine')
        if running: print(str(os.getuid()) + ' 42 ' + str(app))
elif name == 'lsof':
    if '-d' in sys.argv: print('p42\nn' + str(root)); sys.exit(0)
    count = root / 'ss-count'
    n = int(count.read_text()) + 1 if count.exists() else 1
    count.write_text(str(n))
    if settings.get('ss_error'): print('cannot inspect', file=sys.stderr); sys.exit(1)
    if settings.get('empty_udp'): sys.exit(1)
    print('COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME')
    if settings.get('busy') or settings.get('race') and n > 1 or settings.get('restart_race') and n > 2:
        print('sunshine 42 fixture 7u IPv4 0t0 UDP *:' + str(settings.get('port', 47989) + 9))
    # Other users and unrelated ports must not count as this user's Sunshine session.
    print('sunshine 99 other 7u IPv4 0t0 UDP *:47998')
    print('other 100 fixture 7u IPv4 0t0 UDP *:47999')
    if settings.get('unrelated_port'): print('sunshine 42 fixture 7u IPv4 0t0 UDP *:50000')
elif name == 'ifconfig':
    for interface, address in [('lo0', '127.0.0.1'), ('en0', '192.168.31.61'), ('en1', '203.0.113.2'),
        ('utun2', '100.64.0.5'), ('bridge100', '192.168.64.1'), ('awdl0', '10.0.0.2'), ('llw0', '10.0.0.3'),
        ('vmenet0', '192.168.65.1'), ('vmnet1', '192.168.66.1'), ('vboxnet0', '192.168.67.1')]:
        print(interface + ': flags=8863\n    inet ' + address + ' netmask 0xffffff00')
elif name == 'ioreg':
    import plistlib
    state = settings.get('display')
    value = [] if state is None or settings.get('pmset') else [{'IOPowerManagement': {'CurrentPowerState': 0 if state else 4, 'MaxPowerState': 4}}]
    sys.stdout.buffer.write(plistlib.dumps(value))
elif name == 'pmset':
    if settings.get('pmset'): print('IODisplayWrangler 0x123 ' + ('0' if settings.get('display') else '4') + ' 4')
    else: print('Internal failure: Failed to get power state information'); sys.exit(1)
elif name == 'caffeinate':
    if settings.get('wake_error'): sys.exit(1)
    settings['display'] = settings.get('still_asleep', False)
    (root / 'settings.json').write_text(json.dumps(settings))
elif name == 'osascript':
    if settings.get('quit_error'): sys.exit(1)
    (root / 'quit').touch()
elif name == 'open':
    if settings.get('open_error'): sys.exit(1)
    if settings.get('settings_fallback') and 'com.apple.preference.security?' in sys.argv[-1]: sys.exit(1)
elif name == 'brew':
    if settings.get('brew'):
        print(str(root / 'brew') if '--prefix' in sys.argv else 'sunshine 2026.914-brew')
    else: sys.exit(1)
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

class SunshineFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='miffan-p6c-')
        self.root = Path(self.temp.name)
        (self.root / 'bin').mkdir()
        self.config = self.root / 'config/sunshine/sunshine.conf'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('# personal settings\nencoder = nvenc\nlan_encryption_mode = 0\nwan_encryption_mode = 1\nlan_encryption_mode = 1\n')
        for command in ('uname', 'ps', 'lsof', 'ifconfig', 'ioreg', 'pmset', 'caffeinate', 'open', 'osascript', 'brew', 'systemctl', 'ss', 'ip', 'sunshine', 'pacman', 'dpkg-query', 'rpm', 'flatpak', 'openssl'):
            path = self.root / 'bin' / command
            path.write_text(STUB); path.chmod(0o755)
        self.env = dict(os.environ, P6C_FIXTURE=str(self.root), XDG_CONFIG_HOME=str(self.root / 'config'),
            HOME=str(self.root), PATH=str(self.root / 'bin') + ':' + os.environ['PATH'])
    def tearDown(self): self.temp.cleanup()
    def call(self, mode='probe', permission=None, **settings):
        (self.root / 'settings.json').write_text(json.dumps(settings))
        result = subprocess.run(['/bin/sh', str(getattr(self, 'helper', HELPER)), 'sunshine', mode] + ([permission] if permission else []), env=self.env, capture_output=True, text=True, timeout=15)
        self.assertNotIn('\nsunshine ', '\n' + (self.root / 'commands.log').read_text())
        return result.returncode, json.loads(result.stdout)

class SunshineHelperTest(SunshineFixture):
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
        for key in ('display_asleep', 'permissions', 'permissions_from_log'): self.assertIsNone(probe[key])
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

    def test_linux_start_is_idempotent_and_mac_commands_are_unsupported(self):
        code, result = self.call('start', running=True)
        self.assertEqual(0, code); self.assertTrue(result['success'])
        self.assertNotIn('start app-sunshine-custom.service', (self.root / 'commands.log').read_text())
        code, result = self.call('start')
        self.assertEqual(0, code); self.assertTrue(result['success'])
        self.assertIn('start app-sunshine-custom.service', (self.root / 'commands.log').read_text())
        for command, permission in [('wake', None), ('open-settings', 'screen')]:
            code, result = self.call(command, permission=permission)
            self.assertEqual(1, code); self.assertFalse(result['success'])
        commands = (self.root / 'commands.log').read_text()
        self.assertNotIn('caffeinate', commands); self.assertNotIn('open ', commands)

class SunshineMacHelperTest(SunshineFixture):
    # Reuse fixture creation, but Linux-specific acceptance stays in its own test class.
    def setUp(self):
        super().setUp()
        self.config = self.root / '.config/sunshine/sunshine.conf'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('# personal settings\nencoder = videotoolbox\n')
        self.app = self.root / 'Applications/Sunshine.app'
        (self.app / 'Contents').mkdir(parents=True)
        (self.app / 'Contents/Info.plist').write_bytes(plistlib.dumps({'CFBundleShortVersionString': '2026.914-fixture'}))
        # Redirect the fixed system bundle lookup as well: no fixture can see a real installation.
        self.helper = self.root / 'miffan.sh'
        self.helper.write_text(HELPER.read_text().replace("pathlib.Path('/Applications/Sunshine.app')", repr_path(self.root / 'SystemApplications/Sunshine.app')))
        # All state-changing and OS-dependent commands are stubs, including Linux tools.
        self.env['PATH'] = str(self.root / 'bin')
        (self.root / 'bin/python3').symlink_to(sys.executable)
    def call(self, mode='probe', permission=None, **settings):
        return super().call(mode, permission=permission, platform='Darwin', **settings)
    def test_mac_probe_bundle_defaults_addresses_and_permissions_unknown(self):
        before = self.config.read_bytes()
        code, probe = self.call(unrelated_port=True, running=True, display=False)
        self.assertEqual(0, code); self.assertTrue(probe['installed']); self.assertTrue(probe['running'])
        self.assertEqual('2026.914-fixture', probe['version']); self.assertFalse(probe['active_stream'])
        self.assertEqual(0, probe['lan_encryption_mode']); self.assertEqual(1, probe['wan_encryption_mode'])
        self.assertEqual(['192.168.31.61', '100.64.0.5'], probe['candidates'])
        self.assertFalse(probe['display_asleep']); self.assertFalse(probe['permissions_from_log'])
        self.assertEqual({'screen_recording': None, 'accessibility': None}, probe['permissions'])
        self.assertEqual(before, self.config.read_bytes())
        self.assertFalse(list(self.config.parent.glob('*.bak-*')))
        self.assertNotIn('open ', (self.root / 'commands.log').read_text())
    def test_mac_probe_ignores_other_users_and_has_unknown_or_sleeping_display(self):
        _, probe = self.call(); self.assertFalse(probe['running']); self.assertFalse(probe['active_stream']); self.assertIsNone(probe['display_asleep'])
        for pmset in (False, True):
            _, probe = self.call(display=True, pmset=pmset); self.assertTrue(probe['display_asleep'])
            _, probe = self.call(display=False, pmset=pmset); self.assertFalse(probe['display_asleep'])
    def test_mac_permissions_only_explicit_latest_startup_denial(self):
        log = self.config.parent / 'sunshine.log'
        log.write_text('Sunshine version: fixture\nNo screen capture permission!\n')
        _, probe = self.call(); self.assertFalse(probe['permissions']['screen_recording']); self.assertTrue(probe['permissions_from_log'])
        log.write_text(log.read_text() + 'Sunshine version: fixture-new\nNo display devices are active at the moment! Cannot probe the encoders.\n')
        _, probe = self.call(); self.assertIsNone(probe['permissions']['screen_recording']); self.assertFalse(probe['permissions_from_log'])
    def test_mac_enforcement_preserves_settings_and_gracefully_reopens(self):
        before = self.config.read_bytes()
        code, result = self.call('enforce-encryption', running=True)
        self.assertEqual(0, code); self.assertTrue(result['success'])
        backups = list(self.config.parent.glob('*.bak-*')); self.assertEqual(before, backups[0].read_bytes())
        self.assertIn('encoder = videotoolbox', self.config.read_text())
        self.assertIn('lan_encryption_mode = 2', self.config.read_text())
        commands = (self.root / 'commands.log').read_text()
        self.assertIn('osascript -e quit app id', commands); self.assertIn('open -a ' + str(self.app), commands)
        self.assertNotIn('kill', commands)
    def test_mac_busy_unknown_races_and_overrides_reject_enforcement(self):
        before = self.config.read_bytes()
        for settings in (dict(busy=True), dict(ss_error=True), dict(race=True), dict(override=True), dict(ps_error=True)):
            counter = self.root / 'ss-count'
            if counter.exists(): counter.unlink()
            code, result = self.call('enforce-encryption', running=True, **settings)
            self.assertFalse(result['success']); self.assertEqual(1, code)
            self.assertEqual(before, self.config.read_bytes())
            self.assertNotIn('osascript', (self.root / 'commands.log').read_text())
    def test_mac_post_save_race_and_quit_failures_never_reopen_or_kill(self):
        for settings in (dict(restart_race=True), dict(quit_error=True), dict(quit_timeout=True)):
            counter = self.root / 'ss-count'
            if counter.exists(): counter.unlink()
            code, result = self.call('enforce-encryption', running=True, **settings)
            self.assertFalse(result['success']); self.assertEqual(1, code)
            self.assertIn('lan_encryption_mode = 2', self.config.read_text())
            self.assertNotIn('open -a', (self.root / 'commands.log').read_text())
            self.assertNotIn('kill', (self.root / 'commands.log').read_text())
    def test_mac_custom_configuration_and_port_are_used(self):
        custom = self.root / 'custom sunshine.conf'; custom.write_text('port = 50000\n')
        _, probe = self.call(running=True, config_arg='"' + str(custom) + '"', busy=True, port=50000)
        self.assertTrue(probe['active_stream'])
        code, result = self.call('enforce-encryption', running=True, config_arg='"' + str(custom) + '"')
        self.assertTrue(result['success']); self.assertEqual(0, code)
        self.assertIn('lan_encryption_mode = 2', custom.read_text())
        self.assertIn('--args ' + str(custom), (self.root / 'commands.log').read_text())
        self.assertNotIn('lan_encryption_mode', self.config.read_text())
    def test_mac_start_is_idempotent_and_reports_launch_failure(self):
        code, result = self.call('start', running=True); self.assertTrue(result['success']); self.assertEqual(0, code)
        self.assertNotIn('open -a', (self.root / 'commands.log').read_text())
        code, result = self.call('start', ps_error=True); self.assertFalse(result['success']); self.assertEqual(1, code)
        self.assertNotIn('open -a', (self.root / 'commands.log').read_text())
        code, result = self.call('start'); self.assertTrue(result['success']); self.assertEqual(0, code)
        code, result = self.call('start', open_error=True); self.assertFalse(result['success']); self.assertEqual(1, code)
    def test_mac_wake_returns_new_state_or_failure(self):
        for settings, success, asleep in [(dict(display=True), True, False), (dict(display=True, still_asleep=True), True, True), (dict(display=True, wake_error=True), False, True)]:
            code, result = self.call('wake', **settings)
            self.assertEqual(success, result['success']); self.assertEqual(asleep, result['display_asleep'])
    def test_mac_permission_settings_fixed_targets_fallback_and_failure(self):
        for permission, anchor in [('screen', 'Privacy_ScreenCapture'), ('accessibility', 'Privacy_Accessibility')]:
            code, result = self.call('open-settings', permission=permission)
            self.assertEqual(0, code); self.assertTrue(result['success'])
            self.assertIn('com.apple.preference.security?' + anchor, (self.root / 'commands.log').read_text())
        code, result = self.call('open-settings', permission='screen', settings_fallback=True)
        self.assertEqual(0, code); self.assertTrue(result['success'])
        self.assertIn('com.apple.settings.PrivacySecurity.extension', (self.root / 'commands.log').read_text())
        code, result = self.call('open-settings', permission='accessibility', open_error=True)
        self.assertEqual(1, code); self.assertFalse(result['success'])
        code, result = self.call('open-settings', permission='invalid'); self.assertEqual(1, code)
    def test_mac_system_bundle_and_missing_installation(self):
        import shutil
        system = self.root / 'SystemApplications/Sunshine.app'
        system.parent.mkdir()
        self.app.rename(system)
        _, probe = self.call(running=True, empty_udp=True)
        self.assertTrue(probe['installed']); self.assertFalse(probe['active_stream'])
        self.assertEqual('2026.914-fixture', probe['version'])
        shutil.rmtree(system)
        (self.root / 'bin/sunshine').unlink()
        _, probe = self.call()
        self.assertFalse(probe['installed']); self.assertIsNone(probe['version'])
        code, result = self.call('start'); self.assertEqual(1, code); self.assertFalse(result['success'])
    def test_mac_symlink_configuration_is_not_replaced(self):
        target = self.root / 'real.conf'; before = self.config.read_bytes(); self.config.rename(target)
        self.config.symlink_to(target)
        code, result = self.call('enforce-encryption')
        self.assertEqual(1, code); self.assertFalse(result['success']); self.assertTrue(self.config.is_symlink())
        self.assertEqual(before, target.read_bytes())
        self.assertNotIn('open -a', (self.root / 'commands.log').read_text())
    def test_mac_homebrew_version_without_running_binary(self):
        import shutil
        shutil.rmtree(self.app)
        code, probe = self.call(brew=True)
        self.assertEqual(0, code); self.assertTrue(probe['installed']); self.assertEqual('2026.914-brew', probe['version'])
        code, result = self.call('start', brew=True)
        self.assertEqual(1, code); self.assertFalse(result['success'])  # Formula with no bundle cannot use open -a.

def repr_path(path): return 'pathlib.Path(' + repr(str(path)) + ')'

if __name__ == '__main__': unittest.main()
