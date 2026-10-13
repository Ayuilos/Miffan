#!/usr/bin/env python3
"""Reuse emulator-5560's existing receive-only stream instrumentation for Windows.

Temporarily maps the fixture's fixed TCP/UDP addresses to this Windows experiment;
backs up and restores test prefs and exact emulator NAT rules. Never changes Windows firewall.
Imports the already paired probe identity via stdin, not arguments or logs.
"""
import os,shlex,subprocess,xml.etree.ElementTree as ET
from pathlib import Path

ADB=[str(Path.home()/'Library/Android/sdk/platform-tools/adb'),'-s','emulator-5560']
PRIVATE=Path('/Users/chenxiansheng/miffan/.deps/windows-spike')
OUT=Path('/private/tmp/windows-spike-emulator');OUT.mkdir(exist_ok=True)
PREF='shared_prefs/p6b-generated-identity.xml'
def adb(*args,input=None,check=True,timeout=30):
    return subprocess.run(ADB+list(args),input=input,capture_output=True,check=check,timeout=timeout)
def shell(args,**kw):return adb('shell',shlex.join(args),**kw)
def write_pref(content):
    shell(['run-as','me.rerere.stream.test','sh','-c',f'cat > {PREF}.spike; mv {PREF}.spike {PREF}'],input=content)

name=adb('emu','avd','name').stdout.decode()
if 'RikkaHub_API_35_16K' not in name:raise SystemExit('Unexpected AVD; refusing test')
backup=shell(['run-as','me.rerere.stream.test','cat',PREF]).stdout
backup_path=PRIVATE/'emulator-p6b-prefs-before.xml'
if backup_path.exists():
    if backup_path.read_bytes()!=backup:raise SystemExit('Test prefs differ from saved baseline; inspect before retry')
else:
    fd=os.open(backup_path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as f:f.write(backup)
root=ET.Element('map')
for key,value in {'cert':(PRIVATE/'probe-identity/client.pem').read_text(),
                  'key':(PRIVATE/'probe-identity/key.pem').read_text(),
                  'id':(PRIVATE/'probe-identity/uniqueid.dat').read_text().strip(),
                  'pin':(PRIVATE/'probe-identity/host-cert.sha256').read_text().strip()}.items():
    ET.SubElement(root,'string',name=key).text=value
routes=[['-p','tcp','-d','10.0.2.2','--dport',str(remote),'-j','DNAT','--to-destination',f'10.0.2.2:{local}']
        for remote,local in [(47984,57984),(47989,57989),(48010,58010)]]
routes+=[['-p','udp','-d','100.64.0.5','--dport','47998:48000','-j','DNAT','--to-destination','192.168.31.61']]
added=[]
success=False
try:
    adb('root');adb('wait-for-device')
    (OUT/'iptables-before.txt').write_bytes(shell(['iptables-save']).stdout)
    shell(['am','force-stop','me.rerere.stream.test'])
    write_pref(ET.tostring(root,encoding='utf-8',xml_declaration=True))
    for rule in routes:
        shell(['iptables','-t','nat','-I','OUTPUT',*rule]);added.append(rule)
    print('Running existing receive-only fixture on emulator-5560; Windows UDP destination injected',flush=True)
    result=shell(['am','instrument','-w','-r','-e','class','me.rerere.stream.StreamInstrumentedTest#audioAndTrafficAcceptance',
        '-e','software','true','-e','width','1280','-e','height','720','-e','fps','30','-e','bitrate','4000','-e','timeout','15000',
        'me.rerere.stream.test/androidx.test.runner.AndroidJUnitRunner'],check=False,timeout=110)
    (OUT/'instrumentation.txt').write_bytes(result.stdout+result.stderr)
    success=result.returncode==0 and b'OK (' in result.stdout and b'FAILURES' not in result.stdout
    print((result.stdout+result.stderr).decode(errors='replace'),flush=True)
    logs=shell(['logcat','-d','-s','StreamAcceptance:I','*:S']).stdout
    (OUT/'stats.txt').write_bytes(logs)
    print(logs.decode(errors='replace')[-10000:],flush=True)
finally:
    try:
        for rule in reversed(added):shell(['iptables','-t','nat','-D','OUTPUT',*rule],check=False)
        shell(['am','force-stop','me.rerere.stream.test'],check=False)
        write_pref(backup)
        (OUT/'iptables-after.txt').write_bytes(shell(['iptables-save']).stdout)
    finally:
        adb('unroot',check=False)
    print('Restored original test identity prefs and removed only experiment NAT rules',flush=True)
raise SystemExit(0 if success else 1)
