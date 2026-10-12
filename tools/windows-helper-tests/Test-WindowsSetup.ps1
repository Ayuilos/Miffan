param([Parameter(Mandatory=$true)][string]$TemplatePath)
$ErrorActionPreference = 'Stop'
$originalProgramData = $env:ProgramData
$originalProfile = $env:USERPROFILE
$root = Join-Path $env:LOCALAPPDATA ('Miffan\p7d-tests\run-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
function Assert($condition, $message) { if (-not $condition) { throw $message } }
$source = [IO.File]::ReadAllText($TemplatePath)
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
Assert ($errors.Count -eq 0) 'Template parse failed'
# Replace only the OS identity query; all authorization/routing logic remains real.
$identityAst = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-MiffanIdentity'}, $true)
Assert ($null -ne $identityAst) 'Missing identity seam'
$source = $source.Replace($identityAst.Extent.Text, 'function Get-MiffanIdentity { return $script:Identity }')
# Native keygen is read-only; replace it so the harness needs no real host keys.
$native = '$fingerprint = & $keygen -lf $hostKey -E sha256 2>$null'
Assert ($source.Contains($native)) 'Missing keygen seam'
$source = $source.Replace($native, '$fingerprint = "256 SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA fixture (ED25519)"; $LASTEXITCODE = 0')
$source = $source.Replace('__MIFFAN_PUBLIC_KEY__', 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest')
$block = [scriptblock]::Create($source)
function Write-Host { param([Parameter(ValueFromRemainingArguments=$true)]$Object) $script:Messages += ($Object -join ' ') }
function Get-Service { param($Name, $ErrorAction) if ($script:Installed) { @{Name='sshd'} } }
function Add-WindowsCapability { param([switch]$Online, $Name) $script:Calls += 'install'; if ($script:InstallFails) { throw 'mock failure' }; $script:Installed=$true }
function Set-Service { param($Name, $StartupType) Assert ($StartupType -eq 'Automatic') 'Startup'; $script:Calls += 'automatic' }
function Start-Service { param($Name) $script:Calls += 'start' }
function Get-CimInstance { param($ClassName, $Filter) @{PathName='"C:\Program Files\OpenSSH\sshd.exe"'} }
function Get-NetIPConfiguration { $script:Networks }
function Get-NetFirewallRule { param($Name, $PolicyStore, $ErrorAction) Assert ($Name -eq 'Miffan-SSH') 'Touched foreign rule'; if ($script:RuleExists) { @{Name=$Name} } }
function New-NetFirewallRule { param($Name,$Direction,$Action,$Enabled,$Profile,$Program,$Protocol,$LocalPort,$RemoteAddress,$PolicyStore,$DisplayName)
    if ($script:FirewallFails) { throw 'mock firewall failure' }
    $script:Calls += 'new-rule'; $script:RuleExists=$true; $script:Rule=$PSBoundParameters
}
function Set-NetFirewallRule { param($Name,$Direction,$Action,$Enabled,$Profile,$Program,$Protocol,$LocalPort,$RemoteAddress,$PolicyStore)
    $script:Calls += 'set-rule'; $script:Rule=$PSBoundParameters
}
function Set-Acl { param($LiteralPath,$AclObject)
    Assert ($LiteralPath.StartsWith($root + '\')) 'ACL escaped temporary directory'
    $script:Acl=$AclObject; $script:AclPath=$LiteralPath; $script:Calls += 'acl'
}
function Reset-Case($name) {
    $dir = Join-Path $root $name
    $env:ProgramData=Join-Path $dir 'data'; $env:USERPROFILE=Join-Path $dir 'user'
    $null=[IO.Directory]::CreateDirectory((Join-Path $env:ProgramData 'ssh'))
    foreach ($type in @('ed25519','ecdsa','rsa')) {
        $algorithm=if ($type -eq 'ecdsa') { 'ecdsa-sha2-nistp256' } else { 'ssh-' + $type }
        [IO.File]::WriteAllText((Join-Path $env:ProgramData ('ssh\ssh_host_' + $type + '_key.pub')), $algorithm + ' AAAA fixture')
    }
    $script:ConfigPath=Join-Path $env:ProgramData 'ssh\sshd_config'
    [IO.File]::WriteAllText($script:ConfigPath, "# Port 55`r`nMatch Group administrators`r`n AuthorizedKeysFile __PROGRAMDATA__/ssh/administrators_authorized_keys`r`n")
    $script:Identity=@{Elevated=$true;Administrator=$true;Sid='S-1-5-21-1-2-3-1001';Name="$env:COMPUTERNAME\ekk"}
    $script:FirewallFails=$false
    $script:Installed=$true; $script:InstallFails=$false; $script:RuleExists=$false
    $script:Calls=@(); $script:Messages=@(); $script:Acl=$null; $script:Rule=$null
    $script:Networks=@(
        @{NetAdapter=@{Status='Up'};InterfaceAlias='Wi-Fi';InterfaceDescription='Wi-Fi';IPv4DefaultGateway='192.168.31.1';IPv4Address=@(@{IPAddress='192.168.31.61';PrefixLength=23})},
        @{NetAdapter=@{Status='Up'};InterfaceAlias='Tailscale';InterfaceDescription='Tailscale';IPv4Address=@(@{IPAddress='100.64.0.8';PrefixLength=32})},
        @{NetAdapter=@{Status='Down'};InterfaceAlias='Offline';IPv4DefaultGateway='10.0.0.1';IPv4Address=@(@{IPAddress='10.0.0.2';PrefixLength=8})}
    )
}
function Ready { Assert ($script:Messages -contains 'Miffan: this computer is ready.') ($script:Messages -join '; ') }
try {
    Reset-Case 'first'
    $script:Installed=$false
    & $block; Ready
    Assert (($script:Calls -join ',') -eq 'install,automatic,start,acl,new-rule') 'First run sequence'
    Assert (($script:Rule.RemoteAddress -join ',') -eq '100.64.0.0/10,192.168.30.0/23') 'Prefix or Tailscale'
    Assert ($script:Rule.Profile -eq 'Any' -and $script:Rule.Protocol -eq 'TCP' -and $script:Rule.LocalPort -eq 22) 'Firewall parameters'
    Assert ($script:Rule.Program -eq 'C:\Program Files\OpenSSH\sshd.exe') 'MSI program path'
    Assert ($script:Acl.AreAccessRulesProtected) 'Inherited permissions retained'
    $sids=@($script:Acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | ForEach-Object {$_.IdentityReference.Value} | Sort-Object)
    Assert (($sids -join ',') -eq 'S-1-5-18,S-1-5-32-544') 'Admin ACL'
    Assert (@($script:Messages | Where-Object { $_ -match '^  Fingerprint: SHA256:[A-Za-z0-9+/]{43}   \(' }).Count -eq 3) 'Missing negotiated-key alternatives'
    $keyPath=$script:AclPath
    $first=[IO.File]::ReadAllText($keyPath)
    $script:Calls=@(); & $block; Ready
    Assert ([IO.File]::ReadAllText($keyPath) -ceq $first) 'Duplicate key appended'
    Assert (($script:Calls -join ',') -eq 'automatic,start,acl,set-rule') 'Rule not updated'
    foreach ($encoding in @([Text.Encoding]::UTF8,[Text.Encoding]::Unicode,[Text.Encoding]::BigEndianUnicode)) {
        [IO.File]::WriteAllText($keyPath, 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest old-comment', $encoding)
        & $block; Ready
        $bytes=[IO.File]::ReadAllBytes($keyPath)
        Assert ($bytes[0] -eq 115 -and $bytes[1] -eq 115 -and $bytes[-1] -eq 10 -and $bytes -notcontains 0) 'Encoding or final newline'
        Assert (([IO.File]::ReadAllText($keyPath) -split 'ssh-ed25519').Count -eq 2) 'Comment identity comparison'
    }
    [IO.File]::WriteAllText($keyPath, 'command="echo hello",no-pty ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest restricted')
    & $block; Ready
    Assert (([IO.File]::ReadAllText($keyPath) -split 'ssh-ed25519').Count -eq 2) 'Restricted key duplicated without options'
    [IO.File]::WriteAllText($keyPath, '# ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest disabled')
    & $block; Ready
    Assert (([IO.File]::ReadAllText($keyPath) -split 'ssh-ed25519').Count -eq 3) 'Commented-out key treated as authorized'
    Reset-Case 'domain'; $script:Identity.Name='CONTOSO\Example'; & $block; Ready
    Assert ($script:Messages -contains '  Username:    contoso\example') 'Domain username'
    Reset-Case 'not-elevated'; $script:Identity.Elevated=$false
    & $block
    Assert ($script:Calls.Count -eq 0) 'Non-admin modified system'
    Assert (($script:Messages -join '') -match 'Run as administrator') 'Missing elevation instruction'
    Reset-Case 'ordinary'; $script:Identity.Administrator=$false
    & $block; Ready
    Assert ($script:AclPath -eq (Join-Path $env:USERPROFILE '.ssh\authorized_keys')) 'User key path'
    Assert ($script:Acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]).Count -eq 3) 'User ACL'
    Reset-Case 'no-match'; [IO.File]::WriteAllText($script:ConfigPath, "Port 2222`n")
    $script:Networks=@($script:Networks[0]); & $block; Ready
    Assert ($script:Rule.LocalPort -eq 2222 -and $script:Rule.RemoteAddress.Count -eq 1) 'Custom port / no Tailscale'
    Assert ($script:AclPath -eq (Join-Path $env:USERPROFILE '.ssh\authorized_keys')) 'No admin Match routing'
    Reset-Case 'install-failed'; $script:Installed=$false; $script:InstallFails=$true
    & $block
    Assert (($script:Calls -join ',') -eq 'install') 'Continued after install failure'
    Assert ($script:Messages -contains 'https://github.com/PowerShell/Win32-OpenSSH/releases') 'No MSI fallback'
    Reset-Case 'firewall-failed'; $script:FirewallFails=$true; & $block
    Assert (Test-Path -LiteralPath $script:AclPath) 'Completed authorization lost'
    Assert ($script:Messages -contains '  Reason: mock firewall failure') 'System error not printed'
    Assert ($script:Messages -notcontains 'Miffan: this computer is ready.') 'False ready after failure'
    Reset-Case 'custom-host-key'
    $custom=Join-Path $env:ProgramData 'ssh\ssh_host_ecdsa_key'
    [IO.File]::WriteAllText($script:ConfigPath, ('HostKey "' + $custom + '"'))
    & $block; Ready
    Assert (@($script:Messages | Where-Object {$_ -match '^  Fingerprint:'}).Count -eq 1) 'Custom host key ignored'
    Assert (($script:Messages -join ';') -match 'ecdsa-sha2-nistp256') 'ECDSA algorithm label'
    Reset-Case 'empty-network'; $script:Networks=@(); & $block
    Assert ($script:Calls -notcontains 'acl' -and $null -eq $script:Rule) 'Empty source rule allowed'
    Reset-Case 'unsafe-prefix'; $script:Networks[0].IPv4Address[0].PrefixLength=8; & $block
    Assert ($null -eq $script:Rule) 'Public source range allowed'
    Assert ($script:Messages -contains '  Reason: LAN prefix extends beyond private addresses') 'Missing actual error reason'
    Reset-Case 'include'; [IO.File]::WriteAllText($script:ConfigPath, 'Include other.conf'); & $block
    Assert ($script:Calls -notcontains 'acl') 'Unresolved config authorized key'
    [Console]::Out.WriteLine('PASS: PS parser; install; repeat; BOM/UTF-16; elevation; user/admin ACL; port; networks; fallback; fail-closed config')
} finally {
    $env:ProgramData=$originalProgramData; $env:USERPROFILE=$originalProfile
    Remove-Item -LiteralPath $root -Recurse -Force
}
