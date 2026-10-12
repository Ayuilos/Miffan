param([Parameter(Mandatory=$true)][string]$BackupXml,[switch]$Apply)
$ErrorActionPreference='Stop'
$xml=[IO.File]::ReadAllText($BackupXml)
[xml]$check=$xml
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
$level=[string]$check.Task.Principals.Principal.RunLevel
# Export-ScheduledTask omits the default LeastPrivilege element in this backup.
if($check.Task.Principals.Principal.LogonType -ne 'InteractiveToken' -or ($level -and $level -ne 'LeastPrivilege')){throw 'Expected the backed-up Limited / Interactive task'}
if(-not $Apply){Write-Output 'Validated XML. No mutation; pass -Apply after closing all experiment MCP clients.';exit 0}
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$currentBackup=Join-Path $dir ('cua-driver-serve.before-restore-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.xml')
[IO.File]::WriteAllText($currentBackup,(Export-ScheduledTask -TaskName 'cua-driver-serve'),[Text.Encoding]::Unicode)
Stop-ScheduledTask -TaskName 'cua-driver-serve'
Register-ScheduledTask -TaskName 'cua-driver-serve' -Xml $xml -Force|Out-Null
Start-ScheduledTask -TaskName 'cua-driver-serve'
Write-Output ('PRE_RESTORE_BACKUP='+$currentBackup)
Get-ScheduledTask -TaskName 'cua-driver-serve'|Select-Object TaskName,State,@{n='RunLevel';e={$_.Principal.RunLevel}},@{n='LogonType';e={$_.Principal.LogonType}}|ConvertTo-Json
