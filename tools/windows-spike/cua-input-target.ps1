param([int]$TargetPid,[long]$WindowId)
# Temporary foreground lease for the experiment's exact Notepad only.
# No input is synthesized here; focus loss invalidates the probe lease.
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class SpikeInputTarget {
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@
[uint32]$owner=0
[void][SpikeInputTarget]::GetWindowThreadProcessId([IntPtr]$WindowId,[ref]$owner)
if($owner -ne $TargetPid -or (Get-Process -Id $owner).ProcessName -ne 'Notepad'){throw 'Experimental Notepad is missing'}
$path=Join-Path $env:TEMP 'miffan-windows-spike\input-target.json'
$stop=Join-Path $env:TEMP 'miffan-windows-spike\input-target-stop'
if(Test-Path $stop){Remove-Item $stop}
$prior=[SpikeInputTarget]::GetForegroundWindow()
[void][SpikeInputTarget]::SetForegroundWindow([IntPtr]$WindowId)
$deadline=(Get-Date).AddSeconds(35)
try {
 do {
  $active=[SpikeInputTarget]::GetForegroundWindow().ToInt64() -eq $WindowId
  $state=@{Active=$active;WindowId=$WindowId;TargetPid=$owner;Session=(Get-Process -Id $PID).SessionId;Time=(Get-Date -Format o)}
  $tmp=$path+'.tmp'
  [IO.File]::WriteAllText($tmp,($state|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
  Move-Item $tmp $path -Force
  if(-not $active){break}
  Start-Sleep -Milliseconds 100
 } while((Get-Date) -lt $deadline -and -not(Test-Path $stop))
} finally {
 if([SpikeInputTarget]::GetForegroundWindow().ToInt64() -eq $WindowId){[void][SpikeInputTarget]::SetForegroundWindow($prior)}
 [IO.File]::WriteAllText($path,'{"Active":false}')
}
