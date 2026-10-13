$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$task=Get-ScheduledTask -TaskName 'cua-driver-serve'
$rules=@(Get-NetFirewallRule -Direction Inbound|Where-Object {$_.DisplayName -match 'Sunshine' -or $_.Name -match 'Sunshine'}|ForEach-Object {
 $r=$_;$p=$r|Get-NetFirewallPortFilter;$a=$r|Get-NetFirewallAddressFilter;$app=$r|Get-NetFirewallApplicationFilter
 [pscustomobject]@{Name=[string]$r.Name;Enabled=[string]$r.Enabled;Protocol=[string]$p.Protocol;LocalPort=[string[]]$p.LocalPort;Sources=[string[]]$a.RemoteAddress;Program=[string]$app.Program}
})
[pscustomobject]@{
 Time=(Get-Date -Format o);
 Sunshine=(Get-Service -Name SunshineService|Select-Object Name,@{n='State';e={[string]$_.Status}});
 Drivers=@(Get-CimInstance Win32_Process|Where-Object {$_.Name -in @('cua-driver.exe','sunshine.exe','sunshinesvc.exe','input-observer.exe','input-observer-cursor.exe')}|Select-Object Name,ProcessId,SessionId);
 CuaTask=[pscustomobject]@{State=[string]$task.State;UserId=[string]$task.Principal.UserId;RunLevel=[string]$task.Principal.RunLevel;LogonType=[string]$task.Principal.LogonType;Execute=[string]$task.Actions.Execute;Arguments=[string]$task.Actions.Arguments};
 ExperimentTasks=@(Get-ScheduledTask|Where-Object TaskName -like 'Miffan-Windows-Spike-*'|Select-Object TaskName,@{n='State';e={[string]$_.State}});
 Encryption=[IO.File]::ReadAllText('C:\Program Files\Sunshine\config\sunshine.conf');
 Rules=$rules;
 TemporaryFiles=@(Get-ChildItem $dir -File|Select-Object Name,Length);
 UserPathContainsCua=([Environment]::GetEnvironmentVariable('Path','User') -like '*Programs\Cua\cua-driver\bin*')
}|ConvertTo-Json -Depth 5
