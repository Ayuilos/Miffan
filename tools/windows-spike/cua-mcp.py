#!/usr/bin/env python3
"""Persistent SSH stdio MCP diagnostic client; stdin accepts one JSON-RPC body per line.

Writes results under --out (default /private/tmp/windows-spike-cua). Never sends foreground actions
or desktop input automatically. The operator chooses each explicit observed target.
"""
import argparse,base64,json,os,queue,subprocess,sys,threading
from pathlib import Path

parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,default=Path('/private/tmp/windows-spike-cua'))
parser.add_argument('--redact-window',type=int,action='append',default=[])
options=parser.parse_args();os.umask(0o077);OUT=options.out;OUT.mkdir(mode=0o700,exist_ok=True);OUT.chmod(0o700)
remote='"C:\\Users\\Ekk\\AppData\\Local\\Programs\\Cua\\cua-driver\\bin\\cua-driver.exe" mcp --socket \\\\.\\pipe\\cua-driver'
proc=subprocess.Popen(['ssh','-T','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),
    'ekk@192.168.31.61',remote],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(OUT/'stderr.txt').open('w'),text=True,encoding='utf-8',bufsize=1)
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
    if body.get('method')=='tools/call' and body.get('params',{}).get('name')=='get_window_state':
        args=body['params'].get('arguments',{})
        if args.get('window_id') in options.redact_window:args['include_screenshot']=False
    proc.stdin.write(json.dumps(dict(jsonrpc='2.0',id=counter,**body),ensure_ascii=False)+'\n');proc.stdin.flush()
    while True:
        result=responses.get(timeout=40)
        if result.get('id')==counter: return result
def compact(result):
    value=result.get('result',result)
    structured=value.get('structuredContent',{})
    if structured.get('window_id') in options.redact_window:
        title=structured.get('window_title')
        if isinstance(title,str):
            for c in value.get('content',[]):
                if isinstance(c.get('text'),str):c['text']=c['text'].replace(title,'<restored historical window>')
            if isinstance(structured.get('tree_markdown'),str):structured['tree_markdown']=structured['tree_markdown'].replace(title,'<restored historical window>')
            structured['window_title']='<restored historical window>'
        value['content']=[c for c in value.get('content',[]) if c.get('type')!='image']
        hidden=[e.get('value') for e in structured.get('elements',[]) if e.get('role') in ('Document','Edit','Text') and isinstance(e.get('value'),str)]
        for secret in hidden:
            if not secret:continue
            for c in value.get('content',[]):
                if isinstance(c.get('text'),str):c['text']=c['text'].replace(secret,'<redacted historical document>')
            if isinstance(structured.get('tree_markdown'),str):structured['tree_markdown']=structured['tree_markdown'].replace(secret,'<redacted historical document>')
        for e in structured.get('elements',[]):
            if e.get('role') in ('Document','Edit','Text'):e.pop('value',None)
            if title and e.get('value')==title:e['value']='<restored historical window>'
            if title and e.get('label')==title:e['label']='<restored historical window>'
    raw=OUT/f'result-{counter}.json';raw.write_text(json.dumps(result,ensure_ascii=False))
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
