#!/usr/bin/env python3
"""Check a short-lived Session 1 lease for this run's own Notepad, never user text."""
import datetime,json,subprocess,sys
from pathlib import Path
from ssh_ps import command

r=subprocess.run(['ssh','-T','-o','BatchMode=yes','-i',str(Path.home()/'.ssh/id_ed25519_new'),
 'ekk@192.168.31.61',command("[IO.File]::ReadAllText((Join-Path $env:TEMP 'miffan-windows-spike\\input-target.json'))")],
 capture_output=True,timeout=5,stdin=subprocess.DEVNULL)
try:
 v=json.loads(r.stdout);age=(datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(v['Time'])).total_seconds()
 ok=r.returncode==0 and v['Active'] and v['WindowId']==788388 and v['TargetPid']==24040 and v['Session']==1 and 0<=age<0.5
except (ValueError,KeyError):ok=False
print('notepad_input_lease='+('active' if ok else 'refused'),flush=True)
sys.exit(0 if ok else 1)
