$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$bridge=Join-Path $dir 'mcp-bridge'
if(Test-Path $bridge){throw 'Existing bridge artifacts; inspect before retry'}
if(Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -ErrorAction SilentlyContinue){throw 'Existing task; refuse overwrite'}
New-Item -ItemType Directory $bridge|Out-Null
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -File "'+(Join-Path $dir 'cua-session-bridge.ps1')+'"')
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 35) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -Action $action -Principal $principal -Settings $settings|Out-Null
Start-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP'
Write-Output 'Temporary Limited Interactive MCP bridge started; delete task after experiment'
