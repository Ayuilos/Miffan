# --- Partner mini boxes: `space` (helper 14) ---
# A partner's own Linux computer: a cua Space (container) that cua runs on Docker Desktop (WSL 2). Same
# commands, JSON and error codes as miffan.sh's `space` (remote-helper/PROTOCOL.md). The Space token only
# ever reaches stdout of `info`: never stderr, a file of ours or a process argument.
# Processes started from an SSH session die with it (OpenSSH puts them in its job), so Docker Desktop and
# the cua daemon run as tasks in the signed-in user's own session, with ordinary permissions.

$script:SpaceHandoffDir = '/home/cua/Desktop/Shared'
$script:SpaceStateDir = Join-Path $env:USERPROFILE '.miffan'
$script:SpaceDockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
$script:SpaceDockerCli = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
$script:SpaceDaemonTask = 'Miffan-cua-spaces'
$script:SpaceDockerTask = 'Miffan-docker-desktop'
# Docker Desktop's installer is signed by Docker Inc; nothing else is run.
$script:SpaceDockerSigner = 'CN=Docker Inc'

# Ends the command with {"error":code,"detail":text|null} (exit 1).
function Stop-Space([string]$Code, [string]$Detail) {
    $failure = New-Object InvalidOperationException 'space'
    $failure.Data['space_code'] = $Code
    $failure.Data['space_detail'] = $(if ($Detail) { $Detail } else { $null })
    throw $failure
}

function Test-SpaceName([string]$Name) { return $Name -cmatch '^miffan-[0-9a-f]{8}$' }

# The last non-empty line of $Text, as the human-readable detail of a failure.
function Get-SpaceLastLine([string]$Text) {
    $lines = @(([string]$Text) -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count) { return $lines[$lines.Count - 1] }
    return $null
}

# One argument as the Windows C runtime parses it back (cua and docker are Rust and Go programs that do).
function ConvertTo-SpaceArgument([string]$Value) {
    if ($Value -and $Value -notmatch '[\s"]') { return $Value }
    $out = New-Object Text.StringBuilder
    $null = $out.Append('"')
    $slashes = 0
    foreach ($c in $Value.ToCharArray()) {
        if ($c -eq '\') { $slashes++; continue }
        if ($c -eq '"') { $null = $out.Append('\', 2 * $slashes + 1); $slashes = 0; $null = $out.Append('"'); continue }
        if ($slashes) { $null = $out.Append('\', $slashes); $slashes = 0 }
        $null = $out.Append($c)
    }
    if ($slashes) { $null = $out.Append('\', 2 * $slashes) }
    $null = $out.Append('"')
    return $out.ToString()
}

# Runs $File with $Arguments; {code,out,err}. Never through PowerShell's pipeline: its stdin pump can
# outlive the program, and a line on stderr would end this helper under ErrorActionPreference Stop.
function Invoke-SpaceProcess([string]$File, [string[]]$Arguments, [int]$TimeoutSeconds = 120, [hashtable]$Environment = @{}) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $File
    $start.Arguments = (@($Arguments | ForEach-Object { ConvertTo-SpaceArgument $_ }) -join ' ')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $script:Utf8; $start.StandardErrorEncoding = $script:Utf8
    foreach ($key in $Environment.Keys) { $start.EnvironmentVariables[$key] = [string]$Environment[$key] }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            return @{ code = -1; out = ''; err = 'timed out'; timed_out = $true }
        }
        $process.WaitForExit()
        return @{ code = $process.ExitCode; out = $output.Result; err = $errors.Result; timed_out = $false }
    } finally { $process.Dispose() }
}

# The Windows system proxy as http://host:port (HTTPS entry first), or $null. Loopback stays loopback here;
# the Space reaches it as host.docker.internal (Get-SpaceProxyForSpace).
function Get-SpaceSystemProxy {
    $settings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
    if (-not $settings -or $settings.ProxyEnable -ne 1 -or -not $settings.ProxyServer) { return $null }
    $server = [string]$settings.ProxyServer
    if ($server -match '=') {
        $entries = @{}
        foreach ($part in $server -split ';') { if ($part -match '^\s*([a-z]+)=(.+)$') { $entries[$matches[1].ToLowerInvariant()] = $matches[2].Trim() } }
        $server = $(if ($entries['https']) { $entries['https'] } elseif ($entries['http']) { $entries['http'] } else { $null })
        if (-not $server) { return $null }
    }
    $server = $server -replace '^[a-z]+://', ''
    if ($server -notmatch '^[A-Za-z0-9.-]+:[0-9]{1,5}$') { return $null }
    return 'http://' + $server
}

# The system proxy as the Space reaches it: it goes into scripts run there, so nothing but a host name and a port.
function Get-SpaceProxyForSpace {
    $proxy = Get-SpaceSystemProxy
    if (-not $proxy) { return $null }
    $proxy = $proxy -replace '^http://(127\.0\.0\.1|localhost):', 'http://host.docker.internal:'
    if ($proxy -cnotmatch '^http://[A-Za-z0-9.-]+:[0-9]{1,5}$') { return $null }
    return $proxy
}

# cua and everything its daemon starts get the computer's proxy, but never for loopback, where the Space
# ports and the daemon itself live.
function Get-SpaceEnvironment {
    # Windows environment names ignore case, as these keys do.
    $environment = @{ CUA_TELEMETRY = '0'; CUA_INSTALL_NONINTERACTIVE = '1'; NO_PROXY = '127.0.0.1,localhost' }
    $proxy = Get-SpaceSystemProxy
    if ($proxy) { $environment['HTTPS_PROXY'] = $proxy; $environment['HTTP_PROXY'] = $proxy }
    return $environment
}

function Get-SpaceCuaPath {
    $candidates = @((Join-Path $env:LOCALAPPDATA 'Programs\cua\bin\cua.exe'))
    $command = Get-Command cua.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { $candidates += [string]$command.Source }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate } }
    return $null
}

function Invoke-SpaceCua([string[]]$Arguments, [int]$TimeoutSeconds = 120) {
    return Invoke-SpaceProcess $script:SpaceCua $Arguments $TimeoutSeconds (Get-SpaceEnvironment)
}

function Get-SpaceCuaVersion([string]$Path) {
    $result = Invoke-SpaceProcess $Path @('--version') 15
    if ($result.code -eq 0 -and $result.out -match '(\d+\.\d+[0-9.]*)') { return $matches[1] }
    return $null
}

# --- Signed-in session tasks ---

# This SSH user's SID when the user is signed in to Windows (its desktop, explorer, runs in a session, which
# may be locked or disconnected: the tasks run there all the same); else ends the command with needs_login.
function Assert-SpaceConsole {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $desktops = @(Get-CimInstance Win32_Process -Filter "Name='explorer.exe'" -ErrorAction SilentlyContinue | Where-Object {
        $_.SessionId -ne 0 -and (Invoke-CimMethod -InputObject $_ -MethodName GetOwnerSid -ErrorAction SilentlyContinue).Sid -eq $sid })
    if (-not $desktops.Count) { Stop-Space 'needs_login' 'sign in to Windows on this computer with this account' }
    return $sid
}

# Registers (or keeps) task $Name that runs $Execute $Arguments in the signed-in session with ordinary
# permissions, and starts it. A task of that name that differs is replaced; one of another user is refused.
function Start-SpaceSessionTask([string]$Name, [string]$Execute, [string]$Arguments) {
    $sid = Assert-SpaceConsole
    $tasks = @(Get-ScheduledTask -TaskName $Name -TaskPath '\' -ErrorAction SilentlyContinue)
    $task = $(if ($tasks.Count -eq 1) { $tasks[0] } else { $null })
    if ($task -and -not (Test-CuaTaskOwner $task $sid)) { Stop-Space 'daemon_failed' "task $Name belongs to another user" }
    $same = $task -and [string]$task.Principal.RunLevel -eq 'Limited' -and [string]$task.Principal.LogonType -eq 'Interactive' -and
        $task.Actions.Count -eq 1 -and [string]$task.Actions[0].Execute -ceq $Execute -and [string]$task.Actions[0].Arguments -ceq $Arguments
    if (-not $same) {
        $action = $(if ($Arguments) { New-ScheduledTaskAction -Execute $Execute -Argument $Arguments } else { New-ScheduledTaskAction -Execute $Execute })
        $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        $null = Register-ScheduledTask -TaskName $Name -TaskPath '\' -Action $action -Principal $principal -Settings $settings -Force -ErrorAction Stop
    }
    Start-ScheduledTask -TaskName $Name -TaskPath '\' -ErrorAction Stop
}

# --- Docker Desktop ---

function Get-SpaceDockerCli {
    if (Test-Path -LiteralPath $script:SpaceDockerCli -PathType Leaf) { return $script:SpaceDockerCli }
    $command = Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return [string]$command.Source }
    return $null
}

function Test-SpaceDockerInstalled { return (Test-Path -LiteralPath $script:SpaceDockerDesktop -PathType Leaf) }

# $null when Docker answers, else the reason.
function Get-SpaceDockerError {
    $docker = Get-SpaceDockerCli
    if (-not $docker) { return 'docker not installed' }
    $result = Invoke-SpaceProcess $docker @('info', '--format', '{{.ServerVersion}}') 20
    if ($result.code -eq 0) { return $null }
    $line = Get-SpaceLastLine $result.err
    if ($line) { return $line }
    return 'docker is not running'
}

# Docker Desktop installed is enough here: it is started when a Space needs it. "running" says whether it
# answers now.
function Get-SpaceDockerJson {
    if (-not (Test-SpaceDockerInstalled)) { return @{ ok = $false; detail = 'docker not installed'; running = $false } }
    $problem = Get-SpaceDockerError
    return @{ ok = $true; detail = $problem; running = (-not $problem) }
}

# Starts Docker Desktop in the signed-in session when it does not answer, and waits for its engine.
function Start-SpaceDocker {
    if (-not (Test-SpaceDockerInstalled)) { Stop-Space 'needs_docker' 'docker not installed' }
    if (-not (Get-SpaceDockerError)) { return }
    Start-SpaceSessionTask $script:SpaceDockerTask ('"' + $script:SpaceDockerDesktop + '"') ''
    # Its first start sets up its WSL distribution, which takes a while.
    $deadline = [DateTime]::UtcNow.AddSeconds(240)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 3
        $problem = Get-SpaceDockerError
        if (-not $problem) { return }
    }
    Stop-Space 'needs_docker' $(if ($problem) { "Docker Desktop did not start: $problem" } else { 'Docker Desktop did not start' })
}

function Test-SpaceElevated {
    $principal = New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# Docker Desktop's installer is about 600 MB and cua's command line about 80 MB, which take from minutes to
# an hour on slow networks: a task installs cua when it is missing, then downloads Docker Desktop (resuming
# after a drop), checks its signature and installs it, apart from any SSH session, writing its progress to
# status.json. The installer goes once Docker Desktop is in place.
$script:SpaceDockerInstallDir = Join-Path $env:LOCALAPPDATA 'Miffan\docker-install'
$script:SpaceDockerInstallTask = 'Miffan-docker-install'
$script:SpaceDockerWorker = @'
param([string]$Directory, [string]$Url, [string]$Proxy, [string]$Signer, [string]$CuaExe)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$utf8 = New-Object Text.UTF8Encoding($false)
$status = Join-Path $Directory 'status.json'
function Write-Status([string]$Stage, [int64]$Bytes, [int64]$Total, [string]$Message) {
    $json = ConvertTo-Json -Compress -InputObject @{ stage = $Stage; bytes = $Bytes; total = $Total; error = $(if ($Message) { $Message } else { $null }) }
    [IO.File]::WriteAllText($status + '.tmp', $json, $utf8)
    Move-Item -LiteralPath ($status + '.tmp') -Destination $status -Force
}
$part = Join-Path $Directory 'DockerDesktopInstaller.part'
$installer = Join-Path $Directory 'DockerDesktopInstaller.exe'
$have = [int64]0; $total = [int64]0
try {
    if ($CuaExe -and -not (Test-Path -LiteralPath $CuaExe)) {
        Write-Status 'cua' 0 0 $null
        $script = Join-Path $Directory 'cua-install.ps1'
        $client = New-Object Net.WebClient
        if ($Proxy) { $client.Proxy = New-Object Net.WebProxy($Proxy) }
        $client.DownloadFile('https://cua.ai/install.ps1', $script)
        $env:CUA_TELEMETRY = '0'; $env:CUA_INSTALL_NONINTERACTIVE = '1'
        if ($Proxy) { $env:HTTPS_PROXY = $Proxy; $env:HTTP_PROXY = $Proxy }
        $log = Join-Path $Directory 'cua-install.log'
        $process = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Wait -PassThru -WindowStyle Hidden `
            -ArgumentList '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $script + '"'), '-CliOnly', '-Yes', '-NoModifyPath', '-NoOnboarding' `
            -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
        if ($process.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $CuaExe)) {
            $last = @(Get-Content -LiteralPath ($log + '.err'), $log -ErrorAction SilentlyContinue | Where-Object { $_.Trim() }) | Select-Object -Last 1
            throw ('cua: ' + $(if ($last) { $last.Trim() } else { "the installer failed (exit $($process.ExitCode))" }))
        }
    }
    if (-not (Test-Path -LiteralPath $installer)) {
        $failures = 0
        while ($true) {
            $have = $(if (Test-Path -LiteralPath $part) { (Get-Item -LiteralPath $part).Length } else { [int64]0 })
            if ($total -gt 0 -and $have -ge $total) { break }
            $response = $null; $file = $null
            try {
                $request = [Net.HttpWebRequest]::Create($Url)
                $request.Timeout = 30000; $request.ReadWriteTimeout = 30000
                if ($Proxy) { $request.Proxy = New-Object Net.WebProxy($Proxy) }
                if ($have -gt 0) { $request.AddRange([int64]$have) }
                $response = $request.GetResponse()
                # A server that ignores the range sends it all again.
                if ($have -gt 0 -and [int]$response.StatusCode -ne 206) { $have = 0 }
                if ($response.ContentLength -le 0) { throw 'the download has no size' }
                $total = $have + $response.ContentLength
                $file = New-Object IO.FileStream($part, $(if ($have -gt 0) { [IO.FileMode]::Append } else { [IO.FileMode]::Create }), [IO.FileAccess]::Write)
                $stream = $response.GetResponseStream(); $buffer = New-Object byte[] 262144
                $watch = [Diagnostics.Stopwatch]::StartNew()
                Write-Status 'download' $have $total $null
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $file.Write($buffer, 0, $read); $have += $read; $failures = 0
                    if ($watch.ElapsedMilliseconds -ge 1000) { Write-Status 'download' $have $total $null; $watch.Restart() }
                }
            } catch {
                $web = $_.Exception.InnerException
                if ($web -is [Net.WebException] -and $web.Response -and [int]$web.Response.StatusCode -eq 416) { $total = $have; break }
                $failures++
                if ($failures -ge 8) { throw }
                Write-Status 'download' $have $total $null
                Start-Sleep -Seconds ([Math]::Min(60, 5 * $failures))
            } finally { if ($file) { $file.Dispose() }; if ($response) { $response.Close() } }
        }
        Move-Item -LiteralPath $part -Destination $installer -Force
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate -or -not ([string]$signature.SignerCertificate.Subject).StartsWith($Signer + ',')) {
        Remove-Item -LiteralPath $installer -Force
        throw 'the Docker Desktop installer is not signed by Docker'
    }
    Write-Status 'install' $total $total $null
    $process = Start-Process -FilePath $installer -ArgumentList 'install', '--quiet', '--accept-license', '--backend=wsl-2', '--always-run-service' -Wait -PassThru -WindowStyle Hidden
    # 3010: installed, Windows must restart (WSL was just turned on).
    if ($process.ExitCode -eq 3010) { Write-Status 'restart' $total $total $null }
    elseif ($process.ExitCode -ne 0) { throw "the Docker Desktop installer failed (exit $($process.ExitCode))" }
    else { Write-Status 'done' $total $total $null }
    Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue
} catch { Write-Status 'failed' $have $total ([string]$_.Exception.Message) }
'@

# The background install's progress, {stage,bytes,total,error}: stage cua, download, install, done, restart
# or failed; $null when none ran. One whose task is gone mid-way counts as failed, so the next install resumes it.
function Get-SpaceDockerInstall {
    $path = Join-Path $script:SpaceDockerInstallDir 'status.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $status = [IO.File]::ReadAllText($path, $script:Utf8) | ConvertFrom-Json } catch { return $null }
    $stage = [string]$status.stage; $message = $(if ($status.error) { [string]$status.error } else { $null })
    if ($stage -in @('cua', 'download', 'install')) {
        $task = @(Get-ScheduledTask -TaskName $script:SpaceDockerInstallTask -TaskPath '\' -ErrorAction SilentlyContinue)
        if ($task.Count -ne 1 -or [string]$task[0].State -notin @('Running', 'Queued')) { $stage = 'failed'; $message = 'interrupted' }
    }
    return @{ stage = $stage; bytes = [int64]$status.bytes; total = [int64]$status.total; error = $message }
}

# Starts (or resumes) the background install. Needs an administrator's SSH session: Windows OpenSSH gives
# administrators an elevated one, and the task runs elevated as this user, signed in or not.
function Start-SpaceDockerInstall {
    if (-not (Test-SpaceElevated)) { Stop-Space 'needs_admin' 'installing Docker Desktop needs an administrator account' }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Assert-CuaDirectoryParents (Join-Path $env:LOCALAPPDATA 'Miffan')
    if (-not (Test-Path -LiteralPath $script:SpaceDockerInstallDir)) { New-PrivateDirectory $script:SpaceDockerInstallDir $sid }
    Assert-PrivateDirectory $script:SpaceDockerInstallDir $sid
    $worker = Join-Path $script:SpaceDockerInstallDir 'worker.ps1'
    [IO.File]::WriteAllText($worker, $script:SpaceDockerWorker, (New-Object Text.UTF8Encoding($true)))
    $status = Join-Path $script:SpaceDockerInstallDir 'status.json'
    [IO.File]::WriteAllText($status, '{"stage":"cua","bytes":0,"total":0,"error":null}', $script:Utf8)
    $arch = $(if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'amd64' })
    $url = "https://desktop.docker.com/win/main/$arch/Docker%20Desktop%20Installer.exe"
    # The task does not see this session's proxy settings, so it gets the proxy itself (a host and a port).
    $proxy = Get-SpaceSystemProxy
    $arguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $worker + '" -Directory "' +
        $script:SpaceDockerInstallDir + '" -Url "' + $url + '" -Signer "' + $script:SpaceDockerSigner + '" -CuaExe "' +
        (Join-Path $env:LOCALAPPDATA 'Programs\cua\bin\cua.exe') + '"' + $(if ($proxy) { ' -Proxy "' + $proxy + '"' } else { '' })
    $action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments
    $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType S4U -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 4) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $null = Register-ScheduledTask -TaskName $script:SpaceDockerInstallTask -TaskPath '\' -Action $action -Principal $principal -Settings $settings -Force -ErrorAction Stop
    Start-ScheduledTask -TaskName $script:SpaceDockerInstallTask -TaskPath '\' -ErrorAction Stop
}

# Once Docker Desktop is in place: the install's task and leftovers go.
function Clear-SpaceDockerInstall {
    $task = @(Get-ScheduledTask -TaskName $script:SpaceDockerInstallTask -TaskPath '\' -ErrorAction SilentlyContinue)
    if ($task.Count -eq 1) {
        if ([string]$task[0].State -in @('Running', 'Queued')) { return }
        Unregister-ScheduledTask -TaskName $script:SpaceDockerInstallTask -TaskPath '\' -Confirm:$false -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $script:SpaceDockerInstallDir) { Remove-Item -LiteralPath $script:SpaceDockerInstallDir -Recurse -Force -ErrorAction SilentlyContinue }
}

# --- cua and its daemon ---

function Test-SpaceDaemonRunning {
    $result = Invoke-SpaceCua @('daemon', 'status') 15
    return $result.code -eq 0 -and ($result.out + $result.err) -match 'pid [0-9]'
}

# The task's process: runs the daemon in the foreground, without a window, until it exits.
function Invoke-SpaceDaemon {
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) { throw 'The cua daemon must run in the signed-in session' }
    $cua = Get-SpaceCuaPath
    if (-not $cua) { throw 'cua is not installed' }
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $cua; $start.Arguments = 'daemon start --foreground'; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $environment = Get-SpaceEnvironment
    foreach ($key in $environment.Keys) { $start.EnvironmentVariables[$key] = [string]$environment[$key] }
    $process = [Diagnostics.Process]::Start($start); $process.WaitForExit(); exit $process.ExitCode
}

function Start-SpaceDaemon {
    if (Test-SpaceDaemonRunning) { return }
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    Start-SpaceSessionTask $script:SpaceDaemonTask $powershell ('-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $script:HelperFile + '" space daemon')
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 1
        if (Test-SpaceDaemonRunning) { return }
    }
    Stop-Space 'daemon_failed' 'daemon did not start'
}

# Finds cua and makes sure Docker Desktop and the daemon run.
function Initialize-SpaceRuntime {
    $script:SpaceCua = Get-SpaceCuaPath
    if (-not $script:SpaceCua) { Stop-Space 'cua_missing' $null }
    if (Test-SpaceDockerInstalled) { Clear-SpaceDockerInstall }
    Start-SpaceDocker
    Start-SpaceDaemon
}

# --- Spaces ---

function Get-SpaceInfoText([string]$Name) {
    $result = Invoke-SpaceCua @('--json', 'sb', 'info', $Name) 30
    if ($result.code -ne 0) { return $null }
    return ($result.out -replace "[`r`n]", '')
}

# running, stopped, ... or missing when cua does not know the Space or cannot report it.
function Get-SpaceState([string]$Name) {
    $text = Get-SpaceInfoText $Name
    if ($text -and $text -match '"state": *"([^"]*)"') { return $matches[1] }
    return 'missing'
}

function Get-SpacePort([string]$Name) {
    $text = Get-SpaceInfoText $Name
    if ($text -and $text -match '"env": *"[^"]*:([0-9]+)"') { return [int]$matches[1] }
    return $null
}

function Test-SpaceKnown([string]$Name) {
    $result = Invoke-SpaceCua @('--json', 'sb', 'ls') 30
    return $result.code -eq 0 -and ($result.out -replace "[`r`n]", '') -match ('"id" *: *"local:' + [regex]::Escape($Name) + '"')
}

# Waits until the Space runs and its loopback port is published (it changes on every start).
function Wait-SpaceRunning([string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Get-SpaceState $Name) -eq 'running' -and (Get-SpacePort $Name)) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Start-SpaceBox([string]$Name) {
    if (-not (Test-SpaceKnown $Name)) { Stop-Space 'not_found' $null }
    if ((Get-SpaceState $Name) -ne 'running') {
        $result = Invoke-SpaceCua @('spaces', 'start', "local:$Name") 120
        if ($result.code -ne 0) { Stop-Space 'start_failed' (Get-SpaceLastLine $result.err) }
    }
    if (-not (Wait-SpaceRunning $Name)) { Stop-Space 'start_failed' 'not running after start' }
}

function Get-SpaceToken([string]$Name) {
    $path = Join-Path $env:USERPROFILE '.cua\spaces-credentials.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $credentials = [IO.File]::ReadAllText($path, $script:Utf8) | ConvertFrom-Json } catch { return $null }
    $entry = $credentials.PSObject.Properties["local:$Name"]
    if (-not $entry -or -not $entry.Value.token) { return $null }
    return [string]$entry.Value.token
}

# The cua-driver version inside the Space, from the MCP initialize reply; $null when unreadable.
function Get-SpaceDriverVersion([string]$Name, [string]$Token, [int]$Port) {
    $response = $null
    try {
        $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/mcp")
        $request.Method = 'POST'; $request.Proxy = $null; $request.Timeout = 5000; $request.ReadWriteTimeout = 5000
        $request.ContentType = 'application/json'; $request.Accept = 'application/json, text/event-stream'
        $request.Headers['authorization'] = "Bearer $Token"
        $body = $script:Utf8.GetBytes('{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"miffan","version":"1"}}}')
        $stream = $request.GetRequestStream(); $stream.Write($body, 0, $body.Length); $stream.Close()
        $response = $request.GetResponse()
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), $script:Utf8)
        $text = $reader.ReadToEnd() -replace "[`r`n]", ''
        if ($text -match '"serverInfo": *\{[^}]*"version": *"([^"]*)"') { return $matches[1] }
    } catch { } finally { if ($response) { $response.Close() } }
    return $null
}

function Get-SpaceDiskFreeGb {
    try { return [int][Math]::Floor((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($env:LOCALAPPDATA))).AvailableFreeSpace / 1GB) } catch { return $null }
}

# This computer's CPUs and memory and, with Docker running, the WSL 2 VM every Space shares (its size comes
# from .wslconfig, by default half the memory).
function Get-SpaceHostJson([bool]$DockerRunning) {
    $memory = $null
    try { $memory = [int]((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB) } catch { }
    $runtime = $null
    if ($DockerRunning) {
        $result = Invoke-SpaceProcess (Get-SpaceDockerCli) @('info', '--format', '{{.NCPU}} {{.MemTotal}}') 20
        if ($result.code -eq 0 -and $result.out.Trim() -match '^([0-9]+) ([0-9]+)$') { $runtime = @{ cpus = [int]$matches[1]; memory_mb = [int]([int64]$matches[2] / 1MB) } }
    }
    return @{ cpus = [Environment]::ProcessorCount; memory_mb = $memory; runtime = $runtime }
}

function Get-SpaceList {
    $result = Invoke-SpaceCua @('--json', 'sb', 'ls') 30
    $spaces = @()
    if ($result.code -ne 0) { return , $spaces }
    foreach ($match in [regex]::Matches(($result.out -replace "[`r`n]", ''), '"id": *"local:(miffan-[0-9a-z]*)"')) {
        $name = $match.Groups[1].Value
        if (Test-SpaceName $name) { $spaces += , @{ id = "local:$name"; state = (Get-SpaceState $name) } }
    }
    return , $spaces
}

# Read-only: starts nothing.
function Get-SpaceProbe {
    $path = Get-SpaceCuaPath
    $version = $null; $running = $false; $spaces = @()
    if ($path) {
        $script:SpaceCua = $path
        $version = Get-SpaceCuaVersion $path
        if (Test-SpaceDaemonRunning) { $running = $true; $spaces = Get-SpaceList }
    }
    $docker = Get-SpaceDockerJson
    $install = $(if ($docker.ok) { $null } else { Get-SpaceDockerInstall })
    return @{ cua = @{ path = $path; version = $version }; daemon = @{ running = $running }; docker = $docker; docker_install = $install;
        disk_free_gb = (Get-SpaceDiskFreeGb); host = (Get-SpaceHostJson $docker.running); spaces = $spaces }
}

# Installs what is missing (cua's command line, no account; Docker Desktop) and starts both. Docker Desktop
# installs in the background: while it does, the reply carries its progress in docker_install.
function Invoke-SpaceInstall {
    if (-not (Test-SpaceDockerInstalled)) {
        # The background install brings cua too.
        $job = Get-SpaceDockerInstall
        if ($job -and $job.stage -eq 'restart') { Stop-Space 'needs_restart' 'restart Windows to finish installing Docker Desktop' }
        if (-not $job -or $job.stage -notin @('cua', 'download', 'install')) { Start-SpaceDockerInstall; $job = Get-SpaceDockerInstall }
        $path = Get-SpaceCuaPath
        return @{ cua = @{ path = $path; version = $(if ($path) { Get-SpaceCuaVersion $path } else { $null }) }; daemon = @{ running = $false }; docker_install = $job }
    }
    $script:SpaceCua = Get-SpaceCuaPath
    if (-not $script:SpaceCua) {
        $directory = Join-Path $env:LOCALAPPDATA ('Miffan\cua-spaces-install-' + [Guid]::NewGuid().ToString('N'))
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        Assert-CuaDirectoryParents (Join-Path $env:LOCALAPPDATA 'Miffan')
        New-PrivateDirectory $directory $sid
        try {
            $installer = Join-Path $directory 'install.ps1'
            try { (New-Object Net.WebClient).DownloadFile('https://cua.ai/install.ps1', $installer) } catch { Stop-Space 'download_failed' 'could not download the cua installer' }
            $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
            $result = Invoke-SpaceProcess $powershell @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $installer,
                '-CliOnly', '-Yes', '-NoModifyPath', '-NoOnboarding') 1500 (Get-SpaceEnvironment)
            if ($result.code -ne 0) { Stop-Space 'download_failed' $(if (Get-SpaceLastLine $result.err) { Get-SpaceLastLine $result.err } else { 'the cua installer failed' }) }
        } finally { Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue }
        $script:SpaceCua = Get-SpaceCuaPath
        if (-not $script:SpaceCua) { Stop-Space 'download_failed' 'cua is still missing after the installer' }
    }
    Clear-SpaceDockerInstall
    Start-SpaceDocker
    Start-SpaceDaemon
    return @{ cua = @{ path = $script:SpaceCua; version = (Get-SpaceCuaVersion $script:SpaceCua) }; daemon = @{ running = $true } }
}

# Maps the text of a failed create to an error code.
function Get-SpaceCreateCode([string]$Text) {
    $low = $Text.ToLowerInvariant()
    if ($low -match 'no space left|not enough space|disk full|insufficient disk|disk space') { return 'disk_low' }
    if ($low -match 'pull|download|proxy|i/o timeout|tls handshake|dial tcp|no such host') { return 'download_failed' }
    if ($low -match 'docker') { return 'needs_docker' }
    return 'create_failed'
}

function New-SpaceBox([string]$Name, [int]$Cpus, [int]$Memory) {
    $free = Get-SpaceDiskFreeGb
    if ($null -ne $free -and $free -lt 2) { Stop-Space 'disk_low' "$free GB free" }
    # The first create pulls the image (1.3 GB).
    $result = Invoke-SpaceCua @('spaces', 'create', 'linux', '--on', 'local', '--kind', 'container', '--runtime', 'runc', '--name', $Name,
        '--cpus', [string]$Cpus, '--memory-mb', [string]$Memory, '--json') 1700
    $flat = $result.out -replace "[`r`n]", ''
    $message = $(if ($flat -match '"error": *"([^"]*)"') { $matches[1] } else { '' })
    if ($result.code -ne 0 -or $message -or $flat -notmatch ('"id" *: *"local:' + [regex]::Escape($Name) + '"')) {
        $text = $(if ($message) { $message } elseif (Get-SpaceLastLine $result.err) { Get-SpaceLastLine $result.err } else { 'cua spaces create failed' })
        Stop-Space (Get-SpaceCreateCode "$message $($result.err)") $text
    }
    if (-not (Wait-SpaceRunning $Name)) { Stop-Space 'create_failed' 'not running after create' }
}

function Remove-SpaceBox([string]$Name) {
    if (-not (Test-SpaceKnown $Name)) { Stop-Space 'not_found' $null }
    $result = Invoke-SpaceCua @('spaces', 'delete', "local:$Name", '--force') 300
    if ($result.code -ne 0) { Stop-Space 'exec_failed' (Get-SpaceLastLine $result.err) }
    if (Test-SpaceKnown $Name) { Stop-Space 'exec_failed' 'the Space is still listed after delete' }
}

# --- In the Space ---

# Runs sh $Script in the Space as its user; {code,out}. The script travels base64-encoded, so no quoting
# of it reaches a command line.
function Invoke-SpaceShell([string]$Name, [string]$Script, [int]$TimeoutSeconds = 60) {
    $encoded = [Convert]::ToBase64String($script:Utf8.GetBytes($Script))
    $result = Invoke-SpaceCua @('sb', 'exec', $Name, "echo $encoded | base64 -d | sh") $TimeoutSeconds
    return @{ code = $result.code; out = [string]$result.out }
}

function ConvertTo-SpaceShellQuote([string]$Value) { return "'" + $Value.Replace("'", "'\''") + "'" }

# A script for the Space that fetches the probe sites in parallel (curl options $Options) and stops at the
# first that answers. Leaves h=1 on success, else the failures in f. Same as miffan.sh's space_race_script.
function Get-SpaceRaceScript([string]$Options) {
    return "t=`$(mktemp); p=''; for u in www.baidu.com www.cloudflare.com; do (curl -s -o /dev/null -m 5 --connect-timeout 3 $Options `"https://`$u`" && echo ok >> `"`$t`") >/dev/null 2>&1 & p=`"`$p `$!`"; done; while [ ! -s `"`$t`" ]; do a=0; for i in `$p; do kill -0 `$i 2>/dev/null && a=1; done; [ `$a = 1 ] || break; sleep 0.1; done; h=0; f=''; if [ -s `"`$t`" ]; then h=1; else f=' https:www.baidu.com https:www.cloudflare.com'; fi; kill `$p 2>/dev/null; rm -f `"`$t`";"
}

function Get-SpaceShareMarker([string]$Name) { return Join-Path $script:SpaceStateDir "netshare\spaces\$Name" }

# Space $Name through proxy $Proxy in one round: conf (its settings are in place), reach (the proxy answers),
# https, and the failures.
function Test-SpaceShare([string]$Name, [string]$Proxy) {
    $hostPort = $Proxy.Substring(7)
    $script = "c=0; grep -q '^https_proxy=$Proxy`$' /etc/environment 2>/dev/null && [ -f /etc/apt/apt.conf.d/95miffan-proxy ] && grep -q '$hostPort' /etc/firefox/policies/policies.json 2>/dev/null && c=1; r=0; h=0; f=''; code=`$(curl -s -o /dev/null -w '%{http_code}' -m 3 --connect-timeout 2 --noproxy '*' $Proxy/ 2>/dev/null); case `"`$code`" in ''|000) f=' share:unreachable' ;; *) r=1 ;; esac; if [ `$r = 1 ]; then $(Get-SpaceRaceScript "-x $Proxy") fi; echo `"SHARECHECK conf=`$c reach=`$r https=`$h fail=`$f`""
    $line = @((Invoke-SpaceShell $Name $script 30).out -split "`r?`n" | Where-Object { $_ -clike 'SHARECHECK *' })
    $line = $(if ($line.Count) { $line[$line.Count - 1] } else { 'SHARECHECK conf=0 reach=0 https=0 fail= check did not finish' })
    $fail = $(if ($line -match ' fail=(.*)$') { $matches[1].Trim() } else { '' })
    return @{ conf = ($line -like '* conf=1 *'); reach = ($line -like '* reach=1 *'); https = ($line -like '* https=1 *'); fail = $fail }
}

# Points the Space at $Proxy: the variables, apt's and sudo's settings and the browsers' proxy (as miffan.sh).
function Set-SpaceShare([string]$Name, [string]$Proxy) {
    $hostPort = $Proxy.Substring(7)
    $inner = @"
set -e
grep -viE '^(http|https|no)_proxy=' /etc/environment > /etc/environment.miffan || true
printf '%s\n' http_proxy=$Proxy https_proxy=$Proxy HTTP_PROXY=$Proxy HTTPS_PROXY=$Proxy no_proxy=localhost,127.0.0.1,::1 NO_PROXY=localhost,127.0.0.1,::1 >> /etc/environment.miffan
cat /etc/environment.miffan > /etc/environment
rm -f /etc/environment.miffan
mkdir -p /etc/apt/apt.conf.d
printf 'Acquire::http::Proxy "%s/";\nAcquire::https::Proxy "%s/";\n' $Proxy $Proxy > /etc/apt/apt.conf.d/95miffan-proxy
printf 'Defaults env_keep += "http_proxy https_proxy HTTP_PROXY HTTPS_PROXY no_proxy NO_PROXY"\n' > /etc/sudoers.d/miffan-proxy
chmod 0440 /etc/sudoers.d/miffan-proxy
mkdir -p /etc/firefox/policies /etc/chromium/policies/managed
printf '%s\n' '{"policies":{"Proxy":{"Mode":"manual","HTTPProxy":"$hostPort","UseHTTPProxyForAllProtocols":true,"Passthrough":"localhost, 127.0.0.1"},"DisableTelemetry":true,"SkipTermsOfUse":true,"OverrideFirstRunPage":"","OverridePostUpdatePage":"","DontCheckDefaultBrowser":true}}' > /etc/firefox/policies/policies.json
printf '%s\n' '{"ProxyMode":"fixed_servers","ProxyServer":"$hostPort","ProxyBypassList":"localhost;127.0.0.1"}' > /etc/chromium/policies/managed/miffan-proxy.json
"@
    $out = (Invoke-SpaceShell $Name ("sudo -n sh -c " + (ConvertTo-SpaceShellQuote ($inner -replace "`r", '')) + " && echo SHARECONF ok") 30).out
    return $out -match 'SHARECONF ok'
}

# Takes Space $Name off a proxy it no longer has (keeps Firefox's first-run and terms settings).
function Clear-SpaceShare([string]$Name) {
    $inner = @"
set -e
grep -viE '^(http|https|no)_proxy=' /etc/environment > /etc/environment.miffan || true
cat /etc/environment.miffan > /etc/environment
rm -f /etc/environment.miffan /etc/apt/apt.conf.d/95miffan-proxy /etc/sudoers.d/miffan-proxy /etc/chromium/policies/managed/miffan-proxy.json
mkdir -p /etc/firefox/policies
printf '%s\n' '{"policies":{"DisableTelemetry":true,"SkipTermsOfUse":true,"OverrideFirstRunPage":"","OverridePostUpdatePage":"","DontCheckDefaultBrowser":true}}' > /etc/firefox/policies/policies.json
"@
    $out = (Invoke-SpaceShell $Name ("sudo -n sh -c " + (ConvertTo-SpaceShellQuote ($inner -replace "`r", '')) + " && echo SHAREOFF ok") 30).out
    return $out -match 'SHAREOFF ok'
}

# The proxy Space $Name uses, from its marker; $null when it goes out directly.
function Get-SpaceShareProxy([string]$Name) {
    $marker = Get-SpaceShareMarker $Name
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { return $null }
    $proxy = ([IO.File]::ReadAllText($marker, $script:Utf8)).Trim()
    if ($proxy -cmatch '^http://[A-Za-z0-9.-]+:[0-9]{1,5}$') { return $proxy }
    return $null
}

# With the system proxy on, the Space goes out through it as the computer's own programs do (Docker Desktop
# hands host.docker.internal to the computer's loopback); otherwise, or when it does not answer, directly.
function Invoke-SpaceNetcheck([string]$Name) {
    Start-SpaceBox $Name
    $proxy = Get-SpaceProxyForSpace
    if ($proxy) {
        $check = Test-SpaceShare $Name $proxy
        if ($check.reach) {
            if (-not $check.conf -and -not (Set-SpaceShare $Name $proxy)) {
                return @{ dns = $false; https = $false; detail = 'could not set the proxy in the Space'; hint = 'share_failed'; via = 'share' }
            }
            $marker = Get-SpaceShareMarker $Name
            $null = New-Item -ItemType Directory -Path (Split-Path $marker) -Force
            [IO.File]::WriteAllText($marker, $proxy + "`n", $script:Utf8)
            return @{ dns = $check.https; https = $check.https; detail = $(if ($check.fail) { $check.fail } else { $null }); hint = $null; via = 'share'; proxy = $proxy }
        }
    }
    $marker = Get-SpaceShareMarker $Name
    if ((Test-Path -LiteralPath $marker -PathType Leaf) -and (Clear-SpaceShare $Name)) { Remove-Item -LiteralPath $marker -Force }
    $script = "(timeout 3 getent hosts www.baidu.com >/dev/null 2>&1 && echo dns > /tmp/miffan-dns.`$`$) >/dev/null 2>&1 & $(Get-SpaceRaceScript '') d=0; [ `$h = 1 ] && d=1; [ -s /tmp/miffan-dns.`$`$ ] && d=1; [ `$d = 1 ] || f=`" dns:www.baidu.com`$f`"; rm -f /tmp/miffan-dns.`$`$; echo `"NETCHECK dns=`$d https=`$h fail=`$f`""
    $lines = @((Invoke-SpaceShell $Name $script 30).out -split "`r?`n" | Where-Object { $_ -clike 'NETCHECK *' })
    $line = $(if ($lines.Count) { $lines[$lines.Count - 1] } else { 'NETCHECK dns=0 https=0 fail= check did not finish' })
    $fail = $(if ($line -match ' fail=(.*)$') { $matches[1].Trim() } else { '' })
    return @{ dns = ($line -like '* dns=1 *'); https = ($line -match ' https=1( |$)'); detail = $(if ($fail) { $fail } else { $null }); hint = $null; via = 'direct' }
}

# --- Handoff folders ---

# Whether $Path is relative, of clean segments joined by '/'.
function Test-SpaceRelative([string]$Path) {
    if (-not $Path -or $Path.StartsWith('/') -or $Path.Contains('\') -or $Path.Contains([char]0)) { return $false }
    foreach ($segment in $Path.Split('/')) { if (-not $segment -or $segment -eq '.' -or $segment -eq '..') { return $false } }
    return $true
}

# Reads {"folder":...,"path":...} from stdin: the host folder (absolute, or relative to the account's home with
# '/' between segments) and, for out, the path inside the Space's Shared folder.
function Read-SpaceHandoff([bool]$WithPath) {
    try { $request = [Console]::In.ReadToEnd() | ConvertFrom-Json } catch { Stop-Space 'bad_input' 'request' }
    $folder = [string]$request.folder
    if ($folder -match '[\x00-\x1f]') { Stop-Space 'bad_input' 'folder' }
    $folder = $folder.TrimEnd('/', '\')
    if (-not $folder) { Stop-Space 'bad_input' 'folder' }
    if ($folder -match '^[A-Za-z]:[\\/]') {
        $segments = $folder.Substring(3) -split '[\\/]'
        $root = $folder.Substring(0, 3)
    } else {
        if (-not (Test-SpaceRelative $folder)) { Stop-Space 'bad_input' 'folder must be a clean path' }
        $segments = $folder.Split('/')
        $root = $env:USERPROFILE
    }
    foreach ($segment in $segments) {
        if (-not $segment -or $segment -eq '.' -or $segment -eq '..' -or $segment.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) { Stop-Space 'bad_input' 'folder must be a clean path' }
    }
    $relative = $null
    if ($WithPath) {
        $relative = ([string]$request.path).TrimEnd('/')
        if ($relative -match '[\x00-\x1f]' -or -not (Test-SpaceRelative $relative)) { Stop-Space 'bad_input' 'path must stay inside the handoff folder' }
    }
    return @{ folder = (Join-Path $root ($segments -join '\')); path = $relative }
}

# A file name that Windows accepts: characters it refuses in names become '_'.
function ConvertTo-SpaceHostName([string]$Name) {
    $safe = -join ($Name.ToCharArray() | ForEach-Object { if ([IO.Path]::GetInvalidFileNameChars() -contains $_) { '_' } else { $_ } })
    $safe = $safe.TrimEnd(' ', '.')
    if (-not $safe) { return '_' }
    return $safe
}

# $Path, or the same name with " (2)", " (3)"... before the extension when $Exists finds it taken.
function Get-SpaceUnique([string]$Path, [scriptblock]$Exists, [string]$Separator) {
    if (-not (& $Exists $Path)) { return $Path }
    $cut = $Path.LastIndexOf($Separator)
    $directory = $Path.Substring(0, $cut); $base = $Path.Substring($cut + 1)
    if ($base -match '^(.+?)(\.tar\.[^.]+)$' -or $base -match '^(.+)(\.[^.]+)$') { $stem = $matches[1]; $extension = $matches[2] } else { $stem = $base; $extension = '' }
    $n = 2
    while (& $Exists "$directory$Separator$stem ($n)$extension") { $n++ }
    return "$directory$Separator$stem ($n)$extension"
}

# "<size>:<mtime>" of a host file, to recognise one the helper wrote that nobody has touched since.
function Get-SpaceFileSignature([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if (-not $item) { return '' }
    return "$($item.Length):$(([DateTimeOffset]$item.LastWriteTimeUtc).ToUnixTimeSeconds())"
}

function Get-SpaceGivenPath([string]$Name) { return Join-Path $script:SpaceStateDir "space\$Name.handoff-out" }

function New-SpaceHostFolder([string]$Folder) {
    try { $null = New-Item -ItemType Directory -Path $Folder -Force -ErrorAction Stop } catch { Stop-Space 'exec_failed' 'cannot create the host folder' }
    if ((Get-Item -LiteralPath $Folder -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { Stop-Space 'exec_failed' 'the host folder is a link' }
}

function Invoke-SpaceHandoffOut([string]$Name) {
    $request = Read-SpaceHandoff $true
    Start-SpaceBox $Name
    New-SpaceHostFolder $request.folder
    $shared = ConvertTo-SpaceShellQuote $script:SpaceHandoffDir
    if ((Invoke-SpaceShell $Name "mkdir -p $shared").code -ne 0) { Stop-Space 'exec_failed' 'cannot create the handoff folder in the Space' }
    $q = ConvertTo-SpaceShellQuote $request.path
    $listing = Invoke-SpaceShell $Name "cd $shared || exit 3; if [ -L $q ]; then exit 4; elif [ -d $q ]; then find $q -type f; elif [ -f $q ]; then printf '%s\n' $q; else exit 4; fi" 60
    if ($listing.code -eq 4) { Stop-Space 'not_found' $request.path }
    if ($listing.code -ne 0) { Stop-Space 'exec_failed' 'could not list the handoff folder' }
    $given = Get-SpaceGivenPath $Name
    $null = New-Item -ItemType Directory -Path (Split-Path $given) -Force
    $copied = @()
    foreach ($relative in @($listing.out -split "`n" | ForEach-Object { $_.TrimEnd("`r") } | Where-Object { $_ })) {
        $hostRelative = (@($relative.Split('/') | ForEach-Object { ConvertTo-SpaceHostName $_ }) -join '\')
        $destination = Get-SpaceUnique (Join-Path $request.folder $hostRelative) { param($p) Test-Path -LiteralPath $p } '\'
        $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
        $result = Invoke-SpaceCua @('sb', 'cp', "${Name}:$($script:SpaceHandoffDir)/$relative", $destination) 600
        if ($result.code -ne 0) { Stop-Space 'exec_failed' "copy failed: $relative" }
        $written = $destination.Substring($request.folder.Length + 1).Replace('\', '/')
        [IO.File]::AppendAllText($given, "$written`t$(Get-SpaceFileSignature $destination)`n", $script:Utf8)
        $copied += , $written
    }
    return @{ copied = $copied }
}

function Test-SpaceRemoteExists([string]$Name, [string]$Path) {
    $q = ConvertTo-SpaceShellQuote $Path
    $answer = Get-SpaceLastLine (Invoke-SpaceShell $Name "if [ -e $q ] || [ -L $q ]; then echo EXISTS; else echo FREE; fi").out
    if ($answer -eq 'EXISTS') { return $true }
    if ($answer -eq 'FREE') { return $false }
    Stop-Space 'exec_failed' 'could not look inside the Space'
}

function Invoke-SpaceHandoffIn([string]$Name) {
    $request = Read-SpaceHandoff $false
    New-SpaceHostFolder $request.folder
    $stateDirectory = Join-Path $script:SpaceStateDir 'space'
    $null = New-Item -ItemType Directory -Path $stateDirectory -Force
    $state = Join-Path $stateDirectory "$Name.handoff"
    $started = [DateTime]::UtcNow
    $since = $(if (Test-Path -LiteralPath $state -PathType Leaf) { (Get-Item -LiteralPath $state).LastWriteTimeUtc } else { [DateTime]::MinValue })
    $given = Get-SpaceGivenPath $Name
    $givenLines = @(if (Test-Path -LiteralPath $given -PathType Leaf) { [IO.File]::ReadAllLines($given, $script:Utf8) })
    $files = @(Get-ChildItem -LiteralPath $request.folder -Recurse -File -Force -ErrorAction SilentlyContinue | Where-Object {
        -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -and $_.Name -notin @('.DS_Store', 'desktop.ini', 'Thumbs.db') -and $_.LastWriteTimeUtc -gt $since
    } | Sort-Object FullName)
    # What the partner handed out, untouched since, is already in the Space.
    $files = @($files | Where-Object {
        $relative = $_.FullName.Substring($request.folder.Length + 1).Replace('\', '/')
        $givenLines -cnotcontains "$relative`t$(Get-SpaceFileSignature $_.FullName)"
    })
    $copied = @()
    # Runs every turn: with nothing new, the Space is left alone and this answers at once.
    if ($files.Count) {
        Start-SpaceBox $Name
        if ((Invoke-SpaceShell $Name ("mkdir -p " + (ConvertTo-SpaceShellQuote $script:SpaceHandoffDir))).code -ne 0) { Stop-Space 'exec_failed' 'cannot create the handoff folder in the Space' }
    }
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($request.folder.Length + 1).Replace('\', '/')
        $destination = Get-SpaceUnique "$($script:SpaceHandoffDir)/$relative" { param($p) Test-SpaceRemoteExists $Name $p } '/'
        $parent = $destination.Substring(0, $destination.LastIndexOf('/'))
        if ((Invoke-SpaceShell $Name ("mkdir -p " + (ConvertTo-SpaceShellQuote $parent))).code -ne 0) { Stop-Space 'exec_failed' 'cannot create a folder in the Space' }
        $result = Invoke-SpaceCua @('sb', 'cp', $file.FullName, "${Name}:$destination") 600
        if ($result.code -ne 0) { Stop-Space 'exec_failed' "copy failed: $relative" }
        $copied += , $destination.Substring($script:SpaceHandoffDir.Length + 1)
    }
    [IO.File]::WriteAllText($state, '', $script:Utf8)
    (Get-Item -LiteralPath $state).LastWriteTimeUtc = $started
    # Keep only handed-out files a later run could still list: unchanged, and not older than this run.
    if ($givenLines.Count) {
        $epoch = ([DateTimeOffset]$started).ToUnixTimeSeconds()
        $keep = @($givenLines | Where-Object {
            $parts = $_ -split "`t"
            $parts.Count -eq 2 -and (Get-SpaceFileSignature (Join-Path $request.folder $parts[0].Replace('/', '\'))) -ceq $parts[1] -and [int64]($parts[1] -split ':')[1] -ge $epoch
        })
        [IO.File]::WriteAllLines($given, [string[]]$keep, $script:Utf8)
    }
    return @{ copied = $copied }
}

# --- Dispatch ---

function Get-SpaceSize([string]$Cpus, [string]$Memory) {
    if ($Cpus -notmatch '^[0-9]{1,2}$' -or [int]$Cpus -lt 1 -or [int]$Cpus -gt 64) { Stop-Space 'bad_input' 'cpus' }
    if ($Memory -notmatch '^[0-9]{3,6}$' -or [int]$Memory -lt 512 -or [int]$Memory -gt 262144) { Stop-Space 'bad_input' 'memory' }
    return @{ cpus = [int]$Cpus; memory = [int]$Memory }
}

function Invoke-Space([string]$Action, [string]$Name, [string]$Third, [string]$Fourth) {
    switch -CaseSensitive ($Action) {
        'probe' { return Get-SpaceProbe }
        'install' { return Invoke-SpaceInstall }
        { $_ -in @('create', 'reset', 'resize') } {
            if (-not (Test-SpaceName $Name)) { return 'bad_name' }
            $size = Get-SpaceSize $Third $Fourth
            Initialize-SpaceRuntime
            if ($Action -eq 'resize') {
                if (-not (Test-SpaceKnown $Name)) { Stop-Space 'not_found' $null }
                $result = Invoke-SpaceProcess (Get-SpaceDockerCli) @('update', '--cpus', [string]$size.cpus, '--memory', "$($size.memory)m", '--memory-swap', "$($size.memory)m", $Name) 60
                if ($result.code -ne 0) { Stop-Space 'exec_failed' (Get-SpaceLastLine $result.err) }
                return @{ id = "local:$Name"; cpus = $size.cpus; memory_mb = $size.memory }
            }
            if ($Action -eq 'reset') {
                Remove-SpaceBox $Name
                # The new Space starts without the proxy settings; its next network check writes them again.
                $marker = Get-SpaceShareMarker $Name
                if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker -Force }
            }
            New-SpaceBox $Name $size.cpus $size.memory
            return @{ id = "local:$Name"; state = 'running' }
        }
        { $_ -in @('info', 'start', 'stop', 'delete', 'netcheck') } {
            if (-not (Test-SpaceName $Name)) { return 'bad_name' }
            Initialize-SpaceRuntime
            switch ($Action) {
                'info' {
                    if (-not (Test-SpaceKnown $Name)) { Stop-Space 'not_found' $null }
                    Start-SpaceBox $Name
                    $token = Get-SpaceToken $Name
                    if (-not $token) { Stop-Space 'not_found' 'no credentials for the Space' }
                    $port = Get-SpacePort $Name
                    return @{ id = "local:$Name"; state = 'running'; port = $port; token = $token; driver_version = (Get-SpaceDriverVersion $Name $token $port); proxy = (Get-SpaceShareProxy $Name) }
                }
                'start' { Start-SpaceBox $Name; return @{ id = "local:$Name"; state = 'running' } }
                'stop' {
                    if (-not (Test-SpaceKnown $Name)) { Stop-Space 'not_found' $null }
                    $result = Invoke-SpaceCua @('spaces', 'stop', "local:$Name") 120
                    if ($result.code -ne 0) { Stop-Space 'exec_failed' (Get-SpaceLastLine $result.err) }
                    $state = Get-SpaceState $Name
                    if ($state -in @('running', 'missing')) { $state = 'stopped' }
                    return @{ id = "local:$Name"; state = $state }
                }
                'delete' {
                    Remove-SpaceBox $Name
                    $marker = Get-SpaceShareMarker $Name
                    if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker -Force }
                    return @{ id = "local:$Name"; deleted = $true }
                }
                'netcheck' { return Invoke-SpaceNetcheck $Name }
            }
        }
        'handoff' {
            if (-not (Test-SpaceName $Third) -or $Fourth) { return 'bad_name' }
            Initialize-SpaceRuntime
            if ($Name -ceq 'out') { return Invoke-SpaceHandoffOut $Third }
            if ($Name -ceq 'in') { return Invoke-SpaceHandoffIn $Third }
            Stop-Space 'bad_input' 'usage: space handoff in|out <name>'
        }
        default { Stop-Space 'bad_input' 'usage: space probe|install|create|info|start|stop|delete|reset|resize|netcheck|handoff' }
    }
}
