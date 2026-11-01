# --- Relay connector: `relay` (helper 15) ---
# Installs, pairs and removes miffan-connector.exe, the agent that lets the phone reach this computer
# through a Miffan relay (relay/PROTOCOL.md). Same commands and JSON as miffan.sh's `relay`
# (remote-helper/PROTOCOL.md). User data arrives on stdin and is handed to the connector unchanged
# (it parses and validates it); it never goes through a command line.
# The connector runs as a logon scheduled task of this user with ordinary permissions, like the cua
# daemon. Nothing here needs administrator rights.
#
# The release binary is pinned by version and SHA-256, taken from the release's SHA256SUMS when
# relay-connector-v$RelayConnectorVersion was published.

$script:RelayConnectorVersion = '1.0.1'
$script:RelayConnectorSha256 = @{ 'windows-amd64' = '99b68004e6fe30c868dff460539f9361d2279f6847b2f9f1648bbba4b2dee6a2' }
$script:RelayReleaseUrl = 'https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v' + $script:RelayConnectorVersion
# Extra download locations tried after the GitHub release, same file names. None yet.
$script:RelayMirrors = @()
$script:RelayTaskName = 'Miffan-connector'

# Ends the command with {"success":false,"reason":code,"detail":text} (exit 1).
function Stop-Relay([string]$Reason, [string]$Detail) {
    $failure = New-Object InvalidOperationException 'relay'
    $failure.Data['relay_reason'] = $Reason
    $failure.Data['relay_detail'] = $Detail
    throw $failure
}

function Get-RelayPaths {
    $root = Join-Path $env:LOCALAPPDATA 'Miffan'
    return @{
        binDir = Join-Path $root 'bin'
        bin = Join-Path $root 'bin\miffan-connector.exe'
        dataDir = Join-Path $root 'connector'
        log = Join-Path $root 'connector\connector.log'
    }
}

# windows-amd64 for 64-bit Windows (ARM64 runs the x64 build under emulation); $null elsewhere.
function Get-RelayPlatform {
    $arch = $(if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE })
    if ($arch -in @('AMD64', 'ARM64')) { return 'windows-amd64' }
    return $null
}

# The pinned SHA-256 for a platform. MIFFAN_CONNECTOR_SHA256 overrides it (tests, development).
function Get-RelayExpectedSha([string]$Platform) {
    if ($env:MIFFAN_CONNECTOR_SHA256) { return $env:MIFFAN_CONNECTOR_SHA256.ToLowerInvariant() }
    if ($script:RelayConnectorSha256.ContainsKey($Platform)) { return $script:RelayConnectorSha256[$Platform] }
    return ''
}

function Get-RelayFileSha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-RelayDownloadUrls([string]$Platform) {
    $name = 'miffan-connector-' + $Platform + '.exe'
    $urls = @($script:RelayReleaseUrl + '/' + $name)
    foreach ($mirror in $script:RelayMirrors) { $urls += ($mirror.TrimEnd('/') + '/' + $name) }
    return $urls
}

# Downloads the first URL that works (the system proxy applies, as for the cua installer).
function Save-RelayDownload($Urls, [string]$Destination) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    foreach ($url in $Urls) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Destination -TimeoutSec 300
            if ((Test-Path -LiteralPath $Destination) -and (Get-Item -LiteralPath $Destination).Length -gt 0) { return $true }
        } catch { }
    }
    return $false
}

function Get-RelayLastLine([string]$Text) {
    $lines = @(([string]$Text) -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count) { return $lines[$lines.Count - 1] }
    return ''
}

# Runs the connector with fixed words (and a validated --device-id=) as arguments; $StdinText goes to its stdin.
function Invoke-RelayConnector([string[]]$Arguments, [string]$StdinText, [int]$TimeoutSeconds = 60) {
    $paths = Get-RelayPaths
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $paths.bin
    $start.Arguments = ($Arguments -join ' ')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $script:Utf8; $start.StandardErrorEncoding = $script:Utf8
    $process = [Diagnostics.Process]::Start($start)
    try {
        $bytes = $script:Utf8.GetBytes([string]$StdinText)
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { $process.Kill(); return @{ code = 124; out = ''; err = '' } }
        $process.WaitForExit()
        return @{ code = $process.ExitCode; out = $output.Result; err = $errors.Result }
    } finally { $process.Dispose() }
}

function Get-RelayInstalledVersion {
    $paths = Get-RelayPaths
    if (-not (Test-Path -LiteralPath $paths.bin)) { return '' }
    try {
        $result = Invoke-RelayConnector @('version') '' 15
        if ($result.code -ne 0) { return '' }
        $value = (Get-RelayLastLine $result.out) | ConvertFrom-Json
        return [string]$value.version
    } catch { return '' }
}

function Get-RelayTask {
    $tasks = @(Get-ScheduledTask -TaskName $script:RelayTaskName -TaskPath '\' -ErrorAction SilentlyContinue)
    if ($tasks.Count -gt 1) { throw 'Ambiguous relay task' }
    if ($tasks.Count) { return $tasks[0] }
    return $null
}

# running, stopped or missing.
function Get-RelayServiceState {
    $task = Get-RelayTask
    if (-not $task) { return 'missing' }
    if ([string]$task.State -eq 'Running') { return 'running' }
    return 'stopped'
}

# Stops the scheduled task and any connector process of ours, so the executable can be replaced or removed.
function Stop-RelayConnector {
    $paths = Get-RelayPaths
    if (Get-RelayTask) { Stop-ScheduledTask -TaskName $script:RelayTaskName -ErrorAction SilentlyContinue }
    foreach ($process in @(Get-Process -Name 'miffan-connector' -ErrorAction SilentlyContinue)) {
        $same = $false
        try { $same = ($process.Path -eq $paths.bin) } catch { }
        if ($same) { try { $process.Kill(); $null = $process.WaitForExit(10000) } catch { } }
    }
}

function Register-RelayTask {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $existing = Get-RelayTask
    if ($existing -and -not (Test-CuaTaskOwner $existing $sid)) { Stop-Relay 'service_failed' 'the relay task belongs to another user' }
    # The task starts this helper's hidden `relay run`, which starts the connector without a console window
    # and keeps its log. The scheduler restarts it after a crash.
    $arguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $script:HelperFile + '" relay run'
    $action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments
    $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $sid
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
    try {
        $null = Register-ScheduledTask -TaskName $script:RelayTaskName -Action $action -Principal $principal -Trigger $trigger -Settings $settings -Force -ErrorAction Stop
        Start-ScheduledTask -TaskName $script:RelayTaskName -ErrorAction Stop
    } catch { Stop-Relay 'service_failed' 'the scheduled task could not be created or started' }
}

# Keeps the connector's log from growing without bound: past 1 MiB only the last 128 KiB stay.
function Limit-RelayLog {
    $log = (Get-RelayPaths).log
    try {
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -gt 1048576) {
            $bytes = [IO.File]::ReadAllBytes($log)
            $keep = New-Object byte[] 131072
            [Array]::Copy($bytes, $bytes.Length - 131072, $keep, 0, 131072)
            [IO.File]::WriteAllBytes($log, $keep)
        }
    } catch { }
}

# Gets the connector into place, verified. Returns $true when the binary changed.
function Install-RelayBinary([string]$Platform) {
    $paths = Get-RelayPaths
    $useFile = [bool]$env:MIFFAN_CONNECTOR_FILE
    if (-not $useFile -and (Get-RelayInstalledVersion) -eq $script:RelayConnectorVersion) { return $false }
    $expected = Get-RelayExpectedSha $Platform
    $null = [IO.Directory]::CreateDirectory($paths.binDir)
    $temp = Join-Path $paths.binDir ('.miffan-connector-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        if ($useFile) {
            try { Copy-Item -LiteralPath $env:MIFFAN_CONNECTOR_FILE -Destination $temp -Force -ErrorAction Stop }
            catch { Stop-Relay 'install_failed' 'cannot read MIFFAN_CONNECTOR_FILE' }
        } elseif (-not (Save-RelayDownload (Get-RelayDownloadUrls $Platform) $temp)) {
            Stop-Relay 'download_failed' 'could not download the connector'
        }
        if (-not $expected -or (Get-RelayFileSha256 $temp) -cne $expected) {
            Stop-Relay 'checksum_mismatch' 'the connector does not match its pinned SHA-256; nothing was installed'
        }
        # A running executable cannot be replaced on Windows.
        Stop-RelayConnector
        $moved = $false
        for ($attempt = 0; $attempt -lt 10 -and -not $moved; $attempt++) {
            try { Move-Item -LiteralPath $temp -Destination $paths.bin -Force -ErrorAction Stop; $moved = $true }
            catch { Start-Sleep -Milliseconds 300 }
        }
        if (-not $moved) { Stop-Relay 'install_failed' 'cannot install the connector' }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue }
    }
    return $true
}

# Adds the helper's own fields to the connector's one-line JSON. Pure; $Extras is a hashtable.
function Merge-RelayJsonLine([string]$Line, $Extras) {
    $extra = (ConvertTo-PlainJson $Extras).Trim()
    if ($Line -match '^\{.*"success"\s*:\s*true.*\}$') {
        $trimmed = $Line.TrimEnd()
        return $trimmed.Substring(0, $trimmed.Length - 1) + ',' + $extra.Substring(1)
    }
    $reason = 'connector_failed'
    if ($Line -match '"reason"\s*:\s*"([^"]*)"') { $reason = $Matches[1] }
    $copy = @{}
    foreach ($key in $Extras.Keys) { $copy[$key] = $Extras[$key] }
    $copy['error'] = $reason
    return ConvertTo-PlainJson $copy
}

function Get-RelayStatus {
    $paths = Get-RelayPaths
    if (-not (Test-Path -LiteralPath $paths.bin)) { return @{ installed = $false } }
    $extras = @{ installed = $true; service = (Get-RelayServiceState); pinnedVersion = $script:RelayConnectorVersion; linger = $null }
    $line = ''
    try { $line = Get-RelayLastLine (Invoke-RelayConnector @('status') '' 30).out } catch { }
    return Merge-RelayJsonLine $line $extras
}

# A connector JSON line is passed through; the exit code follows its success field.
function Complete-RelayPassthrough($Result) {
    $line = Get-RelayLastLine $Result.out
    if ($line -notmatch '^\{.*\}$') {
        $line = '{"success":false,"reason":"connector_failed","detail":"the connector gave no answer"}'
    }
    if ($Result.code -ne 0 -or $line -notmatch '"success"\s*:\s*true') { $script:RelayExit = 1 }
    return $line
}

function Assert-RelayInstalled {
    if (-not (Test-Path -LiteralPath (Get-RelayPaths).bin)) { Stop-Relay 'not_installed' 'the connector is not installed' }
}

# stdin {"relay":"https://...","accessToken":"..."?}
function Invoke-RelayInstall([string]$Json) {
    $platform = Get-RelayPlatform
    if (-not $platform) { Stop-Relay 'unsupported_platform' 'no connector build for this processor' }
    if ([string]::IsNullOrWhiteSpace($Json)) { Stop-Relay 'bad_input' 'relay is required' }
    $null = Install-RelayBinary $platform
    $init = Invoke-RelayConnector @('init', '--stdin') $Json
    if ($init.code -ne 0) {
        $line = Complete-RelayPassthrough $init
        if ($line -match '"success"\s*:\s*true') { $line = '{"success":false,"reason":"init_failed","detail":"the connector could not be set up"}' }
        return $line
    }
    Limit-RelayLog
    Register-RelayTask
    return Get-RelayStatus
}

# stdin {"device":"<b64u SPKI>","name":"..."}
function Invoke-RelayPair([string]$Json) {
    Assert-RelayInstalled
    return Complete-RelayPassthrough (Invoke-RelayConnector @('pair', '--stdin') $Json)
}

# stdin {"deviceId":"..."}
function Invoke-RelayUnpair([string]$Json) {
    Assert-RelayInstalled
    $request = $null
    try { $request = $Json | ConvertFrom-Json } catch { }
    $id = $(if ($request -and $request.deviceId -is [string]) { $request.deviceId } else { '' })
    if ($id -cnotmatch '^[a-z2-7]{26}$') { Stop-Relay 'bad_input' 'deviceId is not valid' }
    return Complete-RelayPassthrough (Invoke-RelayConnector @('unpair', ('--device-id=' + $id)) '')
}

# stdin (optional) {"forgetIdentity":true}. host.key and the paired-device list stay unless asked, so a
# reinstall keeps the same hostId and the paired phones keep working.
function Invoke-RelayUninstall([string]$Json) {
    $forget = $false
    try { $request = $Json | ConvertFrom-Json; $forget = ($request -and $request.forgetIdentity -eq $true) } catch { }
    $paths = Get-RelayPaths
    Stop-RelayConnector
    if (Get-RelayTask) { Unregister-ScheduledTask -TaskName $script:RelayTaskName -TaskPath '\' -Confirm:$false -ErrorAction SilentlyContinue }
    foreach ($file in @($paths.bin, $paths.log, ($paths.log + '.tmp'), (Join-Path $paths.dataDir 'state.json'), (Join-Path $paths.dataDir 'run.lock'), (Join-Path $paths.dataDir 'config.lock'))) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue }
    }
    if ($forget -and (Test-Path -LiteralPath $paths.dataDir)) { Remove-Item -LiteralPath $paths.dataDir -Recurse -Force -ErrorAction SilentlyContinue }
    return @{ success = $true; identityKept = (-not $forget) }
}

# The scheduled task's entry point: the connector in a hidden window, its stderr appended to connector.log.
function Invoke-RelayRun {
    $paths = Get-RelayPaths
    if (-not (Test-Path -LiteralPath $paths.bin)) { exit 127 }
    Limit-RelayLog
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $paths.bin; $start.Arguments = 'run'
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardError = $true
    $start.StandardErrorEncoding = $script:Utf8
    $process = [Diagnostics.Process]::Start($start)
    $process.StandardInput.Close()
    $writer = New-Object IO.StreamWriter -ArgumentList $paths.log, $true, $script:Utf8
    $writer.AutoFlush = $true
    $lines = 0
    try {
        while ($null -ne ($line = $process.StandardError.ReadLine())) {
            $writer.WriteLine($line)
            $lines++
            if ($lines % 500 -eq 0) { $writer.Dispose(); Limit-RelayLog; $writer = New-Object IO.StreamWriter -ArgumentList $paths.log, $true, $script:Utf8; $writer.AutoFlush = $true }
        }
        $process.WaitForExit()
        $code = $process.ExitCode
    } finally { $writer.Dispose(); $process.Dispose() }
    exit $code
}

# Returns a hashtable (printed as JSON) or a ready-made JSON line (a string).
function Invoke-Relay([string]$Action, [string]$Extra) {
    if ($Extra) { Stop-Relay 'usage' 'unexpected argument' }
    switch -CaseSensitive ($Action) {
        'status' { return Get-RelayStatus }
        'install' { return Invoke-RelayInstall ([Console]::In.ReadToEnd()) }
        'pair' { return Invoke-RelayPair ([Console]::In.ReadToEnd()) }
        'unpair' { return Invoke-RelayUnpair ([Console]::In.ReadToEnd()) }
        'uninstall' { return Invoke-RelayUninstall ([Console]::In.ReadToEnd()) }
        default { Stop-Relay 'usage' 'Usage: relay status|install|pair|unpair|uninstall' }
    }
}
