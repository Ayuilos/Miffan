#!/usr/bin/env python3
"""Hermetic helper safety checks; no server, credentials, or user configuration is touched."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

HELPER = Path(__file__).resolve().parents[2] / "app/src/main/assets/remote/miffan.sh"
MOCK = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name=Path(sys.argv[0]).name
args=sys.argv[1:]
root=Path(os.environ['FIXTURE'])
f=root/'state.json'
s=json.loads(f.read_text()) if f.exists() else {}
with (root/'argv.jsonl').open('a') as log: log.write(json.dumps([name]+args)+'\n')
def save(): f.write_text(json.dumps(s))
if name=='uname': print('Linux' if args==['-s'] else 'aarch64')
elif name=='pgrep':
    pattern=args[-1]
    if pattern=='krdpserver' and s.get('kde_running'): print('5678'); sys.exit(0)
    sys.exit(0 if 'gnome-shell' in pattern else 1)
elif name=='systemctl':
    if 'restart' in args: s['running']=True; save()
    elif 'stop' in args: s['kde_running']=False; s['running']=False; save()
    elif 'MainPID' in args: print('5678' if s.get('kde_running') else '0')
    elif 'LoadState' in args: print('loaded' if s.get('kde_running') or s.get('foreign_unit') else 'not-found')
elif name=='grdctl':
    if args and args[0]=='--headless': args=args[1:]
    if args==['status']:
        print('RDP:\n\tStatus: '+('enabled' if s.get('configured') else 'disabled'))
        print('\tPort: '+str(s.get('port',3389)))
        print('\tTLS certificate: '+s.get('cert',''))
        print('\tTLS key: '+s.get('key',''))
        print('\tUsername: '+('(hidden)' if s.get('credential') else '(empty)'))
    elif args==['--version']: print('gnome-remote-desktop 51')
    elif args==['rdp','set-credentials']:
        lines=sys.stdin.read().splitlines()
        assert len(lines)==2 and len(lines[1])>=32
        # Compare privately; never add the password to diagnostic output or argv.
        assert lines[1]==os.environ['DUMMY_EXPECTED']
        s['credential']=True; save()
    elif args[:2]==['rdp','set-tls-cert']: s['cert']=args[2]; save()
    elif args[:2]==['rdp','set-tls-key']: s['key']=args[2]; save()
    elif args[:2]==['rdp','set-port']: s['port']=int(args[2]); save()
    elif args==['rdp','enable']: s['configured']=True; save()
    elif args in [['rdp','disable-view-only'],['rdp','disable-port-negotiation']]: pass
    else: sys.exit(2)
elif name=='krdpserver': print('krdp 6.7.5')
elif name=='gdbus':
    assert args[:4]==['call','--session','--dest','org.freedesktop.secrets']
    path=args[args.index('--object-path')+1]
    method=args[args.index('--method')+1]
    if s.get('dbus_failure'): sys.exit(1)
    if method=='org.freedesktop.Secret.Service.ReadAlias':
        assert path=='/org/freedesktop/secrets' and args[-1]=='default'
        print("(objectpath '"+s.get('collection','/')+"',)")
    elif method=='org.freedesktop.DBus.Properties.Get':
        assert path==s['collection'] and args[-2:]==['org.freedesktop.Secret.Collection','Locked']
        if s.get('property_failure'): sys.exit(1)
        print(s.get('locked_reply','(<true>,)' if s.get('locked',True) else '(<false>,)'))
    else: raise AssertionError('Must not create or unlock collections')
elif name=='secret-tool':
    assert args[:3]==['store','--label=KRDP/miffan-'+str(os.getuid()),'--collection='+s['collection']]
    assert args[3:]==['xdg:schema','org.qt.keychain','user','miffan-'+str(os.getuid()),'server','KRDP','type','plaintext']
    assert not s.get('locked',True) and '/session' not in s['collection']
    # secret-tool stores every stdin byte, including any accidental trailing newline.
    assert sys.stdin.read()==os.environ['DUMMY_EXPECTED']
    if s.get('store_failure'):
        print(os.environ['DUMMY_EXPECTED'],file=sys.stderr); sys.exit(1)
    s['secret_written']=s.get('secret_written',0)+1; save()
elif name=='systemd-run':
    assert args[-4:]==['krdpserver','--address','127.0.0.1','--plasma']
    assert '--setenv=QTKEYCHAIN_BACKEND=libsecret' in args
    assert '-u' not in args and '-p' not in args
    assert '--unit=miffan-rdp-kde' in args and '--collect' in args
    config=Path(next(a.split('=',2)[2] for a in args if a.startswith('--setenv=XDG_CONFIG_HOME=')))/'krdpserverrc'
    values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
    assert values['SystemUserEnabled']=='false' and values['Users'].startswith('miffan-')
    s['port']=int(values['ListenPort']); s['kde_running']=True; s['running']=True
    s['kde_starts']=s.get('kde_starts',0)+1; save()
elif name=='nc': sys.exit(0 if s.get('running') and int(args[-1])==s.get('port') else 1)
elif name=='timeout':
    sys.exit(subprocess.call(args[1:],stdin=sys.stdin,stdout=sys.stdout,stderr=sys.stderr))
elif name=='script':
    assert args[:5]==['--quiet','--return','--echo','never','--command'] and args[-1]=='/dev/null'
    sys.exit(subprocess.call(args[5].split(),stdin=sys.stdin,stdout=sys.stdout,stderr=sys.stderr))
else: sys.exit(2)
'''.replace('import json, os, sys', 'import json, os, sys, subprocess')

with tempfile.TemporaryDirectory(prefix="p5b-helper-") as tmp:
    root = Path(tmp)
    bindir = root / "bin"
    bindir.mkdir()
    mock = bindir / "mock"
    mock.write_text(MOCK)
    mock.chmod(0o755)
    for name in ["uname", "pgrep", "systemctl", "grdctl", "nc", "script", "timeout", "krdpserver", "gdbus", "secret-tool", "systemd-run"]:
        (bindir / name).symlink_to(mock)
    secret = "testOnlyGeneratedPassword0123456789AB"
    # HOME here belongs only to this disposable subprocess, never the agent shell.
    env = dict(os.environ, HOME=str(root), FIXTURE=str(root), DUMMY_EXPECTED=secret,
               PATH=str(bindir)+os.pathsep+os.environ["PATH"], XDG_CURRENT_DESKTOP="GNOME",
               WAYLAND_DISPLAY="fixture", XDG_RUNTIME_DIR=str(root),
               XDG_CONFIG_HOME=str(root/'.config'), DBUS_SESSION_BUS_ADDRESS='unix:path='+str(root/'bus'))
    def start(password=secret):
        result = subprocess.run(["sh", str(HELPER), "rdp", "start"], input=password+"\n",
                                text=True, capture_output=True, env=env, timeout=25)
        assert secret not in result.stdout+result.stderr
        return result, json.loads(result.stdout)
    result, status = start()
    assert result.returncode==0, status
    assert status['mode']=='headless' and 20000<=status['port']<60000
    assert status['error'] is None and status['username'].startswith('miffan-')
    cert=root/'.miffan/rdp/cert.pem'
    identity=cert.read_bytes()
    der=subprocess.check_output(['openssl','x509','-in',str(cert),'-outform','DER'])
    expected_pin=hashlib.sha256(der).hexdigest()
    assert status['certificate_sha256']==expected_pin
    assert expected_pin!=hashlib.sha256(identity).hexdigest(), 'Must hash DER, not PEM'
    result, second = start()
    assert result.returncode==0 and second['port']==status['port'], second
    assert identity==cert.read_bytes()
    assert second['certificate_sha256']==expected_pin
    for p in [root/'.miffan',root/'.miffan/rdp']:
        assert p.stat().st_mode & 0o777==0o700
    for p in [root/'.miffan/rdp/cert.pem',root/'.miffan/rdp/key.pem']:
        assert p.stat().st_mode & 0o777==0o600
    (root/'.miffan/rdp/gnome-headless.owner').unlink()
    before=(root/'state.json').read_bytes()
    result, status=start()
    assert result.returncode!=0 and status['error']=='rdp_already_configured',status
    assert before==(root/'state.json').read_bytes()
    assert status['certificate_sha256'] is None, 'Do not attest unowned configurations'
    result, status=start('short')
    assert result.returncode!=0 and status['error']=='rdp_start_failed'
    assert secret not in (root/'argv.jsonl').read_text()
    # A missing/locked default collection must never create or unlock a fallback.
    env['XDG_CURRENT_DESKTOP']='KDE'
    login='/org/freedesktop/secrets/collection/login'
    kdewallet='/org/freedesktop/secrets/collection/kdewallet'
    for state in [{}, {'collection':login,'locked':True}, {'dbus_failure':True},
                  {'collection':login,'property_failure':True},
                  {'collection':login,'locked_reply':"('false',)"},
                  {'collection':'/org/freedesktop/secrets/collection/session','locked':False},
                  {'collection':login,'locked':False,'store_failure':True}]:
        (root/'state.json').write_text(json.dumps(state))
        before=(root/'state.json').read_bytes()
        argv_before=len((root/'argv.jsonl').read_text().splitlines())
        result, status=start()
        assert result.returncode!=0 and status['error']=='keyring_locked',status
        assert before==(root/'state.json').read_bytes()
        calls=[json.loads(line) for line in (root/'argv.jsonl').read_text().splitlines()[argv_before:]]
        assert not any(call[0]=='systemd-run' for call in calls)
        assert not (root/'.miffan/rdp/krdp-config').exists()
        if not state.get('store_failure'):
            assert not any(call[0]=='secret-tool' for call in calls)
    # Mixed GNOME/KDE: login belongs to gnome-keyring, KWallet APIs are absent.
    unlocked={'collection':login,'locked':False}
    user_config=root/'.config/krdpserverrc'
    user_config.parent.mkdir()
    user_config.write_text('[General]\nUsers=existing-user\n')
    for state in [unlocked, dict(unlocked,kde_running=True), dict(unlocked,foreign_unit=True)]:
        (root/'state.json').write_text(json.dumps(state))
        before=(root/'state.json').read_bytes()
        existing_config=user_config.read_bytes() if user_config.exists() else None
        result,status=start()
        assert result.returncode!=0 and status['error']=='rdp_already_configured',status
        assert before==(root/'state.json').read_bytes()
        assert (user_config.read_bytes() if user_config.exists() else None)==existing_config
        user_config.unlink(missing_ok=True)
    (root/'state.json').write_text(json.dumps(unlocked))
    result, status=start()
    assert result.returncode==0 and status['server']=='krdp',status
    assert status['certificate_sha256']==expected_pin
    assert json.loads((root/'state.json').read_text())['secret_written']==1
    backend=root/'.miffan/rdp/krdp.owner.backend'
    assert backend.read_text().strip()=='libsecret'
    result, reused=start()
    assert result.returncode==0 and reused['port']==status['port'],reused
    assert reused['certificate_sha256']==expected_pin
    assert json.loads((root/'state.json').read_text())['kde_starts']==1
    assert json.loads((root/'state.json').read_text())['secret_written']==1
    state=json.loads((root/'state.json').read_text()); state['locked']=True
    (root/'state.json').write_text(json.dumps(state))
    result,locked=start()
    assert result.returncode!=0 and locked['error']=='keyring_locked',locked
    assert state==json.loads((root/'state.json').read_text())
    state['locked']=False
    (root/'state.json').write_text(json.dumps(state))
    config=root/'.miffan/rdp/krdp-config/krdpserverrc'
    owned_config=config.read_text()
    config.write_text(owned_config.replace('Users=miffan-','Users=external-'))
    result,changed=start()
    assert result.returncode!=0 and changed['error']=='rdp_already_configured',changed
    assert state==json.loads((root/'state.json').read_text())
    config.write_text(owned_config)
    # Upgrade an active v8-owned unit, then pure KDE with ksecretd's default wallet.
    backend.unlink()
    state=json.loads((root/'state.json').read_text()); state['collection']=kdewallet
    (root/'state.json').write_text(json.dumps(state))
    result, migrated=start()
    assert result.returncode==0 and migrated['port']==reused['port'],migrated
    assert migrated['certificate_sha256']==expected_pin
    assert backend.read_text().strip()=='libsecret'
    assert json.loads((root/'state.json').read_text())['kde_starts']==2
    assert json.loads((root/'state.json').read_text())['secret_written']==2
    for p in [backend,root/'.miffan/rdp/krdp.owner',root/'.miffan/rdp/krdp.owner.digest']:
        assert p.stat().st_mode & 0o777==0o600
    cert.write_text('invalid certificate')
    result, failed=start()
    assert result.returncode!=0 and failed['error']=='rdp_start_failed',failed
    assert failed['certificate_sha256'] is None, 'Failed decoding must not attest an empty-input hash'
    assert (root/'.miffan/rdp/krdp-config/krdpserverrc').stat().st_mode & 0o777==0o600
    assert secret not in (root/'argv.jsonl').read_text()
    calls=[json.loads(line) for line in (root/'argv.jsonl').read_text().splitlines()]
    assert not any(call[0] in ['qdbus6','kwallet-query','kwallet6-query'] for call in calls)
    assert not any('CreateCollection' in arg or 'Unlock' in arg for call in calls for arg in call)
    kde_stops=[call for call in calls if call[0]=='systemctl' and 'stop' in call and 'miffan-rdp-kde.service' in call]
    assert kde_stops==[['systemctl','--user','stop','miffan-rdp-kde.service']]
    # Restrict PATH so a host-installed credential tool cannot mask missing dependencies.
    for missing in ['secret-tool','gdbus']:
        tools_dir=root/('without-'+missing)
        tools_dir.mkdir()
        for tool in bindir.iterdir():
            if tool.name!=missing: (tools_dir/tool.name).symlink_to(tool)
        for name in ['sh','id','mkdir','chmod','rmdir','sed','tr','openssl']:
            (tools_dir/name).symlink_to(shutil.which(name))
        (tools_dir/'python3').symlink_to(sys.executable)
        env['PATH']=str(tools_dir)
        result,status=start()
        assert result.returncode!=0 and status['error']=='credential_setup_unavailable',status
        assert status['certificate_sha256'] is None
    print('PASS: stdin-only exact QtKeychain credentials, default collection refusal, libsecret startup/migration, ownership, persistent DER identity, permissions and fail-closed attestation')
