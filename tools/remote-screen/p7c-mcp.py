#!/usr/bin/env python3
"""P7c SSH byte-stream acceptance. Uses only the SPEC test key path, never key contents.
Default checks MCP/schema + read-only enumeration; no clipboard or app launch.
"""
import base64
import json
from pathlib import Path
import selectors
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path('/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties')
OUT = ROOT / 'app/build/test-output/p7c'
# Same binary frame/stdio handoff as RemoteShell.windowsProcessBootstrap.
BOOTSTRAP = r"""
$ProgressPreference='SilentlyContinue'
$ErrorActionPreference='Stop'
$utf8=New-Object Text.UTF8Encoding($false,$true)
[Console]::InputEncoding=$utf8;[Console]::OutputEncoding=$utf8;$OutputEncoding=$utf8
Add-Type -TypeDefinition @'
using System;using System.IO;using System.Text;using System.Runtime.InteropServices;using System.Threading;
public static class MiffanFrame{
[DllImport("kernel32.dll")]static extern IntPtr GetStdHandle(int n);
[DllImport("kernel32.dll",SetLastError=true)]static extern bool ReadFile(IntPtr h,IntPtr b,int n,out int r,IntPtr o);
[DllImport("kernel32.dll",SetLastError=true)]static extern bool GetOverlappedResult(IntPtr h,IntPtr o,out int r,bool wait);
static byte[] Data(int n){if(n>1048576)throw new IOException();byte[] b=new byte[n];if(n==0)return b;var pin=GCHandle.Alloc(b,GCHandleType.Pinned);IntPtr o=Marshal.AllocHGlobal(32);using(var e=new EventWaitHandle(false,EventResetMode.ManualReset)){try{for(int p=0;p<n;){Marshal.Copy(new byte[32],0,o,32);Marshal.WriteIntPtr(o,IntPtr.Size*2+8,new IntPtr(e.SafeWaitHandle.DangerousGetHandle().ToInt64()|1));int r;if(!ReadFile(GetStdHandle(-10),IntPtr.Add(pin.AddrOfPinnedObject(),p),n-p,out r,o)){if(Marshal.GetLastWin32Error()!=997||!GetOverlappedResult(GetStdHandle(-10),o,out r,true))throw new IOException();}if(r==0)throw new EndOfStreamException();p+=r;}return b;}finally{pin.Free();Marshal.FreeHGlobal(o);}}}
static int Len(){int n=0,k=0;while(true){int c=Data(1)[0];if(c==10&&k>0)return n;if(c<48||c>57||++k>7)throw new IOException();n=n*10+c-48;}}
public static string[] Read(){int a=Len(),b=Len();var u=new UTF8Encoding(false,true);return new[]{u.GetString(Data(a)),u.GetString(Data(b))};}}
'@
try{$f=[MiffanFrame]::Read();Set-Location -LiteralPath $f[0];[Environment]::CurrentDirectory=(Get-Location).ProviderPath;$global:LASTEXITCODE=0;& ([ScriptBlock]::Create($f[1]));exit $LASTEXITCODE}
catch{[Console]::Error.WriteLine('Remote stdio process failed');exit 1}
"""

class Mcp:
    def __init__(self):
        values = dict(line.split('=', 1) for line in CONFIG.read_text().splitlines() if line and not line.startswith('#'))
        command = 'powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ' + base64.b64encode(BOOTSTRAP.encode('utf-16le')).decode()
        self.process = subprocess.Popen(['ssh', '-T', '-oBatchMode=yes', '-oStrictHostKeyChecking=yes', '-i', values['key'], '-p', values['port'], values['user']+'@'+values['host'], command], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        cwd = b'C:\\Users\\Ekk'
        script = b"& (Join-Path $env:LOCALAPPDATA 'Miffan\\bin\\miffan.ps1') cua"
        self.process.stdin.write(f'{len(cwd)}\n{len(script)}\n'.encode() + cwd + script)
        self.process.stdin.flush()
        self.next_id = 0
        self.selector = selectors.DefaultSelector(); self.selector.register(self.process.stdout, selectors.EVENT_READ)
        try:
            self.rpc('initialize', {'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'miffan-p7c-test','version':'1.0'}})
            self.send({'jsonrpc':'2.0','method':'notifications/initialized','params':{}})
        except BaseException:
            self.close()
            raise

    def send(self, value):
        self.process.stdin.write((json.dumps(value, ensure_ascii=False)+'\n').encode()); self.process.stdin.flush()

    def rpc(self, method, params=None):
        self.next_id += 1; request_id = self.next_id
        self.send({'jsonrpc':'2.0','id':request_id,'method':method,'params':params or {}})
        while True:
            if not self.selector.select(35):
                raise TimeoutError('MCP response timed out; no action replay')
            data = self.process.stdout.readline()
            if not data:
                raise RuntimeError('MCP closed: '+self.process.stderr.read().decode(errors="replace"))
            response = json.loads(data)
            if response.get('id') != request_id:
                continue
            if 'error' in response:
                raise RuntimeError(str(response['error']))
            return response['result']

    def close(self):
        self.selector.close()
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.terminate(); self.process.wait(timeout=10)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    mcp = Mcp()
    try:
        tools = mcp.rpc('tools/list')['tools']
        resources = mcp.rpc('resources/list')['resources']
        (OUT/'schema.json').write_text(json.dumps({'tools':tools,'resources':resources}, ensure_ascii=False, indent=2))
        windows = mcp.rpc('tools/call', {'name':'list_windows','arguments':{}})
        assert not windows.get('isError'), 'Window enumeration refused'
        # No user titles/content written to evidence/output.
        print(json.dumps({'success':True,'tools':len(tools),'resources':len(resources),'list_windows':True}))
    finally:
        mcp.close()

if __name__ == '__main__':
    main()
