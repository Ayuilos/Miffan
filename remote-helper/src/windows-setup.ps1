& {
    $ErrorActionPreference = 'Stop'
    $step = 'administrator check'
    $publicKey = '__MIFFAN_PUBLIC_KEY__'
    function Get-MiffanIdentity {
        $id = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($id)
        return @{ Elevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
            Administrator = (@($id.Groups | ForEach-Object { $_.Value }) -contains 'S-1-5-32-544')
            Sid = $id.User.Value; Name = $id.Name }
    }
    function Set-KeyPermissions($path, $owner, $sids) {
        # A fresh DACL removes inherited AND old explicit entries. SIDs work in every locale.
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true, $false)
        $acl.SetOwner((New-Object Security.Principal.SecurityIdentifier($owner)))
        foreach ($sid in $sids) {
            $rule = New-Object Security.AccessControl.FileSystemAccessRule(
                (New-Object Security.Principal.SecurityIdentifier($sid)), 'FullControl', 'Allow')
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $path -AclObject $acl
    }
    try {
        $identity = Get-MiffanIdentity
        if (-not $identity.Elevated) {
            Write-Host 'Please right-click PowerShell and select "Run as administrator", then run this script again.'
            return
        }
        $step = 'OpenSSH installation'
        if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) {
            Write-Host 'Installing OpenSSH Server. This may take a few minutes.'
            try {
                Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null
                if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) { throw 'Service unavailable' }
            } catch {
                Write-Host 'OpenSSH installation failed. Install the official Win32-OpenSSH MSI, then run this script again:'
                Write-Host ('  Reason: ' + $_.Exception.Message)
                Write-Host 'https://github.com/PowerShell/Win32-OpenSSH/releases'
                return
            }
        }
        $step = 'OpenSSH service startup'
        Set-Service sshd -StartupType Automatic
        Start-Service sshd
        $service = Get-CimInstance Win32_Service -Filter "Name='sshd'"
        if ($service.PathName -notmatch '^\s*"?(.+?sshd\.exe)"?\s*$') {
            throw 'Custom sshd service arguments are not supported'
        }
        $sshd = $Matches[1]
        $step = 'OpenSSH configuration check'
        $config = [IO.File]::ReadAllText((Join-Path $env:ProgramData 'ssh\sshd_config'))
        $ports = @(); $hostKeys = @(); $adminMatch = $false; $inAdmin = $false; $inMatch = $false
        foreach ($line in ($config -split '\r?\n')) {
            $line = ($line -replace '#.*$', '').Trim()
            if (-not $line) { continue }
            if ($line -match '^Match\s+(.+)$') {
                $inMatch = $true
                $inAdmin = $Matches[1] -match '^Group\s+administrators$'
                if (-not $inAdmin) { throw 'Custom Match blocks need manual setup' }
                continue
            }
            if (-not $inMatch -and $line -match '^HostKey\s+(.+)$') {
                $hostKeys += $Matches[1].Trim('"').Replace('__PROGRAMDATA__', $env:ProgramData)
            }
            if ($line -match '^HostCertificate\s') { throw 'Host certificates need manual fingerprint verification' }
            if ($line -match '^Include\s') { throw 'Included configuration needs manual setup' }
            if ($line -match '^Port\s') {
                if ($inMatch -or $line -notmatch '^Port\s+(\d+)$') { throw 'Invalid port configuration' }
                $ports += [int]$Matches[1]
            }
            if ($line -match '^AuthorizedKeysFile\s+(.+)$') {
                $value = $Matches[1].Trim('"')
                if ($inAdmin -and $value -eq '__PROGRAMDATA__/ssh/administrators_authorized_keys') { $adminMatch = $true }
                elseif ($inMatch -or $value -ne '.ssh/authorized_keys') { throw 'Custom key paths need manual setup' }
            }
            if ($line -match '^PubkeyAuthentication\s+no$') { throw 'Public key authentication is disabled' }
        }
        if ($ports.Count -eq 0) { $ports = @(22) }
        if ($ports.Count -ne 1 -or $ports[0] -lt 1 -or $ports[0] -gt 65535) { throw 'A single valid SSH port is required' }
        $port = $ports[0]
        $step = 'network discovery'
        $sources = @(); $addresses = @()
        foreach ($interface in @(Get-NetIPConfiguration)) {
            if (-not $interface.NetAdapter -or $interface.NetAdapter.Status -ne 'Up') { continue }
            $tailscale = ($interface.InterfaceAlias -match 'Tailscale' -or $interface.InterfaceDescription -match 'Tailscale')
            foreach ($address in @($interface.IPv4Address)) {
                $ip = [string]$address.IPAddress
                if ($tailscale -and $ip -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.') {
                    $sources += '100.64.0.0/10'
                } elseif ($interface.IPv4DefaultGateway -and ($ip -match '^10\.' -or $ip -match '^192\.168\.' -or $ip -match '^172\.(1[6-9]|2\d|3[01])\.')) {
                    $prefix = [int]$address.PrefixLength
                    if ($prefix -lt 1 -or $prefix -ge 32) { continue }
                    # Do not let an unusually broad prefix extend beyond RFC1918.
                    $minimum = if ($ip -match '^10\.') { 8 } elseif ($ip -match '^172\.') { 12 } else { 16 }
                    if ($prefix -lt $minimum) { throw 'LAN prefix extends beyond private addresses' }
                    $bytes = ([Net.IPAddress]::Parse($ip)).GetAddressBytes()
                    for ($i = 0; $i -lt 4; $i++) {
                        $bits = [Math]::Max(0, [Math]::Min(8, $prefix - 8 * $i))
                        $bytes[$i] = $bytes[$i] -band (256 - [int][Math]::Pow(2, 8 - $bits))
                    }
                    $sources += ([Net.IPAddress]::new($bytes)).ToString() + '/' + $prefix
                } else { continue }
                $addresses += ('  Address:     {0}   ({1})' -f $ip, $interface.InterfaceAlias)
            }
        }
        $sources = @($sources | Sort-Object -Unique)
        if ($sources.Count -eq 0) { throw 'No connected private LAN or Tailscale network found' }
        $step = 'public key authorization'
        $sids = @('S-1-5-32-544', 'S-1-5-18')
        $owner = 'S-1-5-32-544'
        if ($identity.Administrator -and $adminMatch) {
            $keyPath = Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
        } else {
            $keyPath = Join-Path $env:USERPROFILE '.ssh\authorized_keys'
            $owner = $identity.Sid; $sids += $owner
        }
        $directory = Split-Path $keyPath
        # Refuse links before performing elevated file writes.
        foreach ($path in @($directory, $keyPath)) {
            if ((Test-Path -LiteralPath $path) -and ((Get-Item -Force -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'Linked key paths need manual setup'
            }
        }
        [IO.Directory]::CreateDirectory($directory) | Out-Null
        $text = if (Test-Path -LiteralPath $keyPath) { [IO.File]::ReadAllText($keyPath, (New-Object Text.UTF8Encoding($false, $true))) } else { '' }
        # ReadAllText detects UTF-8/UTF-16 BOMs. Never retain their BOM on write.
        if ($text.Contains([char]0)) { throw 'Unrecognized key file encoding' }
        $found = $false
        foreach ($line in ($text -split '\r?\n')) {
            if ($line.TrimStart().StartsWith('#')) { continue }
            if ($line.Trim() -match '^(?:(?:[^\s"]|"(?:[^"\\]|\\.)*")+[ \t]+)?(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521))\s+([A-Za-z0-9+/]+={0,2})(?:\s|$)') {
                if (($Matches[1] + ' ' + $Matches[2]) -ceq $publicKey) { $found = $true }
            }
        }
        if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $text += "`r`n" }
        if (-not $found) { $text += $publicKey + "`r`n" }
        [IO.File]::WriteAllText($keyPath, $text, (New-Object Text.UTF8Encoding($false)))
        Set-KeyPermissions $keyPath $owner $sids
        $step = 'SSH firewall rule'
        $rule = @{ Name = 'Miffan-SSH'; Direction = 'Inbound'; Action = 'Allow'; Enabled = 'True'
            Profile = 'Any'; Program = $sshd; Protocol = 'TCP'; LocalPort = $port; RemoteAddress = $sources
            PolicyStore = 'PersistentStore' }
        if (Get-NetFirewallRule -Name 'Miffan-SSH' -PolicyStore PersistentStore -ErrorAction SilentlyContinue) {
            Set-NetFirewallRule @rule | Out-Null
        } else {
            New-NetFirewallRule @rule -DisplayName 'Miffan SSH' | Out-Null
        }
        $step = 'host key fingerprint'
        if ($hostKeys.Count -eq 0) {
            $hostKeys = @('ed25519', 'ecdsa', 'rsa') | ForEach-Object { Join-Path $env:ProgramData ('ssh\ssh_host_' + $_ + '_key') }
        }
        $keygen = Join-Path (Split-Path $sshd) 'ssh-keygen.exe'
        $fingerprints = @()
        foreach ($hostKeyBase in $hostKeys) {
            $hostKey = $hostKeyBase + '.pub'
            if (-not (Test-Path -LiteralPath $hostKey)) { continue }
            $algorithm = ([IO.File]::ReadAllText($hostKey).Trim() -split '\s+')[0]
            $fingerprint = & $keygen -lf $hostKey -E sha256 2>$null
            if ($LASTEXITCODE -ne 0 -or "$fingerprint" -notmatch '(SHA256:[A-Za-z0-9+/]{43})\s') { throw 'Cannot read host fingerprint' }
            $fingerprints += ('  Fingerprint: ' + $Matches[1] + '   (' + $algorithm + ')')
        }
        if ($fingerprints.Count -eq 0) { throw 'No public host key files found' }
        $username = $identity.Name.ToLowerInvariant()
        if ($username.StartsWith($env:COMPUTERNAME.ToLowerInvariant() + '\')) { $username = $username.Split('\')[-1] }
        Write-Host 'Miffan: this computer is ready.'
        Write-Host ('  Name:        ' + $env:COMPUTERNAME)
        Write-Host ('  Username:    ' + $username)
        $addresses | ForEach-Object { Write-Host $_ }
        Write-Host ('  Port:        ' + $port)
        $fingerprints | ForEach-Object { Write-Host $_ }
        Write-Host 'Compare the fingerprint for the key type shown in Miffan.'
    } catch {
        Write-Host ('Miffan: failed during ' + $step + '. Completed steps have been kept.')
        Write-Host ('  Reason: ' + $_.Exception.Message)
        Write-Host 'Fix the reason above and run the script again; steps already done are kept.'
    }
}
