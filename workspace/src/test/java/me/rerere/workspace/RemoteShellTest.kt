package me.rerere.workspace

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RemoteShellTest {
    @Test fun longLivedBootstrapHandsOffRawStdioWithoutFormattingOrReadAhead() {
        val bootstrap = RemoteShell.windowsProcessBootstrap
        assertFalse(bootstrap.contains("StreamReader"))
        assertFalse(bootstrap.contains("ForEach-Object"))
        assertFalse(bootstrap.contains("Out-String"))
        assertTrue(bootstrap.contains("GetOverlappedResult"))
        assertTrue(bootstrap.contains("MiffanFrame"))
        assertTrue(RemoteShell.windowsProcessCommand.length < 8191)
        assertEquals(bootstrap, String(Base64.getDecoder().decode(RemoteShell.windowsProcessCommand.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE))
    }

    @Test fun detectsPlatformWithoutCommands() {
        listOf("/C:/Users/Ekk", "/c:/中文 空格", "/Z:/", "/d:").forEach {
            assertEquals(RemotePlatform.WINDOWS, RemotePlatform.detect(it))
        }
        listOf("/home/user", "/Users/user", "/", "C:\\Users\\Ekk", "/C:foo").forEach {
            assertEquals(RemotePlatform.POSIX, RemotePlatform.detect(it))
        }
        assertFalse(RemotePlatform.WINDOWS.supportsInteractiveTerminal)
    }

    @Test fun convertsOnlyCommandPathsAndAcceptsDriveLetterCase() {
        val root = "/C:/Users/Ekk/中文 空格'"
        assertEquals("C:\\Users\\Ekk\\中文 空格'", WindowsRemotePath.toLocal(root))
        assertEquals("c:\\", WindowsRemotePath.toLocal("/c:"))
        assertEquals("/c:/Users/Ekk", WindowsRemotePath.toSftp("c:\\Users\\Ekk"))
        assertEquals("$root/子目录", WindowsRemotePath.workingDirectory(root, "c:\\Users\\Ekk\\中文 空格'\\子目录"))
        assertEquals("$root/子目录", WindowsRemotePath.workingDirectory(root, "/c:/Users/Ekk/中文 空格'/子目录"))
        // A session not rooted in a workspace starts commands in the account's home.
        assertEquals("C:\\Users\\ekk", WindowsRemotePath.toLocal(WindowsRemotePath.workingDirectory("/", "/C:/Users/ekk")))
        assertThrows(IllegalArgumentException::class.java) { WindowsRemotePath.workingDirectory(root, "D:\\other") }
        assertThrows(IllegalArgumentException::class.java) { WindowsRemotePath.workingDirectory(root, "../other") }
        assertThrows(IllegalArgumentException::class.java) { WindowsRemotePath.toLocal("/home/user") }
    }

    @Test fun bootstrapIsFixedUtf16AndRequestIsUtf8ByteFramedWithRawStdin() {
        val script = "Write-Output '中文';\nexit 7"
        val path = "/c:/中文 空格/O'Brien"
        val raw = "中文\n0\n数据".toByteArray()
        val request = RemoteShell.request(RemotePlatform.WINDOWS, path, script, raw.inputStream())
        assertEquals(RemoteShell.windowsBootstrap, String(Base64.getDecoder().decode(
            request.command.substringAfter("-EncodedCommand ")), Charsets.UTF_16LE))
        assertFalse(request.command.contains("中文"))
        assertFalse(request.command.contains("O'Brien"))
        val wire = requireNotNull(request.stdin)
        fun readLength(): Int {
            val text = StringBuilder()
            while (true) {
                val b = wire.read()
                if (b == 10) return text.toString().toInt()
                check(b >= 0)
                text.append(b.toChar())
            }
        }
        val cwdLength = readLength()
        val scriptLength = readLength()
        fun field(n: Int) = String(ByteArray(n).also { assertEquals(n, wire.read(it)) }, Charsets.UTF_8)
        assertEquals("c:\\中文 空格\\O'Brien", field(cwdLength))
        assertEquals(script, field(scriptLength))
        assertArrayEquals(raw, wire.readBytes())
        assertEquals(RemoteShell.windowsCommand, RemoteShell.request(RemotePlatform.WINDOWS, "/D:/", "different", null).command)
    }

    @Test fun posixCommandStringsAreByteForByteUnchanged() {
        val root = "/srv/中文 space/O'Brien"
        val command = "printf '%s' \"\$HOME\"\nexit 7"
        val quoted = "'/srv/中文 space/O'\\''Brien'"
        val stdin = "raw".byteInputStream()
        val request = RemoteShell.request(RemotePlatform.POSIX, root, command, stdin)
        assertArrayEquals("cd $quoted || exit 1\n$command".toByteArray(), request.command.toByteArray())
        assertSame(stdin, request.stdin)
        assertArrayEquals("cd $quoted || exit 1\n${RemoteShell.utf8Locale}\nstty -echo 2>/dev/null || :\nexec env ENV= PS1= PS2= /bin/sh +i".toByteArray(),
            RemoteShell.posixTerminal(root, true).toByteArray())
        assertArrayEquals("cd $quoted || exit 1\n${RemoteShell.utf8Locale}\nexec \"\${SHELL:-/bin/sh}\" -i".toByteArray(),
            RemoteShell.posixTerminal(root, false).toByteArray())
        assertArrayEquals("cd $quoted || exit 1\n${RemoteShell.utf8Locale}\nexec /bin/sh -c 'printf '\\''%s'\\'' \"\$HOME\"\nexit 7'".toByteArray(),
            RemoteTerminalCommandSpec(command, "").shellScript(root).toByteArray())
        assertThrows(RemoteTerminalUnsupportedException::class.java) {
            RemoteTerminalCommandSpec("Get-ChildItem", "").shellScript("/C:/Users/Ekk", RemotePlatform.WINDOWS)
        }
    }

    @Test fun clixmlErrorsBecomeReadableAndLiteralEscapesAreDecodedOnce() {
        val xml = "#< CLIXML\r\n<Objs><S S=\"Error\">中文 &amp; error_x000D__x000A_</S><S S=\"Error\">_x005F_x0041_</S></Objs>"
        assertEquals("中文 & error\r\n_x0041_", PowerShellOutput.readable(xml))
        assertEquals("plain error", PowerShellOutput.readable("plain error"))
        assertEquals("中文 stderr", PowerShellOutput.readable(
            "#< CLIXML\r\n中文 stderr<Objs><Obj S=\"progress\"><S>ignored progress</S></Obj></Objs>"))
        assertEquals("error\r\n", PowerShellOutput.readable("#< CLIXML\n<Objs><S S=\"Error\">error_x000D__x000A_</S><"))
    }
}
