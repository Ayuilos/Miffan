#!/usr/bin/env python3
"""Hermetic helper safety checks; no server, credentials, or user configuration is touched."""
import json
import os
from pathlib import Path
import subprocess
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
    elif 'LoadState' in args: print('loaded' if s.get('kde_running') else 'not-found')
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
elif name=='qdbus6':
    if args[-1].endswith('networkWallet'): print('kdewallet' if s.get('wallet_exists') else '')
    else: print('true' if s.get('wallet_open') else 'false')
elif name=='kwallet-query':
    assert args[0]=='-w' and args[2:4]==['-f','KRDP']
    assert sys.stdin.read().strip()==os.environ['DUMMY_EXPECTED']
    s['wallet_written']=True; save()
elif name=='systemd-run':
    assert args[-4:]==['krdpserver','--address','127.0.0.1','--plasma']
    assert '--setenv=QTKEYCHAIN_BACKEND=kwallet6' in args
    config=Path(next(a.split('=',2)[2] for a in args if a.startswith('--setenv=XDG_CONFIG_HOME=')))/'krdpserverrc'
    values=dict(line.split('=',1) for line in config.read_text().splitlines() if '=' in line)
    assert values['SystemUserEnabled']=='false' and values['Users'].startswith('miffan-')
    s['port']=int(values['ListenPort']); s['kde_running']=True; s['running']=True; save()
elif name=='nc': sys.exit(0 if s.get('running') and int(args[-1])==s.get('port') else 1)
elif name=='timeout':
    sys.exit(subprocess.call(args[1:],stdin=sys.stdin,stdout=sys.stdout,stderr=sys.stderr))
elif name=='script':
    assert args[:5]==['--quiet','--return','--echo','never','--command'] and args[-1]=='/dev/null'
    sys.exit(subprocess.call(args[5].split(),stdin=sys.stdin,stdout=sys.stdout,stderr=sys.stderr))
elif name=='openssl':
    if args==['dgst','-sha256']:
        import hashlib
        print('SHA2-256(stdin)= '+hashlib.sha256(sys.stdin.buffer.read()).hexdigest()); sys.exit(0)
    for flag in ['-keyout','-out']: Path(args[args.index(flag)+1]).write_text('test fixture\n')
else: sys.exit(2)
'''.replace('import json, os, sys', 'import json, os, sys, subprocess')

with tempfile.TemporaryDirectory(prefix="p5b-helper-") as tmp:
    root = Path(tmp)
    bindir = root / "bin"
    bindir.mkdir()
    mock = bindir / "mock"
    mock.write_text(MOCK)
    mock.chmod(0o755)
    for name in ["uname", "pgrep", "systemctl", "grdctl", "nc", "script", "openssl", "timeout", "krdpserver", "qdbus6", "kwallet-query", "systemd-run"]:
        (bindir / name).symlink_to(mock)
    secret = "testOnlyGeneratedPassword0123456789AB"
    # HOME here belongs only to this disposable subprocess, never the agent shell.
    env = dict(os.environ, HOME=str(root), FIXTURE=str(root), DUMMY_EXPECTED=secret,
               PATH=str(bindir)+os.pathsep+os.environ["PATH"], XDG_CURRENT_DESKTOP="GNOME",
               WAYLAND_DISPLAY="fixture", XDG_RUNTIME_DIR=str(root))
    def start(password=secret):
        result = subprocess.run(["sh", str(HELPER), "rdp", "start"], input=password+"\n",
                                text=True, capture_output=True, env=env, timeout=25)
        assert secret not in result.stdout+result.stderr
        return result, json.loads(result.stdout)
    result, status = start()
    assert result.returncode==0, status
    assert status['mode']=='headless' and 20000<=status['port']<60000
    assert status['error'] is None and status['username'].startswith('miffan-')
    identity=(root/'.miffan/rdp/cert.pem').read_bytes()
    result, second = start()
    assert result.returncode==0 and second['port']==status['port'], second
    assert identity==(root/'.miffan/rdp/cert.pem').read_bytes()
    for p in [root/'.miffan',root/'.miffan/rdp']:
        assert p.stat().st_mode & 0o777==0o700
    for p in [root/'.miffan/rdp/cert.pem',root/'.miffan/rdp/key.pem']:
        assert p.stat().st_mode & 0o777==0o600
    (root/'.miffan/rdp/gnome-headless.owner').unlink()
    before=(root/'state.json').read_bytes()
    result, status=start()
    assert result.returncode!=0 and status['error']=='rdp_already_configured',status
    assert before==(root/'state.json').read_bytes()
    result, status=start('short')
    assert result.returncode!=0 and status['error']=='rdp_start_failed'
    assert secret not in (root/'argv.jsonl').read_text()
    # A missing or locked wallet must never create an anonymous/plaintext fallback.
    env['XDG_CURRENT_DESKTOP']='KDE'
    (root/'state.json').write_text('{}')
    result, status=start()
    assert result.returncode!=0 and status['error']=='keyring_locked',status
    (root/'state.json').write_text(json.dumps({'wallet_exists':True,'wallet_open':False}))
    result, status=start()
    assert result.returncode!=0 and status['error']=='keyring_locked',status
    (root/'state.json').write_text(json.dumps({'wallet_exists':True,'wallet_open':True}))
    result, status=start()
    assert result.returncode==0 and status['server']=='krdp',status
    assert json.loads((root/'state.json').read_text())['wallet_written']
    result, reused=start()
    assert result.returncode==0 and reused['port']==status['port'],reused
    assert (root/'.miffan/rdp/krdp-config/krdpserverrc').stat().st_mode & 0o777==0o600
    assert secret not in (root/'argv.jsonl').read_text()
    print('PASS: stdin-only credentials, ownership refusal, persistent identity, permissions, bounded password, KWallet refusal and secure startup')
