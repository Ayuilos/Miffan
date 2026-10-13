$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
if(Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -ErrorAction SilentlyContinue){throw 'Bridge task still exists; inspect before resume'}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$bridge=Join-Path $dir 'mcp-bridge'
if(Test-Path $bridge){Move-Item $bridge ($bridge+'.bak-'+$stamp)}
$script=Join-Path $dir 'cua-session-bridge.ps1'
if(Test-Path $script){Copy-Item $script ($script+'.bak-'+$stamp)}
Get-CimInstance Win32_Process | Where-Object Name -eq 'cua-driver.exe' | Select-Object ProcessId,SessionId,CommandLine | ConvertTo-Json
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
[pscustomobject]@{State="$($task.State)";User=$task.Principal.UserId;LogonType="$($task.Principal.LogonType)";RunLevel="$($task.Principal.RunLevel)"}|ConvertTo-Json
