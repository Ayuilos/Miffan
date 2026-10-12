$ErrorActionPreference='Stop'
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
$binary=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
if($task.Principal.RunLevel -ne 'Limited' -or $task.Principal.LogonType -ne 'Interactive' -or
   $task.Actions.Count -ne 1 -or $task.Actions.Execute -ne $binary -or $task.Actions.Arguments -ne 'serve'){
    throw 'Unexpected task identity; refusing change'
}
if(Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -ErrorAction SilentlyContinue){throw 'Close temporary MCP bridge first'}
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$backup=Join-Path $dir ('cua-driver-serve.limited-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.xml')
$xml=Export-ScheduledTask -TaskName 'cua-driver-serve'
[IO.File]::WriteAllText($backup,$xml,[Text.Encoding]::Unicode)
if(-not(Test-Path $backup)){throw 'Backup missing'}
Write-Output ('TASK_XML_BACKUP='+$backup)
Write-Output ('TASK_XML_SHA256='+(Get-FileHash $backup -Algorithm SHA256).Hash.ToLower())
Stop-ScheduledTask -TaskName 'cua-driver-serve'
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Highest
Set-ScheduledTask -TaskName 'cua-driver-serve' -Principal $principal|Out-Null
Start-ScheduledTask -TaskName 'cua-driver-serve'
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
[pscustomobject]@{State="$($task.State)";User=$task.Principal.UserId;LogonType="$($task.Principal.LogonType)";RunLevel="$($task.Principal.RunLevel)";Backup=$backup}|ConvertTo-Json
