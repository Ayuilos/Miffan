param([int]$TargetPid,[long]$WindowId)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class SpikeMetrics {
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 public struct RECT {public int Left,Top,Right,Bottom;}
}
'@
[uint32]$owner=0
[void][SpikeMetrics]::GetWindowThreadProcessId([IntPtr]$WindowId,[ref]$owner)
if($owner -ne $TargetPid -or (Get-Process -Id $owner).ProcessName -ne 'Notepad'){throw 'Exact experimental Notepad target is missing'}
[void][SpikeMetrics]::SetThreadDpiAwarenessContext([IntPtr](-4))
$rect=New-Object SpikeMetrics+RECT
[void][SpikeMetrics]::GetWindowRect([IntPtr]$WindowId,[ref]$rect)
Add-Type -AssemblyName System.Windows.Forms
$result=[pscustomobject]@{
 Session=(Get-Process -Id $PID).SessionId;TargetPid=$owner;WindowId=$WindowId;
 Dpi=[SpikeMetrics]::GetDpiForWindow([IntPtr]$WindowId);Rect=$rect;
 Screens=@([Windows.Forms.Screen]::AllScreens|ForEach-Object {[pscustomobject]@{DeviceName=$_.DeviceName;Primary=$_.Primary;Bounds=$_.Bounds}})
}
[IO.File]::WriteAllText((Join-Path $env:TEMP 'miffan-windows-spike\window-metrics.json'),($result|ConvertTo-Json -Depth 4),(New-Object Text.UTF8Encoding($false)))
