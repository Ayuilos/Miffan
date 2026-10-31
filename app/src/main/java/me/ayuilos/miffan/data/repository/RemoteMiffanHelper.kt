package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession

/** The same bundled helper and version checks serve screen setup and access revocation. */
internal class RemoteMiffanHelper(private val assets: AssetManager) {
    fun ensureInstalled(ssh: RemoteWorkspaceSession) {
        val windows = ssh.platform == RemotePlatform.WINDOWS
        val script = assets.open(if (windows) "remote/miffan.ps1" else "remote/miffan.sh").use { it.readBytes() }
        val version = requireNotNull(Regex(if (windows) "HelperVersion = (\\d+)" else "MIFFAN_HELPER_VERSION=(\\d+)")
            .find(script.toString(Charsets.UTF_8))?.groupValues?.get(1)) { "Helper script has no version" }
        val installed = ssh.execute(if (windows) windowsHelperVersionCommand else "\"\$HOME/.miffan/bin/miffan\" version 2>/dev/null", timeoutMillis = 10_000)
        if (installed.exitCode == 0 && !installed.timedOut && installed.stdout.trim() == version) return
        val command = if (windows) windowsHelperInstallCommand(script.size) else
            "mkdir -p \"\$HOME/.miffan/bin\" && cat > \"\$HOME/.miffan/bin/miffan.tmp\" && " +
                "chmod 755 \"\$HOME/.miffan/bin/miffan.tmp\" && mv -f \"\$HOME/.miffan/bin/miffan.tmp\" \"\$HOME/.miffan/bin/miffan\""
        val result = ssh.execute(command, timeoutMillis = 30_000, stdin = ByteArrayInputStream(script))
        check(result.exitCode == 0 && !result.timedOut) { "Could not install the Miffan helper: ${result.stderr.trim()}" }
    }
}
