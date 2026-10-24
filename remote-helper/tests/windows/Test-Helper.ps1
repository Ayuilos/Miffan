# Pure/offline tests. Never calls live firewall/service/task/clipboard mutation cmdlets.
param(
    [string]$HelperPath = (Join-Path $PSScriptRoot '..\..\..\app\build\generated\remoteHelper\remote\miffan.ps1'),
    [string]$FixturePath = (Join-Path $PSScriptRoot '..\..\fixtures\ps1')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
. $HelperPath
$script:Passed = 0
function Assert-True($Condition, [string]$Message) { if (-not $Condition) { throw "Assertion failed: $Message" } }
function Assert-Equal($Actual, $Expected, [string]$Message) { if ($Actual -cne $Expected) { throw "Assertion failed: $Message; expected [$Expected], actual [$Actual]" } }
function Assert-Throws([scriptblock]$Body, [string]$Message) { $caught=$false; try { & $Body } catch { $caught=$true }; Assert-True $caught $Message }
function Test([string]$Name, [scriptblock]$Body) { & $Body; $script:Passed++; [Console]::Out.WriteLine("PASS $Name") }
# JSON property order is irrelevant; field names, array shapes and values are shared with Kotlin.
function Assert-JsonValue($Actual, $Expected, [string]$Path) {
    if ($null -eq $Expected) { Assert-True ($null -eq $Actual) "$Path null"; return }
    if ($Expected -is [Management.Automation.PSCustomObject]) {
        Assert-True ($Actual -is [Management.Automation.PSCustomObject]) "$Path object"
        $keys=@($Expected.PSObject.Properties.Name | Sort-Object)
        Assert-Equal (@($Actual.PSObject.Properties.Name | Sort-Object) -join '|') ($keys -join '|') "$Path keys"
        foreach ($key in $keys) { Assert-JsonValue $Actual.$key $Expected.$key "$Path.$key" }
    } elseif ($Expected -is [Array]) {
        Assert-True ($Actual -is [Array]) "$Path array"
        Assert-Equal $Actual.Count $Expected.Count "$Path length"
        for ($i=0;$i -lt $Expected.Count;$i++) { Assert-JsonValue $Actual[$i] $Expected[$i] "$Path[$i]" }
    } else { Assert-Equal $Actual $Expected $Path }
}
function Assert-Fixture([string]$Name, $Actual) {
    $path=Join-Path $FixturePath ($Name+'.json')
    $expected=ConvertFrom-Json ([IO.File]::ReadAllText($path))
    $decoded=ConvertFrom-Json (ConvertTo-PlainJson $Actual)
    Assert-JsonValue $decoded $expected $Name
}

$exe = 'C:\Program Files\Sunshine\sunshine.exe'
function Rule([string]$Id='vendor', [string]$Protocol='UDP', $Ports=@('Any'), $Sources=@('Any'), [string]$Program=$exe, [bool]$Enabled=$true) {
    return @{id=$Id;program=$Program;direction='Inbound';enabled=$Enabled;effective=$Enabled;action='Allow';protocol=$Protocol;ports=$Ports;sources=$Sources;policy_source='Local';profile='Any'}
}
Test 'config preserves unrelated bytes, comments, CRLF, duplicates' {
    $original = "# 中文`r`nfoo = keep # inline`r`n lan_encryption_mode=0 # lan`r`nlan_encryption_mode = 1`r`nwan_encryption_mode = 1`r`n"
    $expected = "# 中文`r`nfoo = keep # inline`r`nlan_encryption_mode = 2 # lan`r`nlan_encryption_mode = 2`r`nwan_encryption_mode = 2`r`n"
    Assert-Equal (Edit-EncryptionConfig $original) $expected 'exact retained bytes'
    Assert-Equal (Edit-EncryptionConfig $expected) $expected 'rewrite idempotent'
    Assert-Equal (Edit-EncryptionConfig "foo=bar") "foo=bar`nlan_encryption_mode = 2`nwan_encryption_mode = 2`n" 'append absent keys'
    $values = Get-ConfigValues $original
    Assert-Equal $values.lan_encryption_mode '1' 'last duplicate wins'
    Assert-Equal (Get-ConfigInteger @{} 'wan_encryption_mode' 1) 1 'default'
    Assert-True ($null -eq (Get-ConfigInteger @{port='bad'} 'port' 47989)) 'invalid integer unknown'
}
Test 'public X509 DER fingerprint, not PEM text' {
    $pem = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $expected = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.sha256')).Trim()
    Assert-Equal (Get-CertificateFingerprint $pem) $expected 'known DER hash'
    Assert-Equal (Get-CertificateFingerprint ($pem.Replace("`n", "`r`n"))) $expected 'PEM whitespace invariant'
    Assert-True ((Get-BytesSha256 $script:Utf8.GetBytes($pem)) -ne $expected) 'PEM hash differs'
    Assert-Throws { Get-CertificateFingerprint '-----BEGIN CERTIFICATE-----AA==-----END CERTIFICATE-----' } 'invalid X509 rejected'
}
Test 'Sunshine pairing edits match certificate only and preserve unknown state' {
    $pem = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $hash = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.sha256')).Trim()
    $original = ConvertTo-Json -Depth 100 -Compress -InputObject @{root=@{uniqueid='server';named_devices=@(
        @{name='renamed phone';cert=$pem;uuid='phone'},
        @{name='Miffan';uuid='other';unknown=@{nested=@(1,'保留',$null)}},
        @{name='duplicate';cert=$pem;uuid='duplicate'});unknown=@{nested=@('keep',$true)}};unknown_top=123}
    $edited = Edit-SunshinePairing $original $hash.ToUpperInvariant()
    Assert-Equal $edited.removed 2 'all and only matching certificates removed'
    $data = ConvertFrom-Json $edited.content
    Assert-Equal $data.root.uniqueid 'server' 'server identity kept'
    Assert-Equal $data.root.named_devices.Count 1 'other device kept'
    Assert-Equal $data.root.named_devices[0].name 'Miffan' 'name does not identify phone'
    Assert-Equal $data.root.named_devices[0].unknown.nested[1] '保留' 'nested unknown keys kept'
    Assert-Equal $data.unknown_top 123 'unknown top-level key kept'
    $unchanged = Edit-SunshinePairing $original ('0'*64)
    Assert-Equal $unchanged.removed 0 'no match'
    Assert-Equal $unchanged.content $original 'no-match bytes unchanged'
    Assert-Throws {Edit-SunshinePairing $original 'invalid'} 'invalid hash rejected'
}
Test 'Sunshine unpair state transaction stops before reading shutdown state (mocked)' {
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ('Miffan-unpair-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $testRoot
    $script:UnpairConfig = Join-Path $testRoot 'sunshine.conf'
    $script:UnpairState = Join-Path $testRoot 'custom-state.json'
    $pem = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $hash = Get-CertificateFingerprint $pem
    $original = ConvertTo-Json -Depth 100 -Compress -InputObject @{root=@{uniqueid='server';named_devices=@(@{cert=$pem;name='renamed'})};unknown_top='kept'}
    $script:UnpairLifecycle=@(); $script:UnpairRunning=$true
    function Get-SunshineContext {return @{known=$true;installed=$true;config=$script:UnpairConfig;values=@{file_state='custom-state.json'};service=@{State=$(if ($script:UnpairRunning) {'Running'} else {'Stopped'})}}}
    function Stop-Service {
        param($Name,$ErrorAction)
        Assert-Equal $Name 'SunshineService' 'only Sunshine stopped'
        $script:UnpairLifecycle += 'stop'; $script:UnpairRunning=$false
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:UnpairState))
        $data | Add-Member -NotePropertyName shutdown_key -NotePropertyValue 'flushed'
        [IO.File]::WriteAllText($script:UnpairState,(ConvertTo-Json -InputObject $data -Depth 100),$script:Utf8)
    }
    function Get-Service {
        param($Name)
        $controller=New-Object PSObject
        $controller | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value {param($Status,$Timeout);$script:UnpairLifecycle+='wait'}
        return $controller
    }
    function Get-CimInstance {param($ClassName,$Filter);return @()}
    function Start-Service {param($Name,$ErrorAction);$script:UnpairLifecycle+='start';$script:UnpairRunning=$true}
    try {
        [IO.File]::WriteAllText($script:UnpairState,$original,$script:Utf8)
        $acl=Get-Acl -LiteralPath $script:UnpairState
        $result=Invoke-SunshineUnpair $hash
        Assert-Equal $result.removed 1 'matching phone removed'
        Assert-True $result.restarted 'running service restored'
        Assert-Equal ($script:UnpairLifecycle -join '|') 'stop|wait|start' 'stop and wait precede restart'
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:UnpairState))
        Assert-Equal $data.shutdown_key 'flushed' 'final shutdown state reread'
        Assert-Equal $data.root.named_devices.Count 0 'phone absent'
        Assert-Equal (Get-Acl -LiteralPath $script:UnpairState).Owner $acl.Owner 'owner retained'
        Assert-Equal (Get-Acl -LiteralPath $script:UnpairState).Sddl $acl.Sddl 'ACL retained'
        $script:UnpairLifecycle=@()
        $before=[IO.File]::ReadAllText($script:UnpairState)
        $result=Invoke-SunshineUnpair $hash
        Assert-Fixture 'sunshine-unpair-none' $result
        Assert-Equal $result.removed 0 'retry no match'
        Assert-True (-not $result.restarted) 'no match no restart'
        Assert-Equal $script:UnpairLifecycle.Count 0 'no match no service calls'
        Assert-Equal ([IO.File]::ReadAllText($script:UnpairState)) $before 'no match exact bytes'
        [IO.File]::WriteAllText($script:UnpairState,$original,$script:Utf8)
        $script:UnpairRunning=$false
        $result=Invoke-SunshineUnpair $hash
        Assert-Fixture 'sunshine-unpair' $result
        Assert-True (-not $result.restarted) 'stopped service stays stopped'
        Assert-Equal $script:UnpairLifecycle.Count 0 'stopped service no calls'
    } finally {Remove-Item -LiteralPath $testRoot -Recurse -Force}
}
Test 'Sunshine direct pair validates and canonicalizes public certificates from JSON only' {
    $pem=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $json=ConvertTo-Json -Compress -InputObject @{name='Miffan 手机';cert=$pem.Replace("`n","`r`n")}
    $request=Read-SunshinePairRequest $json
    Assert-Equal $request.cert $pem 'canonical LF PEM'
    Assert-Equal $request.fingerprint (Get-CertificateFingerprint $pem) 'DER fingerprint'
    foreach ($bad in @('invalid', $pem+$pem, 'extra'+$pem, $pem+'extra', $pem+'-----BEGIN PRIVATE KEY-----AA==-----END PRIVATE KEY-----', '-----BEGIN CERTIFICATE-----AA==-----END CERTIFICATE-----')) {
        Assert-Throws {Read-SunshinePairRequest (ConvertTo-Json -Compress -InputObject @{name='phone';cert=$bad})} 'bad PEM rejected'
    }
    foreach ($badName in @('', '  ', ('x'*65), "phone`n", "phone`0", ("phone"+[char]0x85))) {
        Assert-Throws {Read-SunshinePairRequest (ConvertTo-Json -Compress -InputObject @{name=$badName;cert=$pem})} 'invalid name rejected'
    }
    foreach ($badJson in @('{}','[]','null','{bad', ('['+$json+']'), (ConvertTo-Json -Compress -InputObject @{name='x';cert=$pem;extra=1}))) {
        Assert-Throws {Read-SunshinePairRequest $badJson} 'invalid schema rejected'
    }
    Assert-Throws {Invoke-Helper 'sunshine' 'pair' 'not-stdin'} 'CLI credentials rejected'
}
Test 'Sunshine direct pair adds or replaces every DER match and retains deep unknown fields' {
    $pem=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $other=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate-other.pem'))
    $request=Read-SunshinePairRequest (ConvertTo-Json -Compress -InputObject @{name='Miffan 手机';cert=$pem})
    $deep=@{leaf='保留'}; for ($i=0;$i -lt 20;$i++) {$deep=@{nested=$deep}}
    $original=ConvertTo-Json -Depth 100 -Compress -InputObject @{root=@{uniqueid='server';named_devices=@(
        @{name='renamed';cert=$pem;uuid='one'},@{name='Miffan 手机';cert=$other;uuid='other';unknown=$deep},
        @{name='duplicate';cert=$pem.Replace("`n","`r`n");uuid='two'})};unknown=$deep}
    $edit=Edit-SunshinePairing $original $request.fingerprint $request
    Assert-Equal $edit.removed 2 'all duplicate DER matches replaced'
    $data=ConvertFrom-Json $edit.content
    Assert-Equal $data.root.uniqueid 'server' 'server ID kept'
    Assert-Equal $data.root.named_devices.Count 2 'other plus one new phone'
    Assert-Equal $data.root.named_devices[0].cert $other 'same name other cert retained'
    $added=$data.root.named_devices[1]
    Assert-Equal $added.name 'Miffan 手机' 'requested name'
    Assert-Equal $added.cert $pem 'canonical PEM'
    Assert-True $added.enabled 'enabled client'
    Assert-True ($added.uuid -cmatch '^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$') 'uppercase UUID4'
    Assert-Equal (ConvertTo-Json -Depth 100 -Compress -InputObject $data.unknown) (ConvertTo-Json -Depth 100 -Compress -InputObject $deep) 'deep unknown tree retained'
    $empty=Edit-SunshinePairing '{"root":{"uniqueid":"server"}}' $request.fingerprint $request
    Assert-Equal $empty.removed 0 'new array created'
    Assert-Equal (ConvertFrom-Json $empty.content).root.named_devices.Count 1 'first phone added'
    foreach ($bad in @('[]','[{"root":{}}]','{bad','{"root":null}','{"root":[]}','{"root":{"named_devices":null}}','{"root":{"named_devices":{}}}',
        '{"root":{"named_devices":[1]}}','{"root":{"named_devices":[{"name":"x","cert":"x"}]}}','{"root":{"named_devices":[{"name":3,"cert":"x","uuid":"u"}]}}')) {
        Assert-Throws {Edit-SunshinePairing $bad $request.fingerprint $request} 'unrecognized state rejected'
    }
}
Test 'Sunshine direct pair initializes root and server UUID without changing top-level credentials' {
    $pem=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $request=Read-SunshinePairRequest (ConvertTo-Json -Compress -InputObject @{name='phone';cert=$pem})
    foreach ($root in @($null, [pscustomobject]@{}, [pscustomobject]@{uniqueid=$null}, [pscustomobject]@{uniqueid=42}, [pscustomobject]@{uniqueid='original-server-id'})) {
        $original=@{username='offline-admin';password='offline-password-hash';salt='offline-salt';unknown=@{keep=@(1,$null)}}
        if ($null -ne $root) { $original.root=$root }
        $edited=Edit-SunshinePairing (ConvertTo-Json -Depth 100 -InputObject $original) $request.fingerprint $request
        $data=ConvertFrom-Json $edited.content
        Assert-Equal $data.username 'offline-admin' 'web username retained'
        Assert-Equal $data.password 'offline-password-hash' 'web password hash retained'
        Assert-Equal $data.salt 'offline-salt' 'web salt retained'
        Assert-Equal $data.unknown.keep.Count 2 'unknown fields retained'
        Assert-Equal $data.root.named_devices.Count 1 'client registered'
        if ($null -ne $root -and $root.uniqueid -is [string]) {
            Assert-Equal $data.root.uniqueid $root.uniqueid 'existing server identity unchanged'
        } else {
            Assert-True ($data.root.uniqueid -cmatch '^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$') 'new uppercase server UUID4'
        }
    }
}
Test 'Sunshine direct pair creates absent state with inherited ACL after shutdown recheck (mocked)' {
    $testRoot=Join-Path ([IO.Path]::GetTempPath()) ('Miffan-fresh-pair-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $testRoot
    $script:FreshConfig=Join-Path $testRoot 'sunshine.conf'
    $script:FreshState=Join-Path $testRoot 'fresh-state.json'
    $pem=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $json=ConvertTo-Json -Compress -InputObject @{name='phone';cert=$pem}
    $hash=Get-CertificateFingerprint $pem
    $script:FreshRunning=$false;$script:FreshLifecycle=@();$script:FreshFlush=$null
    function Get-SunshineContext {return @{known=$true;installed=$true;config=$script:FreshConfig;values=@{file_state=$script:FreshState};service=@{State=$(if ($script:FreshRunning) {'Running'} else {'Stopped'})}}}
    function Stop-Service {
        param($Name,$ErrorAction)
        $script:FreshLifecycle+='stop';$script:FreshRunning=$false
        Assert-True (-not (Test-Path -LiteralPath $script:FreshState)) 'no state created before stop'
        if ($null -ne $script:FreshFlush) { [IO.File]::WriteAllText($script:FreshState,$script:FreshFlush,$script:Utf8) }
    }
    function Get-Service {
        param($Name)
        $controller=New-Object PSObject
        $controller | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value {param($Status,$Timeout);$script:FreshLifecycle+='wait'}
        return $controller
    }
    function Get-CimInstance {param($ClassName,$Filter);return @()}
    function Start-Service {param($Name,$ErrorAction);$script:FreshLifecycle+='start';$script:FreshRunning=$true}
    try {
        # A freshly created sibling is the reference for normal inherited directory ACLs.
        $reference=Join-Path $testRoot 'acl-reference.json'
        [IO.File]::WriteAllText($reference,'{}',$script:Utf8)
        $referenceAcl=Get-Acl -LiteralPath $reference
        foreach ($running in @($false,$true)) {
            $script:FreshRunning=$running;$script:FreshLifecycle=@()
            $result=Invoke-SunshinePair $json
            Assert-True $result.added 'missing file registered'
            Assert-Equal $result.replaced 0 'new phone'
            Assert-Equal $result.restarted $running 'original service state retained'
            $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:FreshState))
            Assert-True ($data.root.uniqueid -cmatch '^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$') 'new uppercase server UUID4'
            Assert-Equal $data.root.named_devices.Count 1 'first client'
            Assert-Equal (Get-Acl -LiteralPath $script:FreshState).Owner $referenceAcl.Owner 'new owner uses current user'
            Assert-Equal (Get-Acl -LiteralPath $script:FreshState).Sddl $referenceAcl.Sddl 'directory ACL inherited'
            Assert-Equal ($script:FreshLifecycle -join '|') $(if ($running) {'stop|wait|start'} else {''}) 'only running service restarted'
            Remove-Item -LiteralPath $script:FreshState
        }
        $script:FreshLifecycle=@();$script:FreshRunning=$true
        $unpair=Invoke-SunshineUnpair $hash
        Assert-Equal $unpair.removed 0 'missing state already unpaired'
        Assert-True (-not $unpair.restarted) 'missing unpair no restart'
        Assert-Equal $script:FreshLifecycle.Count 0 'missing unpair no service calls'
        Assert-True (-not (Test-Path -LiteralPath $script:FreshState)) 'unpair does not create state'
        $script:FreshFlush='{"username":"offline-admin","password":"offline-hash","salt":"offline-salt","root":{"uniqueid":"shutdown-server-id"}}'
        $result=Invoke-SunshinePair $json
        Assert-True $result.restarted 'first shutdown file reread'
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:FreshState))
        Assert-Equal $data.root.uniqueid 'shutdown-server-id' 'shutdown server ID retained'
        Assert-Equal $data.password 'offline-hash' 'shutdown credentials retained'
        Assert-Equal $data.salt 'offline-salt' 'shutdown salt retained'
        $script:FreshState=Join-Path (Join-Path $testRoot 'missing-parent') 'state.json'
        Assert-Throws {Invoke-SunshinePair $json} 'missing parent rejected'
        Assert-True (-not (Test-Path -LiteralPath (Split-Path -Parent $script:FreshState))) 'missing parent not created'
        $notDirectory=Join-Path $testRoot 'parent-is-file'
        [IO.File]::WriteAllText($notDirectory,'keep',$script:Utf8)
        $script:FreshState=Join-Path $notDirectory 'state.json'
        Assert-Throws {Invoke-SunshinePair $json} 'non-directory parent rejected'
        Assert-Equal ([IO.File]::ReadAllText($notDirectory)) 'keep' 'parent file unchanged'

    } finally {Remove-Item -LiteralPath $testRoot -Recurse -Force}
}
Test 'Sunshine direct pair transaction preserves shutdown state and ACL, restarts only running service (mocked)' {
    $testRoot=Join-Path ([IO.Path]::GetTempPath()) ('Miffan-pair-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $testRoot
    $script:PairConfig=Join-Path $testRoot 'sunshine.conf'
    $script:PairState=Join-Path $testRoot 'custom-state.json'
    $pem=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'certificate.pem'))
    $json=ConvertTo-Json -Compress -InputObject @{name='Miffan 手机';cert=$pem}
    $script:PairLifecycle=@();$script:PairRunning=$true;$script:PairStartError=$false;$script:PairStopError=$false
    function Get-SunshineContext {return @{known=$true;installed=$true;config=$script:PairConfig;values=@{file_state='custom-state.json'};service=@{State=$(if ($script:PairRunning) {'Running'} else {'Stopped'})}}}
    function Stop-Service {
        param($Name,$ErrorAction)
        if ($script:PairStopError) {throw [UnauthorizedAccessException]::new('offline denied')}
        $script:PairLifecycle+='stop';$script:PairRunning=$false
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:PairState))
        $data | Add-Member -NotePropertyName shutdown_key -NotePropertyValue 'flushed' -Force
        [IO.File]::WriteAllText($script:PairState,(ConvertTo-Json -InputObject $data -Depth 100),$script:Utf8)
    }
    function Get-Service {
        param($Name)
        $controller=New-Object PSObject
        $controller | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value {param($Status,$Timeout);$script:PairLifecycle+='wait'}
        return $controller
    }
    function Get-CimInstance {param($ClassName,$Filter);return @()}
    function Start-Service {param($Name,$ErrorAction);$script:PairLifecycle+='start';if ($script:PairStartError) {throw 'offline start error'};$script:PairRunning=$true}
    try {
        $original='{"root":{"uniqueid":"server"},"unknown":true}'
        [IO.File]::WriteAllText($script:PairState,$original,$script:Utf8)
        $acl=Get-Acl -LiteralPath $script:PairState
        $result=Invoke-SunshinePair $json
        Assert-True $result.added 'new phone written'
        Assert-Equal $result.replaced 0 'new phone count'
        Assert-True $result.restarted 'running service restarted'
        Assert-Equal ($script:PairLifecycle -join '|') 'stop|wait|start' 'shutdown and wait before restart'
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:PairState))
        Assert-Equal $data.shutdown_key 'flushed' 'shutdown state reread'
        Assert-True $data.unknown 'unknown preserved'
        Assert-Equal (Get-Acl -LiteralPath $script:PairState).Owner $acl.Owner 'owner preserved'
        Assert-Equal (Get-Acl -LiteralPath $script:PairState).Sddl $acl.Sddl 'ACL preserved'
        $script:PairRunning=$false;$script:PairLifecycle=@()
        $result=Invoke-SunshinePair $json
        Assert-True (-not $result.restarted) 'stopped stays stopped'
        Assert-Equal $result.replaced 1 'same phone replaced'
        Assert-Equal $script:PairLifecycle.Count 0 'no service calls when stopped'
        # Two duplicate client records are replaced with exactly one new UUID.
        $data=ConvertFrom-Json ([IO.File]::ReadAllText($script:PairState))
        $data.root.named_devices=@($data.root.named_devices[0],$data.root.named_devices[0])
        [IO.File]::WriteAllText($script:PairState,(ConvertTo-Json -InputObject $data -Depth 100),$script:Utf8)
        $script:PairRunning=$true
        $result=Invoke-SunshinePair $json
        Assert-Fixture 'sunshine-pair-replaced' $result
        Assert-Equal (ConvertFrom-Json ([IO.File]::ReadAllText($script:PairState))).root.named_devices.Count 1 'duplicates removed'
        [IO.File]::WriteAllText($script:PairState,$original,$script:Utf8)
        $script:PairRunning=$false
        Assert-Fixture 'sunshine-pair' (Invoke-SunshinePair $json)
        $script:PairRunning=$true;$script:PairLifecycle=@()
        [IO.File]::WriteAllText($script:PairState,'{"root":{"named_devices":[1]}}',$script:Utf8)
        $before=[IO.File]::ReadAllBytes($script:PairState)
        Assert-Throws {Invoke-SunshinePair $json} 'bad state rejected'
        Assert-Equal (Get-BytesSha256 ([IO.File]::ReadAllBytes($script:PairState))) (Get-BytesSha256 $before) 'bad state byte identical'
        Assert-Equal $script:PairLifecycle.Count 0 'bad state no stop'
        [IO.File]::WriteAllText($script:PairState,$original,$script:Utf8)
        $script:PairStopError=$true
        Assert-Throws {Invoke-SunshinePair $json} 'stop denied'
        Assert-Equal ([IO.File]::ReadAllText($script:PairState)) $original 'denied stop no write'
        $script:PairStopError=$false;$script:PairStartError=$true
        try {Invoke-SunshinePair $json;throw 'expected restart failure'} catch {Assert-Equal $_.Exception.Message 'Sunshine pair attempted; could not restart Sunshine' 'fixed restart detail'}
        Assert-Equal (ConvertFrom-Json ([IO.File]::ReadAllText($script:PairState))).root.named_devices.Count 1 'pairing retained after failed restart'
        # Denied atomic replacement and denied reads expose only stable permission details.
        [IO.File]::WriteAllText($script:PairState,$original,$script:Utf8)
        $script:PairRunning=$false;$script:PairStartError=$false
        function Set-Acl {param($LiteralPath,$AclObject,$ErrorAction);throw [UnauthorizedAccessException]::new('offline private path')}
        try {Invoke-SunshinePair $json;throw 'expected write denial'} catch {Assert-Equal $_.Exception.Message 'Cannot edit Sunshine state or control its service; check administrator rights' 'fixed write detail'}
        Assert-Equal ([IO.File]::ReadAllText($script:PairState)) $original 'denied write exact bytes'
        Remove-Item Function:Set-Acl
        function Get-SunshineContext {throw [UnauthorizedAccessException]::new('offline private path')}
        try {Invoke-SunshinePair $json;throw 'expected read denial'} catch {Assert-Equal $_.Exception.Message 'Cannot read Sunshine state or configuration; check administrator rights' 'fixed read detail'}
        Assert-Equal ([IO.File]::ReadAllText($script:PairState)) $original 'denied read exact bytes'
        function Get-SunshineContext {return @{known=$false;installed=$true}}
        Assert-Throws {Invoke-SunshinePair $json} 'unknown service model rejected'
        Assert-Equal ([IO.File]::ReadAllText($script:PairState)) $original 'unknown service no write'

    } finally {Remove-Item -LiteralPath $testRoot -Recurse -Force}
}
Test 'network validation and Windows dotted-mask canonicalization' {
    Assert-Equal (ConvertTo-Network '192.168.31.8/255.255.255.0') '192.168.31.0/24' 'mask normalized'
    Assert-Equal (ConvertTo-Network '100.64.0.8/10') '100.64.0.0/10' 'Tailscale normalized'
    Assert-Equal (ConvertTo-Network 'fd00::123/64') 'fd00::/64' 'IPv6 normalized'
    foreach ($bad in @('Any','LocalSubnet','*','192.168.0.1','0.0.0.0/0','::/0','10.0.0.0/33','10.0.0.0/255.0.255.0','10.0.0.0/24;calc','fe80::1%3/64')) { Assert-Throws {ConvertTo-Network $bad} "invalid source $bad" }
    Assert-Throws { Get-Networks @() } 'empty sources rejected'
}
Test 'ActiveStore enforcement arrays and path normalization' {
    Assert-True (Test-RuleEnforced $true 'OK' @('ProfileInactive','Enforced','Enforced')) 'mixed profile status still enforced'
    Assert-True (-not (Test-RuleEnforced $false 'Inactive' @('Disabled'))) 'disabled not enforced'
    Assert-True (-not (Test-RuleEnforced $true 'OK' @('ProfileInactive'))) 'only inactive profiles'
    Assert-True (-not (Test-RuleEnforced $true 'Unknown' @('Unknown'))) 'unknown not reported as enforced'
    Assert-True (Test-SunshineProgram 'C:\\Program Files\\Sunshine\\sunshine.exe' $exe) 'equivalent Windows separators'
    Assert-True (-not (Test-SunshineProgram 'C:\other\sunshine.exe' $exe)) 'different exe path'
}
Test 'firewall exposure for enabled rules, wildcard protocols and ports' {
    Assert-True ((Get-RuleRisks (Rule 'tcp' 'TCP')).Count -gt 0) 'TCP Any exposed'
    Assert-True ((Get-RuleRisks (Rule 'any' 'Any')).Count -gt 0) 'Any protocol exposed'
    Assert-True ((Get-RuleRisks (Rule 'udp')).Count -gt 0) 'UDP Any exposed'
    Assert-True ((Get-RuleRisks (Rule 'udp' 'UDP' @('47998-48001') @('192.168.31.0/24'))).Count -gt 0) 'extra UDP port exposed'
    Assert-Equal (Get-RuleRisks (Rule 'off' 'TCP' @('Any') @('Any') $exe $false)).Count 0 'disabled irrelevant'
    Assert-Equal (Get-RuleRisks (Rule 'safe' 'UDP' @('47998-48000') @('192.168.31.0/24'))).Count 0 'narrow UDP safe'
    Assert-True (Test-UdpPorts @('47998','47999','48000')) 'separate ports safe'
    Assert-True (-not (Test-UdpPorts @('47998-47997'))) 'reversed range rejected'
}
Test 'firewall plan scope, safe fixed UDP creation, and idempotence' {
    $rules = @((Rule 'tcp' 'TCP'), (Rule 'udp'))
    $plan = Get-FirewallPlan $rules $exe @('192.168.31.0/24','100.64.0.0/10') @('tcp','udp')
    Assert-True $plan.changed 'repair needed'
    Assert-Equal $plan.disable_rules.Count 2 'both vendor rules disabled'
    Assert-Equal $plan.add_rule.protocol 'UDP' 'only UDP'
    Assert-Equal ($plan.add_rule.ports -join ',') '47998-48000' 'only allowed ports'
    Assert-Throws { Get-FirewallPlan $rules $exe @('192.168.31.0/24') @('udp') } 'partial repair rejected'
    Assert-Throws { Get-FirewallPlan @((Rule 'ssh' 'TCP' @('22') @('192.168.31.0/24') 'C:\Windows\System32\OpenSSH\sshd.exe')) $exe @('192.168.31.0/24') @('ssh') } 'unrelated program protected'
    $gpo = Rule 'gpo' 'TCP'; $gpo.policy_source='GroupPolicy'
    Assert-Throws { Get-FirewallPlan @($gpo) $exe @('192.168.31.0/24') @('gpo') } 'GPO protected'
    $collision = Rule 'Miffan-Sunshine-UDP-LAN' 'TCP' @('22') @('Any') 'other.exe'
    Assert-Throws { Get-FirewallPlan @($collision) $exe @('192.168.31.0/24') @() } 'Miffan name does not authorize other program'
    $safe = Rule 'Miffan-Sunshine-UDP-LAN' 'UDP' @('47998-48000') @('192.168.31.0/255.255.255.0','100.64.0.0/255.192.0.0')
    $plan = Get-FirewallPlan @($safe) $exe @('100.64.0.0/10','192.168.31.0/24') @()
    Assert-True (-not $plan.changed) 'existing Windows mask sources no-op'
    Assert-True ($null -eq $plan.add_rule) 'no redundant rule'
}
Test 'firewall check exposes unmanageable global rules and profile risk' {
    $global = Rule 'global' 'TCP' @('Any') @('Any') 'Any'
    $result = Get-FirewallCheck @{rules=@($global);profiles=@();profiles_safe=$true} $exe
    Assert-True (-not $result.firewall_model_ok) 'global open rule unsafe'
    Assert-Equal ($result.unmanageable_rules -join ',') 'global' 'global cannot auto-disable'
    Assert-Throws {Get-FirewallPlan @($global) $exe @('192.168.31.0/24') @('global')} 'global scope rejected'
    Assert-True (-not (Get-FirewallCheck @{rules=@();profiles=@();profiles_safe=$false} $exe).firewall_model_ok) 'disabled firewall unsafe'
}
Test 'strict firewall stdin schema cannot introduce TCP or extra changes' {
    $r = Read-FirewallRequest '{"disable_rules":[],"allowed_sources":["192.168.31.0/24"],"add_rule":null}'
    Assert-Equal $r.disable_rules.Count 0 'empty array retained'
    foreach ($json in @('{"disable_rules":[],"allowed_sources":["Any"]}','{"disable_rules":[],"allowed_sources":["192.168.31.0/24"],"protocol":"TCP"}','{"disable_rules":"id","allowed_sources":["192.168.31.0/24"],"add_rule":null}','{"disable_rules":[5],"allowed_sources":["192.168.31.0/24"],"add_rule":null}')) { Assert-Throws {Read-FirewallRequest $json} 'unsafe request rejected' }
}
Test 'compact JSON retains primitives, Unicode, arrays, null and boolean' {
    $line = ConvertTo-PlainJson @{success=$true;detail="中文`nquote\";nothing=$null;items=@();one=@('one');number=1}
    Assert-True (-not $line.Contains("`n")) 'one JSON line'
    Assert-True ($line.Length -lt 200) 'no ETS expansion'
    $decoded = ConvertFrom-Json $line
    Assert-True $decoded.success 'boolean'
    Assert-Equal $decoded.items.Count 0 'empty JSON array'
    Assert-Equal $decoded.one.Count 1 'single JSON array'
    Assert-Equal $decoded.detail "中文`nquote\" 'Unicode/escape roundtrip'
    Assert-Equal (ConvertTo-PlainJson (Invoke-Helper 'version' '' '')) ([string]$script:HelperVersion) 'integer version contract'
}
Test 'firewall review includes exact LAN and detected Tailscale sources without granting writes' {
    function Get-NetIPConfiguration { return @(
        @{NetAdapter=@{Status='Up'};InterfaceAlias='Ethernet';InterfaceDescription='LAN';IPv4DefaultGateway=@{NextHop='192.168.31.1'};IPv4Address=@(@{IPAddress='192.168.31.61';PrefixLength=24})},
        @{NetAdapter=@{Status='Up'};InterfaceAlias='Tailscale';InterfaceDescription='Tailscale Tunnel';IPv4DefaultGateway=$null;IPv4Address=@(@{IPAddress='100.64.0.8';PrefixLength=32})},
        @{NetAdapter=@{Status='Up'};InterfaceAlias='VM';InterfaceDescription='Virtual';IPv4DefaultGateway=$null;IPv4Address=@(@{IPAddress='172.20.0.1';PrefixLength=16})}
    ) }
    try {
        $sources=Get-FirewallSources
        Assert-True (Test-SameSet $sources @('192.168.31.0/24','100.64.0.0/10')) 'only active gateway LAN and Tailscale'
        $snapshot=@{rules=@((Rule 'unsafe'));profiles=@();profiles_safe=$true}
        $review=Get-FirewallReview $snapshot $exe $sources
        Assert-True (-not $review.firewall_model_ok) 'review required'
        Assert-True ($null -ne $review.suggested_plan) 'complete plan'
        Assert-True (Test-SameSet @($review.suggested_plan.Keys) @('disable_rules','allowed_sources','add_rule')) 'exact three fields'
        Assert-True (Test-SameSet $review.suggested_plan.add_rule.sources $sources) 'fixed explicit sources'
        $empty=Get-FirewallReview $snapshot $exe @()
        Assert-True (-not $empty.firewall_model_ok -and $null -eq $empty.suggested_plan) 'unknown sources fail closed'
        $snapshot.profiles_safe=$false
        $blocked=Get-FirewallReview $snapshot $exe $sources
        Assert-True ($null -eq $blocked.suggested_plan) 'profiles cannot be repaired by rule-only plan'
    } finally { Remove-Item Function:Get-NetIPConfiguration }
}
Test 'review includes preexisting explicit UDP sources outside current approved proposal' {
    $old=Rule 'old-explicit' 'UDP' @('47998-48000') @('10.0.0.0/8')
    $snapshot=@{rules=@($old);profiles=@();profiles_safe=$true}
    Assert-True ((Get-FirewallCheck $snapshot $exe).firewall_model_ok) 'old generic check accepts explicit subnet'
    $review=Get-FirewallReview $snapshot $exe @('192.168.31.0/24','100.64.0.0/10')
    Assert-True (-not $review.firewall_model_ok) 'current network scope requires review'
    Assert-True ($review.suggested_plan.disable_rules -contains 'old-explicit') 'old scope explicitly disabled in approved plan'
    Assert-True ($review.unsafe_rules[0].reasons -contains 'sources_differ_from_proposed_networks') 'source scope risk exposed'
    Assert-Throws { Get-FirewallPlan $snapshot.rules $exe @('192.168.31.0/24') @() } 'apply cannot leave an explicit extra scope enabled'
    $old.policy_source='GroupPolicy'
    $blocked=Get-FirewallReview $snapshot $exe @('192.168.31.0/24')
    Assert-True ($null -eq $blocked.suggested_plan -and $blocked.unmanageable_rules -contains 'old-explicit') 'GPO scope cannot receive a partial repair plan'
}
Test 'unknown stream state conservatively blocks restart' {
    Assert-True (Get-StreamActivity @{known=$false;values=@{}}).active 'unknown configuration'
    Assert-True (-not (Get-StreamActivity @{known=$false;values=@{}}).known) 'unknown state explicit'
    function Get-NetUDPEndpoint { throw 'offline endpoint failure' }
    Assert-True (Get-StreamActivity @{known=$true;values=@{}}).active 'endpoint error active'
    function Get-NetUDPEndpoint { [pscustomobject]@{LocalPort=48000;OwningProcess=999} }
    Assert-True (Get-StreamActivity @{known=$true;values=@{}}).active 'any owner port conflict blocks'
    function Get-NetUDPEndpoint { }
    Assert-True (-not (Get-StreamActivity @{known=$true;values=@{}}).active) 'no endpoints no stream'
}
Test 'empty apply plan refuses before backup and mutations (mocked)' {
    $script:Fixture = @{rules=@((Rule 'Miffan-Sunshine-UDP-LAN' 'UDP' @('47998-48000') @('192.168.31.0/24')));profiles=@();profiles_safe=$true}
    $script:BackupCalled=$false;$script:MutationCalled=$false
    function Get-SunshineContext { @{installed=$true;known=$true;executable=$exe} }
    function Get-FirewallSnapshot { param($Executable); return $script:Fixture }
    function Save-FirewallBackup { $script:BackupCalled=$true;throw 'Unexpected backup' }
    function Invoke-FirewallMutations { $script:MutationCalled=$true;throw 'Unexpected mutation' }
    Assert-Throws {Invoke-FirewallApply @{allowed_sources=@('192.168.31.0/24');disable_rules=@();add_rule=$null}} 'empty plan refuses'
    Assert-True (-not $script:BackupCalled -and -not $script:MutationCalled) 'zero writes for empty plan'
}
Test 'apply backups precede mutation and detects concurrent rule change (mocked)' {
    $script:Fixture = @{rules=@((Rule 'vendor' 'TCP'));profiles=@();profiles_safe=$true}
    $script:Snapshots=0; $script:BackupDone=$false; $script:MutationDone=$false
    function Get-SunshineContext { @{installed=$true;known=$true;executable=$exe} }
    function Get-FirewallSnapshot { param($Executable); $script:Snapshots++; if($script:Snapshots -ge 2){return @{rules=@();profiles=@();profiles_safe=$true}};return $script:Fixture }
    function Save-FirewallBackup { $script:BackupDone=$true; return 'offline-backup' }
    function Invoke-FirewallMutations { $script:MutationDone=$true }
    $requested=Get-FirewallPlan $script:Fixture.rules $exe @('192.168.31.0/24') @('vendor')
    Assert-Throws {Invoke-FirewallApply $requested} 'concurrent change rejected'
    Assert-True ($script:BackupDone -and -not $script:MutationDone) 'backup before refusal, no mutation'
}

Test 'successful apply is backed up, fixed-scope and rechecked (mocked)' {
    $script:Fixture = @{rules=@((Rule 'vendor' 'TCP'));profiles=@();profiles_safe=$true}
    $script:Order=@()
    function Get-SunshineContext { @{installed=$true;known=$true;executable=$exe} }
    function Get-FirewallSnapshot { param($Executable); $script:Order+='snapshot'; return $script:Fixture }
    function Save-FirewallBackup { $script:Order+='backup'; return 'offline-backup' }
    function Invoke-FirewallMutations {param($Plan); $script:Order+='mutation';$script:Fixture=@{rules=@((Rule 'Miffan-Sunshine-UDP-LAN' 'UDP' @('47998-48000') @('192.168.31.0/24')));profiles=@();profiles_safe=$true} }
    $requested=Get-FirewallPlan $script:Fixture.rules $exe @('192.168.31.0/24') @('vendor')
    $r=Invoke-FirewallApply $requested
    Assert-True ($r.success -and $r.changed) 'changed repair verified'
    Assert-Equal $r.backup 'offline-backup' 'backup surfaced'
    Assert-Equal ($script:Order -join '|') 'snapshot|backup|snapshot|mutation|snapshot' 'order and recheck'
}
Test 'service start and encryption enforcement no-op never mutate (mocked)' {
    function Get-SunshineContext { @{known=$true;installed=$true;service=@{State='Running'};config=$HelperPath;content='lan_encryption_mode = 2';values=@{lan_encryption_mode='2';wan_encryption_mode='2'}} }
    function Get-StreamActivity { @{known=$true;active=$false} }
    function Start-Service { throw 'Unexpected service start' }
    function Restart-Service { throw 'Unexpected service restart' }
    function New-BackupPath { throw 'Unexpected backup' }
    Assert-True (-not (Invoke-SunshineStart).changed) 'already-running start no-op'
    Assert-True (-not (Invoke-EnforceEncryption).changed) '2/2 enforcement no-op'
    function Get-StreamActivity { @{known=$true;active=$true} }
    Assert-Throws {Invoke-EnforceEncryption} 'active stream refuses even if already 2/2'
}
Test 'encryption atomic replacement and private backup use only temporary fixture' {
    $savedLocal = $env:LOCALAPPDATA
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ('Miffan-test-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $testRoot
    $script:ConfigFixture = Join-Path $testRoot 'sunshine.conf'
    $original = "# 中文`r`nfoo = bar`r`nlan_encryption_mode = 0`r`nwan_encryption_mode = 1`r`n"
    [IO.File]::WriteAllText($script:ConfigFixture,$original,(New-Object Text.UTF8Encoding($true)))
    $script:Restarted=$false
    try {
        $env:LOCALAPPDATA=$testRoot
        function Get-SunshineContext {
            $bytes=[IO.File]::ReadAllBytes($script:ConfigFixture)
            $text=$script:Utf8.GetString($bytes).TrimStart([char]0xFEFF)
            return @{known=$true;installed=$true;config=$script:ConfigFixture;content=$text;bytes=$bytes;values=(Get-ConfigValues $text)}
        }
        function Get-StreamActivity { @{known=$true;active=$false} }
        function Restart-Service {param($Name); Assert-Equal $Name 'SunshineService' 'only Sunshine restart';$script:Restarted=$true}
        $r=Invoke-EnforceEncryption
        Assert-True ($r.success -and $r.changed -and $script:Restarted) 'fixture replacement and mocked restart'
        Assert-Equal ([IO.File]::ReadAllText($r.backup)) $original 'backup exact original'
        Assert-Equal ([IO.File]::ReadAllText($script:ConfigFixture)) (Edit-EncryptionConfig $original) 'new config exact'
        Assert-Equal ([IO.File]::ReadAllBytes($script:ConfigFixture)[0]) 239 'BOM preserved'
        Assert-PrivateDirectory (Split-Path $r.backup) ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
    } finally { $env:LOCALAPPDATA=$savedLocal;Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
Test 'desktop job uses private ACL, Limited interactive principal, verified result, cleanup (mocked task)' {
    $savedLocal=$env:LOCALAPPDATA
    $testRoot=Join-Path ([IO.Path]::GetTempPath()) ('Miffan-test-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $testRoot
    $script:JobSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $script:Lifecycle=@();$script:JobDirectory=$null
    try {
        $env:LOCALAPPDATA=$testRoot
        function Get-ConsoleSession { @{present=$true;sid=$script:JobSid;id=1;lock_state='unlocked'} }
        function New-ScheduledTaskAction {param($Execute,$Argument);Assert-True ($Argument -notmatch '剪贴板内容') 'text absent in arguments';return @{Execute=$Execute;Argument=$Argument} }
        function New-ScheduledTaskPrincipal {param($UserId,$LogonType,$RunLevel);Assert-Equal $LogonType 'Interactive' 'interactive';Assert-Equal $RunLevel 'Limited' 'ordinary privileges';return @{} }
        function New-ScheduledTaskSettingsSet {param($ExecutionTimeLimit,[switch]$AllowStartIfOnBatteries,[switch]$DontStopIfGoingOnBatteries);return @{} }
        function Register-ScheduledTask {param($TaskName,$Action,$Principal,$Settings);Assert-True ($TaskName.StartsWith('Miffan-clip-')) 'task prefix';$script:Lifecycle+='register'}
        function Start-ScheduledTask {
            param($TaskName)
            $script:Lifecycle+='start'
            $script:JobDirectory=(Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Miffan') -Directory | Select-Object -First 1).FullName
            Assert-PrivateDirectory $script:JobDirectory $script:JobSid
            Assert-Equal ([IO.File]::ReadAllText((Join-Path $script:JobDirectory 'input.txt'))) '剪贴板内容' 'stdin remains in file only'
            [IO.File]::WriteAllText((Join-Path $script:JobDirectory 'result.json'),'{"success":true,"session_id":1,"verified":true}')
        }
        function Get-ScheduledTask {param($TaskName,$TaskPath,$ErrorAction);return @{State='Running'}}
        function Stop-ScheduledTask {param($TaskName,$ErrorAction);$script:Lifecycle+='stop'}
        function Unregister-ScheduledTask {param($InputObject,$Confirm,$ErrorAction);$script:Lifecycle+='unregister'}
        $r=Invoke-DesktopJob 'clip' '剪贴板内容'
        Assert-True ($r.success -and $r.verified) 'readback required'
        Assert-Equal ($script:Lifecycle -join '|') 'register|start|stop|unregister' 'full lifecycle'
        Assert-True (-not (Test-Path $script:JobDirectory)) 'data deleted'
        function Get-ConsoleSession { @{present=$true;sid=$script:JobSid;id=1;lock_state='unknown'} }
        Assert-Throws {Invoke-DesktopJob 'clip' 'text'} 'unknown lock refuses clipboard'
        function Get-ConsoleSession { @{present=$true;sid='S-1-5-18';id=1;lock_state='unlocked'} }
        Assert-Throws {Invoke-DesktopJob 'clip' 'text'} 'other desktop user refused'
    } finally { $env:LOCALAPPDATA=$savedLocal;Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
Test 'base probe does not create a desktop task (mocked)' {
    function Get-MachineProbe { @{helper=1;os='windows';displays=$null;display_state='unknown';session=@{present=$true;type='console'};cua=@{min='0.34.0';ok=$false};vnc=@{running=$false}} }
    function Invoke-DesktopJob { throw 'Base probe created a task' }
    $p=Invoke-Helper 'probe' '' ''
    Assert-Equal $p.os 'windows' 'platform'
    Assert-True ($null -eq $p.displays) 'lightweight displays unknown'
    Assert-Throws {Invoke-Helper 'clip' 'secret-in-argument' ''} 'clipboard argv rejected'
}

Test 'ActiveStore association never falls back to divergent PersistentStore (mocked provider)' {
    $script:PolicyQueries=@()
    function Get-NetFirewallApplicationFilter {param($PolicyStore,[switch]$All);Assert-Equal $PolicyStore 'ActiveStore' 'application policy explicit';return [pscustomobject]@{Program=$exe;InstanceID='vendor'} }
    function Get-NetFirewallRule {
        param($PolicyStore,[switch]$All,$AssociatedNetFirewallApplicationFilter)
        $script:PolicyQueries += $PolicyStore
        Assert-True ($All -and -not $AssociatedNetFirewallApplicationFilter) 'association queries prohibited'
        if($PolicyStore -eq 'ActiveStore'){return [pscustomobject]@{Name='vendor';InstanceID='vendor';DisplayName='Sunshine';Direction='Inbound';Enabled='True';PrimaryStatus='OK';EnforcementStatus=@('ProfileInactive','Enforced');Action='Allow';Profile='Any';PolicyStoreSourceType='Local'}}
        # The dangerous regression: this store says disabled and has no enforcement status.
        return [pscustomobject]@{Name='vendor';InstanceID='vendor';DisplayName='Sunshine';Direction='Inbound';Enabled='False';PrimaryStatus='OK';EnforcementStatus=@('NotApplicable');Action='Allow';Profile='Any';PolicyStoreSourceType='Local'}
    }
    function Get-NetFirewallPortFilter {param($PolicyStore,[switch]$All);Assert-True $All 'explicit table enumeration';Assert-Equal $PolicyStore 'ActiveStore' 'port policy explicit';return [pscustomobject]@{InstanceID='vendor';Protocol='TCP';LocalPort=@('Any')} }
    function Get-NetFirewallAddressFilter {param($PolicyStore,[switch]$All);Assert-True $All 'explicit table enumeration';Assert-Equal $PolicyStore 'ActiveStore' 'address policy explicit';return [pscustomobject]@{InstanceID='vendor';RemoteAddress=@('Any')} }
    function Get-NetFirewallProfile {param($PolicyStore);Assert-Equal $PolicyStore 'ActiveStore' 'profiles policy explicit';foreach($name in @('Domain','Private','Public')){[pscustomobject]@{Name=$name;Enabled='True';DefaultInboundAction='Block'}} }
    $snapshot=Get-FirewallSnapshot $exe
    Assert-True ($snapshot.rules[0].enabled -and $snapshot.rules[0].effective) 'uses effective ActiveStore rule'
    Assert-Equal ($script:PolicyQueries -join '|') 'ActiveStore' 'no implicit/default policy association'
    Assert-Equal ((Get-FirewallCheck $snapshot $exe).suggested_changes.disable_rules -join '|') 'vendor' 'hazard not hidden by PersistentStore'
}
Test 'caller plan mismatch refuses before any backup/write (mocked)' {
    $script:Fixture=@{rules=@((Rule 'vendor' 'TCP'));profiles=@();profiles_safe=$true}
    function Get-SunshineContext { @{installed=$true;known=$true;executable=$exe} }
    function Get-FirewallSnapshot {param($Executable);return $script:Fixture}
    $script:Wrote=$false
    function Save-FirewallBackup {$script:Wrote=$true;throw 'Unexpected backup'}
    function Invoke-FirewallMutations {$script:Wrote=$true;throw 'Unexpected mutation'}
    $requested=Get-FirewallPlan $script:Fixture.rules $exe @('192.168.31.0/24') @('vendor')
    $requested.add_rule.replace=$true
    Assert-Throws {Invoke-FirewallApply $requested} 'stale replacement plan refused'
    Assert-True (-not $script:Wrote) 'mismatch zero writes'
    $requested.add_rule.replace=$false;$requested.add_rule.sources=@('100.64.0.0/10')
    Assert-Throws {Invoke-FirewallApply $requested} 'different approved sources refused'
    Assert-True (-not $script:Wrote) 'different sources zero writes'
}
Test 'NetSecurity-style module import overwrites same-name guard; internal boundary still prevents writes' {
    # An isolated fake module recreates auto-import shadowing without any real NetSecurity calls.
    $temp=Join-Path ([IO.Path]::GetTempPath()) ('Miffan-mock-'+[Guid]::NewGuid().ToString('N')+'.psm1')
    [IO.File]::WriteAllText($temp,"function New-NetFirewallRule { 'module-overwrote-guard' }; Export-ModuleMember -Function New-NetFirewallRule")
    function global:New-NetFirewallRule { 'original-guard' }
    $loaded=$null
    try {
        Assert-Equal (New-NetFirewallRule) 'original-guard' 'guard before import'
        $loaded=Import-Module $temp -Global -Force -PassThru
        Assert-Equal (New-NetFirewallRule) 'module-overwrote-guard' 'import replaced same-name guard'
        $script:Fixture=@{rules=@((Rule 'Miffan-Sunshine-UDP-LAN' 'UDP' @('47998-48000') @('192.168.31.0/24')));profiles=@();profiles_safe=$true}
        function Get-SunshineContext { @{installed=$true;known=$true;executable=$exe} }
        function Get-FirewallSnapshot {param($Executable);return $script:Fixture}
        $script:Wrote=$false
        function Save-FirewallBackup {$script:Wrote=$true}
        function Invoke-FirewallMutations {$script:Wrote=$true;throw 'Internal mutation boundary reached'}
        Assert-Throws {Invoke-FirewallApply @{allowed_sources=@('192.168.31.0/24');disable_rules=@();add_rule=$null}} 'empty plan stopped internally'
        Assert-True (-not $script:Wrote) 'never relies on the overwritten native guard'
        $source=[IO.File]::ReadAllText($HelperPath)
        Assert-True ($source.Contains('NetSecurity\New-NetFirewallRule') -and $source.Contains('NetSecurity\Remove-NetFirewallRule') -and $source.Contains('NetSecurity\Disable-NetFirewallRule')) 'production mutations module-qualified'
    } finally {if($loaded){Remove-Module $loaded};Remove-Item -LiteralPath $temp -Force;Remove-Item Function:\New-NetFirewallRule -ErrorAction SilentlyContinue}
}

Test 'desktop cleanup accepts completion race and refuses a still-running task (mocked)' {
    $script:States=0
    function Get-ScheduledTask {param($TaskName,$TaskPath,$ErrorAction);$script:States++;if($script:States -eq 1){return @{State='Running'}};return @{State='Ready'}}
    function Stop-ScheduledTask {param($TaskName,$ErrorAction);throw 'Task has already finished'}
    Stop-OwnedDesktopTask 'Miffan-mock-race'
    Assert-Equal $script:States 2 'fresh task state proves completion'
    function Get-ScheduledTask {param($TaskName,$TaskPath,$ErrorAction);return @{State='Running'}}
    Assert-Throws {Stop-OwnedDesktopTask 'Miffan-mock-running'} 'failed stop of running task surfaced'
}
Test 'approved firewall add_rule schema accepts only fixed UDP and exact fields' {
    $json = '{"disable_rules":["vendor"],"allowed_sources":["192.168.31.0/24"],"add_rule":{"id":"Miffan-Sunshine-UDP-LAN","program":"C:\\Program Files\\Sunshine\\sunshine.exe","protocol":"UDP","ports":["47998-48000"],"sources":["192.168.31.0/24"],"replace":false}}'
    $request=Read-FirewallRequest $json
    Assert-Equal $request.add_rule.protocol 'UDP' 'accepted fixed rule'
    foreach($bad in @($json.Replace('"UDP"','"TCP"'),$json.Replace('47998-48000','22'),$json.Replace('"replace":false','"replace":"false"'),$json.Replace('Miffan-Sunshine-UDP-LAN','Miffan-SSH-LAN'))) {Assert-Throws {Read-FirewallRequest $bad} 'unsafe addition refused'}
}

Test 'desktop unregister retries transient provider failure on the exact root task (mocked)' {
    $script:UnregisterCalls=0
    function Get-ScheduledTask {param($TaskName,$TaskPath,$ErrorAction);Assert-Equal $TaskName 'Miffan-mock' 'exact name';Assert-Equal $TaskPath '\' 'explicit root';return @{TaskName='Miffan-mock';State='Ready'}}
    function Unregister-ScheduledTask {param($InputObject,$Confirm,$ErrorAction);Assert-Equal $InputObject.TaskName 'Miffan-mock' 'fresh exact object';$script:UnregisterCalls++;if($script:UnregisterCalls -eq 1){throw 'transient provider error'}}
    Unregister-OwnedDesktopTask 'Miffan-mock'
    Assert-Equal $script:UnregisterCalls 2 'retried completed task removal'
}
Test 'cua bridge compiles on Windows PowerShell 5.1 without daemon access' {
    Initialize-CuaBridge
    Assert-True ([bool]('MiffanCuaBridge' -as [type])) 'C# compiled against .NET Framework'
}
Test 'one-time bridge credentials: replay, expiry, wrong token and path traversal' {
    $directory=Join-Path ([IO.Path]::GetTempPath()) ('Miffan-cua-offline-'+[Guid]::NewGuid().ToString('N'))
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    New-PrivateDirectory $directory $sid
    try {
        Assert-PrivateDirectory $directory $sid
        $id=[Guid]::NewGuid().ToString('N'); $token='a'*64
        $file=[MiffanCuaBridge]::Ticket($directory,$id)
        [IO.File]::WriteAllText($file,$token,$script:Utf8)
        Assert-True ([MiffanCuaBridge]::Consume($directory,$id,$token)) 'first use succeeds'
        Assert-True (-not [MiffanCuaBridge]::Consume($directory,$id,$token)) 'replay refused'
        [IO.File]::WriteAllText($file,$token,$script:Utf8)
        Assert-True (-not [MiffanCuaBridge]::Consume($directory,$id,('b'*64))) 'wrong token refused'
        Assert-True (-not [IO.File]::Exists($file)) 'wrong auth consumes ticket'
        [IO.File]::WriteAllText($file,$token,$script:Utf8); [IO.File]::SetLastWriteTimeUtc($file,[DateTime]::UtcNow.AddSeconds(-31))
        Assert-True (-not [MiffanCuaBridge]::Consume($directory,$id,$token)) 'expired token refused'
        Assert-Throws {[MiffanCuaBridge]::Ticket($directory,'../user-file')} 'invalid credential path'
    } finally {Remove-Item -LiteralPath $directory -Recurse -Force}
}
Test 'bridge rejects missing, locked and different-user console sessions' {
    foreach($console in @(@{present=$false;sid='S-1';id=1;lock_state='unlocked'},@{present=$true;sid='S-2';id=1;lock_state='unlocked'},@{present=$true;sid='S-1';id=0;lock_state='unlocked'},@{present=$true;sid='S-1';id=1;lock_state='locked'},@{present=$true;sid='S-1';id=1;lock_state='unknown'})) {
        Assert-Throws {Assert-CuaConsole @{console=$console;sid='S-1'}} 'unsafe console refused'
    }
    Assert-CuaConsole @{console=@{present=$true;sid='S-1';id=1;lock_state='unlocked'};sid='S-1'}
}
Test 'bridge registers only ordinary interactive owned task and starts on demand (mocked)' {
    $script:BridgeChecks=0; $script:BridgeStarted=$false; $script:BridgeRegistered=$false
    function Get-CuaBridgeContext {return @{console=@{present=$true;sid='S-1';id=1;lock_state='unlocked'};sid='S-1';pipe='Miffan-offline';task='Miffan-offline';directory='C:\offline'}}
    function Get-CuaStatus {return @{ok=$true;permission_review_needed=$false;daemon_session_ids=@(1)}}
    function New-Item {param($ItemType,$Path,[switch]$Force)}
    function Assert-CuaDirectoryParents {param($Path)}
    function New-PrivateDirectory {param($Path,$Sid)}
    function Assert-PrivateDirectory {param($Path,$Sid)}
    function Test-CuaBridgeAvailable {param($Name);$script:BridgeChecks++;return $script:BridgeChecks -gt 1}
    function Get-ScheduledTask {param($TaskName,$TaskPath,$ErrorAction);return @()}
    function New-ScheduledTaskAction {param($Execute,$Argument);Assert-True ($Argument.Contains('cua bridge') -and $Argument.Contains('-WindowStyle Hidden')) 'hidden bridge command';return @{Execute=$Execute;Arguments=$Argument}}
    function New-ScheduledTaskPrincipal {param($UserId,$LogonType,$RunLevel);Assert-Equal $UserId 'S-1' 'same SID';Assert-Equal $RunLevel 'Limited' 'ordinary task';Assert-Equal $LogonType 'Interactive' 'interactive task';return @{UserId=$UserId}}
    function New-ScheduledTaskSettingsSet {param($ExecutionTimeLimit,$MultipleInstances,[switch]$AllowStartIfOnBatteries,[switch]$DontStopIfGoingOnBatteries);Assert-Equal $MultipleInstances 'IgnoreNew' 'no duplicate worker';return @{}}
    function Register-ScheduledTask {param($TaskName,$Action,$Principal,$Settings,$ErrorAction);Assert-True ($TaskName.StartsWith('Miffan-')) 'only owned prefix';$script:BridgeRegistered=$true}
    function Start-ScheduledTask {param($TaskName,$ErrorAction);$script:BridgeStarted=$true}
    $context=Ensure-CuaBridge
    Assert-True ($script:BridgeRegistered -and $script:BridgeStarted) 'registered and started once'
    $script:BridgeRegistered=$false;$script:BridgeStarted=$false
    $null=Ensure-CuaBridge
    Assert-True (-not $script:BridgeRegistered -and -not $script:BridgeStarted) 'available bridge reused'
    function Get-CuaStatus {return @{ok=$true;permission_review_needed=$true;daemon_session_ids=@(1)}}
    Assert-Throws {Ensure-CuaBridge} 'elevated daemon task refused, not downgraded'
}
Test 'install refuses elevated existing task before network or mutation (mocked)' {
    function Get-CuaTask {return @{Principal=@{RunLevel='Highest';LogonType='Interactive'}}}
    function Invoke-WebRequest {throw 'MUST NOT DOWNLOAD'}
    function Register-ScheduledTask {throw 'MUST NOT REGISTER'}
    $message=$null
    try {Invoke-CuaInstall} catch {$message=$_.Exception.Message}
    Assert-Equal $message 'Cua autostart uses elevated permissions; user review required' 'explicit review refusal'
}
Test 'install follows the stable release and keeps the autostart ordinary (mocked)' {
    function Get-CuaTask {return @{Principal=@{RunLevel='Limited';LogonType='Interactive'}}}
    function Test-CuaTaskOwner {param($Task,$Sid);return $true}
    $script:Releases=@();$script:Registered=0
    function Test-CuaDownloadFast {return $true}
    function Install-CuaRelease {param($Release);$script:Releases+=,$Release}
    function Register-CuaDaemonTask {param($Sid);$script:Registered++}
    function Get-CuaStatus {return @{ok=$true;version=$(if ($script:Releases.Count) {'0.37.0'} else {'0.34.0'});permission_review_needed=$false}}
    function Test-CuaWorks {param($Version);Assert-Equal $Version '0.37.0' 'new release checked';return $true}
    $result=Invoke-CuaInstall
    Assert-True $result.success 'update succeeded'
    Assert-Equal $result.version '0.37.0' 'installed version reported'
    Assert-Equal $result.previous '0.34.0' 'previous version reported'
    Assert-Equal ($script:Releases -join ',') '' 'only the stable release installed'
    Assert-Equal $script:Registered 1 'ordinary autostart registered once'
}
Test 'install puts the previous release back when the new one fails its check (mocked)' {
    function Get-CuaTask {return @{Principal=@{RunLevel='Limited';LogonType='Interactive'}}}
    function Test-CuaTaskOwner {param($Task,$Sid);return $true}
    $script:Releases=@();$script:Registered=0
    function Test-CuaDownloadFast {return $true}
    function Install-CuaRelease {param($Release);$script:Releases+=,$Release}
    function Register-CuaDaemonTask {param($Sid);$script:Registered++}
    function Get-CuaStatus {return @{ok=$true;version=$(if ($script:Releases.Count -eq 1) {'0.37.0'} else {'0.34.0'});permission_review_needed=$false}}
    function Test-CuaWorks {param($Version);return $Version -eq '0.34.0'}
    $result=Invoke-CuaInstall
    Assert-True (-not $result.success) 'update reported as failed'
    Assert-True $result.rolled_back 'previous release works again'
    Assert-Equal $result.failed_version '0.37.0' 'failed release named'
    Assert-Equal $result.version '0.34.0' 'previous release installed'
    Assert-Equal ($script:Releases -join ',') ',0.34.0' 'stable, then the previous release'
    Assert-Equal $script:Registered 2 'autostart registered for each release'
}
Test 'install that leaves the same version is not reported as an update (mocked)' {
    function Get-CuaTask {return @{Principal=@{RunLevel='Limited';LogonType='Interactive'}}}
    function Test-CuaTaskOwner {param($Task,$Sid);return $true}
    function Test-CuaDownloadFast {return $true}
    function Install-CuaRelease {param($Release)}
    function Register-CuaDaemonTask {throw 'MUST NOT REREGISTER'}
    function Get-CuaStatus {return @{ok=$true;version='0.34.0';permission_review_needed=$false}}
    $result=Invoke-CuaInstall
    Assert-True (-not $result.success) 'unchanged version is a failed update'
    Assert-Equal $result.version '0.34.0' 'installed version unchanged'
}
Test 'install stops before the installer when GitHub downloads are unreachable or too slow (mocked)' {
    function Get-CuaTask {return @{Principal=@{RunLevel='Limited';LogonType='Interactive'}}}
    function Test-CuaTaskOwner {param($Task,$Sid);return $true}
    function Get-CuaStatus {return @{ok=$true;version='0.34.0';permission_review_needed=$false}}
    function Test-CuaDownloadFast {return $false}
    function Install-CuaRelease {throw 'MUST NOT INSTALL'}
    $message=$null
    try {Invoke-CuaInstall} catch {$message=$_.Exception.Message}
    Assert-Equal $message 'Cua download failed; nothing was changed' 'blocked network reported before any change'
}
Test 'install refuses an invalid release before downloading (mocked)' {
    function New-PrivateDirectory {throw 'MUST NOT CREATE A DIRECTORY'}
    function Invoke-WebRequest {throw 'MUST NOT DOWNLOAD'}
    $message=$null
    try {Install-CuaRelease '0.34.0; calc'} catch {$message=$_.Exception.Message}
    Assert-Equal $message 'Cua installer failed; existing autostart was not changed' 'release refused'
}
Test 'install check accepts a running daemon on a locked desktop without the bridge (mocked)' {
    function Get-CuaStatus {return @{ok=$true;version='0.37.0';daemon_session_ids=@(1)}}
    function Get-CuaBridgeContext {return @{console=@{present=$true;sid='S-1';id=1;lock_state='locked'}}}
    function Ensure-CuaBridge {throw 'MUST NOT START THE BRIDGE'}
    Assert-True (Test-CuaWorks '0.37.0') 'locked desktop: daemon is enough'
}
Test 'check-update reports a missing driver (mocked)' {
    function Get-CuaExecutable {return $null}
    Assert-Equal (Invoke-CuaCheckUpdate).error 'not_installed' 'not installed'
}
Test 'clipboard chooses available bridge, and unavailable bridge falls back (mocked)' {
    function Get-CuaBridgeContext {return @{console=@{present=$true;sid='S-1';id=1;lock_state='unlocked'};sid='S-1';pipe='Miffan-offline';directory='C:\offline'}}
    function Test-CuaBridgeAvailable {param($Name);return $false}
    function Invoke-DesktopJob {param($Mode,$Text);Assert-Equal $Mode 'clip' 'interactive fallback';Assert-Equal $Text "中文`nEnglish" 'text untouched';return @{success=$true;verified=$true;transport='mock-interactive'}}
    $script:Ensured=0
    function Ensure-CuaBridge {$script:Ensured++;throw 'bridge did not start'}
    function Get-CuaStatus {return @{ok=$false}}
    Assert-Equal (Invoke-CuaClipboard "中文`nEnglish").transport 'mock-interactive' 'fallback selected without starting bridge'
    Assert-Equal $script:Ensured 0 'no bridge start without a usable cua-driver'
    function Get-CuaStatus {return @{ok=$true}}
    Assert-Equal (Invoke-CuaClipboard "中文`nEnglish").transport 'mock-interactive' 'failed on-demand start falls back before any write'
    Assert-Equal $script:Ensured 1 'bridge started on demand when cua-driver is usable'
    Assert-Throws {Invoke-CuaClipboard ([string][char]0)} 'NUL refused'
}

Test 'cua warm starts the bridge only when cua-driver is usable (mocked)' {
    $script:Ensured=0
    function Ensure-CuaBridge {$script:Ensured++;return @{}}
    function Get-CuaBridgeContext {return @{console=@{present=$true;sid='S-1';id=1;lock_state='unlocked'};sid='S-1';pipe='Miffan-offline';directory='C:\offline'}}
    function Test-CuaBridgeAvailable {param($Name);return $false}
    function Get-CuaStatus {return @{ok=$false}}
    Assert-Equal (Invoke-CuaWarm).ready $false 'unusable cua-driver is not warmed'
    Assert-Equal $script:Ensured 0 'no start without a usable cua-driver'
    function Get-CuaStatus {return @{ok=$true}}
    Assert-Equal (Invoke-CuaWarm).ready $true 'usable cua-driver warms the bridge'
    Assert-Equal $script:Ensured 1 'bridge started once'
    function Test-CuaBridgeAvailable {param($Name);return $true}
    Assert-Equal (Invoke-CuaWarm).detail 'Bridge already running' 'running bridge left alone'
    Assert-Equal $script:Ensured 1 'no second start'
}

Test 'stale bridge tasks of older helpers are removed, nothing else (mocked)' {
    $context=@{sid='S-1';task='Miffan-cua-S-1-1-v5'}
    function Test-CuaTaskOwner {param($Task,$Sid);return $Task.Principal.UserId -eq $Sid}
    function Get-ScheduledTask {param($TaskPath,$ErrorAction);return @(
        @{TaskName='Miffan-cua-S-1-1-v4';Principal=@{RunLevel='Limited';UserId='S-1'}},
        @{TaskName='Miffan-cua-S-1-1-v5';Principal=@{RunLevel='Limited';UserId='S-1'}},
        @{TaskName='Miffan-cua-S-2-1-v4';Principal=@{RunLevel='Limited';UserId='S-2'}},
        @{TaskName='Miffan-cua-S-1-1-v3';Principal=@{RunLevel='Highest';UserId='S-1'}},
        @{TaskName='cua-driver-serve';Principal=@{RunLevel='Limited';UserId='S-1'}})}
    $script:Removed=@()
    function Unregister-ScheduledTask {param($TaskName,$TaskPath,$Confirm,$ErrorAction);$script:Removed+=$TaskName}
    Remove-StaleCuaBridgeTasks $context
    Assert-Equal ($script:Removed -join ',') 'Miffan-cua-S-1-1-v4' 'only older Limited bridge tasks of this user'
}

Test 'daemon permission review rejects elevated, unknown or another-user runtime (mocked)' {
    function Test-CuaTaskOwner {param($Task,$Sid);return $Task.Principal.UserId -eq $Sid}
    $context=@{sid='test-user'}
    $task=@{Principal=@{RunLevel='Limited';UserId='test-user'}}
    Assert-True (-not (Test-CuaPermissionReview $task $context @(8192))) 'medium runtime admitted'
    Assert-True (Test-CuaPermissionReview $task $context @(12288)) 'high daemon refused even with Limited task'
    Assert-True (Test-CuaPermissionReview $task $context @(-1)) 'unknown daemon integrity refused'
    Assert-True (Test-CuaPermissionReview $task $context @(8192,12288)) 'mixed daemon permissions refused'
    $task.Principal.RunLevel='Highest'
    Assert-True (Test-CuaPermissionReview $task $context @(8192)) 'elevated autostart still reported'
    $task.Principal.RunLevel='Limited';$task.Principal.UserId='other-user'
    Assert-True (Test-CuaPermissionReview $task $context @(8192)) 'another user task requires review'
}

Test 'shared machine probe fixture with OS discovery mocked' {
    $oldArch=$env:PROCESSOR_ARCHITECTURE; $oldName=$env:COMPUTERNAME
    function Get-ConsoleSession { return @{present=$true;type='console';desktop='windows';id=1;user='fixture';domain='FIXTURE';sid='S-1-5-21-fixture';lock_state='unlocked'} }
    function Get-CuaStatus { return @{path=$null;installed=$false;version=$null;min='0.34.0';ok=$false;autostart_run_level=$null;daemon_session_ids=@();daemon_integrity_rids=@();bridge_available=$false;permission_review_needed=$false} }
    function Get-CimInstance { param($ClassName); if ($ClassName -eq 'Win32_OperatingSystem') {return @{Caption='Fixture Windows';Version='10.0';BuildNumber='fixture'}}; return @{Name='Fixture GPU'} }
    try {
        $env:PROCESSOR_ARCHITECTURE='AMD64';$env:COMPUTERNAME='Fixture Desktop'
        Assert-Fixture 'probe' (Invoke-Helper 'probe' '' '')
    } finally {$env:PROCESSOR_ARCHITECTURE=$oldArch;$env:COMPUTERNAME=$oldName}
}
Test 'shared Sunshine probe branches with read-only discovery mocked' {
    function Get-ConsoleSession {return @{id=1;lock_state='unlocked'}}
    function Get-StreamCandidates {return ,@('192.168.31.61')}
    function Get-NetTCPConnection {param($State);return @()}
    function Get-CertificateFingerprint {param($Pem);throw 'No certificate in this fixture'}
    function Get-SunshineContext {return $script:ContractContext}
    function Get-StreamActivity {param($Context);return @{active=$script:ContractBusy;known=$true}}
    foreach ($name in @('sunshine-missing','sunshine-running','sunshine-busy')) {
        $installed=$name -ne 'sunshine-missing';$script:ContractBusy=$name -eq 'sunshine-busy'
        $script:ContractContext=@{known=$installed;installed=$installed;version=$(if($installed){'fixture'}else{$null});config='C:\fixture\sunshine.conf';values=@{lan_encryption_mode='2';wan_encryption_mode='2'};service=$(if($installed){@{State='Running'}}else{$null});processes=$(if($installed){@(@{ProcessId=42;SessionId=1})}else{@()})}
        Assert-Fixture $name (Get-SunshineProbe)
    }
    Assert-Fixture 'sunshine-start' (Invoke-Helper 'sunshine' 'start' '')
    $script:ContractBusy=$false;$script:ContractContext.content='lan_encryption_mode=2'
    function Assert-RegularPath {param($Path)}
    Assert-Fixture 'sunshine-encryption' (Invoke-Helper 'sunshine' 'enforce-encryption' '')
}
Test 'shared firewall check review and result fixtures with mutation boundary mocked' {
    $sources=@('192.168.31.0/24')
    $safe=Rule 'Miffan-Sunshine-UDP-LAN' 'UDP' @('47998-48000') $sources
    $snapshot=@{rules=@($safe);profiles=@();profiles_safe=$true}
    Assert-Fixture 'firewall-check' (Get-FirewallReview $snapshot $exe $sources)
    Assert-Fixture 'firewall-review' (Get-FirewallReview @{rules=@();profiles=@();profiles_safe=$true} $exe $sources)
    $script:ContractSnapshot=$snapshot;$script:ContractReads=0
    function Get-SunshineContext {return @{installed=$true;known=$true;executable=$exe}}
    function Get-FirewallSnapshot {param($Executable);$script:ContractReads++;if($script:ContractReads -le 2){return @{rules=@();profiles=@();profiles_safe=$true}};return $script:ContractSnapshot}
    function Invoke-FirewallMutations {param($Plan);Assert-True $Plan.changed 'mocked mutation'}
    function Save-FirewallBackup {param($Plan,$Snapshot);return 'C:\fixture\backup'}
    $request=Read-FirewallRequest ([IO.File]::ReadAllText((Join-Path $FixturePath 'firewall-review.json')) | ConvertFrom-Json | Select-Object -ExpandProperty suggested_plan | ConvertTo-Json -Depth 10 -Compress)
    Assert-Fixture 'firewall-result' (Invoke-FirewallApply $request)
}
Test 'shared clipboard fallback and locked-console failure contracts' {
    function Get-CuaBridgeContext {return @{console=@{present=$true;sid='fixture';id=1;lock_state=$script:ContractLock};sid='fixture';pipe='fixture'}}
    function Test-CuaBridgeAvailable {param($Name);return $false}
    function Get-CuaStatus {return @{ok=$false}}
    function Invoke-DesktopJob {param($Mode,$Text);return @{success=$true;verified=$true;detail='Clipboard written and read back';session_id=1}}
    $script:ContractLock='unlocked'
    Assert-Fixture 'clipboard' (Invoke-Helper 'clip' '' '')
    $script:ContractLock='locked'
    try { $null=Invoke-Helper 'clip' '' '';throw 'Expected locked refusal' }
    catch {
        Assert-True ($_.Exception.Message -cin $script:SafeFailures) 'failure is exportable'
        Assert-Fixture 'clipboard-failed' @{success=$false;detail=$_.Exception.Message;error_type=$_.Exception.GetType().Name;backup=$null;cleanup_task=$null;cleanup_directory=$null}
    }
}
Test 'shared clipboard stream replies match C# producer literals' {
    $literals=[regex]::Matches($script:CuaBridgeSource,'"\{\\"success\\":(?:true|false),\\"verified\\":(?:true|false)\}"')
    Assert-Equal $literals.Count 2 'two C# stream reply branches'
    $names=@('clipboard-serve','clipboard-serve-failed')
    for($i=0;$i -lt 2;$i++) {
        $line=$literals[$i].Value.Trim('"').Replace('\"','"')
        Assert-Fixture $names[$i] (ConvertFrom-Json $line)
    }
}

[Console]::Out.WriteLine((ConvertTo-PlainJson @{success=$true;passed=$script:Passed;scope='offline; firewall/service/task mutations mocked'}))
