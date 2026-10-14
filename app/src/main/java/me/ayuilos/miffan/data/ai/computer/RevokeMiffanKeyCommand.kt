package me.ayuilos.miffan.data.ai.computer

import me.rerere.workspace.RemotePlatform

/** The same single-line command is used over SSH and for offline manual cleanup. */
fun revokeMiffanKeyCommand(publicKey: String, platform: RemotePlatform): String {
    require(publicKey.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Expected one public key" }
    val fields = publicKey.trim().split(Regex("\\s+"), limit = 3)
    require(fields.size >= 2 && fields[0].matches(Regex("(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521))")) &&
        fields[1].matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "Invalid SSH public key" }
    return when (platform) {
        RemotePlatform.POSIX -> posixRevoke(fields[0], fields[1])
        RemotePlatform.WINDOWS -> windowsRevoke(fields[0], fields[1])
    }
}

private fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

private fun posixRevoke(type: String, key: String): String {
    // Tokenize an optional authorized_keys options field, including quoted spaces/escapes.
    val filter = """
        function matches(s, a) { split(s, a, /[ \t\r]+/); return a[1] == kt && a[2] == kb }
        { s = ${'$'}0; sub(/^[ \t]+/, "", s); remove = 0;
          if (substr(s, 1, 1) != "#") {
            remove = matches(s); quoted = 0; escaped = 0;
            for (i = 1; !remove && i <= length(s); i++) {
              c = substr(s, i, 1);
              if (escaped) { escaped = 0; continue }
              if (c == "\\") { escaped = 1; continue }
              if (c == "\"") { quoted = !quoted; continue }
              if (!quoted && c ~ /[ \t]/) { s = substr(s, i); sub(/^[ \t]+/, "", s); remove = matches(s); break }
            }
          }
          if (!remove) { if (kept) printf "%s\n", previous; previous = ${'$'}0; kept = 1; lastKept = NR }
        }
        END { if (kept) { printf "%s", previous; if (lastKept < NR || finalNewline) printf "\n" } }
    """.trimIndent().replace('\n', ' ')
    val script = """
        set -eu;
        if [ -f "${'$'}HOME/.miffan/bin/miffan" ]; then sh "${'$'}HOME/.miffan/bin/miffan" vnc stop >/dev/null; fi;
        if [ -f "${'$'}HOME/.miffan/rdp/krdp.owner" ] && command -v systemctl >/dev/null 2>&1; then
            state=${'$'}(systemctl --user show miffan-rdp-kde.service -p LoadState --value);
            if [ "${'$'}state" = loaded ]; then systemctl --user stop miffan-rdp-kde.service; fi;
        fi;
        rm -rf "${'$'}HOME/.miffan";
        f="${'$'}HOME/.ssh/authorized_keys";
        if [ -L "${'$'}HOME/.ssh" ] || [ -L "${'$'}f" ]; then echo 'Linked authorized_keys needs manual cleanup' >&2; exit 1; fi;
        if [ -f "${'$'}f" ]; then
            t=${'$'}(mktemp "${'$'}HOME/.ssh/.miffan-revoke.XXXXXX");
            trap 'rm -f "${'$'}t"' 0;
            trap 'exit 1' 1 2 15;
            cp -p "${'$'}f" "${'$'}t";
            finalNewline=0; [ "${'$'}(tail -c 1 "${'$'}f" | od -An -tu1 | tr -d ' ')" != 10 ] || finalNewline=1;
            awk -v kt=${shellQuote(type)} -v kb=${shellQuote(key)} -v finalNewline="${'$'}finalNewline" ${shellQuote(filter)} "${'$'}f" >"${'$'}t";
            if ! cmp -s "${'$'}t" "${'$'}f"; then mv -f "${'$'}t" "${'$'}f"; fi;
        fi
    """.trimIndent().replace('\n', ' ')
    // A subshell keeps traps, variables and set -e out of the user's interactive terminal.
    return "sh -c ${shellQuote(script)}"
}

private fun windowsRevoke(type: String, key: String): String {
    val pattern = windowsMiffanKeyPattern(type, key)
    val script = """
        & {
            ${'$'}ErrorActionPreference = 'Stop';
            ${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent();
            ${'$'}sid = ${'$'}identity.User.Value;
            ${'$'}root = Join-Path ${'$'}env:LOCALAPPDATA 'Miffan';
            ${'$'}helper = Join-Path ${'$'}root 'bin\miffan.ps1';
            ${'$'}pattern = '${pattern.replace("'", "''")}';
            ${'$'}paths = @(Join-Path ${'$'}env:USERPROFILE '.ssh\authorized_keys');
            if (@(${'$'}identity.Groups | Where-Object { ${'$'}_.Value -eq 'S-1-5-32-544' }).Count) { ${'$'}paths += Join-Path ${'$'}env:ProgramData 'ssh\administrators_authorized_keys' };
            ${'$'}files = @();
            try {
                foreach (${'$'}path in ${'$'}paths) {
                    if (-not (Test-Path -LiteralPath ${'$'}path)) { continue };
                    foreach (${'$'}p in @((Split-Path ${'$'}path), ${'$'}path)) {
                        if ((Get-Item -Force -LiteralPath ${'$'}p).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked key paths need manual cleanup' }
                    };
                    try { ${'$'}stream = [IO.File]::Open(${'$'}path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read) }
                    catch { throw ('Cannot edit authorized keys: ' + ${'$'}path + '. Run cleanup from an elevated PowerShell terminal. ' + ${'$'}_.Exception.Message) };
                    ${'$'}files += @{ stream = ${'$'}stream; path = ${'$'}path };
                    ${'$'}reader = New-Object IO.StreamReader(${'$'}stream, (New-Object Text.UTF8Encoding(${'$'}false, ${'$'}true)), ${'$'}true, 1024, ${'$'}true);
                    ${'$'}text = ${'$'}reader.ReadToEnd(); ${'$'}encoding = ${'$'}reader.CurrentEncoding; ${'$'}reader.Dispose();
                    if (${'$'}text.Contains([char]0)) { throw ('Unrecognized key file encoding: ' + ${'$'}path) };
                    ${'$'}stream.Position = 0; ${'$'}first = ${'$'}stream.ReadByte(); ${'$'}stream.Position = 0;
                    ${'$'}bom = @(); if (${'$'}first -eq 239 -or ${'$'}first -eq 255 -or ${'$'}first -eq 254) { ${'$'}bom = ${'$'}encoding.GetPreamble() };
                    ${'$'}files[-1].bytes = [byte[]](${'$'}bom + ${'$'}encoding.GetBytes([regex]::Replace(${'$'}text, ${'$'}pattern, '')))
                };
                foreach (${'$'}p in @(${'$'}root, (Split-Path ${'$'}root))) {
                    if ((Test-Path -LiteralPath ${'$'}p) -and ((Get-Item -Force -LiteralPath ${'$'}p).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Linked helper paths need manual cleanup' }
                };
                foreach (${'$'}task in @(Get-ScheduledTask -TaskPath '\')) {
                    ${'$'}name = [string]${'$'}task.TaskName; ${'$'}owner = [string]${'$'}task.Principal.UserId;
                    if (${'$'}owner -ne ${'$'}sid) { try { ${'$'}owner = (New-Object Security.Principal.NTAccount -ArgumentList ${'$'}owner).Translate([Security.Principal.SecurityIdentifier]).Value } catch { continue } };
                    if (${'$'}owner -ne ${'$'}sid -or [string]${'$'}task.Principal.RunLevel -ne 'Limited' -or [string]${'$'}task.Principal.LogonType -ne 'Interactive' -or ${'$'}task.Actions.Count -ne 1) { continue };
                    ${'$'}arguments = [string]${'$'}task.Actions[0].Arguments;
                    ${'$'}owned = ${'$'}false;
                    if (${'$'}name -cmatch '^Miffan-(?:display|clip)-([a-f0-9]{32})${'$'}') {
                        ${'$'}worker = Join-Path ${'$'}root ('job-' + ${'$'}Matches[1] + '\worker.ps1');
                        ${'$'}owned = ${'$'}arguments.Contains('-File "' + ${'$'}worker + '" ')
                    } elseif (${'$'}name -cmatch ('^Miffan-cua-' + [regex]::Escape(${'$'}sid) + '-[0-9]+-v[0-9]+${'$'}')) {
                        ${'$'}owned = ${'$'}arguments.EndsWith('-File "' + ${'$'}helper + '" cua bridge', [StringComparison]::Ordinal)
                    } elseif (${'$'}name -ceq 'cua-driver-serve') {
                        ${'$'}owned = ${'$'}arguments.EndsWith('-File "' + ${'$'}helper + '" cua daemon', [StringComparison]::Ordinal)
                    };
                    if (${'$'}owned) { Stop-ScheduledTask -InputObject ${'$'}task; Unregister-ScheduledTask -InputObject ${'$'}task -Confirm:${'$'}false }
                };
                if (Test-Path -LiteralPath ${'$'}root) { Remove-Item -LiteralPath ${'$'}root -Recurse -Force };
                foreach (${'$'}file in ${'$'}files) {
                    ${'$'}file.stream.Position = 0; ${'$'}file.stream.Write(${'$'}file.bytes, 0, ${'$'}file.bytes.Length); ${'$'}file.stream.SetLength(${'$'}file.bytes.Length); ${'$'}file.stream.Flush()
                }
            } finally { foreach (${'$'}file in ${'$'}files) { ${'$'}file.stream.Dispose() } }
        }
    """.trimIndent().replace('\n', ' ')
    return script
}

/** .NET and JVM compatible regex; only a full key token matches, never a comment. */
internal fun windowsMiffanKeyPattern(type: String, key: String): String {
    fun escape(value: String) = value.map { if (it in "\\.^${'$'}|?*+()[]{}") "\\$it" else "$it" }.joinToString("")
    return """(?m)^[ \t]*(?![ \t]*#)(?:(?:[^\s"]|"(?:[^"\\\r\n]|\\[^\r\n])*")+[ \t]+)?""" +
        escape(type) + """[ \t]+""" + escape(key) + """(?:[ \t]+[^\r\n]*)?\r?(?:\n|${'$'})"""
}
