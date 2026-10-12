$ErrorActionPreference = 'Stop'
Get-ChildItem (Join-Path $env:TEMP 'miffan-windows-spike') | Select-Object Name,Length,LastWriteTime | ConvertTo-Json
Get-Process powershell -ErrorAction SilentlyContinue | Select-Object Id,SessionId,StartTime | ConvertTo-Json
