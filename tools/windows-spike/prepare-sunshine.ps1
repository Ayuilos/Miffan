$ErrorActionPreference = 'Stop'
$spikeDir = Join-Path $env:TEMP 'miffan-windows-spike'
New-Item -ItemType Directory -Force $spikeDir | Out-Null
if (Test-Path (Join-Path $spikeDir 'firewall-before.clixml')) { throw 'Baseline already exists; inspect before retrying' }
Get-NetFirewallRule | Export-Clixml (Join-Path $spikeDir 'firewall-before.clixml')
Get-NetFirewallRule | Get-NetFirewallPortFilter | Export-Clixml (Join-Path $spikeDir 'firewall-ports-before.clixml')
$release = Invoke-RestMethod 'https://api.github.com/repos/LizardByte/Sunshine/releases/latest' -TimeoutSec 45
if ($release.prerelease -or $release.draft) { throw 'Not a stable release' }
$asset = $release.assets | Where-Object name -eq 'Sunshine-Windows-AMD64-installer.msi'
if (-not $asset) { $asset = $release.assets | Where-Object name -eq 'Sunshine-Windows-AMD64-installer.exe' }
if (-not $asset) { throw 'No official AMD64 installer' }
$dest = Join-Path $spikeDir $asset.name
Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $dest -TimeoutSec 90
$hash = (Get-FileHash -Algorithm SHA256 $dest).Hash.ToLower()
if ($asset.digest -and $asset.digest -ne "sha256:$hash") { throw 'Release digest mismatch' }
[pscustomobject]@{Version=$release.tag_name; Published=$release.published_at; Url=$asset.browser_download_url;
    Installer=$dest; Sha256=$hash; Digest=$asset.digest; Signature=(Get-AuthenticodeSignature $dest).Status.ToString();
    BackupDirectory=$spikeDir} | ConvertTo-Json
