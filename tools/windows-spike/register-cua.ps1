$ErrorActionPreference='Stop'
$binary=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
if(-not(Test-Path $binary)){throw 'Missing installed binary'}
if(Get-ScheduledTask -TaskName 'cua-driver-serve' -ErrorAction SilentlyContinue){throw 'Task exists; refusing overwrite'}
$action=New-ScheduledTaskAction -Execute $binary -Argument 'serve'
$userSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$trigger=New-ScheduledTaskTrigger -AtLogOn -User $userSid
$principal=New-ScheduledTaskPrincipal -UserId $userSid -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'cua-driver-serve' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Miffan Windows spike per-user background daemon' | Out-Null
Start-ScheduledTask -TaskName 'cua-driver-serve'
Get-ScheduledTask -TaskName 'cua-driver-serve' | Select-Object TaskName,State,Principal,Actions | ConvertTo-Json -Depth 5
