$ErrorActionPreference='Stop'
Get-CimInstance Win32_Process | Where-Object Name -eq 'cua-driver.exe' | Select-Object ProcessId,SessionId,CommandLine | ConvertTo-Json
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
[pscustomobject]@{State="$($task.State)";User=$task.Principal.UserId;LogonType="$($task.Principal.LogonType)";RunLevel="$($task.Principal.RunLevel)";Action=@($task.Actions|Select-Object Execute,Arguments);LastResult=(Get-ScheduledTaskInfo $task).LastTaskResult}|ConvertTo-Json -Depth 3
Get-ChildItem "$env:USERPROFILE\.cua-driver" -File -Recurse | Where-Object Extension -in '.log','.txt' | Select-Object FullName,Length | ConvertTo-Json
Get-ChildItem '\\.\pipe\' | Where-Object Name -match 'cua' | Select-Object Name | ConvertTo-Json
