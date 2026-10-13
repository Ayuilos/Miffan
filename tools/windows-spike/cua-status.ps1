$ErrorActionPreference='Stop'
$binary=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
& $binary --version
& $binary status
& $binary doctor
& $binary autostart status
Get-Process cua-driver -ErrorAction SilentlyContinue | Select-Object Id,SessionId,Path | ConvertTo-Json
Get-ScheduledTask -TaskName 'cua-driver-serve' -ErrorAction SilentlyContinue | Select-Object TaskName,State,Principal,Actions | ConvertTo-Json -Depth 5
