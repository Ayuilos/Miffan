$ErrorActionPreference='Stop'
$name='Miffan-Windows-Spike-Input-Observer'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$task=Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
if($task){
 if($task.Actions.Execute -notin @((Join-Path $dir 'input-observer.exe'),(Join-Path $dir 'input-observer-cursor.exe'))){throw 'Unexpected observer action; refuse cleanup'}
 [IO.File]::WriteAllText((Join-Path $dir 'input-observer.task-final.xml'),(Export-ScheduledTask -TaskName $name),[Text.Encoding]::Unicode)
 [IO.File]::WriteAllText((Join-Path $dir 'input-observer-stop'),'stop')
 Stop-ScheduledTask -TaskName $name
 Unregister-ScheduledTask -TaskName $name -Confirm:$false
}
[pscustomobject]@{ObserverTaskRemaining=[bool](Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue);ObserverProcesses=@(Get-CimInstance Win32_Process|Where-Object {$_.ExecutablePath -in @((Join-Path $dir 'input-observer.exe'),(Join-Path $dir 'input-observer-cursor.exe'))}|Select-Object Name,ProcessId,SessionId);CuaTask=(Get-ScheduledTask -TaskName 'cua-driver-serve').Principal|Select-Object UserId,RunLevel,LogonType}|ConvertTo-Json -Depth 4
