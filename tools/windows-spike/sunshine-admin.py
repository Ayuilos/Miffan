#!/usr/bin/env python3
"""Test-only pinned Sunshine admin API. Secrets stay in the authorized credential directory."""
import argparse, base64, hashlib, http.client, json, os, re, secrets, ssl, subprocess, sys, time
from pathlib import Path

PRIVATE=Path('/Users/chenxiansheng/miffan/.deps/windows-spike')
PIN='8edb025280591442b733c675de6dafb1b011b0f69abddd1f18ed178b14bf512d'

def request(path, data=None, authenticated=True):
    creds=json.loads((PRIVATE/'admin.json').read_text())
    conn=http.client.HTTPSConnection('127.0.0.1',57990,timeout=15,context=ssl._create_unverified_context())
    conn.connect()
    if hashlib.sha256(conn.sock.getpeercert(binary_form=True)).hexdigest()!=PIN:
        conn.close(); raise RuntimeError('Host certificate mismatch')
    headers={'Content-Type':'application/json'}
    if authenticated:
        headers['Authorization']='Basic '+base64.b64encode((creds['username']+':'+creds['password']).encode()).decode()
    conn.request('GET' if data is None else 'POST',path,None if data is None else json.dumps(data),headers)
    response=conn.getresponse(); body=response.read(); status=response.status; conn.close()
    if status!=200: raise RuntimeError('Admin request failed, HTTP '+str(status))
    result=json.loads(body)
    if result.get('status') is False and path!='/api/pin': raise RuntimeError('Admin operation returned failure')
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['create','pair']);a=p.parse_args()
    if a.mode=='create':
        PRIVATE.mkdir(mode=0o700,parents=True,exist_ok=True); os.chmod(PRIVATE,0o700)
        path=PRIVATE/'admin.json'
        if path.exists():
            creds=json.loads(path.read_text())
        else:
            fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            creds={'username':'miffan-win-spike','password':secrets.token_urlsafe(32)}
            with os.fdopen(fd,'w') as f: json.dump(creds,f)
        request('/api/password',{'newUsername':creds['username'],'newPassword':creds['password'],'confirmNewPassword':creds['password']},False)
        print('Test admin created; password stored only in authorized admin.json (0600)')
    else:
        probe=Path(__file__).resolve().parents[1]/'p6a-probe/build/p6a-probe'
        keydir=PRIVATE/'probe-identity'
        proc=subprocess.Popen([str(probe),'pair','--http-host','127.0.0.1','--http-port','57989','--https-port','57984',
            '--keydir',str(keydir),'--name','miffan-win-spike'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        sent=False
        try:
            for line in proc.stdout:
                match=re.search(r'^PIN=(\d{4})',line)
                if match:
                    pin=match.group(1);time.sleep(2)
                    identity=(keydir/'uniqueid.dat').read_text().strip()
                    pending=[]
                    for attempt in range(8):
                        pending=request('/api/pin').get('pairings',[])
                        # Only choose our request; never approve another client.
                        own=[x for x in pending if x.get('name')=='miffan-win-spike' or x.get('id')==identity]
                        if len(own)==1: break
                        time.sleep(1)
                    else: raise RuntimeError('Could not identify exactly one test pairing request')
                    accepted=request('/api/pin',{'pairing_id':own[0]['id'],'pin':pin,'name':'miffan-win-spike'})
                    sent=accepted.get('status',False); print('Pair API completion status='+str(sent)+' (PIN withheld)')
                elif not re.search(r'PIN|pin|密钥',line):
                    print(line.rstrip())
            if proc.wait()!=0 or not sent: raise RuntimeError('Probe pairing failed')
            print('Pairing completed')
        finally:
            if proc.poll() is None: proc.terminate();proc.wait(timeout=15)

if __name__=='__main__':
    try: main()
    except Exception as error:
        detail=str(error) if isinstance(error,RuntimeError) else type(error).__name__
        print('Operation failed: '+detail,file=sys.stderr);sys.exit(1)
