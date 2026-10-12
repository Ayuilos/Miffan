# Authorized installation only. Never edits firewall rules itself.
# The firewall check is the first operation after msiexec finishes.
$ErrorActionPreference = 'Stop'
$spikeDir = Join-Path $env:TEMP 'miffan-windows-spike'
$installer = Join-Path $spikeDir 'Sunshine-Windows-AMD64-installer.msi'
if (Test-Path 'C:\Program Files\Sunshine') { throw 'Sunshine already exists; inspect before retrying' }
if (-not (Test-Path (Join-Path $spikeDir 'firewall-before.clixml'))) { throw 'Missing baseline' }
$hash = (Get-FileHash -Algorithm SHA256 $installer).Hash.ToLower()
if ($hash -ne '1d7fed8beecd5889dc7ff14cf9f42d6d38f37c3066c13c6c2a5f4e91847e0ccf') { throw 'MSI hash mismatch' }
Write-Output ('INSTALLER_SIGNATURE=' + (Get-AuthenticodeSignature $installer).Status)
$process = Start-Process msiexec.exe -ArgumentList @('/i', ('"'+$installer+'"'), '/qn', '/norestart', 'REBOOT=ReallySuppress') -NoNewWindow -PassThru
$pending = -not $process.WaitForExit(90000)
if ($pending) {
    Write-Output 'INSTALLER_PENDING: inspect status, do not repeat or dismiss any desktop prompt'
}
# Immediately inspect all Sunshine inbound rules; Any port includes the four TCP ports.
$rules = @(Get-NetFirewallRule -Direction Inbound | Where-Object {
    $_.DisplayName -match 'Sunshine' -or $_.Name -match 'Sunshine'
} | ForEach-Object {
    $rule = $_; $ports = $rule | Get-NetFirewallPortFilter
    $address = $rule | Get-NetFirewallAddressFilter; $app = $rule | Get-NetFirewallApplicationFilter
    [pscustomobject]@{Name=$rule.Name;DisplayName=$rule.DisplayName;Enabled="$($rule.Enabled)";
        Action="$($rule.Action)";Profile="$($rule.Profile)";Protocol=$ports.Protocol;
        LocalPort=$ports.LocalPort;RemoteAddress=$address.RemoteAddress;Program=$app.Program}
})
[pscustomobject]@{InstallerPending=$pending;InstallerExit=$process.ExitCode;Rules=$rules} | ConvertTo-Json -Depth 6
# Always return to the controller after this check. No configuration/pairing here.
if ($pending) { exit 124 }
