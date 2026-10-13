$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
foreach($name in 'high-daemon-process.json','high-daemon-exit.txt','high-daemon-stderr.txt'){
    if(Test-Path (Join-Path $dir $name)){Write-Output $name; Get-Content (Join-Path $dir $name)}
}
Get-CimInstance Win32_Process|Where-Object Name -eq 'cua-driver.exe'|Select-Object ProcessId,SessionId,CommandLine|ConvertTo-Json
