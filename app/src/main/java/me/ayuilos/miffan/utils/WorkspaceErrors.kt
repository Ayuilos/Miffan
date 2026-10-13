package me.ayuilos.miffan.utils

import android.content.res.Resources
import me.ayuilos.miffan.R
import me.rerere.workspace.RemoteFileTimeoutException
import me.rerere.workspace.RemoteTerminalUnsupportedException
import me.rerere.workspace.SshKeyException

/** Translate owned errors; retain diagnostic text supplied by the OS or remote service. */
internal fun Throwable.workspaceErrorMessage(resources: Resources): String? = when (this) {
    is RemoteFileTimeoutException -> resources.getString(R.string.workspace_error_file_idle)
    is RemoteTerminalUnsupportedException -> resources.getString(R.string.workspace_terminal_windows_unsupported)
    is SshKeyException -> resources.getString(when (reason) {
        SshKeyException.Reason.EMPTY_PASSPHRASE -> R.string.workspace_error_key_passphrase_empty
        SshKeyException.Reason.EXPORT_FAILED -> R.string.workspace_error_key_export
        SshKeyException.Reason.INVALID_SIZE -> R.string.workspace_error_key_size
        SshKeyException.Reason.IMPORT_FAILED -> R.string.workspace_error_key_import
    })
    else -> localizedMessage
}
