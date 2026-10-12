$ErrorActionPreference='Stop'
$root='C:\Program Files\Sunshine'
$processes=@(Get-CimInstance Win32_Process | Where-Object Name -match '^sunshine(svc)?\.exe$' | Select-Object Name,ProcessId,SessionId,ExecutablePath,CommandLine)
$rules=@(Get-NetFirewallRule -Direction Inbound | Where-Object { $_.DisplayName -match 'Sunshine' -or $_.Name -match 'Sunshine' } | ForEach-Object {
    $r=$_; $p=$r|Get-NetFirewallPortFilter; $a=$r|Get-NetFirewallAddressFilter; $app=$r|Get-NetFirewallApplicationFilter
    [pscustomobject]@{Name=$r.Name;Enabled="$($r.Enabled)";Action="$($r.Action)";Profile="$($r.Profile)";Protocol=$p.Protocol;LocalPort=$p.LocalPort;RemoteAddress=$a.RemoteAddress;Program=$app.Program}
})
$files=@(Get-ChildItem $root -ErrorAction SilentlyContinue | Select-Object Name,Length)
$config=Join-Path $root 'config\sunshine.conf'
$publicCert=Join-Path $root 'config\credentials\cacert.pem'
$fingerprint=$null
if(Test-Path $publicCert){
    $pem=[IO.File]::ReadAllText($publicCert)
    $raw=[Convert]::FromBase64String(($pem -replace '-----[^-]+-----','' -replace '\s',''))
    $fingerprint=([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($raw))).Replace('-','').ToLower()
}
$listeners=@(Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 47984,47989,47990,48010 | Select-Object LocalAddress,LocalPort,OwningProcess)
[pscustomobject]@{
    Version=(Get-Item "$root\sunshine.exe").VersionInfo | Select-Object FileVersion,ProductVersion;
    Services=@(Get-CimInstance Win32_Service | Where-Object Name -match 'Sunshine' | Select-Object Name,State,StartName,PathName,ProcessId,StartMode);
    Processes=$processes;Files=$files;Rules=$rules;ConfigPath=$config;
    Config=if(Test-Path $config){Get-Content $config}else{$null};
    CertificateSha256=$fingerprint;Listeners=$listeners;
    Udp=@(Get-NetUDPEndpoint | Where-Object LocalPort -in 47998,47999,48000 | Select-Object LocalAddress,LocalPort,OwningProcess);
    ConfigFiles=@(Get-ChildItem "$root\config" -Recurse -File -ErrorAction SilentlyContinue | Select-Object FullName,Length)
}|ConvertTo-Json -Depth 7
# Only diagnostic encoder lines, never dump general request logs.
if(Test-Path "$root\config\sunshine.log"){
    Get-Content "$root\config\sunshine.log" -Tail 250 | Select-String 'Sunshine version|Found.*encoder|Trying encoder|NVENC|H.264|HEVC|Display|Resolution|Error|Fatal' | ForEach-Object {$_.Line}
}
