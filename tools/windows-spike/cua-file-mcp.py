#!/usr/bin/env python3
"""Persistent experiment MCP client over the temporary interactive-session file bridge."""
import argparse,base64,json,os,subprocess,sys,time
from pathlib import Path
from ssh_ps import command

parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,default=Path('/private/tmp/windows-spike-cua-bridge'))
OUT=parser.parse_args().out;os.umask(0o077);OUT.mkdir(mode=0o700,exist_ok=True);OUT.chmod(0o700)
REMOTE='C:/Users/Ekk/AppData/Local/Temp/miffan-windows-spike/mcp-bridge'
SSH=['ssh','-T','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),'ekk@192.168.31.61']
sequence=0;counter=0
def rpc(body,notify=False):
    global sequence,counter
    sequence+=1;counter+=1
    request={'jsonrpc':'2.0',**body}
    if not notify:request['id']=counter
    local=OUT/f'in-{sequence}.json';local.write_text(json.dumps(request,ensure_ascii=False))
    subprocess.run(['scp','-q','-i',str(Path.home()/'.ssh/id_ed25519_new'),str(local),'ekk@192.168.31.61:'+REMOTE+f'/in-{sequence}.tmp'],check=True,stdin=subprocess.DEVNULL)
    ps=f"Move-Item '{REMOTE}/in-{sequence}.tmp' '{REMOTE}/in-{sequence}.json'; "
    if not notify:
        ps+=f"$deadline=(Get-Date).AddSeconds(45); while(-not(Test-Path '{REMOTE}/out-{sequence}.json')){{ if((Get-Date)-gt $deadline){{throw 'Bridge timeout'}}; Start-Sleep -Milliseconds 100 }}; Write-Output 'ready'"
    result=subprocess.run(SSH+[command(ps)],capture_output=True,timeout=55,stdin=subprocess.DEVNULL)
    if result.returncode:raise RuntimeError('SSH bridge returned error; inspect remote stderr')
    if notify:return {}
    raw=OUT/f'result-{sequence}.json'
    subprocess.run(['scp','-q','-i',str(Path.home()/'.ssh/id_ed25519_new'),'ekk@192.168.31.61:'+REMOTE+f'/out-{sequence}.json',str(raw)],check=True,stdin=subprocess.DEVNULL)
    reply=json.loads(raw.read_text(encoding='utf-8'))
    value=reply.get('result',reply)
    if 'tools' in value:
        return {'file':str(raw),'tools':[{'name':x['name'],'properties':list(x.get('inputSchema',{}).get('properties',{})),'required':x.get('inputSchema',{}).get('required',[])} for x in value['tools']]}
    for i,c in enumerate(value.get('content',[])):
        if c.get('type')=='image':
            path=OUT/f'image-{sequence}-{i}.png';path.write_bytes(base64.b64decode(c['data']));c.clear();c.update(type='image_file',path=str(path))
    return {'file':str(raw),'response':value}
try:
    print(json.dumps(rpc({'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'miffan-win-spike','version':'1'}}})),flush=True)
    rpc({'method':'notifications/initialized'},True)
    for line in sys.stdin:
        body=json.loads(line)
        if body.get('method')=='exit':break
        print(json.dumps(rpc(body),ensure_ascii=False)[:18000],flush=True)
finally:
    subprocess.run(SSH+[command(f"New-Item -ItemType File '{REMOTE}/stop' -Force|Out-Null")],capture_output=True,timeout=15,stdin=subprocess.DEVNULL)
