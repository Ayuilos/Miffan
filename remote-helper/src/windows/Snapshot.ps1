# The screen page over a relay shows still pictures instead of a stream (snapshot mode). `snapshot serve`
# stays open for the whole screen: it first answers `ready PATH`, where cua-driver writes each capture,
# then reads one request line per picture, `QUALITY [SINCE]`, and answers with one header line, raw
# picture bytes after it, so nothing is base64 and no command starts per picture:
#   same DIGEST                the capture's MD5 equals SINCE; no picture follows
#   jpeg|png DIGEST LENGTH     then LENGTH bytes of the picture
#   error CODE                 no picture follows
# A capture is re-encoded as JPEG through System.Drawing, else sent as the PNG. EOF on stdin ends it.

function Get-SnapshotPaths {
    $dir = Join-Path $env:LOCALAPPDATA 'Miffan\snapshot'
    return @{ dir = $dir; png = (Join-Path $dir 'desktop.png') }
}

# One reply: the header line and the picture bytes, or $null bytes when no picture follows.
function Get-SnapshotReply([string]$Png, [string]$Quality, [string]$Since) {
    if (-not (Test-Path -LiteralPath $Png)) { return @{ header = 'error no_snapshot'; data = $null } }
    $bytes = [IO.File]::ReadAllBytes($Png)
    if ($bytes.Length -eq 0) { return @{ header = 'error no_snapshot'; data = $null } }
    $md5 = [Security.Cryptography.MD5]::Create()
    try { $digest = -join ($md5.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) } finally { $md5.Dispose() }
    if ($Since -and $Since -ceq $digest) { return @{ header = "same $digest"; data = $null } }
    $q = 55
    if (-not [int]::TryParse($Quality, [ref]$q)) { $q = 55 }
    $q = [Math]::Min(90, [Math]::Max(20, $q))
    $data = $bytes; $format = 'png'
    $image = $null; $source = $null; $output = $null; $parameters = $null
    try {
        Add-Type -AssemblyName System.Drawing
        $source = New-Object IO.MemoryStream (, $bytes)
        $image = [Drawing.Image]::FromStream($source)
        $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
        if ($codec) {
            $parameters = New-Object Drawing.Imaging.EncoderParameters 1
            $parameters.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]$q)
            $output = New-Object IO.MemoryStream
            $image.Save($output, $codec, $parameters)
            $data = $output.ToArray(); $format = 'jpeg'
        }
    } catch {
        $data = $bytes; $format = 'png'
    } finally {
        foreach ($disposable in @($parameters, $image, $source, $output)) { if ($disposable) { $disposable.Dispose() } }
    }
    return @{ header = "$format $digest $($data.Length)"; data = $data }
}

function Invoke-SnapshotServe {
    $paths = Get-SnapshotPaths
    $null = New-Item -ItemType Directory -Force -Path $paths.dir -ErrorAction Stop
    # Raw handles, as for clip serve: Windows OpenSSH's overlapped pipes can hang Console's own streams.
    Initialize-CuaBridge
    $stdin = [MiffanCuaBridge]::Standard(-10, [IO.FileAccess]::Read)
    $stdout = [MiffanCuaBridge]::Standard(-11, [IO.FileAccess]::Write)
    [MiffanCuaBridge]::WriteLine($stdout, 'ready ' + $paths.png)
    while ($true) {
        # EOF (the screen closed) or a broken pipe ends it.
        try { $line = [MiffanCuaBridge]::ReadLine($stdin, 4096) } catch { return }
        $words = $line.Trim() -split ' '
        $reply = $null
        try { $reply = Get-SnapshotReply $paths.png $words[0] $(if ($words.Count -gt 1) { $words[1] } else { '' }) }
        catch { $reply = @{ header = 'error io_error'; data = $null } }
        $head = [Text.Encoding]::ASCII.GetBytes($reply.header + "`n")
        $stdout.Write($head, 0, $head.Length)
        if ($null -ne $reply.data) { $stdout.Write($reply.data, 0, $reply.data.Length) }
        $stdout.Flush()
    }
}
