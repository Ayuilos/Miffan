#!/usr/bin/env python3
"""Check a short-lived Session 1 lease for this run's own Notepad, never user text."""
import json,subprocess,sys,time,uuid
from pathlib import Path
from ssh_ps import command

nonce=str(uuid.uuid4());started=time.monotonic()
v={}
script=f"""$d=Join-Path $env:TEMP 'miffan-windows-spike';$timer=[Diagnostics.Stopwatch]::StartNew();[IO.File]::WriteAllText((Join-Path $d 'input-observer-request.txt'),'{nonce}');$deadline=(Get-Date).AddSeconds(3);do{{$p=Join-Path $d 'input-observer-result.json';if(Test-Path $p){{try{{$v=[IO.File]::ReadAllText($p)|ConvertFrom-Json;if($v.RequestId -eq '{nonce}'){{$v|Add-Member -NotePropertyName ResponseAfterRequestMs -NotePropertyValue $timer.ElapsedMilliseconds;[Console]::Write(($v|ConvertTo-Json -Compress));exit 0}}}}catch{{}}}};Start-Sleep -Milliseconds 50}}while((Get-Date)-lt $deadline);exit 1"""
r=subprocess.run(['ssh','-T','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),
 'ekk@192.168.31.61',command(script)],capture_output=True,timeout=5,stdin=subprocess.DEVNULL)
try:
 v=json.loads(r.stdout)
 ok=r.returncode==0 and v['RequestId']==nonce and v['Active'] and v['ForegroundHwnd']==788388 and v['WindowId']==788388 and v['TargetPid']==24040 and v['Session']==1 and v['ResponseAfterRequestMs']<500 and time.monotonic()-started<5
 # Protocol/Windows absolute-coordinate quantization can round by one pixel.
 if '--cursor-check' in sys.argv:ok=ok and abs(v.get('CursorX',-1000)-600)<=1 and abs(v.get('CursorY',-1000)-500)<=1 and v.get('CursorRootHwnd')==788388
except (ValueError,KeyError):ok=False
print('notepad_input_lease='+('active' if ok else 'refused'),flush=True)
if '--inspect' in sys.argv:print(json.dumps(dict(v,client_elapsed_ms=round((time.monotonic()-started)*1000,1)) if r.returncode==0 else {'error':'observer timeout'},ensure_ascii=False),flush=True)
sys.exit(0 if ok else 1)
