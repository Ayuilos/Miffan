package me.ayuilos.miffan.data.repository

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.workspace.RemotePlatform

/** Only fixed operations belong here; all caller data is carried on stdin by RemoteShell. */
internal fun screenHelperCommand(platform: RemotePlatform, operation: String): String {
    require(operation.matches(Regex("[a-z]+(?: [a-z-]+){0,2}")))
    return if (platform == RemotePlatform.WINDOWS)
        "& \"\$env:LOCALAPPDATA\\Miffan\\bin\\miffan.ps1\" $operation"
    else "\"\$HOME/.miffan/bin/miffan\" $operation"
}

/** The helper's `space` command; [args] are fixed words and app-made Space names, checked by the callers. */
internal fun spaceHelperCommand(platform: RemotePlatform, args: List<String>): String {
    val words = args.joinToString(" ")
    return if (platform == RemotePlatform.WINDOWS) "& \"\$env:LOCALAPPDATA\\Miffan\\bin\\miffan.ps1\" space $words"
    else "\"\$HOME/.miffan/bin/miffan\" space $words"
}

internal val windowsHelperVersionCommand = """
    ${'$'}helper = Join-Path ${'$'}env:LOCALAPPDATA 'Miffan\bin\miffan.ps1'
    if (Test-Path -LiteralPath ${'$'}helper -PathType Leaf) { & ${'$'}helper version }
""".trimIndent()

/**
 * Uploads [script] over stdin and installs it. Binary upload retains the asset BOM; the replacement
 * happens in the same directory, retaining the old ACL.
 *
 * Windows OpenSSH hands the command an overlapped stdin pipe. A synchronous read of it (what
 * `[Console]::OpenStandardInput()` does) hangs for good once the reader catches up with the data in
 * flight, which with Win32-OpenSSH 10.0 happens to any upload over ~128 KiB, such as the helper. So stdin
 * is read as an asynchronous FileStream, each read with a deadline, exactly [script]'s size, and the result
 * must match its SHA-256. A stalled upload ends the process instead of leaving it waiting; temp files left
 * by earlier, stuck uploads are removed.
 */
internal fun windowsHelperInstallCommand(script: ByteArray): String {
    val sha256 = MessageDigest.getInstance("SHA-256").digest(script).joinToString("") { "%02X".format(it) }
    return """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}directory = Join-Path ${'$'}env:LOCALAPPDATA 'Miffan\bin'
    ${'$'}null = New-Item -ItemType Directory -Path ${'$'}directory -Force
    ${'$'}parent = Get-Item -LiteralPath ${'$'}directory -Force
    while (${'$'}parent) {
        if (${'$'}parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse helper parent is not allowed' }
        ${'$'}parent = ${'$'}parent.Parent
    }
    ${'$'}target = Join-Path ${'$'}directory 'miffan.ps1'
    if ((Test-Path -LiteralPath ${'$'}target) -and ((Get-Item -LiteralPath ${'$'}target -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Reparse helper is not allowed' }
    Get-ChildItem -LiteralPath ${'$'}directory -Filter 'miffan-*.tmp' -File -Force | Where-Object { ${'$'}_.LastWriteTime -lt (Get-Date).AddMinutes(-10) } |
        Remove-Item -Force -ErrorAction SilentlyContinue
    ${'$'}temp = Join-Path ${'$'}directory ('miffan-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        try {
            Add-Type -Namespace MiffanUpload -Name Std -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern System.IntPtr GetStdHandle(int n);'
            ${'$'}handle = New-Object Microsoft.Win32.SafeHandles.SafeFileHandle ([MiffanUpload.Std]::GetStdHandle(-10), ${'$'}false)
            ${'$'}stdin = New-Object IO.FileStream (${'$'}handle, [IO.FileAccess]::Read, 65536, ${'$'}true)
        } catch { ${'$'}stdin = [Console]::OpenStandardInput() }
        ${'$'}file = [IO.File]::Open(${'$'}temp, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            ${'$'}buffer = New-Object byte[] 65536; ${'$'}left = ${script.size}
            while (${'$'}left -gt 0) {
                ${'$'}pending = ${'$'}stdin.ReadAsync(${'$'}buffer, 0, [Math]::Min(${'$'}buffer.Length, ${'$'}left))
                if (-not ${'$'}pending.Wait(20000)) { throw 'Helper upload stalled' }
                ${'$'}read = ${'$'}pending.Result
                if (${'$'}read -le 0) { throw 'Helper upload ended early' }
                ${'$'}file.Write(${'$'}buffer, 0, ${'$'}read); ${'$'}left -= ${'$'}read
            }
            ${'$'}file.Flush(${'$'}true)
        } finally { ${'$'}file.Dispose() }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath ${'$'}temp).Hash -ne '$sha256') { throw 'Helper upload is corrupt' }
        if (Test-Path -LiteralPath ${'$'}target -PathType Leaf) { [IO.File]::Replace(${'$'}temp, ${'$'}target, [NullString]::Value) }
        else { [IO.File]::Move(${'$'}temp, ${'$'}target) }
    } finally { if (Test-Path -LiteralPath ${'$'}temp) { Remove-Item -LiteralPath ${'$'}temp -Force } }
""".trimIndent()
}

@Serializable
internal data class WindowsClipboardResult(val success: Boolean, val verified: Boolean = false, val detail: String)
internal fun parseWindowsClipboard(output: String): WindowsClipboardResult =
    Json { ignoreUnknownKeys = true }.decodeFromString(output.trim().lineSequence().last())

internal fun windowsStreamUnavailable(fallback: RemoteStreamFallback?): RemoteScreenUnavailableException {
    val failure = fallback ?: RemoteStreamFallback(RemoteStreamFallbackReason.OTHER)
    val detail = when (failure.reason) {
        RemoteStreamFallbackReason.UDP_UNREACHABLE -> "Sunshine UDP is unreachable. Use the same LAN, or Tailscale with UDP 47998–48000 allowed."
        RemoteStreamFallbackReason.SUNSHINE_MISSING -> "Install Sunshine on this Windows host."
        RemoteStreamFallbackReason.SUNSHINE_NOT_RUNNING -> "Start the Sunshine service on this Windows host."
        RemoteStreamFallbackReason.NOT_PAIRED -> "Pair this client with Sunshine before opening the Windows screen."
        RemoteStreamFallbackReason.ENCRYPTION_NOT_ENFORCED -> "Sunshine LAN and WAN encryption must both be set to 2."
        else -> "Windows Sunshine connection failed: ${failure.reason}."
    }
    return RemoteScreenUnavailableException(RemoteScreenProblem.STREAM_UNAVAILABLE,
        listOfNotNull(detail, failure.detail).joinToString(" "), streamFailure = failure)
}
