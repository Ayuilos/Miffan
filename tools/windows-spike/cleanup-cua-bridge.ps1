$ErrorActionPreference='Stop'
$task=Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -ErrorAction SilentlyContinue
if($task){
    if($task.Principal.RunLevel -ne 'Limited' -or $task.Actions.Arguments -notmatch 'miffan-windows-spike.+cua-session-bridge.ps1'){throw 'Unexpected task identity; refusing cleanup'}
    New-Item -ItemType File (Join-Path $env:TEMP 'miffan-windows-spike\mcp-bridge\stop') -Force|Out-Null
    $until=(Get-Date).AddSeconds(15)
    while((Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP').State -eq 'Running' -and (Get-Date)-lt $until){Start-Sleep -Milliseconds 200}
    if((Get-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP').State -eq 'Running'){throw 'Bridge still running; no forced stop'}
    Unregister-ScheduledTask -TaskName 'Miffan-Windows-Spike-MCP' -Confirm:$false
}
Write-Output 'Removed only temporary experiment MCP task; kept cua-driver-serve and daemon'
