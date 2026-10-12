$ErrorActionPreference='Stop'
$root='C:\Program Files\Sunshine'
$config=Join-Path $root 'config\sunshine.conf'
if(@(Get-NetUDPEndpoint | Where-Object LocalPort -in 47998,47999,48000).Count){throw 'Active/pending stream; refusing config change'}
$content=[IO.File]::ReadAllText($config)
$backup=$config+'.bak-windows-spike-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
Copy-Item -LiteralPath $config -Destination $backup -ErrorAction Stop
$lines=@($content -split '\r?\n' | Where-Object { $_ -notmatch '^\s*(lan_encryption_mode|wan_encryption_mode)\s*=' })
$lines+=@('lan_encryption_mode = 2','wan_encryption_mode = 2')
$temp=$config+'.windows-spike.tmp'
[IO.File]::WriteAllText($temp,($lines -join "`n")+"`n",(New-Object Text.UTF8Encoding($false)))
if([IO.File]::ReadAllText($config)-ne $content){Remove-Item $temp;throw 'Configuration changed concurrently'}
if(@(Get-NetUDPEndpoint | Where-Object LocalPort -in 47998,47999,48000).Count){Remove-Item $temp;throw 'Stream started concurrently'}
Move-Item -LiteralPath $temp -Destination $config -Force
Restart-Service SunshineService
[pscustomobject]@{Success=$true;Backup=$backup;LanEncryptionMode=2;WanEncryptionMode=2}|ConvertTo-Json
