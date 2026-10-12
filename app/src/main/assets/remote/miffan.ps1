# Miffan Windows helper v4. PowerShell 5.1; install this file as UTF-8 WITH BOM.
# Dot-source to load pure functions for offline tests; otherwise stdout is one JSON line.
# version deliberately retains the POSIX integer contract. cua stdio is a byte stream; diagnostics stay off stdout.
param([string]$Command, [string]$Action, [string]$Operation)

$script:HelperVersion = 7
$script:HelperFile = $PSCommandPath
$script:Utf8 = New-Object Text.UTF8Encoding($false, $true)

function ConvertTo-PlainJson($Value) {
    # Callers construct only hashtables, arrays and primitive values, never CIM/ETS objects.
    ConvertTo-Json -InputObject $Value -Depth 12 -Compress
}
function Get-ConfigValues([string]$Content) {
    $values = @{}
    foreach ($line in [regex]::Split($Content, '\r\n|\n|\r')) {
        $clean = ($line -split '#', 2)[0]
        if ($clean -match '^\s*([a-zA-Z0-9_]+)\s*=\s*(.*?)\s*$') { $values[$matches[1]] = [string]$matches[2] }
    }
    return $values
}
function Get-ConfigInteger($Values, [string]$Key, [int]$Default) {
    if (-not $Values.ContainsKey($Key)) { return $Default }
    $number = 0
    if ([int]::TryParse($Values[$Key], [ref]$number)) { return $number }
    return $null
}
function Edit-EncryptionConfig([string]$Content) {
    # Replace every duplicate in place and preserve all unrelated bytes/comments/newlines.
    $seen = @{}
    $edited = [regex]::Replace($Content, '(?m)^[\t ]*(lan_encryption_mode|wan_encryption_mode)[\t ]*=[^\r\n]*', {
        param($match)
        $key = $match.Groups[1].Value
        $seen[$key] = $true
        $comment = [regex]::Match($match.Value, '[\t ]*#.*$').Value
        return "$key = 2$comment"
    })
    $newline = "`n"
    if ($Content.Contains("`r`n")) { $newline = "`r`n" }
    foreach ($key in @('lan_encryption_mode', 'wan_encryption_mode')) {
        if (-not $seen.ContainsKey($key)) {
            if ($edited.Length -and -not ($edited.EndsWith("`n") -or $edited.EndsWith("`r"))) { $edited += $newline }
            $edited += "$key = 2$newline"
        }
    }
    return $edited
}
function Get-BytesSha256([byte[]]$Bytes) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
function Get-CertificateFingerprint([string]$Pem) {
    $match = [regex]::Match($Pem, '-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----')
    if (-not $match.Success) { throw 'Invalid public certificate PEM' }
    $der = [Convert]::FromBase64String(($match.Groups[1].Value -replace '\s', ''))
    $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList @(,$der)
    try { return Get-BytesSha256 $cert.RawData } finally { $cert.Reset() }
}
function ConvertTo-Network([string]$Value) {
    # Accept only explicit IPv4/IPv6 subnets, never Any, LocalSubnet, a host/range or /0.
    $parts = $Value.Split('/')
    if ($parts.Count -ne 2) { throw 'An explicit source subnet is required' }
    $address = $null
    if (-not [Net.IPAddress]::TryParse($parts[0], [ref]$address)) { throw 'Invalid subnet address' }
    $bytes = $address.GetAddressBytes()
    $bits = $bytes.Length * 8
    $prefix = 0
    if (-not [int]::TryParse($parts[1], [ref]$prefix)) {
        $mask = $null
        if ($bits -ne 32 -or -not [Net.IPAddress]::TryParse($parts[1], [ref]$mask)) { throw 'Invalid subnet mask' }
        $prefix = 0; $zeroSeen = $false
        foreach ($b in $mask.GetAddressBytes()) {
            for ($i = 7; $i -ge 0; $i--) {
                if (($b -band (1 -shl $i)) -ne 0) {
                    if ($zeroSeen) { throw 'Non-contiguous subnet mask' }
                    $prefix++
                } else { $zeroSeen = $true }
            }
        }
    }
    if ($prefix -lt 1 -or $prefix -gt $bits -or ($bits -eq 128 -and $address.ScopeId -gt 0)) { throw 'Unsafe subnet prefix' }
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $remaining = [Math]::Max(0, [Math]::Min(8, $prefix - 8 * $i))
        $bytes[$i] = $bytes[$i] -band (256 - [int][Math]::Pow(2, 8 - $remaining))
    }
    $network = New-Object Net.IPAddress -ArgumentList @(,$bytes)
    if ($network.Equals([Net.IPAddress]::Any) -and $prefix -lt 8) { throw 'Overly broad unspecified source' }
    return $network.ToString() + '/' + $prefix
}
function Get-Networks($Values) {
    $result = @($Values | ForEach-Object { ConvertTo-Network ([string]$_) } | Sort-Object -Unique)
    if ($result.Count -eq 0) { throw 'At least one source subnet is required' }
    return ,$result
}
function Test-SameSet($Left, $Right) {
    return (@($Left | Sort-Object -Unique) -join '|') -ceq (@($Right | Sort-Object -Unique) -join '|')
}
function Test-SunshineProgram([string]$Program, [string]$Executable) {
    # Exact path only; display names or the Miffan prefix are not authorization.
    try { return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Program).Trim('"')).Equals([IO.Path]::GetFullPath($Executable), [StringComparison]::OrdinalIgnoreCase) } catch { return $false }
}
function Test-UdpPorts($Ports) {
    $numbers = @()
    foreach ($port in @($Ports)) {
        if ([string]$port -match '^([0-9]+)-([0-9]+)$') {
            $first = [int]$matches[1]; $last = [int]$matches[2]
            if ($first -lt 47998 -or $last -gt 48000 -or $first -gt $last) { return $false }
            $numbers += @($first..$last)
        } elseif ([string]$port -match '^\d+$' -and [int]$port -ge 47998 -and [int]$port -le 48000) {
            $numbers += [int]$port
        } else { return $false }
    }
    return $numbers.Count -gt 0
}
function Get-RuleRisks($Rule) {
    $risks = @()
    if (-not $Rule.effective -or $Rule.action -ne 'Allow') { return ,$risks }
    if ($Rule.protocol -in @('TCP', '6', 'Any', '256')) {
        $external = @($Rule.sources | Where-Object { $_ -notin @('127.0.0.1', '127.0.0.1/8', '::1', '::1/128') }).Count -gt 0
        if ($external) { $risks += 'tcp_or_other_protocol' }
    }
    try { $null = Get-Networks $Rule.sources } catch { $risks += 'unrestricted_or_unverifiable_source' }
    if (-not (Test-UdpPorts $Rule.ports) -and $Rule.protocol -in @('UDP', '17', 'Any', '256')) { $risks += 'udp_ports_outside_47998_48000' }
    return ,$risks
}
function Get-FirewallPlan($Rules, [string]$Executable, $Sources, $DisableIds) {
    $sourcesNormalized = Get-Networks $Sources
    $disable = @($DisableIds | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    foreach ($id in $disable) {
        $found = @($Rules | Where-Object { $_.id -ceq $id })
        if ($found.Count -ne 1 -or -not (Test-SunshineProgram $found[0].program $Executable) -or $found[0].direction -ne 'Inbound' -or $found[0].policy_source -ne 'Local') {
            throw 'Only local inbound rules for this sunshine.exe can be disabled'
        }
    }
    $toDisable = @($Rules | Where-Object { $_.id -cin $disable -and $_.enabled } | ForEach-Object { $_.id })
    $safeExisting = @($Rules | Where-Object {
        $r = $_
        $addresses = @(); try { $addresses = Get-Networks $r.sources } catch { }
        (Test-SunshineProgram $r.program $Executable) -and $r.effective -and $r.action -eq 'Allow' -and
        $r.protocol -in @('UDP', '17') -and (Test-UdpPorts $r.ports) -and
        (Test-SameSet $r.ports @('47998-48000')) -and (Test-SameSet $addresses $sourcesNormalized) -and
        $r.profile -eq 'Any' -and $r.id -cnotin $disable
    })
    $create = $null
    if ($safeExisting.Count -eq 0) {
        $managed = @($Rules | Where-Object { $_.id -ceq 'Miffan-Sunshine-UDP-LAN' })
        if ($managed.Count -gt 0 -and ($managed.Count -ne 1 -or -not (Test-SunshineProgram $managed[0].program $Executable) -or $managed[0].policy_source -ne 'Local')) { throw 'Managed rule name belongs to another program or policy' }
        $create = @{ id = 'Miffan-Sunshine-UDP-LAN'; protocol = 'UDP'; ports = @('47998-48000'); sources = $sourcesNormalized; program = $Executable; replace = ($managed.Count -eq 1) }
    }
    # Do not carry out a partial security repair: every hazardous effective Allow must be covered.
    foreach ($rule in $Rules) {
        $outsideSources = $false
        if ($rule.effective -and $rule.action -eq 'Allow' -and $rule.protocol -in @('UDP','17') -and (Test-SunshineProgram $rule.program $Executable)) {
            try {
                $addresses = Get-Networks $rule.sources
                $outsideSources = @($addresses | Where-Object { $_ -cnotin $sourcesNormalized }).Count -gt 0
            } catch { $outsideSources = $true }
        }
        if (((Get-RuleRisks $rule).Count -gt 0 -or $outsideSources) -and $rule.id -cnotin $disable -and -not ($create -and $create.replace -and $rule.id -ceq $create.id)) {
            throw 'Plan leaves an unsafe effective rule; check and review again'
        }
    }
    return @{ disable_rules = $toDisable; add_rule = $create; allowed_sources = $sourcesNormalized; changed = ($toDisable.Count -gt 0 -or $null -ne $create) }
}
function New-BackupPath([string]$Name) {
    $root = Join-Path $env:LOCALAPPDATA 'Miffan'
    $null = New-Item -ItemType Directory -Path $root -Force
    $parent = Get-Item -LiteralPath $root -Force
    while ($parent) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse backup parent is not allowed' }
        $parent = $parent.Parent
    }
    $folder = Join-Path $root 'backup'
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    New-PrivateDirectory $folder $sid
    Assert-PrivateDirectory $folder $sid
    return Join-Path $folder ($Name + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff') + '-' + [Guid]::NewGuid().ToString('N'))
}
function Initialize-Native {
    if ('MiffanNative' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class MiffanNative {
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll", CharSet=CharSet.Unicode)] static extern bool WTSQuerySessionInformation(IntPtr server, uint session, int kind, out IntPtr data, out uint bytes);
    [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr data);
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct WTSLevel {
        public uint SessionId; public int SessionState; public int SessionFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=33)] public string Station;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=21)] public string User;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=18)] public string Domain;
        public long Logon, Connect, Disconnect, LastInput, Current;
        public uint InBytes, OutBytes, InFrames, OutFrames, InCompressed, OutCompressed;
    }
    [StructLayout(LayoutKind.Sequential)] public struct WTSInfo { public uint Level; public WTSLevel Data; }
    public static string QueryString(uint session, int kind) {
        IntPtr data; uint bytes;
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, kind, out data, out bytes)) return null;
        try { return Marshal.PtrToStringUni(data); } finally { WTSFreeMemory(data); }
    }
    public static int LockFlag(uint session) {
        IntPtr data; uint bytes;
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, 25, out data, out bytes)) return -1;
        try {
            if (bytes < Marshal.SizeOf(typeof(WTSInfo))) return -1;
            WTSInfo info = (WTSInfo)Marshal.PtrToStructure(data, typeof(WTSInfo));
            return info.Level == 1 && info.Data.SessionId == session ? info.Data.SessionFlags : -1;
        } finally { WTSFreeMemory(data); }
    }
}
'@
}
function Get-ConsoleSession {
    Initialize-Native
    $id = [MiffanNative]::WTSGetActiveConsoleSessionId()
    $user = $null; $domain = $null; $lock = 'unknown'; $sid = $null
    if ($id -ne [uint32]::MaxValue) {
        $user = [MiffanNative]::QueryString($id, 5); $domain = [MiffanNative]::QueryString($id, 7)
        $flag = [MiffanNative]::LockFlag($id)
        if ($flag -eq 0) { $lock = 'locked' } elseif ($flag -eq 1) { $lock = 'unlocked' }
        if ($user) {
            try {
                $account = New-Object Security.Principal.NTAccount -ArgumentList $domain, $user
                $sid = $account.Translate([Security.Principal.SecurityIdentifier]).Value
            } catch { }
        }
    }
    return @{ present = [bool]$user; type = 'console'; desktop = 'windows'; id = $(if ($id -eq [uint32]::MaxValue) { $null } else { [int]$id }); user = $user; domain = $domain; sid = $sid; lock_state = $lock }
}
function Get-MachineProbe {
    $session = Get-ConsoleSession
    $osInfo = Get-CimInstance Win32_OperatingSystem
    $gpu = @(Get-CimInstance Win32_VideoController | ForEach-Object { [string]$_.Name })
    return @{ helper = $script:HelperVersion; os = 'windows'; arch = [string]$env:PROCESSOR_ARCHITECTURE;
        session = $session; cua = (Get-CuaStatus); vnc = @{server=$null;running=$false}; rdp = @{server=$null;running=$false}; clipboard = 'interactive-task';
        os_version = [string]$osInfo.Caption; os_version_number = [string]$osInfo.Version; os_build = [string]$osInfo.BuildNumber;
        displays = $null; display_state = 'unknown'; gpu = $gpu }
}
function Get-SunshineContext {
    $service = Get-CimInstance Win32_Service -Filter "Name='SunshineService'"
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='sunshine.exe'")
    $root = $null
    if ($service -and [string]$service.PathName -match '^\s*"?(.+?)\\tools\\sunshinesvc\.exe"?\s*$') { $root = $matches[1] }
    if (-not $root) { $root = Join-Path $env:ProgramFiles 'Sunshine' }
    $exe = [IO.Path]::GetFullPath((Join-Path $root 'sunshine.exe'))
    $known = [bool]($service -and $service.StartName -eq 'LocalSystem' -and $service.PathName -match '^\s*"?(.+?)\\tools\\sunshinesvc\.exe"?\s*$' -and $processes.Count -le 1)
    foreach ($p in $processes) {
        if (-not (Test-SunshineProgram ([string]$p.ExecutablePath) $exe) -or [string]$p.CommandLine -notmatch '^\s*"?(?:[^"\r\n]*\\)?sunshine\.exe"?\s*$') { $known = $false }
    }
    $config = [IO.Path]::GetFullPath((Join-Path $root 'config\sunshine.conf'))
    $content = $null; $values = @{}; $originalBytes = $null
    if (Test-Path -LiteralPath $config -PathType Leaf) { $originalBytes = [IO.File]::ReadAllBytes($config); $content = $script:Utf8.GetString($originalBytes).TrimStart([char]0xFEFF); $values = Get-ConfigValues $content }
    $version = $null
    if (Test-Path -LiteralPath $exe -PathType Leaf) { $version = [string][Diagnostics.FileVersionInfo]::GetVersionInfo($exe).ProductVersion }
    return @{ service = $service; processes = $processes; executable = $exe; config = $config; content = $content; bytes = $originalBytes; values = $values; version = $version; known = $known; installed = [bool](Test-Path -LiteralPath $exe -PathType Leaf) }
}
function Get-StreamActivity($Context) {
    # Pending streams own these sockets too. Unknown ownership/state is conservatively active.
    try {
        $base = Get-ConfigInteger $Context.values 'port' 47989
        if ($null -eq $base -or $base -lt 1 -or $base -gt 65524 -or -not $Context.known) { return @{active=$true;known=$false} }
        $ports = @(($base+9), ($base+10), ($base+11))
        $endpoints = @(Get-NetUDPEndpoint)
        # A port collision is also unsafe for restart, regardless of PID.
        return @{active=(@($endpoints | Where-Object { $_.LocalPort -in $ports }).Count -gt 0);known=$true}
    } catch { return @{active=$true;known=$false} }
}
function Get-StreamCandidates {
    return ,@(Get-NetIPAddress -AddressFamily IPv4 | ForEach-Object {
        $ip = [string]$_.IPAddress
        if ($ip -match '^10\.' -or $ip -match '^192\.168\.' -or $ip -match '^172\.(1[6-9]|2\d|3[01])\.' -or $ip -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.') { $ip }
    } | Sort-Object -Unique)
}
function Get-FirewallSources {
    # Only active LAN interfaces with an IPv4 default gateway; never guess /24 from an IP.
    # The CGNAT range is proposed only when a live Tailscale adapter is actually present.
    $sources = @()
    foreach ($interface in @(Get-NetIPConfiguration)) {
        if (-not $interface.NetAdapter -or $interface.NetAdapter.Status -ne 'Up') { continue }
        $tailscale = ([string]$interface.InterfaceAlias -match 'Tailscale' -or [string]$interface.InterfaceDescription -match 'Tailscale')
        foreach ($address in @($interface.IPv4Address)) {
            $ip = [string]$address.IPAddress
            if ($tailscale -and $ip -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.') { $sources += '100.64.0.0/10' }
            elseif ($interface.IPv4DefaultGateway -and ($ip -match '^10\.' -or $ip -match '^192\.168\.' -or $ip -match '^172\.(1[6-9]|2\d|3[01])\.')) {
                if ($address.PrefixLength -ge 1 -and $address.PrefixLength -lt 32) { $sources += ConvertTo-Network ($ip + '/' + $address.PrefixLength) }
            }
        }
    }
    return ,@($sources | Sort-Object -Unique)
}
function Get-FirewallReview($Snapshot, [string]$Executable, $Sources) {
    $check = Get-FirewallCheck $Snapshot $Executable
    $check.allowed_sources = @($Sources)
    $check.suggested_plan = $null
    $check.plan_detail = $null
    try {
        if (-not $Snapshot.profiles_safe) { throw 'Firewall profiles do not enforce inbound blocking' }
        $allowed = Get-Networks $Sources
        # Existing explicit subnets are not automatically approval for this network's sources.
        # Include otherwise narrow UDP rules with different scopes in the complete review plan.
        foreach ($rule in $Snapshot.rules) {
            if (-not $rule.effective -or $rule.action -ne 'Allow' -or $rule.protocol -notin @('UDP','17') -or
                -not (Test-SunshineProgram $rule.program $Executable) -or (Get-RuleRisks $rule).Count -gt 0) { continue }
            $ruleSources = Get-Networks $rule.sources
            if (@($ruleSources | Where-Object { $_ -cnotin $allowed }).Count -gt 0) {
                $check.unsafe_rules += @{id=$rule.id;reasons=@('sources_differ_from_proposed_networks')}
                $check.firewall_model_ok = $false
                if ($rule.policy_source -eq 'Local') { $check.suggested_changes.disable_rules += $rule.id }
                else { $check.unmanageable_rules += $rule.id }
            }
        }
        if ($check.unmanageable_rules.Count -gt 0) { throw 'Policy-managed firewall rules cannot be repaired locally' }
        $plan = Get-FirewallPlan $Snapshot.rules $Executable $Sources $check.suggested_changes.disable_rules
        if ($plan.changed) {
            $check.suggested_plan = @{disable_rules=@($plan.disable_rules);allowed_sources=@($plan.allowed_sources);add_rule=$plan.add_rule}
            $check.firewall_model_ok = $false
        }
    } catch {
        $check.firewall_model_ok = $false
        $check.plan_detail = 'Cannot propose a complete local repair; inspect profiles, source subnets and policy-managed rules'
    }
    return $check
}
function Get-SunshineProbe {
    $context = Get-SunshineContext
    $activity = Get-StreamActivity $context
    $certPath = Join-Path (Split-Path -Parent $context.config) 'credentials\cacert.pem'
    if ($context.values.ContainsKey('cert')) {
        $certPath = $context.values['cert']
        if (-not [IO.Path]::IsPathRooted($certPath)) { $certPath = Join-Path (Split-Path -Parent $context.config) $certPath }
    }
    $fingerprint = $null
    if ($context.known) { try { $fingerprint = Get-CertificateFingerprint ([IO.File]::ReadAllText($certPath)) } catch { } }
    $listeners = @(); $portsKnown = $true
    try {
        $ids = @($context.processes | ForEach-Object { [int]$_.ProcessId })
        $listeners = @(Get-NetTCPConnection -State Listen | Where-Object { $_.OwningProcess -in $ids } | ForEach-Object { @{ address=[string]$_.LocalAddress; port=[int]$_.LocalPort; pid=[int]$_.OwningProcess } })
    } catch { $portsKnown = $false }
    $session = Get-ConsoleSession
    return @{ installed=$context.installed;version=$context.version;running=[bool]($context.service -and $context.service.State -eq 'Running' -and $context.processes.Count -gt 0);
        lan_encryption_mode=$(if ($context.known) { Get-ConfigInteger $context.values 'lan_encryption_mode' 0 } else { $null });
        wan_encryption_mode=$(if ($context.known) { Get-ConfigInteger $context.values 'wan_encryption_mode' 1 } else { $null });
        certificate_sha256=$fingerprint;active_stream=[bool]$activity.active;stream_state_known=[bool]$activity.known;candidates=(Get-StreamCandidates);
        display_asleep=$null;screen_locked=$(if ($session.lock_state -eq 'unknown') {$null} else {$session.lock_state -eq 'locked'});permissions=$null;permissions_from_log=$null;
        service_state=$(if ($context.service) {[string]$context.service.State} else {$null});console_session_id=$session.id;
        daemon_session_ids=@($context.processes | ForEach-Object {[int]$_.SessionId});listeners=$listeners;listeners_known=$portsKnown;configuration_known=$context.known }
}
function Invoke-SunshineStart {
    $context = Get-SunshineContext
    if (-not $context.known -or -not $context.installed) { throw 'Cannot verify the Sunshine service model' }
    if ($context.service.State -eq 'Running') { return @{success=$true;detail='Sunshine is already running';changed=$false;backup=$null} }
    Start-Service -Name SunshineService
    return @{success=$true;detail='Sunshine start requested';changed=$true;backup=$null}
}
function Assert-RegularPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Regular file required' }
    $parent = $item.Directory
    while ($parent) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse directory is not allowed' }
        $parent = $parent.Parent
    }
}
function Invoke-EnforceEncryption {
    $context = Get-SunshineContext
    if (-not $context.known -or -not $context.installed -or $null -eq $context.content) { throw 'Cannot verify Sunshine configuration' }
    $activity = Get-StreamActivity $context
    if ($activity.active -or -not $activity.known) { throw 'Active or unknown stream; refusing encryption change' }
    Assert-RegularPath $context.config
    if ((Get-ConfigInteger $context.values 'lan_encryption_mode' 0) -eq 2 -and (Get-ConfigInteger $context.values 'wan_encryption_mode' 1) -eq 2) {
        return @{success=$true;detail='Encryption is already enforced';changed=$false;backup=$null}
    }
    if ((Get-BytesSha256 ([IO.File]::ReadAllBytes($context.config))) -ne (Get-BytesSha256 $context.bytes)) { throw 'Sunshine configuration changed before backup' }
    $backup = New-BackupPath 'sunshine.conf'
    $script:LastBackup = $backup
    Copy-Item -LiteralPath $context.config -Destination $backup
    $temporary = $context.config + '.miffan-' + [Guid]::NewGuid().ToString('N')
    try {
        $bytes = $context.bytes
        if ((Get-BytesSha256 ([IO.File]::ReadAllBytes($backup))) -ne (Get-BytesSha256 $bytes)) { throw 'Sunshine configuration changed during backup' }
        # Preserve a pre-existing UTF-8 BOM as well as unrelated configuration text.
        $encoding = New-Object Text.UTF8Encoding(($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191))
        [IO.File]::WriteAllText($temporary, (Edit-EncryptionConfig $context.content), $encoding)
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $context.config)
        $current = Get-SunshineContext
        $activity = Get-StreamActivity $current
        if (-not $current.known -or $current.config -ne $context.config -or $activity.active -or -not $activity.known -or -not (Test-SameSet @((Get-BytesSha256 $bytes)) @((Get-BytesSha256 ([IO.File]::ReadAllBytes($context.config)))))) { throw 'State changed; refusing configuration replacement' }
        [IO.File]::Replace($temporary, $context.config, [System.Management.Automation.Language.NullString]::Value)
        $activity = Get-StreamActivity (Get-SunshineContext)
        if ($activity.active -or -not $activity.known) { throw 'Encryption saved; stream state prevents restart' }
        Restart-Service -Name SunshineService
        return @{success=$true;detail='Encryption enforced; Sunshine restarted';changed=$true;backup=$backup}
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}
function Test-RuleEnforced([bool]$Enabled, [string]$Status, $Enforcement) {
    # NetSecurity exposes these CIM enum values as strings in Windows PowerShell 5.1.
    return $Enabled -and $Status -eq 'OK' -and @($Enforcement | Where-Object { [string]$_ -in @('Enforced','Full','1') }).Count -gt 0
}
function Get-PolicyIndex($Objects) {
    $index = @{}
    foreach ($object in $Objects) {
        $id = [string]$object.InstanceID
        if (-not $id -or $index.ContainsKey($id)) {throw 'Ambiguous ActiveStore filter identity'}
        $index[$id] = $object
    }
    return $index
}
function Get-FirewallSnapshot([string]$Executable) {
    $rules = @()
    # ActiveStore includes effective local/GPO policy; disabled rules are retained for plans.
    # Association queries can return NotApplicable/PersistentStore data even with
    # -PolicyStore ActiveStore on this PS 5.1 provider. Enumerate each ActiveStore
    # table explicitly and join by InstanceID; never use an association query here.
    $activeRules = Get-PolicyIndex @(Get-NetFirewallRule -PolicyStore ActiveStore -All)
    $activePorts = Get-PolicyIndex @(Get-NetFirewallPortFilter -PolicyStore ActiveStore -All)
    $activeAddresses = Get-PolicyIndex @(Get-NetFirewallAddressFilter -PolicyStore ActiveStore -All)
    $applications = @(Get-NetFirewallApplicationFilter -PolicyStore ActiveStore -All | Where-Object { Test-SunshineProgram ([string]$_.Program) $Executable })
    foreach ($application in $applications) {
        $id = [string]$application.InstanceID
        $rule = $activeRules[$id]
        if (-not $rule -or -not $activePorts.ContainsKey($id) -or -not $activeAddresses.ContainsKey($id)) {throw 'ActiveStore rule or filters disappeared during inspection'}
        if ([string]$rule.Direction -ne 'Inbound') {continue}
        $program = [string]$application.Program
        $port = $activePorts[$id]
        $address = $activeAddresses[$id]
        $enforcement = @($rule.EnforcementStatus | ForEach-Object { [string]$_ })
        $enabled = [string]$rule.Enabled -eq 'True'
        $status = [string]$rule.PrimaryStatus
        $rules += @{ id=[string]$rule.Name;display_name=[string]$rule.DisplayName;program=$program;direction=[string]$rule.Direction;
            enabled=$enabled;effective=(Test-RuleEnforced $enabled $status $enforcement);
            action=[string]$rule.Action;profile=[string]$rule.Profile;protocol=[string]$port.Protocol;ports=@($port.LocalPort | ForEach-Object {[string]$_});
            sources=@($address.RemoteAddress | ForEach-Object {[string]$_});policy_source=[string]$rule.PolicyStoreSourceType;
            status=$status;enforcement=@($rule.EnforcementStatus | ForEach-Object {[string]$_}) }
    }
    $profiles = @(Get-NetFirewallProfile -PolicyStore ActiveStore | ForEach-Object { @{name=[string]$_.Name;enabled=([string]$_.Enabled -in @('True','1'));default_inbound=[string]$_.DefaultInboundAction} })
    # NotConfigured is the Windows default Block, but report it explicitly in the snapshot.
    $profileOk = @($profiles | Where-Object { -not $_.enabled -or $_.default_inbound -notin @('Block','NotConfigured') }).Count -eq 0 -and $profiles.Count -eq 3
    return @{rules=$rules;profiles=$profiles;profiles_safe=$profileOk}
}
function Get-FirewallCheck($Snapshot, [string]$Executable) {
    $unsafe = @(); $disable = @(); $blocked = @()
    foreach ($rule in $Snapshot.rules) {
        $risks = Get-RuleRisks $rule
        if ($risks.Count -gt 0) {
            $unsafe += @{id=$rule.id;reasons=$risks}
            if ((Test-SunshineProgram $rule.program $Executable) -and $rule.policy_source -eq 'Local') { $disable += $rule.id } else { $blocked += $rule.id }
        }
    }
    $udp = @($Snapshot.rules | Where-Object { (Test-SunshineProgram $_.program $Executable) -and $_.effective -and $_.action -eq 'Allow' -and $_.protocol -in @('UDP','17') -and (Get-RuleRisks $_).Count -eq 0 -and (Test-SameSet $_.ports @('47998-48000')) })
    return @{success=$true;detail='Firewall inspected';firewall_model_ok=($Snapshot.profiles_safe -and $unsafe.Count -eq 0 -and $udp.Count -gt 0);udp_rule_present=($udp.Count -gt 0);
        rules=@($Snapshot.rules);profiles=@($Snapshot.profiles);unsafe_rules=$unsafe;unmanageable_rules=$blocked;
        suggested_changes=@{disable_rules=$disable;add_rule=$(if ($udp.Count -eq 0) {@{id='Miffan-Sunshine-UDP-LAN';protocol='UDP';ports=@('47998-48000');allowed_sources=$null;source_required=$true;program=$Executable}} else {$null})}}
}
function Read-FirewallRequest([string]$Text) {
    $request = ConvertFrom-Json -InputObject $Text
    $names = @($request.PSObject.Properties.Name)
    if (-not (Test-SameSet $names @('disable_rules','allowed_sources','add_rule'))) { throw 'Expected disable_rules, allowed_sources and add_rule' }
    if ($request.disable_rules -isnot [Array] -or $request.allowed_sources -isnot [Array]) { throw 'Firewall request fields must be arrays' }
    foreach ($value in @($request.disable_rules) + @($request.allowed_sources)) { if ($value -isnot [string]) { throw 'Firewall request values must be strings' } }
    $add = $null
    if ($null -ne $request.add_rule) {
        $r = $request.add_rule
        if (-not (Test-SameSet @($r.PSObject.Properties.Name) @('id','program','protocol','ports','sources','replace'))) { throw 'Unexpected add_rule fields' }
        if ($r.id -cne 'Miffan-Sunshine-UDP-LAN' -or $r.protocol -cne 'UDP' -or $r.ports -isnot [Array] -or -not (Test-SameSet $r.ports @('47998-48000')) -or $r.sources -isnot [Array] -or $r.replace -isnot [bool] -or $r.program -isnot [string]) { throw 'Only the fixed Sunshine UDP rule may be added' }
        foreach ($value in $r.sources) { if ($value -isnot [string]) {throw 'Firewall request values must be strings'} }
        $add = @{id=[string]$r.id;program=[string]$r.program;protocol='UDP';ports=@('47998-48000');sources=(Get-Networks $r.sources);replace=[bool]$r.replace}
    }
    return @{disable_rules=@($request.disable_rules);allowed_sources=(Get-Networks $request.allowed_sources);add_rule=$add}
}
function Assert-FirewallPlanMatches($Calculated, $Requested, [string]$Executable) {
    # A check is advisory. Apply always recomputes from ActiveStore before any backup/write.
    if (-not $Calculated.changed) { throw 'Empty firewall plan; no writes were performed' }
    if (-not (Test-SameSet $Calculated.disable_rules $Requested.disable_rules)) { throw 'Firewall plan differs from the caller-approved plan' }
    if (($null -eq $Calculated.add_rule) -ne ($null -eq $Requested.add_rule)) { throw 'Firewall plan differs from the caller-approved plan' }
    if ($Calculated.add_rule) {
        $expected = $Requested.add_rule; $actual = $Calculated.add_rule
        if ($expected.id -cne $actual.id -or $expected.protocol -cne 'UDP' -or -not (Test-SunshineProgram $expected.program $Executable) -or
            -not (Test-SameSet $expected.ports $actual.ports) -or -not (Test-SameSet $expected.sources $actual.sources) -or $expected.replace -ne $actual.replace) {
            throw 'Firewall plan differs from the caller-approved plan'
        }
    }
}
# PersistentStore below is deliberate: backup/mutation targets only approved Local rules.
# All inspection and recomputation use ActiveStore; GPO/effective policy is never modified.
function Save-FirewallBackup($Plan, $Snapshot) {
    $path = New-BackupPath 'sunshine-firewall'
    $null = New-Item -ItemType Directory -Path $path
    # Full local objects + every associated filter permit targeted manual restoration.
    $ids = @($Plan.disable_rules)
    if ($Plan.add_rule -and $Plan.add_rule.replace) { $ids += $Plan.add_rule.id }
    foreach ($id in $ids) {
        $r = Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Name -ceq $id }
        if (@($r).Count -ne 1) { throw 'Local rule changed before backup' }
        $entry = @{rule=$r;application=(Get-NetFirewallApplicationFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);port=(Get-NetFirewallPortFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);address=(Get-NetFirewallAddressFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);
            service=(Get-NetFirewallServiceFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);interface=(Get-NetFirewallInterfaceFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);interface_type=(Get-NetFirewallInterfaceTypeFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r);security=(Get-NetFirewallSecurityFilter -PolicyStore PersistentStore -AssociatedNetFirewallRule $r)}
        $entry | Export-Clixml -LiteralPath (Join-Path $path ((Get-BytesSha256 $script:Utf8.GetBytes($id)) + '.clixml'))
    }
    [IO.File]::WriteAllText((Join-Path $path 'plan.json'), (ConvertTo-PlainJson @{plan=$Plan;before=$Snapshot}), $script:Utf8)
    return $path
}
function Invoke-FirewallMutations($Plan) {
    foreach ($id in $Plan.disable_rules) { Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Name -ceq $id } | NetSecurity\Disable-NetFirewallRule | Out-Null }
    if ($Plan.add_rule) {
        $r = $Plan.add_rule
        if ($r.replace) { Get-NetFirewallRule -PolicyStore PersistentStore | Where-Object { $_.Name -ceq $r.id } | NetSecurity\Remove-NetFirewallRule }
        NetSecurity\New-NetFirewallRule -PolicyStore PersistentStore -Name $r.id -DisplayName 'Miffan Sunshine UDP LAN' -Direction Inbound -Action Allow -Enabled True -Profile Any -Program $r.program -Protocol UDP -LocalPort '47998-48000' -RemoteAddress $r.sources | Out-Null
    }
}
function Get-SnapshotSignature($Snapshot) {
    # Sort before hashing so enumeration order does not cause spurious conflicts.
    Get-BytesSha256 $script:Utf8.GetBytes((ConvertTo-PlainJson @{profiles=@($Snapshot.profiles | Sort-Object name);rules=@($Snapshot.rules | Sort-Object id)}))
}
function Invoke-FirewallApply($Request) {
    $context = Get-SunshineContext
    if (-not $context.installed -or -not $context.known) { throw 'Cannot verify Sunshine installation' }
    $snapshot = Get-FirewallSnapshot $context.executable
    if (-not $snapshot.profiles_safe) { throw 'Firewall profiles do not enforce inbound blocking' }
    $plan = Get-FirewallPlan $snapshot.rules $context.executable $Request.allowed_sources $Request.disable_rules
    Assert-FirewallPlanMatches $plan $Request $context.executable
    $backup = $null
    if ($plan.changed) {
        $backup = Save-FirewallBackup $plan $snapshot
        $script:LastBackup = $backup
        $fresh = Get-FirewallSnapshot $context.executable
        if ((Get-SnapshotSignature $snapshot) -ne (Get-SnapshotSignature $fresh)) { throw 'Firewall changed after review; no rules were changed' }
        Invoke-FirewallMutations $plan
    }
    $after = Get-FirewallSnapshot $context.executable
    $result = Get-FirewallCheck $after $context.executable
    $remaining = Get-FirewallPlan $after.rules $context.executable $Request.allowed_sources @()
    $result.success = $result.firewall_model_ok -and -not $remaining.changed
    $result.detail = $(if ($result.success) {'Firewall plan verified'} else {'Firewall verification failed; inspect backup'})
    $result.changed = $plan.changed; $result.backup = $backup; $result.applied_changes = $plan
    return $result
}

# Desktop worker receives no text in its arguments: data stays in an ACL-protected file.
$script:DesktopWorker = @'
param([string]$Directory, [int]$ExpectedSession, [string]$ExpectedSid, [string]$Mode)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$utf8 = New-Object Text.UTF8Encoding($false, $true)
$result = @{success=$false;detail='Desktop worker failed'}
try {
    $session = [Diagnostics.Process]::GetCurrentProcess().SessionId
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($session -ne $ExpectedSession -or $session -eq 0 -or $sid -ne $ExpectedSid) { throw 'Wrong desktop session' }
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class MiffanDisplay {
    [DllImport("shcore.dll")] public static extern int SetProcessDpiAwareness(int awareness);
    [DllImport("shcore.dll")] public static extern int GetProcessDpiAwareness(IntPtr process, out int awareness);
    [DllImport("shcore.dll")] public static extern int GetDpiForMonitor(IntPtr monitor, int kind, out uint x, out uint y);
    [DllImport("user32.dll")] public static extern IntPtr MonitorFromPoint(Point p, uint flags);
    [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
    [StructLayout(LayoutKind.Sequential)] public struct Point { public int X, Y; }
}
"@
    if ([MiffanDisplay]::WTSGetActiveConsoleSessionId() -ne $ExpectedSession) { throw 'Console session changed' }
    if ($Mode -eq 'display') {
        $null = [MiffanDisplay]::SetProcessDpiAwareness(2)
        $awareness = 0
        $dpiKnown = [MiffanDisplay]::GetProcessDpiAwareness([IntPtr]::Zero, [ref]$awareness) -eq 0 -and $awareness -eq 2
        Add-Type -AssemblyName System.Windows.Forms
        $displays = @([Windows.Forms.Screen]::AllScreens | ForEach-Object {
            $screen = $_
            $point = New-Object MiffanDisplay+Point
            $point.X = $screen.Bounds.X + 1; $point.Y = $screen.Bounds.Y + 1
            $handle = [MiffanDisplay]::MonitorFromPoint($point, 0)
            $dx = [uint32]0; $dy = [uint32]0
            $got = $dpiKnown -and [MiffanDisplay]::GetDpiForMonitor($handle, 0, [ref]$dx, [ref]$dy) -eq 0
            @{name=[string]$screen.DeviceName;primary=[bool]$screen.Primary;x=[int]$screen.Bounds.X;y=[int]$screen.Bounds.Y;
                width=[int]$screen.Bounds.Width;height=[int]$screen.Bounds.Height;
                dpi_x=$(if ($got) {[int]$dx} else {$null});dpi_y=$(if ($got) {[int]$dy} else {$null});scale_percent=$(if ($got) {[Math]::Round($dx*100.0/96,2)} else {$null})}
        })
        $result = @{success=$true;detail='Desktop displays inspected';session_id=$session;display_count=$displays.Count;displays=$displays}
    } elseif ($Mode -eq 'clip') {
        Add-Type -AssemblyName System.Windows.Forms
        $text = [IO.File]::ReadAllText((Join-Path $Directory 'input.txt'), $utf8)
        if ($text.Contains([char]0)) { throw 'NUL cannot be represented in a text clipboard' }
        $verified = $false
        for ($attempt = 0; $attempt -lt 10; $attempt++) {
            try {
                if ($text.Length -eq 0) { [Windows.Forms.Clipboard]::Clear() }
                else { [Windows.Forms.Clipboard]::SetText($text, [Windows.Forms.TextDataFormat]::UnicodeText) }
                $readback = [Windows.Forms.Clipboard]::GetText([Windows.Forms.TextDataFormat]::UnicodeText)
                if ($readback -ceq $text) { $verified = $true; break }
            } catch { }
            Start-Sleep -Milliseconds 100
        }
        if (-not $verified) { throw 'Clipboard readback did not match' }
        $result = @{success=$true;detail='Clipboard written and read back';session_id=$session;verified=$true}
    } else { throw 'Unknown desktop worker mode' }
} catch { $result = @{success=$false;detail='Desktop worker failed';session_id=[Diagnostics.Process]::GetCurrentProcess().SessionId} }
# No clipboard text/hash/window titles are exported. Commit the result atomically.
$temp = Join-Path $Directory 'result.tmp'
[IO.File]::WriteAllText($temp, (ConvertTo-Json -InputObject $result -Depth 8 -Compress), $utf8)
[IO.File]::Move($temp, (Join-Path $Directory 'result.json'))
'@
function New-PrivateDirectory([string]$Path, [string]$Sid) {
    # Create with its DACL in a single operation (no inherited-access window).
    $security = New-Object Security.AccessControl.DirectorySecurity
    $security.SetAccessRuleProtection($true, $false)
    $identity = New-Object Security.Principal.SecurityIdentifier -ArgumentList $Sid
    $security.SetOwner($identity)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule -ArgumentList $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
    $null = $security.AddAccessRule($rule)
    $null = [IO.Directory]::CreateDirectory($Path, $security)
}
function Assert-PrivateDirectory([string]$Path, [string]$Sid) {
    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Private directory must not be a reparse point' }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw 'Private directory must not inherit permissions' }
    $entries = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($entries.Count -ne 1 -or $entries[0].IdentityReference.Value -ne $Sid -or $entries[0].AccessControlType -ne 'Allow') { throw 'Unexpected private directory ACL' }
}
function Stop-OwnedDesktopTask([string]$TaskName) {
    $state = [string](Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop).State
    if ($state -in @('Ready','Disabled')) { return }
    try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop }
    catch {
        # The worker may have finished between querying State and Stop-ScheduledTask.
        $fresh = [string](Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop).State
        if ($fresh -notin @('Ready','Disabled')) { throw 'Desktop task could not be stopped' }
    }
}
function Unregister-OwnedDesktopTask([string]$TaskName) {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $owned = @(Get-ScheduledTask -TaskName $TaskName -TaskPath '\' -ErrorAction SilentlyContinue)
        if ($owned.Count -eq 0) { return }
        if ($owned.Count -ne 1) { throw 'Ambiguous desktop task identity' }
        try {
            # Use the fresh CIM object, with an explicit root TaskPath, rather than a
            # name-only provider lookup while the completed task transitions to Ready.
            Unregister-ScheduledTask -InputObject $owned[0] -Confirm:$false -ErrorAction Stop
            return
        } catch {
            if ($attempt -eq 2) { throw 'Desktop task could not be unregistered' }
            Start-Sleep -Milliseconds 100
        }
    }
}
function Invoke-DesktopJob([string]$Mode, [string]$Text) {
    if ($Mode -notin @('display','clip')) { throw 'Unknown desktop operation' }
    $console = Get-ConsoleSession
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (-not $console.present -or -not $console.sid -or $console.sid -ne $sid -or $console.id -eq 0) { throw 'Desktop job requires the logged-in console user to match the SSH user' }
    if ($Mode -eq 'clip' -and $console.lock_state -ne 'unlocked') { throw 'Clipboard requires a confirmed unlocked console session' }
    if ($Mode -eq 'clip' -and $Text.Contains([char]0)) { throw 'Clipboard text cannot contain NUL' }
    $root = Join-Path $env:LOCALAPPDATA 'Miffan'
    $null = New-Item -ItemType Directory -Path $root -Force
    # Never traverse junctions in the job path.
    $parent = Get-Item -LiteralPath $root -Force
    while ($parent) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse job parent is not allowed' }
        $parent = $parent.Parent
    }
    $token = [Guid]::NewGuid().ToString('N')
    $directory = Join-Path $root ('job-' + $token)
    $task = 'Miffan-' + $Mode + '-' + $token
    $registered = $false
    $script:LastDesktopTask = $task; $script:LastDesktopDirectory = $directory
    New-PrivateDirectory $directory $sid
    try {
        Assert-PrivateDirectory $directory $sid
        $worker = Join-Path $directory 'worker.ps1'
        [IO.File]::WriteAllText($worker, $script:DesktopWorker, (New-Object Text.UTF8Encoding($true)))
        if ($Mode -eq 'clip') { [IO.File]::WriteAllText((Join-Path $directory 'input.txt'), $Text, $script:Utf8) }
        # Windows user paths cannot contain double quotes. No user text enters this command.
        $arguments = '-NoLogo -NoProfile -NonInteractive -STA -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $worker + '" -Directory "' + $directory + '" -ExpectedSession ' + $console.id + ' -ExpectedSid ' + $sid + ' -Mode ' + $Mode
        $action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments
        $principal = New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Seconds 20) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        # Register without -Force; do not replace any existing task.
        $null = Register-ScheduledTask -TaskName $task -Action $action -Principal $principal -Settings $settings
        $registered = $true
        $fresh = Get-ConsoleSession
        if ($fresh.id -ne $console.id -or $fresh.sid -ne $sid -or ($Mode -eq 'clip' -and $fresh.lock_state -ne 'unlocked')) { throw 'Console session changed before task start' }
        Start-ScheduledTask -TaskName $task
        $resultPath = Join-Path $directory 'result.json'
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path -LiteralPath $resultPath) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
        if (-not (Test-Path -LiteralPath $resultPath)) { throw 'Desktop task timed out' }
        $result = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($resultPath, $script:Utf8))
        # Rebuild primitives rather than forwarding arbitrary worker/ETS objects.
        if (-not $result.success -or $result.session_id -ne $console.id) { throw 'Desktop task failed or returned another session' }
        if ($Mode -eq 'clip') { if ($result.verified -ne $true) {throw 'Clipboard verification missing'}; return @{success=$true;detail='Clipboard written and read back';session_id=[int]$result.session_id;verified=[bool]$result.verified} }
        $displays = @($result.displays | ForEach-Object { @{name=[string]$_.name;primary=[bool]$_.primary;x=[int]$_.x;y=[int]$_.y;width=[int]$_.width;height=[int]$_.height;dpi_x=$_.dpi_x;dpi_y=$_.dpi_y;scale_percent=$_.scale_percent} })
        return @{success=$true;detail='Desktop displays inspected';session_id=[int]$result.session_id;display_count=$displays.Count;displays=$displays}
    } finally {
        # Stop only our one-shot task before deleting files, including on timeout/failure.
        $cleanupFailed = $false
        $stopped = $true
        if ($registered) {
            try { Stop-OwnedDesktopTask $task } catch { $stopped=$false; $cleanupFailed=$true }
            try { Unregister-OwnedDesktopTask $task } catch { $cleanupFailed=$true }
        }
        if ($stopped) {
            try { Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction Stop } catch { $cleanupFailed=$true }
        }
        if ($cleanupFailed) { throw 'Desktop task cleanup failed; inspect the reported task and directory' }
        $script:LastDesktopTask = $null; $script:LastDesktopDirectory = $null
    }
}
$script:CuaBridgeSource = @'
using System;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Principal;
using System.Security.AccessControl;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
using System.Collections.Generic;
using System.Web.Script.Serialization;

// No daemon ACL/permission changes. Every MCP child inherits this Limited interactive token.
public static class MiffanCuaBridge {
    public static readonly Encoding Utf8 = new UTF8Encoding(false, true);
    [StructLayout(LayoutKind.Sequential)] struct SA { public int length; public IntPtr descriptor; public int inherit; }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern SafePipeHandle CreateNamedPipe(string name, uint mode, uint pipeMode, uint instances, uint output, uint input, uint timeout, ref SA security);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] static extern bool WaitNamedPipe(string name, uint timeout);
    [DllImport("kernel32.dll")] static extern uint WTSGetActiveConsoleSessionId();
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool GetTokenInformation(IntPtr token, int kind, IntPtr buffer, int size, out int needed);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);
    [DllImport("user32.dll", SetLastError=true)] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool GetUserObjectInformation(IntPtr obj, int index, StringBuilder info, int length, out int needed);
    [DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int number);
    static Stream Standard(int number, FileAccess access) {
        // Windows OpenSSH inherits OVERLAPPED pipe handles. ConsoleStream's synchronous
        // ReadFile can handle the first buffered batch then hang on the next pending read.
        try { return new FileStream(new SafeFileHandle(GetStdHandle(number),false),access,65536,true); }
        catch (ArgumentException) { return number==-10 ? Console.OpenStandardInput() : Console.OpenStandardOutput(); }
    }
    static long lastActivity;
    static int active;
    static readonly System.Collections.Concurrent.ConcurrentDictionary<NamedPipeServerStream,byte> clients = new System.Collections.Concurrent.ConcurrentDictionary<NamedPipeServerStream,byte>();
    static readonly System.Collections.Concurrent.ConcurrentDictionary<int,Process> children = new System.Collections.Concurrent.ConcurrentDictionary<int,Process>();
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
    [DllImport("advapi32.dll")] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    static int TokenIntegrity(IntPtr token) {
        int size; GetTokenInformation(token,25,IntPtr.Zero,0,out size);
        if(size<=0) throw new IOException("Cannot check integrity");
        IntPtr memory=Marshal.AllocHGlobal(size);
        try {
            if(!GetTokenInformation(token,25,memory,size,out size)) throw new IOException("Cannot check integrity");
            IntPtr sid=Marshal.ReadIntPtr(memory);
            return Marshal.ReadInt32(GetSidSubAuthority(sid,(uint)(Marshal.ReadByte(GetSidSubAuthorityCount(sid))-1)));
        } finally {Marshal.FreeHGlobal(memory);}
    }
    public static int Integrity() {using(var identity=WindowsIdentity.GetCurrent()) return TokenIntegrity(identity.Token);}
    public static int ProcessIntegrity(int pid) {
        IntPtr process=OpenProcess(0x1000,false,pid),token=IntPtr.Zero;
        if(process==IntPtr.Zero) return -1;
        try {if(!OpenProcessToken(process,8,out token)) return -1;return TokenIntegrity(token);}
        catch {return -1;}
        finally {if(token!=IntPtr.Zero) CloseHandle(token);CloseHandle(process);}
    }
    public static bool DesktopAvailable(int session) {
        if (session == 0 || WTSGetActiveConsoleSessionId() != session) return false;
        IntPtr desktop = OpenInputDesktop(0, false, 1);
        if (desktop == IntPtr.Zero) return false;
        try { var name = new StringBuilder(128); int needed; return GetUserObjectInformation(desktop, 2, name, 256, out needed) && name.ToString() == "Default"; }
        finally { CloseDesktop(desktop); }
    }
    public static bool Available(string name) { return WaitNamedPipe(@"\\.\pipe\" + name, 1); }
    static NamedPipeServerStream Create(string name, string sid, bool first) {
        // Protected DACL: precisely this user, no Everyone/Administrators/System grant.
        var security = new PipeSecurity(); security.SetSecurityDescriptorSddlForm("O:"+sid+"D:P(A;;GA;;;"+sid+")");
        byte[] descriptor = security.GetSecurityDescriptorBinaryForm();
        IntPtr memory = Marshal.AllocHGlobal(descriptor.Length);
        try {
            Marshal.Copy(descriptor, 0, memory, descriptor.Length);
            var attributes = new SA { length=Marshal.SizeOf(typeof(SA)), descriptor=memory, inherit=0 };
            // DUPLEX | OVERLAPPED | FIRST_PIPE_INSTANCE, byte mode | REJECT_REMOTE_CLIENTS.
            var handle = CreateNamedPipe(@"\\.\pipe\"+name, 3u|0x40000000u|(first?0x00080000u:0u), 0x8u, 8, 65536, 65536, 1000, ref attributes);
            if (handle.IsInvalid) { handle.Dispose(); throw new IOException("Bridge pipe unavailable"); }
            return new NamedPipeServerStream(PipeDirection.InOut, true, false, handle);
        } finally { Marshal.FreeHGlobal(memory); }
    }
    static void Touch() { Interlocked.Exchange(ref lastActivity, Environment.TickCount & 0xffffffffL); }
    static long Age(long then) { return ((Environment.TickCount & 0xffffffffL)-then)&0xffffffffL; }
    public static string ReadLine(Stream stream, int limit) {
        using (var data = new MemoryStream()) {
            for (int i=0; i<limit; i++) { int b=stream.ReadByte(); if(b<0) throw new EndOfStreamException(); if(b==10) return Utf8.GetString(data.ToArray()); data.WriteByte((byte)b); }
            throw new IOException("Frame too long");
        }
    }
    public static void WriteLine(Stream stream, string line) { byte[] data=Utf8.GetBytes(line+"\n"); stream.Write(data,0,data.Length); stream.Flush(); }
    public static string Ticket(string root, string id) {
        Guid value; if (!Guid.TryParseExact(id,"N",out value)) throw new IOException("Invalid ticket");
        return Path.Combine(root,id+".ticket");
    }
    public static bool Consume(string root, string id, string token) {
        string file=Ticket(root,id), consumed=file+".used-"+Guid.NewGuid().ToString("N");
        try {
            if ((File.GetAttributes(file)&FileAttributes.ReparsePoint)!=0 || (DateTime.UtcNow-File.GetLastWriteTimeUtc(file)).TotalSeconds>30) return false;
            File.Move(file,consumed); // Atomic claim. Even failed authentication consumes the ticket.
            string expected=File.ReadAllText(consumed,Utf8);
            if (expected.Length!=64 || token.Length!=64) return false;
            int difference=0; for(int i=0;i<64;i++) difference |= expected[i]^token[i];
            return difference==0;
        } catch { return false; }
        finally { if (File.Exists(consumed)) File.Delete(consumed); }
    }
    static void Pump(Stream input, Stream output) {
        byte[] data=new byte[65536]; int n;
        while((n=input.ReadAsync(data,0,data.Length).GetAwaiter().GetResult())>0) { output.WriteAsync(data,0,n).GetAwaiter().GetResult(); output.FlushAsync().GetAwaiter().GetResult(); Touch(); }
    }
    static void Serve(NamedPipeServerStream pipe, string root, string exe, int session) {
        Process child=null;
        try {
            var auth=Task.Run(()=>ReadLine(pipe,128));
            if (!auth.Wait(5000)) return;
            string[] parts=auth.Result.Split(' ');
            if(parts.Length!=2 || !DesktopAvailable(session) || !Consume(root,parts[0],parts[1])) return;
            var start=new ProcessStartInfo(exe,"mcp --socket \\\\.\\pipe\\cua-driver") {
                UseShellExecute=false, CreateNoWindow=true, RedirectStandardInput=true, RedirectStandardOutput=true, RedirectStandardError=true,
                StandardOutputEncoding=Utf8, StandardErrorEncoding=Utf8
            };
            child=Process.Start(start); children[child.Id]=child; child.StandardInput.NewLine="\n";
            // BaseStreams bypass CP936; explicit UTF-8 on the text wrappers too.
            WriteLine(pipe,"OK "+parts[1]); Touch();
            var stderr=Task.Run(()=> { byte[] bytes=new byte[4096]; while(child.StandardError.BaseStream.Read(bytes,0,bytes.Length)>0) {} });
            var incoming=Task.Run(()=>Pump(pipe,child.StandardInput.BaseStream));
            var outgoing=Task.Run(()=>Pump(child.StandardOutput.BaseStream,pipe));
            Task.WaitAny(incoming,outgoing);
        } catch { /* Never log user JSON or exception text. EOF informs the client; no replay. */ }
        finally {
            pipe.Dispose(); byte removed; clients.TryRemove(pipe,out removed);
            if(child!=null) { try { child.StandardInput.Close(); if(!child.WaitForExit(1000)) child.Kill(); } catch {} Process removedChild; children.TryRemove(child.Id,out removedChild); child.Dispose(); }
            Interlocked.Decrement(ref active); Touch();
        }
    }
    public static void Run(string name, string root, string exe, string sid, int session, int idleSeconds) {
        if(WindowsIdentity.GetCurrent().User.Value!=sid || Process.GetCurrentProcess().SessionId!=session || Integrity()!=8192 || !DesktopAvailable(session)) throw new IOException("Bridge requires a Limited unlocked console session");
        Touch(); NamedPipeServerStream listener=Create(name,sid,true);
        try {
            while(true) {
                var waiting=listener.WaitForConnectionAsync();
                while(!waiting.Wait(250)) {
                    if(!DesktopAvailable(session) || (Age(Interlocked.Read(ref lastActivity))>idleSeconds*1000L)) return;
                }
                waiting.GetAwaiter().GetResult();
                var connected=listener;
                while (Volatile.Read(ref active)>=6) { Thread.Sleep(100); if(!DesktopAvailable(session) || Age(Interlocked.Read(ref lastActivity))>idleSeconds*1000L) { connected.Dispose(); return; } }
                // Keep at least one instance alive throughout, preventing name takeover.
                listener=Create(name,sid,false);
                clients[connected]=0; Interlocked.Increment(ref active);
                Task.Run(()=>Serve(connected,root,exe,session));
            }
        } finally {
            listener.Dispose();
            foreach(var client in clients.Keys) { try { client.Dispose(); } catch {} }
            // Kill only the MCP children this bridge created, never the shared daemon.
            foreach(var child in children.Values) { try { if(!child.HasExited) child.Kill(); } catch {} }
        }
    }
    public static NamedPipeClientStream Connect(string name, string root) {
        string id=Guid.NewGuid().ToString("N"); byte[] random=new byte[32];
        using(var generator=System.Security.Cryptography.RandomNumberGenerator.Create()) generator.GetBytes(random);
        string token=BitConverter.ToString(random).Replace("-","").ToLowerInvariant();
        string ticket=Ticket(root,id);
        using(var file=new FileStream(ticket,FileMode.CreateNew,FileAccess.Write,FileShare.None)) { byte[] data=Utf8.GetBytes(token); file.Write(data,0,data.Length); }
        var pipe=new NamedPipeClientStream(".",name,PipeDirection.InOut,PipeOptions.Asynchronous,TokenImpersonationLevel.Identification);
        try {
            pipe.Connect(5000); WriteLine(pipe,id+" "+token);
            var acknowledgement=Task.Run(()=>ReadLine(pipe,128));
            if(!acknowledgement.Wait(5000) || acknowledgement.Result!="OK "+token) throw new IOException("Bridge authentication failed");
            return pipe;
        } catch { pipe.Dispose(); throw; }
        finally { if(File.Exists(ticket)) File.Delete(ticket); }
    }
    public static void Stdio(string name,string root) {
        Console.InputEncoding=Utf8; Console.OutputEncoding=Utf8;
        using(var pipe=Connect(name,root)) {
            var incoming=Task.Run(()=> { Pump(Standard(-10,FileAccess.Read),pipe); });
            var outgoing=Task.Run(()=> { Pump(pipe,Standard(-11,FileAccess.Write)); });
            Task.WaitAny(incoming,outgoing);
        }
    }
    static Dictionary<string,object> Rpc(Stream pipe, int id, string method, object parameters) {
        var json=new JavaScriptSerializer { MaxJsonLength=8*1024*1024 };
        WriteLine(pipe,json.Serialize(new {jsonrpc="2.0",id=id,method=method,@params=parameters}));
        for(int i=0;i<32;i++) {
            var read=Task.Run(()=>ReadLine(pipe,8*1024*1024));
            if(!read.Wait(10000)) throw new IOException("MCP timeout");
            var response=json.Deserialize<Dictionary<string,object>>(read.Result);
            if(!response.ContainsKey("id") || Convert.ToInt32(response["id"])!=id) continue;
            if(response.ContainsKey("error")) throw new IOException("MCP error");
            var result=(Dictionary<string,object>)response["result"];
            if(result.ContainsKey("isError") && (bool)result["isError"]) throw new IOException("Clipboard tool refused");
            return result;
        }
        throw new IOException("MCP response missing");
    }
    public static bool Clipboard(string name,string root,string text) {
        using(var pipe=Connect(name,root)) {
            Rpc(pipe,1,"initialize",new { protocolVersion="2025-06-18", capabilities=new {}, clientInfo=new {name="miffan-clip",version="1.0"} });
            WriteLine(pipe,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            Rpc(pipe,2,"tools/call",new {name="clipboard_write",arguments=new {text=text}});
            var result=Rpc(pipe,3,"tools/call",new {name="clipboard_read",arguments=new {include_text=true}});
            // Verification stays local to the helper; no old clipboard read, text/hash output.
            if(result.ContainsKey("structuredContent")) {
                var structured=result["structuredContent"] as Dictionary<string,object>;
                if(structured!=null && structured.ContainsKey("text")) return (string)structured["text"]==text;
            }
            foreach(var item in (System.Collections.ArrayList)result["content"]) {
                var content=(Dictionary<string,object>)item;
                if(content.ContainsKey("text")) {
                    try { var value=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>((string)content["text"]); if(value.ContainsKey("text")) return (string)value["text"]==text; } catch {}
                }
            }
            return false;
        }
    }
}
'@
# P7c: ordinary interactive task + authenticated local pipe. No TCP/file MCP transport.
$script:CuaVersion = '0.34.0'
function Initialize-CuaBridge {
    if ('MiffanCuaBridge' -as [type]) { return }
    Add-Type -TypeDefinition $script:CuaBridgeSource -ReferencedAssemblies 'System.dll','System.Core.dll','System.Web.Extensions.dll'
}
function Test-CuaBridgeAvailable([string]$Name) { Initialize-CuaBridge; return [MiffanCuaBridge]::Available($Name) }
function Get-CuaExecutable {
    $candidates = @((Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'), (Join-Path $env:USERPROFILE '.local\bin\cua-driver.exe'))
    $command = Get-Command cua-driver.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { $candidates += [string]$command.Source }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate } }
    return $null
}
function Test-CuaTaskOwner($Task, [string]$Sid) {
    $owner=[string]$Task.Principal.UserId
    if ($owner -eq $Sid) {return $true}
    try {return (New-Object Security.Principal.NTAccount -ArgumentList $owner).Translate([Security.Principal.SecurityIdentifier]).Value -eq $Sid} catch {return $false}
}
function Get-CuaTask {
    $tasks = @(Get-ScheduledTask -TaskName 'cua-driver-serve' -TaskPath '\' -ErrorAction SilentlyContinue)
    if ($tasks.Count -gt 1) { throw 'Ambiguous cua autostart task' }
    if ($tasks.Count) { return $tasks[0] }
    return $null
}
function Get-CuaBridgeContext {
    $console = Get-ConsoleSession
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $suffix = $sid + '-' + $console.id + '-v' + $script:HelperVersion
    return @{console=$console;sid=$sid;directory=(Join-Path $env:LOCALAPPDATA 'Miffan\cua-bridge');pipe=('Miffan-cua-'+$suffix);task=('Miffan-cua-'+$suffix)}
}
function Assert-CuaDirectoryParents([string]$Path) {
    $parent=Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    while ($parent) {
        if (-not ($parent -is [IO.DirectoryInfo]) -or ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'Reparse bridge parent is not allowed'}
        $parent=$parent.Parent
    }
}
function Assert-CuaConsole($Context) {
    $console=$Context.console
    if (-not $console.present -or $console.sid -ne $Context.sid -or $console.id -eq 0) {throw 'Cua requires the SSH user to match the console user'}
    if ($console.lock_state -ne 'unlocked') {throw 'Cua requires a confirmed unlocked console session'}
}
function Test-CuaPermissionReview($Task, $Context, $Integrity) {
    return (($Task -and ([string]$Task.Principal.RunLevel -ne 'Limited' -or -not (Test-CuaTaskOwner $Task $Context.sid))) -or @($Integrity | Where-Object {$_ -ne 8192}).Count -gt 0)
}
function Get-CuaStatus {
    $exe = Get-CuaExecutable; $version=$null
    if ($exe) {
        # Never invoke a native command via PowerShell's pipeline on a stdio connection:
        # its stdin pump can outlive --version and steal later MCP requests.
        $start=New-Object Diagnostics.ProcessStartInfo
        $start.FileName=$exe; $start.Arguments='--version'; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
        $start.RedirectStandardInput=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
        $start.StandardOutputEncoding=$script:Utf8; $start.StandardErrorEncoding=$script:Utf8
        $process=[Diagnostics.Process]::Start($start)
        try {
            $process.StandardInput.Close()
            $output=$process.StandardOutput.ReadToEndAsync(); $errors=$process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(5000)) {$process.Kill();throw 'Cua version check timed out'}
            if ($process.ExitCode -eq 0 -and $output.Result -match 'cua-driver (\d+\.\d+\.\d+)') {$version=$matches[1]}
        } finally {$process.Dispose()}
    }
    $task=Get-CuaTask; $runLevel=$null
    if ($task) {$runLevel=[string]$task.Principal.RunLevel}
    $daemons=@(Get-CimInstance Win32_Process -Filter "Name='cua-driver.exe'" | Where-Object {$_.CommandLine -match '(^|\s)serve(\s|$)'})
    $sessions=@($daemons | ForEach-Object {[int]$_.SessionId} | Sort-Object -Unique)
    $context=Get-CuaBridgeContext
    Initialize-CuaBridge
    $integrity=@($daemons | Where-Object {$_.SessionId -eq $context.console.id} | ForEach-Object {[MiffanCuaBridge]::ProcessIntegrity([int]$_.ProcessId)})
    $review=Test-CuaPermissionReview $task $context $integrity
    $available=(Test-CuaBridgeAvailable $context.pipe)
    return @{path=$exe;installed=[bool]$exe;version=$version;min=$script:CuaVersion;ok=([bool]$version -and [version]$version -ge [version]$script:CuaVersion -and -not $review);autostart_run_level=$runLevel;daemon_session_ids=$sessions;daemon_integrity_rids=$integrity;bridge_available=$available;permission_review_needed=[bool]$review}
}
function Ensure-CuaBridge {
    $context=Get-CuaBridgeContext
    Assert-CuaConsole $context
    $status=Get-CuaStatus
    if ($status.permission_review_needed) {throw 'Cua permissions are elevated or unknown; user review required'}
    if (-not $status.ok -or $context.console.id -notin $status.daemon_session_ids) {throw 'A supported ordinary cua daemon must be running in the console session'}
    $root=Join-Path $env:LOCALAPPDATA 'Miffan'
    $null=New-Item -ItemType Directory -Path $root -Force
    Assert-CuaDirectoryParents $root
    New-PrivateDirectory $context.directory $context.sid
    Assert-PrivateDirectory $context.directory $context.sid
    Assert-CuaDirectoryParents $context.directory
    if (-not (Test-CuaBridgeAvailable $context.pipe)) {
        $tasks=@(Get-ScheduledTask -TaskName $context.task -TaskPath '\' -ErrorAction SilentlyContinue)
        if ($tasks.Count -gt 1) {throw 'Ambiguous bridge task'}
        $arguments='-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script:HelperFile+'" cua bridge'
        if ($tasks.Count) {
            $task=$tasks[0]
            if ([string]$task.Principal.RunLevel -ne 'Limited' -or [string]$task.Principal.LogonType -ne 'Interactive' -or -not (Test-CuaTaskOwner $task $context.sid) -or $task.Actions.Count -ne 1 -or [string]$task.Actions[0].Arguments -cne $arguments) {throw 'Existing bridge task differs; user review required'}
        } else {
            Remove-StaleCuaBridgeTasks $context
            $action=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments
            $principal=New-ScheduledTaskPrincipal -UserId $context.sid -LogonType Interactive -RunLevel Limited
            $settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
            $null=Register-ScheduledTask -TaskName $context.task -Action $action -Principal $principal -Settings $settings -ErrorAction Stop
        }
        Start-ScheduledTask -TaskName $context.task -ErrorAction Stop
        $deadline=[DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-CuaBridgeAvailable $context.pipe)) {
            if ([DateTime]::UtcNow -ge $deadline) {throw 'Cua bridge did not start'}
            Start-Sleep -Milliseconds 100
        }
    }
    return $context
}
# Each helper version registers its own bridge task; drop this user's tasks left by older versions.
# Only exact Miffan-cua-<sid>-* names owned by this user at Limited level are touched; a running
# old bridge keeps running until its idle exit.
function Remove-StaleCuaBridgeTasks($Context) {
    foreach ($old in @(Get-ScheduledTask -TaskPath '\' -ErrorAction SilentlyContinue | Where-Object {
        $_.TaskName -clike ('Miffan-cua-' + $Context.sid + '-*') -and $_.TaskName -cne $Context.task -and
        [string]$_.Principal.RunLevel -eq 'Limited' -and (Test-CuaTaskOwner $_ $Context.sid) })) {
        Unregister-ScheduledTask -TaskName $old.TaskName -TaskPath '\' -Confirm:$false -ErrorAction SilentlyContinue
    }
}
# Starts the bridge ahead of typing on a screen page, so the first clipboard write is fast too.
# Best effort: an unusable cua-driver or a locked desktop just reports not ready.
function Invoke-CuaWarm {
    if (-not (Get-CuaStatus).ok) {return @{success=$true;ready=$false;detail='cua-driver not usable'}}
    $context=Get-CuaBridgeContext; Assert-CuaConsole $context
    Initialize-CuaBridge
    if (Test-CuaBridgeAvailable $context.pipe) {return @{success=$true;ready=$true;detail='Bridge already running'}}
    $null=Ensure-CuaBridge
    return @{success=$true;ready=$true;detail='Bridge started'}
}
function Invoke-CuaBridge {
    $context=Get-CuaBridgeContext; Assert-CuaConsole $context
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -ne $context.console.id) {throw 'Bridge must run in the console session'}
    Assert-PrivateDirectory $context.directory $context.sid
    Initialize-CuaBridge
    [MiffanCuaBridge]::Run($context.pipe,$context.directory,(Get-CuaExecutable),$context.sid,$context.console.id,120)
}
function Invoke-CuaStdio {
    $context=Ensure-CuaBridge
    [MiffanCuaBridge]::Stdio($context.pipe,$context.directory)
}
function Invoke-CuaClipboard([string]$Text) {
    if ($Text.Contains([char]0)) {throw 'Clipboard text cannot contain NUL'}
    $context=Get-CuaBridgeContext; Assert-CuaConsole $context
    Initialize-CuaBridge
    # Typing on the screen page writes the clipboard often; start the bridge on demand so each write
    # does not pay for a one-shot task. Nothing has been written yet, so falling back here is safe.
    if (-not (Test-CuaBridgeAvailable $context.pipe)) {
        $started=$false
        if ((Get-CuaStatus).ok) { try { $context=Ensure-CuaBridge; $started=$true } catch { } }
        if (-not $started) {return Invoke-DesktopJob 'clip' $Text}
    }
    Assert-PrivateDirectory $context.directory $context.sid
    # Once clipboard_write could have reached the daemon, never replay via the fallback.
    if (-not [MiffanCuaBridge]::Clipboard($context.pipe,$context.directory,$Text)) {throw 'Clipboard readback did not match'}
    return @{success=$true;verified=$true;session_id=$context.console.id;transport='cua-bridge';detail='Clipboard written and read back'}
}
function Invoke-CuaInstall {
    $existing=Get-CuaTask
    if ($existing -and ([string]$existing.Principal.RunLevel -ne 'Limited' -or [string]$existing.Principal.LogonType -ne 'Interactive')) {throw 'Cua autostart uses elevated permissions; user review required'}
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($existing -and -not (Test-CuaTaskOwner $existing $sid)) {throw 'Cua autostart belongs to another user'}
    $directory=Join-Path $env:LOCALAPPDATA ('Miffan\cua-install-'+[Guid]::NewGuid().ToString('N'))
    Assert-CuaDirectoryParents (Join-Path $env:LOCALAPPDATA 'Miffan')
    New-PrivateDirectory $directory $sid
    try {
        $installer=Join-Path $directory 'install.ps1'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://cua.ai/driver/install.ps1' -OutFile $installer
        # Official NoAutoStart alone re-registers an existing task as Highest! Explicit default
        # per-user paths make the installer skip ALL shared autostart changes (isolated mode).
        $installCode=@'
$env:CUA_DRIVER_RS_VERSION='0.34.0'
$env:CUA_DRIVER_RS_HOME=Join-Path $env:USERPROFILE '.cua-driver'
$env:CUA_DRIVER_RS_INSTALL_DIR=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin'
& $args[0] -Release 0.34.0 -NoAutoStart
'@
        $bootstrap=Join-Path $directory 'install-user.ps1'
        [IO.File]::WriteAllText($bootstrap,$installCode,(New-Object Text.UTF8Encoding($true)))
        & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $installer *> $null
        if ($LASTEXITCODE -ne 0) {throw 'Cua installer failed; existing autostart was not changed'}
        $status=Get-CuaStatus
        if ($status.version -ne $script:CuaVersion -or $status.permission_review_needed) {throw 'Cua installation could not be verified'}
        $arguments='-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script:HelperFile+'" cua daemon'
        $action=New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments
        $principal=New-ScheduledTaskPrincipal -UserId $sid -LogonType Interactive -RunLevel Limited
        $trigger=New-ScheduledTaskTrigger -AtLogOn -User $sid
        $settings=New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        # Only after the user's existing install/upgrade confirmation; never used by status/open.
        $null=Register-ScheduledTask -TaskName 'cua-driver-serve' -Action $action -Principal $principal -Trigger $trigger -Settings $settings -Force -ErrorAction Stop
        Start-ScheduledTask -TaskName 'cua-driver-serve'
        return @{success=$true;version=$script:CuaVersion;autostart_run_level='Limited';detail='Cua installed for this user with ordinary interactive autostart'}
    } finally {Remove-Item -LiteralPath $directory -Recurse -Force}
}

function Invoke-Helper([string]$Verb, [string]$Subcommand, [string]$Suboperation) {
    switch ($Verb) {
        'version' { if ($Subcommand -or $Suboperation) {throw 'Usage: version'}; return $script:HelperVersion }
        'probe' {
            if (-not $Subcommand -and -not $Suboperation) { return Get-MachineProbe }
            if ($Subcommand -eq 'display' -and -not $Suboperation) { return Invoke-DesktopJob 'display' '' }
            throw 'Usage: probe [display]'
        }
        'sunshine' {
            if ($Subcommand -ne 'firewall' -and $Suboperation) {throw 'Unexpected Sunshine argument'}
            switch ($Subcommand) {
                { $_ -in @('', 'probe') } { return Get-SunshineProbe }
                'start' { return Invoke-SunshineStart }
                'enforce-encryption' { return Invoke-EnforceEncryption }
                'firewall' {
                    switch ($Suboperation) {
                        'check' { $c=Get-SunshineContext; if (-not $c.installed) {throw 'Sunshine is not installed'}; return Get-FirewallReview (Get-FirewallSnapshot $c.executable) $c.executable (Get-FirewallSources) }
                        'apply' { return Invoke-FirewallApply (Read-FirewallRequest ([Console]::In.ReadToEnd())) }
                        default { throw 'Usage: sunshine firewall check|apply' }
                    }
                }
                default { throw 'Usage: sunshine probe|start|enforce-encryption|firewall' }
            }
        }
        'clip' { if ($Subcommand -or $Suboperation) {throw 'Clipboard text must come from stdin'}; return Invoke-CuaClipboard ([Console]::In.ReadToEnd()) }
        'cua' { if ($Subcommand -eq 'status' -and -not $Suboperation) {return Get-CuaStatus}; if ($Subcommand -eq 'install' -and -not $Suboperation) {return Invoke-CuaInstall}; if ($Subcommand -eq 'warm' -and -not $Suboperation) {return Invoke-CuaWarm}; throw 'Usage: cua [status|install|warm|bridge]' }
        default { throw 'Usage: version|probe [display]|sunshine|clip|cua' }
    }
}
$script:SafeFailures = @(
    'Cua autostart uses elevated permissions; user review required',
    'Cua permissions are elevated or unknown; user review required',
    'Cua installer failed; existing autostart was not changed',
    'Cua autostart belongs to another user',
    'Cua requires the SSH user to match the console user',
    'Cua requires a confirmed unlocked console session',
    'Cua version check timed out',
    'Cua installation could not be verified',
    'A supported ordinary cua daemon must be running in the console session',
    'Existing bridge task differs; user review required',
    'Cua bridge did not start',
    'Reparse bridge parent is not allowed',
    'Active or unknown stream; refusing encryption change',
    'ActiveStore rule or filters disappeared during inspection',
    'Ambiguous ActiveStore filter identity',
    'Ambiguous desktop task identity',
    'An explicit source subnet is required',
    'At least one source subnet is required',
    'Cannot verify Sunshine configuration',
    'Cannot verify Sunshine installation',
    'Cannot verify the Sunshine service model',
    'Clipboard readback did not match',
    'Clipboard requires a confirmed unlocked console session',
    'Clipboard text cannot contain NUL',
    'Clipboard text must come from stdin',
    'Clipboard verification missing',
    'Console session changed',
    'Console session changed before task start',
    'Desktop job requires the logged-in console user to match the SSH user',
    'Desktop task cleanup failed; inspect the reported task and directory',
    'Desktop task could not be stopped',
    'Desktop task could not be unregistered',
    'Desktop task failed or returned another session',
    'Desktop task timed out',
    'Empty firewall plan; no writes were performed',
    'Encryption saved; stream state prevents restart',
    'Expected disable_rules, allowed_sources and add_rule',
    'Firewall changed after review; no rules were changed',
    'Firewall plan differs from the caller-approved plan',
    'Firewall profiles do not enforce inbound blocking',
    'Firewall request fields must be arrays',
    'Firewall request values must be strings',
    'Invalid public certificate PEM',
    'Invalid subnet address',
    'Invalid subnet mask',
    'Local rule changed before backup',
    'Managed rule name belongs to another program or policy',
    'NUL cannot be represented in a text clipboard',
    'Non-contiguous subnet mask',
    'Only local inbound rules for this sunshine.exe can be disabled',
    'Only the fixed Sunshine UDP rule may be added',
    'Overly broad unspecified source',
    'Plan leaves an unsafe effective rule; check and review again',
    'Private directory must not be a reparse point',
    'Private directory must not inherit permissions',
    'Regular file required',
    'Reparse backup parent is not allowed',
    'Reparse directory is not allowed',
    'Reparse job parent is not allowed',
    'State changed; refusing configuration replacement',
    'Sunshine configuration changed before backup',
    'Sunshine configuration changed during backup',
    'Sunshine is not installed',
    'Unexpected Sunshine argument',
    'Unexpected add_rule fields',
    'Unexpected private directory ACL',
    'Unknown desktop operation',
    'Unknown desktop worker mode',
    'Unsafe subnet prefix',
    'Usage: probe [display]',
    'Usage: sunshine firewall check|apply',
    'Usage: sunshine probe|start|enforce-encryption|firewall',
    'Usage: version',
    'Usage: version|probe [display]|sunshine|clip',
    'Wrong desktop session'
)
if ($MyInvocation.InvocationName -ne '.') {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    $WarningPreference = 'SilentlyContinue'
    # Preserve a UTF-8 reader installed by the caller (including buffered stdin).
    if ([Console]::InputEncoding.CodePage -ne 65001) { [Console]::InputEncoding = $script:Utf8 }
    [Console]::OutputEncoding = $script:Utf8
    $OutputEncoding = $script:Utf8
    $script:LastBackup = $null; $script:LastDesktopTask=$null; $script:LastDesktopDirectory=$null
    try {
        if ($Command -eq 'cua' -and -not $Operation -and $Action -in @('', 'bridge','daemon')) {
            if ($Action -eq 'bridge') {Invoke-CuaBridge}
            elseif ($Action -eq 'daemon') {
                Initialize-CuaBridge
                if ([MiffanCuaBridge]::Integrity() -ne 8192 -or [Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) {throw 'Cua daemon task must use ordinary interactive permissions'}
                $start=New-Object Diagnostics.ProcessStartInfo
                $start.FileName=Get-CuaExecutable; $start.Arguments='serve'; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
                $process=[Diagnostics.Process]::Start($start); $process.WaitForExit(); exit $process.ExitCode
            } else {Invoke-CuaStdio}
            exit 0
        }
        $result = Invoke-Helper $Command $Action $Operation
        [Console]::Out.WriteLine((ConvertTo-PlainJson $result))
        if ($result -is [Collections.IDictionary] -and $result.Contains('success') -and -not $result.success) { exit 1 }
        exit 0
    } catch {
        # Never print exception messages: they could contain stdin text or confidential config.
        if ($Command -eq 'cua' -and $Action -in @('', 'bridge','daemon')) {
            [Console]::Error.WriteLine('Cua bridge unavailable; check cua status and the unlocked console session')
            exit 1
        }
        [Console]::Out.WriteLine((ConvertTo-PlainJson @{success=$false;detail=$(if ($_.Exception.Message -cin $script:SafeFailures) {$_.Exception.Message} else {'Helper command failed'});error_type=$_.Exception.GetType().Name;backup=$script:LastBackup;cleanup_task=$script:LastDesktopTask;cleanup_directory=$script:LastDesktopDirectory}))
        exit 1
    }
}
