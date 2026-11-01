# Offline tests for `relay` (Relay.ps1). No scheduled task, process or network is touched: the paths point
# into a temporary LOCALAPPDATA, and the connector "binary" is only a file whose checksum is compared.
param(
    [string]$HelperPath = (Join-Path $PSScriptRoot '..\..\..\app\build\generated\remoteHelper\remote\miffan.ps1')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. $HelperPath
$script:Passed = 0
# Scheduled tasks are never touched: these shadow the real cmdlets (functions win over cmdlets).
function Get-ScheduledTask { return @() }
function Stop-ScheduledTask { }
function Unregister-ScheduledTask { }
function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw "Assertion failed: $Message" } }
function Assert-Equal($Actual, $Expected, [string]$Message) { if ($Actual -cne $Expected) { throw "Assertion failed: $Message; expected [$Expected], actual [$Actual]" } }
function Test([string]$Name, [scriptblock]$Body) { & $Body; $script:Passed++; [Console]::Out.WriteLine("PASS $Name") }
function Get-RelayFailure([scriptblock]$Body) {
    try { & $Body } catch {
        $data = $_.Exception.Data
        if ($data -and $data.Contains('relay_reason')) { return [string]$data['relay_reason'] }
        throw
    }
    return $null
}

$savedLocal = $env:LOCALAPPDATA
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('relay-test-' + [Guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($sandbox)
$env:LOCALAPPDATA = $sandbox
$env:MIFFAN_CONNECTOR_FILE = ''
$env:MIFFAN_CONNECTOR_SHA256 = ''
try {
    Test 'the pinned digest is a placeholder that never matches, and the version is pinned' {
        Assert-Equal $script:RelayConnectorVersion '1.0.0' 'pinned version'
        Assert-Equal (Get-RelayExpectedSha 'windows-amd64') ('0' * 64) 'placeholder until the release exists'
        Assert-Equal (Get-RelayExpectedSha 'linux-amd64') '' 'unknown platform has no digest'
        $env:MIFFAN_CONNECTOR_SHA256 = 'ABCDEF'
        Assert-Equal (Get-RelayExpectedSha 'windows-amd64') 'abcdef' 'override is lower-cased'
        $env:MIFFAN_CONNECTOR_SHA256 = ''
    }
    Test 'platform detection' {
        $saved = @($env:PROCESSOR_ARCHITECTURE, $env:PROCESSOR_ARCHITEW6432)
        try {
            $env:PROCESSOR_ARCHITEW6432 = ''
            $env:PROCESSOR_ARCHITECTURE = 'AMD64'; Assert-Equal (Get-RelayPlatform) 'windows-amd64' 'AMD64'
            $env:PROCESSOR_ARCHITECTURE = 'ARM64'; Assert-Equal (Get-RelayPlatform) 'windows-amd64' 'ARM64 runs the x64 build'
            $env:PROCESSOR_ARCHITECTURE = 'x86'; Assert-True ($null -eq (Get-RelayPlatform)) 'x86 unsupported'
            $env:PROCESSOR_ARCHITEW6432 = 'AMD64'; Assert-Equal (Get-RelayPlatform) 'windows-amd64' '32-bit host on 64-bit Windows'
        } finally { $env:PROCESSOR_ARCHITECTURE = $saved[0]; $env:PROCESSOR_ARCHITEW6432 = $saved[1] }
    }
    Test 'download locations: the GitHub release first, then mirrors' {
        $urls = @(Get-RelayDownloadUrls 'windows-amd64')
        Assert-Equal $urls.Count 1 'no mirrors yet'
        Assert-Equal $urls[0] 'https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v1.0.0/miffan-connector-windows-amd64.exe' 'release URL'
        $saved = $script:RelayMirrors
        try {
            $script:RelayMirrors = @('https://mirror.example/connector/')
            $urls = @(Get-RelayDownloadUrls 'windows-amd64')
            Assert-Equal $urls[1] 'https://mirror.example/connector/miffan-connector-windows-amd64.exe' 'mirror URL'
        } finally { $script:RelayMirrors = $saved }
    }
    Test 'status line merging' {
        $extras = @{ installed = $true; service = 'running'; pinnedVersion = '1.0.0'; linger = $null }
        $ok = Merge-RelayJsonLine '{"connected":true,"devices":[{"id":"x"}],"success":true,"version":"1.0.0"}' $extras
        $value = $ok | ConvertFrom-Json
        Assert-True ($value.success -eq $true -and $value.connected -eq $true -and $value.installed -eq $true) 'both sides present'
        Assert-Equal $value.service 'running' 'service'
        Assert-Equal $value.devices[0].id 'x' 'nested arrays survive'
        Assert-True ($null -eq $value.linger) 'linger null'
        $bad = (Merge-RelayJsonLine '{"detail":"x","reason":"not_initialized","success":false}' $extras) | ConvertFrom-Json
        Assert-Equal $bad.error 'not_initialized' 'reason becomes error'
        Assert-True ($bad.installed -eq $true -and $bad.service -eq 'running') 'helper fields kept'
        $none = (Merge-RelayJsonLine '' $extras) | ConvertFrom-Json
        Assert-Equal $none.error 'connector_failed' 'no answer'
    }
    Test 'last line of connector output' {
        Assert-Equal (Get-RelayLastLine "noise`r`n{`"a`":1}`r`n`r`n") '{"a":1}' 'last non-empty line'
        Assert-Equal (Get-RelayLastLine '') '' 'empty'
    }
    Test 'a wrong checksum installs nothing and leaves no temporary file' {
        $paths = Get-RelayPaths
        $fake = Join-Path $sandbox 'fake-connector.exe'
        [IO.File]::WriteAllBytes($fake, [byte[]](1, 2, 3, 4))
        $env:MIFFAN_CONNECTOR_FILE = $fake
        try {
            $env:MIFFAN_CONNECTOR_SHA256 = ('f' * 64)
            Assert-Equal (Get-RelayFailure { Install-RelayBinary 'windows-amd64' }) 'checksum_mismatch' 'wrong digest'
            $env:MIFFAN_CONNECTOR_SHA256 = ''
            Assert-Equal (Get-RelayFailure { Install-RelayBinary 'windows-amd64' }) 'checksum_mismatch' 'placeholder digest'
        } finally { $env:MIFFAN_CONNECTOR_FILE = ''; $env:MIFFAN_CONNECTOR_SHA256 = '' }
        Assert-True (-not (Test-Path -LiteralPath $paths.bin)) 'nothing installed'
        Assert-Equal @(Get-ChildItem -LiteralPath $paths.binDir -Force -ErrorAction SilentlyContinue).Count 0 'no leftovers'
    }
    Test 'a missing source file is reported, not installed' {
        $env:MIFFAN_CONNECTOR_FILE = (Join-Path $sandbox 'missing.exe')
        try { Assert-Equal (Get-RelayFailure { Install-RelayBinary 'windows-amd64' }) 'install_failed' 'missing file' }
        finally { $env:MIFFAN_CONNECTOR_FILE = '' }
    }
    Test 'commands need an installed connector; unpair validates the id before anything runs' {
        Assert-Equal (Get-RelayFailure { Invoke-RelayPair '{}' }) 'not_installed' 'pair'
        Assert-Equal (Get-RelayFailure { Invoke-RelayUnpair '{}' }) 'not_installed' 'unpair'
        $paths = Get-RelayPaths
        $null = [IO.Directory]::CreateDirectory($paths.binDir)
        [IO.File]::WriteAllBytes($paths.bin, [byte[]](0))  # exists, but would fail if it were ever started
        foreach ($bad in @('{}', '', 'not json', '{"deviceId":"short"}', '{"deviceId":"DO6P7C6WSQ3X7AUS7OVINATTGO"}', '{"deviceId":"x; rm -rf ~"}', '{"deviceId":["do6p7c6wsq3x7aus7ovinattgo"]}')) {
            Assert-Equal (Get-RelayFailure { Invoke-RelayUnpair $bad }) 'bad_input' ("unpair " + $bad)
        }
        Remove-Item -LiteralPath $paths.bin -Force
    }
    Test 'install with empty input and unknown actions fail cleanly' {
        $saved = $env:PROCESSOR_ARCHITECTURE
        try { $env:PROCESSOR_ARCHITECTURE = 'AMD64'; $env:PROCESSOR_ARCHITEW6432 = ''; Assert-Equal (Get-RelayFailure { Invoke-RelayInstall '  ' }) 'bad_input' 'blank relay' }
        finally { $env:PROCESSOR_ARCHITECTURE = $saved }
        Assert-Equal (Get-RelayFailure { Invoke-Relay 'bogus' '' }) 'usage' 'unknown action'
        Assert-Equal (Get-RelayFailure { Invoke-Relay 'status' 'extra' }) 'usage' 'extra argument'
    }
    Test 'status before install' {
        $status = Get-RelayStatus
        Assert-True ($status -is [hashtable] -and $status.installed -eq $false) 'not installed'
        Assert-Equal (ConvertTo-PlainJson $status) '{"installed":false}' 'JSON shape'
    }
    Test 'uninstall keeps host.key and config.json unless asked' {
        $paths = Get-RelayPaths
        $null = [IO.Directory]::CreateDirectory($paths.dataDir)
        $null = [IO.Directory]::CreateDirectory($paths.binDir)
        foreach ($name in @('host.key', 'config.json', 'state.json', 'run.lock')) { [IO.File]::WriteAllBytes((Join-Path $paths.dataDir $name), [byte[]](1)) }
        [IO.File]::WriteAllBytes($paths.bin, [byte[]](1))
        $result = Invoke-RelayUninstall ''
        Assert-True ($result.success -eq $true -and $result.identityKept -eq $true) 'identity kept'
        Assert-True ((Test-Path -LiteralPath (Join-Path $paths.dataDir 'host.key')) -and (Test-Path -LiteralPath (Join-Path $paths.dataDir 'config.json'))) 'identity files stay'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $paths.dataDir 'state.json'))) 'state removed'
        Assert-True (-not (Test-Path -LiteralPath $paths.bin)) 'binary removed'
        $result = Invoke-RelayUninstall '{"forgetIdentity":true}'
        Assert-True ($result.identityKept -eq $false -and -not (Test-Path -LiteralPath $paths.dataDir)) 'identity forgotten on request'
    }
} finally {
    $env:LOCALAPPDATA = $savedLocal
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}
[Console]::Out.WriteLine((ConvertTo-PlainJson @{success=$true;passed=$script:Passed;scope='offline relay tests; scheduled task, process and network untouched'}))
