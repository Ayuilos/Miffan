$ErrorActionPreference = 'Stop'
$rules = @(Get-NetFirewallRule -Direction Inbound | Where-Object {
    $_.DisplayName -match 'Sunshine|Miffan|cua-driver'
} | ForEach-Object {
    $rule = $_
    $port = $rule | Get-NetFirewallPortFilter
    $address = $rule | Get-NetFirewallAddressFilter
    $app = $rule | Get-NetFirewallApplicationFilter
    [pscustomobject]@{Name=$rule.Name; DisplayName=$rule.DisplayName; Enabled="$($rule.Enabled)";
        Action="$($rule.Action)"; Profile="$($rule.Profile)"; Protocol=$port.Protocol;
        LocalPort=$port.LocalPort; RemoteAddress=$address.RemoteAddress; Program=$app.Program}
})
[pscustomobject]@{
    Time=(Get-Date -Format o); Computer=$env:COMPUTERNAME; Home=$HOME;
    UserProfile=$env:USERPROFILE; CurrentDirectory=(Get-Location).Path;
    PowerShell=$PSVersionTable.PSVersion.ToString(); ProcessSession=(Get-Process -Id $PID).SessionId;
    Sessions=(@(quser.exe) -join "`n");
    GPU=@(Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,VideoModeDescription);
    Services=@(Get-CimInstance Win32_Service | Where-Object Name -match 'Sunshine|cua' | Select-Object Name,State,StartName,PathName);
    Processes=@(Get-Process -ErrorAction SilentlyContinue | Where-Object ProcessName -match '^sunshine|^cua-driver' | Select-Object Id,ProcessName,SessionId,Path);
    Rules=$rules;
    Profiles=@(Get-NetConnectionProfile | Select-Object InterfaceAlias,NetworkCategory);
    GitBash=(Test-Path 'C:\Program Files\Git\bin\bash.exe');
    Wsl=(Get-Command wsl.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source);
    SunshineInstalled=(Test-Path 'C:\Program Files\Sunshine');
    CuaInstalled=(Test-Path "$env:LOCALAPPDATA\Programs\trycua\cua-driver-rs\bin\cua-driver.exe");
    Winget=(Get-Command winget.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
} | ConvertTo-Json -Depth 6
