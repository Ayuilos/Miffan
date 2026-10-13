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

internal val windowsHelperVersionCommand = """
    ${'$'}helper = Join-Path ${'$'}env:LOCALAPPDATA 'Miffan\bin\miffan.ps1'
    if (Test-Path -LiteralPath ${'$'}helper -PathType Leaf) { & ${'$'}helper version }
""".trimIndent()

/** Binary upload retains the asset BOM. Replace in the same directory, retaining the old ACL. */
internal val windowsHelperInstallCommand = """
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
        try { [Console]::OpenStandardInput().CopyTo(${'$'}file); ${'$'}file.Flush(${'$'}true) } finally { ${'$'}file.Dispose() }
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
