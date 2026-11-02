# The screen page over a relay shows still pictures instead of a stream (snapshot mode): cua-driver
# writes each capture to `snapshot path`, and `snapshot encode QUALITY SINCE` sends it back as one
# JSON line. A capture whose MD5 equals SINCE answers {"same":true} and sends no picture; any other is
# re-encoded as JPEG through System.Drawing, else sent as the PNG. The picture is base64 in "data".

function Get-SnapshotPaths {
    $dir = Join-Path $env:LOCALAPPDATA 'Miffan\snapshot'
    return @{ dir = $dir; png = (Join-Path $dir 'desktop.png') }
}

function Invoke-Snapshot([string]$Action, [string]$Quality, [string]$Since) {
    $paths = Get-SnapshotPaths
    if ($Action -ceq 'path') {
        $null = New-Item -ItemType Directory -Force -Path $paths.dir -ErrorAction Stop
        return (ConvertTo-PlainJson @{ path = $paths.png })
    }
    if ($Action -cne 'encode') { throw 'Usage: snapshot path|encode QUALITY [SINCE]' }
    if (-not (Test-Path -LiteralPath $paths.png)) { return '{"error":"no_snapshot"}' }
    $bytes = [IO.File]::ReadAllBytes($paths.png)
    if ($bytes.Length -eq 0) { return '{"error":"no_snapshot"}' }
    $md5 = [Security.Cryptography.MD5]::Create()
    try { $digest = -join ($md5.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) } finally { $md5.Dispose() }
    if ($Since -and $Since -ceq $digest) { return '{"same":true,"digest":"' + $digest + '"}' }
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
    # Base64 needs no escaping; building the line directly keeps a large picture fast.
    return '{"digest":"' + $digest + '","format":"' + $format + '","data":"' + [Convert]::ToBase64String($data) + '"}'
}
