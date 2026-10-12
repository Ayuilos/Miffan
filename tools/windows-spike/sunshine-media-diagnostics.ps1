$ErrorActionPreference='Stop'
$root='C:\Program Files\Sunshine\config'
Write-Output 'CONFIG (non-secret media keys only)'
Get-Content (Join-Path $root 'sunshine.conf') | Where-Object {$_ -match '^\s*(lan_encryption_mode|wan_encryption_mode|audio_sink|virtual_sink|encoder|output_name|capture|minimum_fps_target)\s*='} | ForEach-Object {[string]$_}
Write-Output 'SESSION / ENCODER / AUDIO LOG (bounded filter; no request payloads)'
Get-ChildItem $root -Filter 'sunshine.log*' | Sort-Object LastWriteTime | ForEach-Object {
    Get-Content $_.FullName -Tail 350 | Where-Object {$_ -match 'Creating encoder|NvEnc: created encoder|CLIENT CONNECTED|CLIENT DISCONNECTED|Audio|audio|WASAPI|Opus|opus|Default.*device|sample rate|capture.*fail|Desktop resolution|Display refresh'} | ForEach-Object {[string]$_}
}
Write-Output 'AUDIO DEVICE STATUS (no playback)'
Get-CimInstance Win32_SoundDevice | Select-Object Name,Status,ConfigManagerErrorCode | ConvertTo-Json -Depth 3
