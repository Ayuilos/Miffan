package me.ayuilos.miffan.data.repository

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import me.rerere.workspace.RemotePlatform
import me.rerere.workspace.RemoteWorkspaceSession

private const val INSTALL_TIMEOUT_MILLIS = 30_000L

/** The same bundled helper and version checks serve screen setup and access revocation. */
internal class RemoteMiffanHelper(private val assets: AssetManager) {
    fun ensureInstalled(ssh: RemoteWorkspaceSession) {
        val windows = ssh.platform == RemotePlatform.WINDOWS
        val script = assets.open(if (windows) "remote/miffan.ps1" else "remote/miffan.sh").use { it.readBytes() }
        val version = requireNotNull(Regex(if (windows) "HelperVersion = (\\d+)" else "MIFFAN_HELPER_VERSION=(\\d+)")
            .find(script.toString(Charsets.UTF_8))?.groupValues?.get(1)) { "Helper script has no version" }
        if (installedVersion(ssh, windows) == version) return
        // Opening a screen starts several helper calls at once; only one of them uploads.
        installs.withLock {
            if (installedVersion(ssh, windows) == version) return
            val command = if (windows) windowsHelperInstallCommand(script) else
                "mkdir -p \"\$HOME/.miffan/bin\" && cat > \"\$HOME/.miffan/bin/miffan.tmp\" && " +
                    "chmod 755 \"\$HOME/.miffan/bin/miffan.tmp\" && mv -f \"\$HOME/.miffan/bin/miffan.tmp\" \"\$HOME/.miffan/bin/miffan\""
            val result = ssh.execute(command, timeoutMillis = INSTALL_TIMEOUT_MILLIS, stdin = ByteArrayInputStream(script))
            check(!result.timedOut) { "Could not install the Miffan helper: timed out after ${INSTALL_TIMEOUT_MILLIS / 1000} s" }
            check(result.exitCode == 0) {
                "Could not install the Miffan helper: " + result.stderr.trim().ifBlank { "exit code ${result.exitCode}" }
            }
        }
    }

    private fun installedVersion(ssh: RemoteWorkspaceSession, windows: Boolean): String? {
        val installed = ssh.execute(if (windows) windowsHelperVersionCommand else "\"\$HOME/.miffan/bin/miffan\" version 2>/dev/null", timeoutMillis = 10_000)
        return installed.stdout.trim().takeIf { installed.exitCode == 0 && !installed.timedOut }
    }

    private companion object {
        /** Shared by every helper instance; installs are rare and short, so one lock for all computers is enough. */
        val installs = ReentrantLock()
    }
}
