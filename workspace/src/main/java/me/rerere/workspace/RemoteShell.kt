package me.rerere.workspace

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Base64

enum class RemotePlatform(val storageName: String) {
    POSIX("posix"), WINDOWS("windows");

    val supportsInteractiveTerminal: Boolean get() = this == POSIX

    companion object {
        /** Detection uses SFTP, before any shell command is dispatched. */
        fun detect(sftpHome: String): RemotePlatform =
            if (Regex("^/[A-Za-z]:(/.*)?$").matches(sftpHome)) WINDOWS else POSIX

        fun parse(value: String): RemotePlatform = entries.firstOrNull { it.storageName == value } ?: POSIX
    }
}

class RemoteTerminalUnsupportedException : UnsupportedOperationException(
    "Interactive terminals are not yet supported on Windows. Use single PowerShell commands instead.",
)

/** SFTP paths stay in the file layer; only command execution converts to native drive paths. */
object WindowsRemotePath {
    fun toLocal(sftpPath: String): String {
        require(RemotePlatform.detect(sftpPath) == RemotePlatform.WINDOWS) { "Expected an absolute Windows SFTP drive path" }
        return sftpPath.drop(1).replace('/', '\\').let { if (it.length == 2) "$it\\" else it }
    }

    fun toSftp(localPath: String): String {
        require(Regex("^[A-Za-z]:[\\\\/].*").matches(localPath)) { "Expected an absolute Windows drive path" }
        return "/" + localPath.replace('\\', '/')
    }

    fun workingDirectory(root: String, cwd: String): String =
        RemoteWorkspacePath(root).absolute(
            if (Regex("^[A-Za-z]:[\\\\/].*").matches(cwd)) toSftp(cwd) else cwd,
            allowRoot = true,
        )
}

internal data class RemoteShellRequest(val command: String, val stdin: InputStream?)

/** Fixed bootstrap only in argv. The two length-prefixed UTF-8 fields precede raw stdin. */
internal object RemoteShell {
    fun posixExecute(directory: String, command: String): String = "cd ${quote(directory)} || exit 1\n$command"
    fun posixTerminal(directory: String, persistent: Boolean): String = if (persistent) {
        "cd ${quote(directory)} || exit 1\nstty -echo 2>/dev/null || :\nexec env ENV= PS1= PS2= /bin/sh +i"
    } else "cd ${quote(directory)} || exit 1\nexec \"\${SHELL:-/bin/sh}\" -i"

    fun request(platform: RemotePlatform, directory: String, script: String, stdin: InputStream?): RemoteShellRequest =
        if (platform == RemotePlatform.POSIX) RemoteShellRequest(posixExecute(directory, script), stdin)
        else RemoteShellRequest(windowsCommand, SequenceInputStream(
            ByteArrayInputStream(windowsFrame(directory, script)), stdin ?: ByteArrayInputStream(byteArrayOf()),
        ))

    fun windowsFrame(directory: String, script: String): ByteArray {
        val cwd = WindowsRemotePath.toLocal(directory).toByteArray(Charsets.UTF_8)
        val code = script.toByteArray(Charsets.UTF_8)
        require(cwd.size <= 1_048_576 && code.size <= 1_048_576) { "PowerShell request exceeds frame limit" }
        return "${cwd.size}\n${code.size}\n".toByteArray(Charsets.US_ASCII) + cwd + code
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    // Read framing from the binary stream, with no buffered reader consuming script stdin.
    // Console.In is then a UTF-8 reader of the remaining raw data. Native pipeline input is
    // explicit: [Console]::In.ReadToEnd() | native.exe. Nothing interprets raw data as script.
    val windowsBootstrap: String = """
        ${'$'}ProgressPreference = 'SilentlyContinue'
        ${'$'}utf8 = New-Object System.Text.UTF8Encoding(${'$'}false)
        [Console]::InputEncoding = ${'$'}utf8
        [Console]::OutputEncoding = ${'$'}utf8
        ${'$'}OutputEncoding = ${'$'}utf8
        ${'$'}wire = [Console]::OpenStandardInput()
        function Read-Length {
            ${'$'}digits = ''
            while (${'$'}true) {
                ${'$'}b = ${'$'}wire.ReadByte()
                if (${'$'}b -eq 10) { break }
                if (${'$'}b -lt 48 -or ${'$'}b -gt 57 -or ${'$'}digits.Length -ge 7) { throw 'Invalid PowerShell request frame' }
                ${'$'}digits += [char]${'$'}b
            }
            if (${'$'}digits.Length -eq 0) { throw 'Missing frame length' }
            ${'$'}n = [int]${'$'}digits
            if (${'$'}n -gt 1048576) { throw 'PowerShell request exceeds frame limit' }
            return ${'$'}n
        }
        function Read-Field([int]${'$'}length) {
            ${'$'}bytes = New-Object byte[] ${'$'}length
            ${'$'}offset = 0
            while (${'$'}offset -lt ${'$'}length) {
                ${'$'}n = ${'$'}wire.Read(${'$'}bytes, ${'$'}offset, ${'$'}length - ${'$'}offset)
                if (${'$'}n -eq 0) { throw 'Incomplete PowerShell request frame' }
                ${'$'}offset += ${'$'}n
            }
            return ${'$'}utf8.GetString(${'$'}bytes)
        }
        try {
            ${'$'}cwdLength = Read-Length
            ${'$'}scriptLength = Read-Length
            ${'$'}cwd = Read-Field ${'$'}cwdLength
            ${'$'}code = Read-Field ${'$'}scriptLength
            [Console]::SetIn((New-Object System.IO.StreamReader(${'$'}wire, ${'$'}utf8)))
            Set-Location -LiteralPath ${'$'}cwd -ErrorAction Stop
            [Environment]::CurrentDirectory = (Get-Location).ProviderPath
            ${'$'}script = [ScriptBlock]::Create(${'$'}code)
            ${'$'}global:LASTEXITCODE = 0
            ${'$'}script:hadError = ${'$'}false
            & ${'$'}script 2>&1 | ForEach-Object {
                if (${'$'}_ -is [System.Management.Automation.ErrorRecord]) {
                    if (${'$'}_.FullyQualifiedErrorId -notlike 'NativeCommandError*') { ${'$'}script:hadError = ${'$'}true }
                    [Console]::Error.WriteLine(${'$'}_.ToString())
                } elseif (${'$'}_ -is [string]) {
                    [Console]::Out.WriteLine(${'$'}_)
                } else {
                    [Console]::Out.WriteLine((${'$'}_ | Out-String -Width 4096).TrimEnd("`r", "`n"))
                }
            }
            if (${'$'}LASTEXITCODE -ne 0) { exit ${'$'}LASTEXITCODE }
            if (${'$'}script:hadError) { exit 1 }
            exit 0
        } catch {
            [Console]::Error.WriteLine(${'$'}_.ToString())
            exit 1
        }
    """.trimIndent()

    val windowsCommand: String = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
        Base64.getEncoder().encodeToString(windowsBootstrap.toByteArray(Charsets.UTF_16LE))
}
