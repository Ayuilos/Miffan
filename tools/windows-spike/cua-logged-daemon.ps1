# Experiment-only hidden launch with UTF-8 diagnostic capture. No approval bypass.
$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike'
$info=[Diagnostics.ProcessStartInfo]::new()
$info.FileName=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
$info.Arguments='serve';$info.UseShellExecute=$false;$info.CreateNoWindow=$true
$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
$info.StandardErrorEncoding=[Text.UTF8Encoding]::new($false)
$p=[Diagnostics.Process]::Start($info)
[IO.File]::WriteAllText((Join-Path $dir 'high-daemon-process.json'),(@{Pid=$p.Id;ParentPid=$PID;Session=(Get-Process -Id $PID).SessionId;Time=(Get-Date -Format o)}|ConvertTo-Json))
$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
$p.WaitForExit()
[IO.File]::WriteAllText((Join-Path $dir 'high-daemon-stdout.txt'),$stdout.Result,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $dir 'high-daemon-stderr.txt'),$stderr.Result,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $dir 'high-daemon-exit.txt'),[string]$p.ExitCode)
exit $p.ExitCode
