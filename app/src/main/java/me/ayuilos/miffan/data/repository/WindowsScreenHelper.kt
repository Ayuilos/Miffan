package me.ayuilos.miffan.data.repository

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
 * Binary upload retains the asset BOM. Replace in the same directory, retaining the old ACL. Reads exactly
 * [size] bytes: Windows OpenSSH sometimes never delivers the end of stdin after a large upload (seen with
 * the 150 KB helper), and a copy to the end would then wait until the call times out.
 */
internal fun windowsHelperInstallCommand(size: Int): String = """
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
    ${'$'}temp = Join-Path ${'$'}directory ('miffan-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        ${'$'}file = [IO.File]::Open(${'$'}temp, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            ${'$'}stdin = [Console]::OpenStandardInput(); ${'$'}buffer = New-Object byte[] 65536; ${'$'}left = $size
            while (${'$'}left -gt 0) {
                ${'$'}read = ${'$'}stdin.Read(${'$'}buffer, 0, [Math]::Min(${'$'}buffer.Length, ${'$'}left))
                if (${'$'}read -le 0) { throw 'Helper upload ended early' }
                ${'$'}file.Write(${'$'}buffer, 0, ${'$'}read); ${'$'}left -= ${'$'}read
            }
            ${'$'}file.Flush(${'$'}true)
        } finally { ${'$'}file.Dispose() }
        if (Test-Path -LiteralPath ${'$'}target -PathType Leaf) { [IO.File]::Replace(${'$'}temp, ${'$'}target, [NullString]::Value) }
        else { [IO.File]::Move(${'$'}temp, ${'$'}target) }
    } finally { if (Test-Path -LiteralPath ${'$'}temp) { Remove-Item -LiteralPath ${'$'}temp -Force } }
""".trimIndent()

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
