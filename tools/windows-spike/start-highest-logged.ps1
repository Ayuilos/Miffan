$ErrorActionPreference='Stop'
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
if($task.Principal.RunLevel -ne 'Highest' -or $task.Principal.LogonType -ne 'Interactive' -or $task.State -eq 'Running'){throw 'Unexpected task state; refuse diagnostic wrapper'}
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$backup=Join-Path $dir ('cua-driver-serve.highest-before-wrapper-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.xml')
[IO.File]::WriteAllText($backup,(Export-ScheduledTask -TaskName 'cua-driver-serve'),[Text.Encoding]::Unicode)
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -File "'+(Join-Path $dir 'cua-logged-daemon.ps1')+'"')
Set-ScheduledTask -TaskName 'cua-driver-serve' -Action $action|Out-Null
Start-ScheduledTask -TaskName 'cua-driver-serve'
Write-Output ('DIAGNOSTIC_ACTION_BACKUP='+$backup)
