$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$name='Miffan-Windows-Spike-Input-Observer'
$task=Get-ScheduledTask -TaskName $name
if($task.Actions.Execute -ne (Join-Path $dir 'input-observer.exe')){throw 'Unexpected observer action'}
$backup=Join-Path $dir 'input-observer.task-before-cursor.xml'
if(Test-Path $backup){throw 'Backup already exists'}
[IO.File]::WriteAllText($backup,(Export-ScheduledTask -TaskName $name),[Text.Encoding]::Unicode)
$exe=Join-Path $dir 'input-observer-cursor.exe'
if(Test-Path $exe){throw 'Cursor observer binary already exists'}
Add-Type -TypeDefinition ([IO.File]::ReadAllText((Join-Path $dir 'input-observer-cursor.cs'))) -OutputAssembly $exe -OutputType WindowsApplication
Stop-ScheduledTask -TaskName $name
Set-ScheduledTask -TaskName $name -Action (New-ScheduledTaskAction -Execute $exe)|Out-Null
Start-ScheduledTask -TaskName $name
Write-Output ('TASK_BACKUP='+$backup)
