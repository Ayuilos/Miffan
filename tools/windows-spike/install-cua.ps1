$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
if(Test-Path "$env:USERPROFILE\.cua-driver\packages\current\cua-driver.exe"){throw 'Existing cua-driver install; inspect before changes'}
if(Get-ScheduledTask -TaskName 'cua-driver-serve' -ErrorAction SilentlyContinue){throw 'Existing autostart task; refusing overwrite'}
[IO.File]::WriteAllText((Join-Path $dir 'user-path-before.txt'),[Environment]::GetEnvironmentVariable('Path','User'))
'absent'|Set-Content (Join-Path $dir 'cua-task-before.txt')
# Temporary per-process proxy over SSH; no system/user proxy configuration changes.
$PSDefaultParameterValues['Invoke-WebRequest:Proxy']='http://127.0.0.1:18889'
$PSDefaultParameterValues['Invoke-RestMethod:Proxy']='http://127.0.0.1:18889'
& (Join-Path $dir 'install-cua-official.ps1') -NoAutoStart -Release 0.34.0
if(-not $?){throw 'Official installer failed'}
$binary=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
& $binary --version
# Register an explicitly limited, per-user interactive task for the spec's medium-IL test.
# Official installer currently defaults to Highest; do not invoke that registration here.
# Register separately after inspecting the installation result.
