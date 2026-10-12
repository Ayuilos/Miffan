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

    /** Long-lived stdio: never format stdout or buffer the post-frame stdin. */
    // Read precisely the frame using event-based OVERLAPPED I/O. Do not buffer ahead or
    // bind stdin to IOCP: the stdio owner binds that inherited handle once after the frame.
    val windowsProcessBootstrap: String = """
${'$'}ProgressPreference='SilentlyContinue'
${'$'}ErrorActionPreference='Stop'
${'$'}utf8=New-Object Text.UTF8Encoding(${'$'}false,${'$'}true)
[Console]::InputEncoding=${'$'}utf8;[Console]::OutputEncoding=${'$'}utf8;${'$'}OutputEncoding=${'$'}utf8
Add-Type -TypeDefinition @'
using System;using System.IO;using System.Text;using System.Runtime.InteropServices;using System.Threading;
public static class MiffanFrame{
[DllImport("kernel32.dll")]static extern IntPtr GetStdHandle(int n);
[DllImport("kernel32.dll",SetLastError=true)]static extern bool ReadFile(IntPtr h,IntPtr b,int n,out int r,IntPtr o);
[DllImport("kernel32.dll",SetLastError=true)]static extern bool GetOverlappedResult(IntPtr h,IntPtr o,out int r,bool wait);
static byte[] Data(int n){if(n>1048576)throw new IOException();byte[] b=new byte[n];if(n==0)return b;var pin=GCHandle.Alloc(b,GCHandleType.Pinned);IntPtr o=Marshal.AllocHGlobal(32);using(var e=new EventWaitHandle(false,EventResetMode.ManualReset)){try{for(int p=0;p<n;){Marshal.Copy(new byte[32],0,o,32);Marshal.WriteIntPtr(o,IntPtr.Size*2+8,new IntPtr(e.SafeWaitHandle.DangerousGetHandle().ToInt64()|1));int r;if(!ReadFile(GetStdHandle(-10),IntPtr.Add(pin.AddrOfPinnedObject(),p),n-p,out r,o)){if(Marshal.GetLastWin32Error()!=997||!GetOverlappedResult(GetStdHandle(-10),o,out r,true))throw new IOException();}if(r==0)throw new EndOfStreamException();p+=r;}return b;}finally{pin.Free();Marshal.FreeHGlobal(o);}}}
static int Len(){int n=0,k=0;while(true){int c=Data(1)[0];if(c==10&&k>0)return n;if(c<48||c>57||++k>7)throw new IOException();n=n*10+c-48;}}
public static string[] Read(){int a=Len(),b=Len();var u=new UTF8Encoding(false,true);return new[]{u.GetString(Data(a)),u.GetString(Data(b))};}}
'@
try{${'$'}f=[MiffanFrame]::Read();Set-Location -LiteralPath ${'$'}f[0];[Environment]::CurrentDirectory=(Get-Location).ProviderPath;${'$'}global:LASTEXITCODE=0;& ([ScriptBlock]::Create(${'$'}f[1]));exit ${'$'}LASTEXITCODE}
catch{[Console]::Error.WriteLine('Remote stdio process failed');exit 1}
    """.trimIndent()

    val windowsProcessCommand: String = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
        Base64.getEncoder().encodeToString(windowsProcessBootstrap.toByteArray(Charsets.UTF_16LE))

    val windowsCommand: String = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
        Base64.getEncoder().encodeToString(windowsBootstrap.toByteArray(Charsets.UTF_16LE))
}
