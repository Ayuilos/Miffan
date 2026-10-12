#!/usr/bin/env python3
"""Persistent SSH stdio MCP diagnostic client; stdin accepts one JSON-RPC body per line.

Writes raw results only to /private/tmp/windows-spike-cua. Never sends foreground actions
or desktop input automatically. The operator chooses each explicit observed target.
"""
import base64,json,queue,subprocess,sys,threading
from pathlib import Path

OUT=Path('/private/tmp/windows-spike-cua');OUT.mkdir(exist_ok=True)
remote='"C:\\Users\\Ekk\\AppData\\Local\\Programs\\Cua\\cua-driver\\bin\\cua-driver.exe" mcp --socket \\\\.\\pipe\\cua-driver'
proc=subprocess.Popen(['ssh','-T','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),
    'ekk@192.168.31.61',remote],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(OUT/'stderr.txt').open('w'),text=True,bufsize=1)
responses=queue.Queue()
def receive():
    for line in proc.stdout:
        try: responses.put(json.loads(line))
        except ValueError: pass
threading.Thread(target=receive,daemon=True).start()
counter=0
def rpc(body):
    global counter
    counter+=1
    proc.stdin.write(json.dumps(dict(jsonrpc='2.0',id=counter,**body),ensure_ascii=False)+'\n');proc.stdin.flush()
    while True:
        result=responses.get(timeout=40)
        if result.get('id')==counter: return result
def compact(result):
    raw=OUT/f'result-{counter}.json';raw.write_text(json.dumps(result,ensure_ascii=False))
    value=result.get('result',result)
    if 'tools' in value:
        return {'file':str(raw),'tools':[{'name':x['name'],'properties':list(x.get('inputSchema',{}).get('properties',{})),
            'required':x.get('inputSchema',{}).get('required',[])} for x in value['tools']]}
    for i,c in enumerate(value.get('content',[])):
        if c.get('type')=='image':
            dest=OUT/f'image-{counter}-{i}.png';dest.write_bytes(base64.b64decode(c['data']))
            c.clear();c.update(type='image_file',path=str(dest))
    return {'file':str(raw),'response':value}
try:
    initial=rpc({'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'miffan-win-spike','version':'1'}}})
    print(json.dumps(initial),flush=True)
    proc.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n');proc.stdin.flush()
    for line in sys.stdin:
        body=json.loads(line)
        if body.get('method')=='exit': break
        try: print(json.dumps(compact(rpc(body)),ensure_ascii=False)[:20000],flush=True)
        except queue.Empty: print('MCP timeout; inspect before retry',flush=True)
finally:
    proc.stdin.close()
    try:proc.wait(timeout=10)
    except subprocess.TimeoutExpired:proc.terminate();proc.wait(timeout=10)
