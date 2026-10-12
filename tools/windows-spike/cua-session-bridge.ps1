# Experiment-only file transport around a medium-IL MCP client. No desktop shims.
$ErrorActionPreference='Stop'
$dir=Join-Path $env:TEMP 'miffan-windows-spike\mcp-bridge'
$binary=Join-Path $env:LOCALAPPDATA 'Programs\Cua\cua-driver\bin\cua-driver.exe'
$info=New-Object Diagnostics.ProcessStartInfo
$info.FileName=$binary;$info.Arguments='mcp --socket \\.\pipe\cua-driver'
$info.UseShellExecute=$false;$info.CreateNoWindow=$true
$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
$info.StandardOutputEncoding=New-Object Text.UTF8Encoding($false)
$info.StandardErrorEncoding=New-Object Text.UTF8Encoding($false)
# Reading UTF-8 from the child must not fall back to Windows CP936.
$proc=[Diagnostics.Process]::Start($info)
$inputWriter=[IO.StreamWriter]::new($proc.StandardInput.BaseStream,[Text.UTF8Encoding]::new($false))
$inputWriter.AutoFlush=$true
$stderr=$proc.StandardError.ReadToEndAsync()
$deadline=(Get-Date).AddMinutes(30)
try{
    for($sequence=1;(Get-Date)-lt $deadline;$sequence++){
        $inputFile=Join-Path $dir "in-$sequence.json"
        while(-not(Test-Path $inputFile)){
            if($proc.HasExited -or (Test-Path (Join-Path $dir 'stop')) -or (Get-Date)-gt $deadline){return}
            Start-Sleep -Milliseconds 100
        }
        $body=[IO.File]::ReadAllText($inputFile,(New-Object Text.UTF8Encoding($false)))
        $inputWriter.WriteLine($body);$inputWriter.Flush()
        $request=$body|ConvertFrom-Json
        if($null -ne $request.id){
            $read=$proc.StandardOutput.ReadLineAsync()
            if(-not $read.Wait(40000)){throw 'MCP response timeout'}
            if($null -eq $read.Result){throw 'MCP process closed stdout'}
            $outputFile=Join-Path $dir "out-$sequence.json"
            [IO.File]::WriteAllText($outputFile+'.tmp',$read.Result,(New-Object Text.UTF8Encoding($false)))
            Move-Item ($outputFile+'.tmp') $outputFile
        }
    }
}finally{
    $inputWriter.Close()
    if(-not $proc.WaitForExit(10000)){$proc.Kill()}
    [IO.File]::WriteAllText((Join-Path $dir 'stderr.txt'),$stderr.Result)
}
