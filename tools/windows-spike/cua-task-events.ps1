$ErrorActionPreference='Stop'
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
Get-ScheduledTaskInfo $task|Select-Object LastRunTime,LastTaskResult,NextRunTime,NumberOfMissedRuns|ConvertTo-Json
Export-ScheduledTask -TaskName 'cua-driver-serve'
Get-Process -ErrorAction SilentlyContinue|Where-Object ProcessName -match '^(consent|smartscreen|cua-driver)$'|Select-Object Id,ProcessName,SessionId|ConvertTo-Json
Get-WinEvent -FilterHashtable @{LogName='Microsoft-Windows-TaskScheduler/Operational';StartTime=(Get-Date).AddMinutes(-10)} -MaxEvents 60 -ErrorAction SilentlyContinue|Where-Object Message -match 'cua-driver-serve'|Select-Object TimeCreated,Id,Message|ConvertTo-Json
