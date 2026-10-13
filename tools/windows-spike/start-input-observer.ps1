$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$name='Miffan-Windows-Spike-Input-Observer'
if(Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue){throw 'Observer task already exists; inspect instead of overwriting'}
$exe=Join-Path $dir 'input-observer.exe'
if(Test-Path $exe){throw 'Observer binary already exists; refuse overwrite'}
Add-Type -TypeDefinition ([IO.File]::ReadAllText((Join-Path $dir 'input-observer.cs'))) -OutputAssembly $exe -OutputType WindowsApplication
[IO.File]::WriteAllText((Join-Path $dir 'input-observer-task-before.txt'),'absent')
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Highest
$action=New-ScheduledTaskAction -Execute $exe
$settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes 11) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $name -Action $action -Principal $principal -Settings $settings|Out-Null
Start-ScheduledTask -TaskName $name
Get-ScheduledTask -TaskName $name|Select-Object TaskName,State|ConvertTo-Json
