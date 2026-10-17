#!/usr/bin/env python3
"""Offline helper acceptance: fake user unit/package/network tools; never connects to a host."""
import hashlib
import json
import os
import plistlib
import sys
from pathlib import Path
import subprocess
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))  # Trusted local support, also under python -I.
from golden import golden

HELPER = Path(__file__).resolve().parents[1] / 'src/miffan.sh'

STUB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
root = pathlib.Path(os.environ['P6C_FIXTURE'])
settings = json.loads((root / 'settings.json').read_text())
with (root / 'commands.log').open('a') as log: log.write(name + ' ' + ' '.join(sys.argv[1:]) + '\n')
if name == 'uname': print(settings.get('platform', 'Linux')); sys.exit(0)
if name == 'cp':
    if settings.get('copy_error'): sys.exit(1)
    import subprocess
    assert all(pathlib.Path(p).is_relative_to(root) for p in sys.argv[2:])
    sys.exit(subprocess.run(['/bin/cp']+sys.argv[1:]).returncode)
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
    elif 'stop' in sys.argv:
        if settings.get('stop_error'): sys.exit(1)
        if not settings.get('stop_timeout'): settings['running'] = False
        (root / 'settings.json').write_text(json.dumps(settings))
        state = root / 'config/sunshine/sunshine_state.json'
        if settings.get('expect_missing_on_stop'): assert not state.exists()
        if settings.get('flush_state'): state.write_text(json.dumps(settings['flush_state']))
    elif 'restart' in sys.argv or 'start' in sys.argv:
        if settings.get('start_error'): sys.exit(1)
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
    running = settings.get('running') and (not (root / 'quit').exists() or settings.get('term_timeout'))
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
elif name == 'ioreg' and 'Root' in sys.argv:
    import plistlib
    locked = settings.get('locked')
    users = [{'kCGSSessionUserIDKey': os.getuid() + 1, 'kCGSSessionOnConsoleKey': False, 'CGSSessionScreenIsLocked': True}]
    if locked is not None:
        users.append(dict({'kCGSSessionUserIDKey': os.getuid(), 'kCGSSessionOnConsoleKey': True}, **({'CGSSessionScreenIsLocked': True} if locked else {})))
    sys.stdout.buffer.write(plistlib.dumps({'IOConsoleUsers': users}))
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
    raise AssertionError('Sunshine must not use Apple Events')
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

# Injected only into the temporary macOS helper; no real process receives any signal.
MOCK_SIGNAL = r'''def fixture_signal(pid, signum):
    fixture = pathlib.Path(os.environ['P6C_FIXTURE'])
    settings = json.loads((fixture / 'settings.json').read_text())
    assert pid == 42 and signum == signal.SIGTERM, 'Only simulated current-user Sunshine may receive SIGTERM'
    with (fixture / 'commands.log').open('a') as log: log.write('signal SIGTERM ' + str(pid) + '\n')
    if settings.get('term_permission'): raise PermissionError(errno.EPERM, 'offline signal permission denied')
    if settings.get('term_error'): raise OSError(errno.EIO, 'offline signal failure')
    (fixture / 'quit').touch()
    if settings.get('flush_state'):
        (fixture / '.config/sunshine/sunshine_state.json').write_text(json.dumps(settings['flush_state']))
    if settings.get('term_gone'): raise ProcessLookupError(errno.ESRCH, 'offline already exited')
os.kill = fixture_signal
'''

class SunshineFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='miffan-p6c-')
        self.root = Path(self.temp.name)
        (self.root / 'bin').mkdir()
        self.config = self.root / 'config/sunshine/sunshine.conf'
        self.config.parent.mkdir(parents=True)
        self.config.write_text('# personal settings\nencoder = nvenc\nlan_encryption_mode = 0\nwan_encryption_mode = 1\nlan_encryption_mode = 1\n')
        for command in ('uname', 'ps', 'lsof', 'ifconfig', 'ioreg', 'pmset', 'caffeinate', 'open', 'osascript', 'brew', 'systemctl', 'ss', 'ip', 'sunshine', 'pacman', 'dpkg-query', 'rpm', 'flatpak', 'openssl', 'cp'):
            path = self.root / 'bin' / command
            path.write_text(STUB); path.chmod(0o755)
        self.env = dict(os.environ, P6C_FIXTURE=str(self.root), XDG_CONFIG_HOME=str(self.root / 'config'),
            HOME=str(self.root), PATH=str(self.root / 'bin') + ':' + os.environ['PATH'])
    def tearDown(self): self.temp.cleanup()
    def call(self, mode='probe', permission=None, stdin=None, **settings):
        (self.root / 'settings.json').write_text(json.dumps(settings))
        result = subprocess.run(['/bin/sh', str(getattr(self, 'helper', HELPER)), 'sunshine', mode] + ([permission] if permission else []), env=self.env, input=stdin, capture_output=True, text=True, timeout=25)
        self.assertNotIn('\nsunshine ', '\n' + (self.root / 'commands.log').read_text())
        self.assertNotIn('osascript ', (self.root / 'commands.log').read_text())
        self.assertNotIn("Traceback", result.stderr)
        self.last_stderr = result.stderr
        return result.returncode, json.loads(result.stdout)

class SunshineContractTest(SunshineFixture):
    def test_linux_contracts(self):
        for name, settings in [('sunshine-stopped', {}), ('sunshine-running', dict(running=True)),
                               ('sunshine-busy', dict(running=True, busy=True))]:
            code, result = self.call(**settings)
            self.assertEqual(0, code); golden(name, result)
        for mode, name, settings in [('start', 'sunshine-start', {}),
                ('enforce-encryption', 'sunshine-encryption', {}),
                ('enforce-encryption', 'sunshine-refused', dict(busy=True))]:
            _, result = self.call(mode, **settings); golden(name, result)


class SunshinePairingFixture(SunshineFixture):
    def setUp(self):
        super().setUp()
        self.pem = (Path(__file__).resolve().parent / 'windows/certificate.pem').read_text()
        self.fingerprint = (Path(__file__).resolve().parent / 'windows/certificate.sha256').read_text().strip()
        self.other = dict(name='Miffan', cert=self.pem, uuid='other')
        import base64
        self.other['cert'] = '-----BEGIN CERTIFICATE-----\n' + base64.b64encode(b'other DER').decode() + '\n-----END CERTIFICATE-----'
        self.state = self.config.parent / 'sunshine_state.json'
        self.data = dict(root=dict(uniqueid='server', named_devices=[dict(name='renamed phone', cert=self.pem, uuid='phone'), self.other], unknown=dict(nested=[1, '保留', None])), unknown_top=True)
        self.state.write_text(json.dumps(self.data, indent=2))
        self.state.chmod(0o640)
        # Existing service discovery reads proc for running Linux; redirect only those reads.
        self.helper = self.root / 'miffan.sh'
        source = HELPER.read_text().replace("pathlib.Path('/proc/' + pid + '/cmdline').read_bytes().decode().split('\\0')", "['/usr/bin/sunshine']").replace("pathlib.Path(os.readlink('/proc/' + pid + '/cwd'))", 'pathlib.Path.home()')
        self.helper.write_text(source)

class SunshineUnpairTest(SunshinePairingFixture):
    def test_matching_fingerprint_removes_only_this_phone_and_preserves_state(self):
        self.data['root']['named_devices'].append(dict(name='duplicate', cert=self.pem, uuid='duplicate'))
        self.state.write_text(json.dumps(self.data))
        before = self.state.stat()
        code, result = self.call('unpair', permission=self.fingerprint.upper(), running=True, busy=True)
        self.assertEqual(0, code); self.assertEqual(dict(removed=2, restarted=True), result)
        expected = self.data; expected['root']['named_devices'] = [self.other]
        self.assertEqual(expected, json.loads(self.state.read_text()))
        after = self.state.stat()
        self.assertEqual((before.st_mode, before.st_uid, before.st_gid), (after.st_mode, after.st_uid, after.st_gid))
        commands = (self.root / 'commands.log').read_text()
        self.assertLess(commands.index('systemctl --user stop'), commands.index('systemctl --user start'))
    def test_no_match_changes_no_bytes_or_services(self):
        before = self.state.read_bytes()
        code, result = self.call('unpair', permission='0'*64, running=True)
        golden('sunshine-unpair-none', result)
        self.assertEqual(0, code); self.assertEqual(dict(removed=0, restarted=False), result)
        self.assertEqual(before, self.state.read_bytes())
        commands = (self.root / 'commands.log').read_text()
        self.assertNotIn('--user stop', commands); self.assertNotIn('--user start', commands)
    def test_missing_state_is_already_unpaired_without_service_changes(self):
        self.state.unlink()
        code, result = self.call('unpair', permission=self.fingerprint, running=True)
        self.assertEqual(0, code); self.assertEqual(dict(removed=0, restarted=False), result)
        self.assertFalse(self.state.exists())
        self.assertNotIn('--user stop', (self.root / 'commands.log').read_text())

    def test_unpair_does_not_initialize_root_or_server_identity(self):
        credentials = '{"username":"offline-admin","password":"offline-hash","salt":"offline-salt"}'
        self.state.write_text(credentials)
        code, result = self.call('unpair', permission=self.fingerprint)
        self.assertEqual(0, code); self.assertEqual(dict(removed=0, restarted=False), result)
        self.assertEqual(credentials, self.state.read_text())
        self.data['root'].pop('uniqueid')
        self.state.write_text(json.dumps(self.data))
        code, result = self.call('unpair', permission=self.fingerprint)
        self.assertEqual(0, code); self.assertEqual(1, result['removed'])
        self.assertNotIn('uniqueid', json.loads(self.state.read_text())['root'])

    def test_stopped_sunshine_is_not_started(self):
        code, result = self.call('unpair', permission=self.fingerprint)
        golden('sunshine-unpair', result)
        self.assertEqual(0, code); self.assertEqual(dict(removed=1, restarted=False), result)
        self.assertNotIn('--user start', (self.root / 'commands.log').read_text())
    def test_shutdown_flush_is_reread_and_unknown_keys_are_kept(self):
        flushed = json.loads(json.dumps(self.data)); flushed['new_key'] = {'from_shutdown': 1}
        code, result = self.call('unpair', permission=self.fingerprint, running=True, flush_state=flushed)
        self.assertEqual(0, code); self.assertTrue(result['restarted'])
        self.assertEqual(flushed['new_key'], json.loads(self.state.read_text())['new_key'])
    def test_stop_failure_keeps_state(self):
        before = self.state.read_bytes()
        code, result = self.call('unpair', permission=self.fingerprint, running=True, stop_error=True)
        self.assertEqual(1, code); self.assertFalse(result['success']); self.assertEqual(before, self.state.read_bytes())
        self.assertEqual('Could not stop Sunshine to change pairing', result['detail'])
        self.assertNotIn('--user start', (self.root / 'commands.log').read_text())
    def test_custom_relative_and_absolute_state_paths(self):
        for custom in (self.config.parent / 'custom.json', self.root / 'absolute state.json'):
            self.state.rename(custom)
            self.config.write_text('file_state = ' + (custom.name if custom.parent == self.config.parent else str(custom)) + '\n')
            code, result = self.call('unpair', permission=self.fingerprint)
            self.assertEqual(0, code); self.assertEqual(1, result['removed'])
            custom.write_text(json.dumps(self.data)); custom.rename(self.state)
    def test_invalid_fingerprint_and_invalid_json_keep_state(self):
        for fingerprint, content in [('invalid', json.dumps(self.data)), (self.fingerprint, '{invalid')]:
            self.state.write_text(content)
            code, result = self.call('unpair', permission=fingerprint)
            self.assertEqual(1, code); self.assertFalse(result['success']); self.assertEqual(content, self.state.read_text())

class SunshinePairTest(SunshinePairingFixture):
    # Reuse the hermetic transaction fixture.
    def pair(self, name='Miffan 手机', pem=None, **settings):
        return self.call('pair', stdin=json.dumps(dict(name=name, cert=self.pem if pem is None else pem)) + '\n', **settings)
    def assert_added(self, result, replaced, restarted):
        self.assertEqual(dict(added=True, replaced=replaced, restarted=restarted, client_sha256=self.fingerprint), result)
        devices = json.loads(self.state.read_text())['root']['named_devices']
        added = devices[-1]
        self.assertEqual({'name', 'cert', 'uuid', 'enabled'}, set(added))
        self.assertTrue(added['enabled'])
        import ssl, uuid
        self.assertEqual(self.pem, added['cert'])
        self.assertEqual(4, uuid.UUID(added['uuid']).version)
        self.assertEqual(str(uuid.UUID(added['uuid'])).upper(), added['uuid'])
        self.assertEqual(self.fingerprint, hashlib.sha256(ssl.PEM_cert_to_DER_cert(added['cert'])).hexdigest())
    def test_pair_new_preserves_unknown_fields_and_permissions(self):
        self.data['root']['named_devices'] = [self.other]
        self.state.write_text(json.dumps(self.data))
        before = self.state.stat()
        code, result = self.pair(pem=self.pem.replace('\n', '\r\n'))
        self.assertEqual(0, code); self.assert_added(result, 0, False); golden('sunshine-pair', result)
        data = json.loads(self.state.read_text())
        self.assertEqual(self.data, dict(data, root=dict(data['root'], named_devices=[self.other])))
        self.assertEqual('Miffan 手机', data['root']['named_devices'][-1]['name'])
        after = self.state.stat()
        self.assertEqual((before.st_mode, before.st_uid, before.st_gid), (after.st_mode, after.st_uid, after.st_gid))
        self.assertNotIn('--user start', (self.root / 'commands.log').read_text())
        self.assertNotIn('Miffan 手机', (self.root / 'commands.log').read_text())
        self.assertNotIn('BEGIN CERTIFICATE', (self.root / 'commands.log').read_text())
    def test_pair_replaces_all_duplicates_and_rereads_shutdown_state(self):
        flushed = json.loads(json.dumps(self.data))
        flushed['root']['named_devices'] += [dict(name='duplicate', cert=self.pem.replace('\n','\r\n'), uuid='duplicate')]
        flushed['shutdown'] = dict(keep=['new', True])
        code, result = self.pair(running=True, flush_state=flushed)
        self.assertEqual(0, code); self.assert_added(result, 2, True); golden('sunshine-pair-replaced', result)
        data = json.loads(self.state.read_text())
        self.assertEqual(flushed['shutdown'], data['shutdown']); self.assertEqual(self.other, data['root']['named_devices'][0])
        commands = (self.root / 'commands.log').read_text()
        self.assertLess(commands.index('--user stop'), commands.index('--user start'))
    def test_pair_creates_missing_devices_array(self):
        self.state.write_text('{"root":{"uniqueid":"server"},"unknown":1}')
        code, result = self.pair()
        self.assertEqual(0, code); self.assert_added(result, 0, False)
        self.assertEqual('server', json.loads(self.state.read_text())['root']['uniqueid'])
    def test_pair_unrecognized_state_keeps_exact_bytes_and_service(self):
        for invalid in ('[]', '{bad', '{"root":null}', '{"root":[]}', '{"root":{"named_devices":{}}}',
            '{"root":{"named_devices":null}}', '{"root":{"named_devices":[1]}}',
            '{"root":{"named_devices":[{"name":"x","cert":"x"}]}}',
            '{"root":{"named_devices":[{"name":5,"cert":"x","uuid":"x"}]}}'):
            self.state.write_text(invalid)
            code, result = self.pair(running=True)
            self.assertEqual(1, code); self.assertEqual('Unrecognized Sunshine state format', result['detail'])
            golden('sunshine-pair-state-unrecognized', result)
            self.assertEqual(invalid, self.state.read_text())
            self.assertNotIn('--user stop', (self.root / 'commands.log').read_text())
    def test_pair_rejects_invalid_input_without_data_in_diagnostics(self):
        import base64
        import ssl
        trailing_der = ssl.DER_cert_to_PEM_cert(ssl.PEM_cert_to_DER_cert(self.pem) + b'extra')
        bad_der = '-----BEGIN CERTIFICATE-----\n' + base64.b64encode(b'not X509').decode() + '\n-----END CERTIFICATE-----'
        for name, pem in [('x', 'bad PEM'), ('x', bad_der), ('x', trailing_der), ('x', self.pem+self.pem),
            ('x', self.pem+'-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----'),
            ('x', 'extra'+self.pem), ('x', self.pem+'extra'), ('', self.pem), (' '*3, self.pem),
            ('x'*65,self.pem), ('x\n',self.pem), ('x\x00',self.pem), ('x\x85',self.pem)]:
            before = self.state.read_bytes()
            code, result = self.pair(name=name, pem=pem, running=True)
            self.assertEqual(1, code); golden('sunshine-pair-invalid', result)
            self.assertEqual(before, self.state.read_bytes())
            self.assertNotIn('BEGIN CERTIFICATE', json.dumps(result)+self.last_stderr)
            self.assertNotIn('--user stop', (self.root / 'commands.log').read_text())
        for request in ('{}','null','[]','{bad',json.dumps(dict(name='x',cert=self.pem,extra=True))):
            code, result = self.call('pair', stdin=request)
            self.assertEqual(1, code); self.assertFalse(result['success'])
        code, result = self.call('pair', permission='not-stdin', stdin=json.dumps(dict(name='x',cert=self.pem)))
        self.assertEqual(1, code); self.assertFalse(result['success'])
    def test_pair_read_permissions_and_copy_permissions_fail_safely(self):
        # Inject permission errors instead of relying on the UID running the test.
        source = self.helper.read_text()
        for old, new in [("data = json.loads(state.read_text()) if original is not None else {}", "raise PermissionError(errno.EACCES, 'PRIVATE_PATH')"),
                         ("os.replace(temporary, state)", "(_ for _ in ()).throw(PermissionError(errno.EACCES, 'PRIVATE_PATH'))")]:
            self.helper.write_text(source.replace(old,new))
            before = self.state.read_bytes()
            code, result = self.pair(running=True)
            self.assertEqual(1, code)
            self.assertEqual('Cannot edit Sunshine state or control its service; check administrator rights',result['detail'])
            golden('sunshine-pair-no-permission',result)
            self.assertEqual(before,self.state.read_bytes()); self.assertNotIn('PRIVATE_PATH',json.dumps(result))
        self.helper.write_text(source)
    @unittest.skipUnless(sys.platform == 'darwin', 'macOS ACL verification')
    def test_pair_preserves_macos_acl(self):
        subprocess.run(['/bin/chmod', '+a', 'everyone allow read', str(self.state)],check=True)
        def acl(): return subprocess.check_output(['/bin/ls','-le',str(self.state)],text=True).splitlines()[1:]
        before = acl()
        code, result = self.pair()
        self.assertEqual(0,code);self.assertEqual(before,acl())

    def test_pair_restart_failure_reports_possible_write(self):
        code, result = self.pair(running=True,start_error=True)
        self.assertEqual(1,code); self.assertEqual('Sunshine pair attempted; could not restart Sunshine',result['detail'])
        golden('sunshine-pair-restart-failed',result)
        self.assertEqual('Miffan 手机',json.loads(self.state.read_text())['root']['named_devices'][-1]['name'])
    def test_pair_custom_state_and_regular_file_requirement(self):
        custom = self.root / 'custom state.json'
        self.state.rename(custom); self.config.write_text('file_state = '+str(custom)+'\n')
        code, result = self.pair(); self.assertEqual(0, code)
        self.assertEqual(1,result['replaced'])
        self.state.symlink_to(custom); self.config.write_text('')
        before = custom.read_bytes()
        code, result = self.pair(); self.assertEqual(1,code); self.assertEqual(before,custom.read_bytes())
        self.state.unlink()
        code, result = self.pair(); self.assertEqual(0,code); self.assertTrue(self.state.exists())
        self.assert_added(result, 0, False)
    def test_pair_creates_missing_file_after_stopping_with_private_permissions(self):
        for running in (False, True):
            with self.subTest(running=running):
                self.state.unlink()
                code, result = self.pair(running=running, expect_missing_on_stop=True)
                self.assertEqual(0, code); self.assert_added(result, 0, running)
                data = json.loads(self.state.read_text())
                self.assertEqual({'root'}, set(data)); self.assertEqual({'uniqueid', 'named_devices'}, set(data['root']))
                import uuid
                identifier = data['root']['uniqueid']
                self.assertEqual(4, uuid.UUID(identifier).version)
                self.assertEqual(str(uuid.UUID(identifier)).upper(), identifier)
                info = self.state.stat()
                self.assertEqual(0o600, info.st_mode & 0o7777); self.assertEqual(os.getuid(), info.st_uid)
    def test_pair_creates_missing_file_at_overridden_path(self):
        self.state.unlink()
        self.state = self.root / 'fresh custom state.json'
        self.config.write_text('file_state = ' + str(self.state) + '\n')
        code, result = self.pair()
        self.assertEqual(0, code); self.assert_added(result, 0, False)
        self.assertFalse((self.config.parent / 'sunshine_state.json').exists())
    def test_pair_initializes_root_and_uniqueid_without_losing_credentials(self):
        credentials = dict(username='offline-admin', password='offline-password-hash', salt='offline-salt', unknown=dict(keep=[1, None]))
        for root in (None, {}, dict(uniqueid=None), dict(uniqueid=42), dict(uniqueid='unchanged-server-id')):
            with self.subTest(root=root):
                data = dict(credentials)
                if root is not None: data['root'] = root
                self.state.write_text(json.dumps(data))
                before = self.state.stat()
                code, result = self.pair()
                self.assertEqual(0, code); self.assert_added(result, 0, False)
                saved = json.loads(self.state.read_text())
                self.assertEqual(credentials, {k: v for k, v in saved.items() if k != 'root'})
                if root is not None and isinstance(root.get('uniqueid'), str):
                    self.assertEqual(root['uniqueid'], saved['root']['uniqueid'])
                else:
                    import uuid
                    identifier = saved['root']['uniqueid']
                    self.assertEqual(4, uuid.UUID(identifier).version)
                    self.assertEqual(str(uuid.UUID(identifier)).upper(), identifier)
                after = self.state.stat()
                self.assertEqual((before.st_mode, before.st_uid, before.st_gid), (after.st_mode, after.st_uid, after.st_gid))
                self.assertNotIn('offline-password-hash', json.dumps(result) + self.last_stderr)
    def test_pair_rereads_first_shutdown_file_and_preserves_server_uniqueid(self):
        self.state.unlink()
        flushed = dict(username='offline-admin', password='offline-hash', salt='offline-salt', root=dict(uniqueid='shutdown-server-id'))
        code, result = self.pair(running=True, flush_state=flushed)
        self.assertEqual(0, code); self.assert_added(result, 0, True)
        saved = json.loads(self.state.read_text())
        saved['root'].pop('named_devices')
        self.assertEqual(flushed, saved)
        self.assertEqual('shutdown-server-id', saved['root']['uniqueid'])
    def test_pair_missing_file_refuses_missing_nondirectory_or_symlink_parent(self):
        self.state.unlink()
        regular = self.root / 'not-a-directory'; regular.write_text('keep')
        link = self.root / 'directory-link'; link.symlink_to(self.config.parent, target_is_directory=True)
        for parent in (self.root / 'missing-directory', regular, link):
            self.config.write_text('file_state = ' + str(parent / 'new-state.json') + '\n')
            code, result = self.pair(running=True)
            self.assertEqual(1, code); self.assertEqual('Unrecognized Sunshine state format', result['detail'])
            self.assertFalse((self.config.parent / 'new-state.json').exists())
        self.assertEqual('keep', regular.read_text())
        self.assertNotIn('--user stop', (self.root / 'commands.log').read_text())

    def test_pair_stop_failure_and_wait_timeout_are_not_permission_failures(self):
        for failure in ('stop_error', 'stop_timeout'):
            before = self.state.read_bytes()
            code, result = self.pair(running=True, **{failure: True})
            self.assertEqual(1, code); self.assertEqual('Could not stop Sunshine to change pairing', result['detail'])
            self.assertEqual(before, self.state.read_bytes())
            self.assertNotIn('--user start', (self.root / 'commands.log').read_text())
    def test_pair_copy_failure_is_not_assumed_to_be_permission_failure(self):
        before = self.state.read_bytes()
        code, result = self.pair(copy_error=True)
        self.assertEqual(1, code); self.assertEqual('Could not preserve Sunshine state permissions', result['detail'])
        self.assertEqual(before, self.state.read_bytes())

    def test_pair_refuses_unknown_service_model(self):
        source = self.helper.read_text()
        self.helper.write_text(source.replace("if mode in ('enforce-encryption', 'unpair', 'pair') and not configuration_known:",
            "configuration_known = False\nif mode in ('enforce-encryption', 'unpair', 'pair') and not configuration_known:"))
        before = self.state.read_bytes()
        code, result = self.pair(running=True)
        self.assertEqual(1, code); self.assertEqual(before, self.state.read_bytes())
        self.assertNotIn('--user stop', (self.root / 'commands.log').read_text())

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
        self.helper.write_text(HELPER.read_text().replace("pathlib.Path('/Applications/Sunshine.app')", repr_path(self.root / 'SystemApplications/Sunshine.app')).replace("def mac_processes(timeout=10):", MOCK_SIGNAL + "\ndef mac_processes(timeout=10):"))
        # All state-changing and OS-dependent commands are stubs, including Linux tools.
        self.env['PATH'] = str(self.root / 'bin')
        (self.root / 'bin/python3').symlink_to(sys.executable)
    def call(self, mode='probe', permission=None, stdin=None, **settings):
        return super().call(mode, permission=permission, stdin=stdin, platform='Darwin', **settings)
    def test_mac_contracts(self):
        for name, settings in [('sunshine-macos', dict(running=True, locked=False, display=False)),
                ('sunshine-macos-locked', dict(running=True, locked=True, display=True))]:
            _, result = self.call(**settings); golden(name, result)
        _, result = self.call('wake', display=True); golden('sunshine-wake', result)
        _, result = self.call('open-settings', permission='screen'); golden('sunshine-settings', result)

    def test_mac_unpair_stops_and_reopens_only_when_paired(self):
        pem = (Path(__file__).resolve().parent / 'windows/certificate.pem').read_text()
        fingerprint = (Path(__file__).resolve().parent / 'windows/certificate.sha256').read_text().strip()
        state = self.config.parent / 'sunshine_state.json'
        data = dict(root=dict(uniqueid='server', named_devices=[dict(name='renamed', cert=pem)], unknown=dict(keep=True)))
        state.write_text(json.dumps(data, indent=2))
        before = state.read_bytes()
        code, result = self.call('unpair', permission='0'*64, running=True)
        golden('sunshine-unpair-none', result)
        self.assertEqual(0, code); self.assertEqual(dict(removed=0, restarted=False), result)
        self.assertEqual(before, state.read_bytes())
        self.assertNotIn('osascript', (self.root / 'commands.log').read_text())
        code, result = self.call('unpair', permission=fingerprint, running=True, busy=True)
        self.assertEqual(0, code); self.assertEqual(dict(removed=1, restarted=True), result)
        data['root']['named_devices'] = []
        self.assertEqual(data, json.loads(state.read_text()))
        commands = (self.root / 'commands.log').read_text()
        self.assertLess(commands.index('signal SIGTERM 42'), commands.index('open -a'))

    def test_mac_pair_stops_reopens_and_preserves_unknown_state(self):
        pem = (Path(__file__).resolve().parent / 'windows/certificate.pem').read_text()
        fingerprint = (Path(__file__).resolve().parent / 'windows/certificate.sha256').read_text().strip()
        state = self.config.parent / 'sunshine_state.json'
        state.write_text('{"root":{"uniqueid":"server"},"unknown":{"kept":true}}')
        code, result = self.call('pair', stdin=json.dumps(dict(name='phone', cert=pem)), running=True)
        self.assertEqual(0, code)
        self.assertEqual(dict(added=True,replaced=0,restarted=True,client_sha256=fingerprint),result)
        data = json.loads(state.read_text())
        self.assertEqual(dict(kept=True),data['unknown']);self.assertEqual('server',data['root']['uniqueid'])
        commands = (self.root / 'commands.log').read_text()
        self.assertLess(commands.index('signal SIGTERM 42'),commands.index('open -a'))

    def test_mac_pair_and_unpair_stop_failures_keep_state_and_never_reopen(self):
        pem = (Path(__file__).resolve().parent / 'windows/certificate.pem').read_text()
        fingerprint = (Path(__file__).resolve().parent / 'windows/certificate.sha256').read_text().strip()
        state = self.config.parent / 'sunshine_state.json'
        for mode in ('pair', 'unpair'):
            for failure in ('term_error', 'term_timeout', 'term_permission'):
                with self.subTest(mode=mode, failure=failure):
                    (self.root / 'quit').unlink(missing_ok=True)
                    state.write_text(json.dumps(dict(root=dict(uniqueid='server', named_devices=[dict(name='phone', cert=pem, uuid='phone')]))))
                    before = state.read_bytes()
                    request = json.dumps(dict(name='phone', cert=pem)) if mode == 'pair' else None
                    code, result = self.call(mode, permission=fingerprint if mode == 'unpair' else None,
                        stdin=request, running=True, **{failure: True})
                    self.assertEqual(1, code)
                    detail = 'Cannot edit Sunshine state or control its service; check administrator rights' if failure == 'term_permission' else 'Could not stop Sunshine to change pairing'
                    self.assertEqual(detail, result['detail'])
                    self.assertEqual(before, state.read_bytes())
                    commands = (self.root / 'commands.log').read_text()
                    self.assertIn('signal SIGTERM 42', commands)
                    self.assertNotIn('open -a', commands); self.assertNotIn('SIGKILL', commands)
    def test_mac_sigterm_rereads_flushed_state_and_tolerates_already_exited_process(self):
        pem = (Path(__file__).resolve().parent / 'windows/certificate.pem').read_text()
        state = self.config.parent / 'sunshine_state.json'
        state.write_text('{"root":{"uniqueid":"server"}}')
        flushed = dict(root=dict(uniqueid='shutdown-server'), shutdown=dict(kept=True))
        code, result = self.call('pair', stdin=json.dumps(dict(name='phone', cert=pem)), running=True,
            term_gone=True, flush_state=flushed)
        self.assertEqual(0, code); self.assertTrue(result['restarted'])
        saved = json.loads(state.read_text())
        self.assertEqual(flushed['shutdown'], saved['shutdown']); self.assertEqual('shutdown-server', saved['root']['uniqueid'])

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
        self.assertIsNone(probe['screen_locked'])
        for locked in (True, False):
            _, probe = self.call(locked=locked); self.assertEqual(locked, probe['screen_locked'])
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
        self.assertIn('signal SIGTERM 42', commands); self.assertIn('open -a ' + str(self.app), commands)
        self.assertNotIn('SIGKILL', commands)
    def test_mac_busy_unknown_races_and_overrides_reject_enforcement(self):
        before = self.config.read_bytes()
        for settings in (dict(busy=True), dict(ss_error=True), dict(race=True), dict(override=True), dict(ps_error=True)):
            counter = self.root / 'ss-count'
            if counter.exists(): counter.unlink()
            code, result = self.call('enforce-encryption', running=True, **settings)
            self.assertFalse(result['success']); self.assertEqual(1, code)
            self.assertEqual(before, self.config.read_bytes())
            self.assertNotIn('osascript', (self.root / 'commands.log').read_text())
    def test_mac_post_save_race_and_stop_failures_never_reopen_or_force_kill(self):
        for settings in (dict(restart_race=True), dict(term_error=True), dict(term_timeout=True)):
            counter = self.root / 'ss-count'
            if counter.exists(): counter.unlink()
            code, result = self.call('enforce-encryption', running=True, **settings)
            self.assertFalse(result['success']); self.assertEqual(1, code)
            self.assertIn('lan_encryption_mode = 2', self.config.read_text())
            self.assertNotIn('open -a', (self.root / 'commands.log').read_text())
            self.assertNotIn('SIGKILL', (self.root / 'commands.log').read_text())
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
        golden('sunshine-missing', probe)
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
