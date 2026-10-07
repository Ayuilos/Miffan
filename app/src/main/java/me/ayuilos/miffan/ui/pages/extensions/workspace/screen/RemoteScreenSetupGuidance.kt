package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.repository.RemoteScreenProblem
import me.ayuilos.miffan.data.repository.RemoteScreenUnavailableException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

internal fun remoteScreenSetupError(resources: Resources, error: Throwable): String =
    when ((error as? RemoteScreenUnavailableException)?.problem) {
        RemoteScreenProblem.NO_WORKSPACE -> resources.getString(R.string.workspace_screen_no_workspace)
        RemoteScreenProblem.NO_GRAPHICAL_SESSION -> resources.getString(R.string.workspace_screen_no_session)
        RemoteScreenProblem.NOT_ENABLED, RemoteScreenProblem.BAD_ENDPOINT, RemoteScreenProblem.PASSWORD_MISSING ->
            resources.getString(R.string.workspace_screen_config_incomplete)
        RemoteScreenProblem.VNC_START_FAILED -> resources.getString(R.string.workspace_screen_start_failed)
        else -> error.localizedMessage ?: resources.getString(R.string.workspace_screen_connection_failed)
    }

/** Shared by the environment check and the screen failure panel so commands cannot drift. */
@Composable
internal fun RemoteScreenVncInstallGuidance(sessionType: String?, desktop: String? = null) {
    val server = when (sessionType) {
        "wayland" -> "wayvnc"
        "x11" -> "x11vnc"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (server == null) {
            Text(stringResource(R.string.workspace_screen_session_unsupported))
        } else {
            Text(stringResource(R.string.workspace_screen_install_vnc, server))
            RemoteScreenCopyCommand("Arch Linux", "sudo pacman -S $server")
            RemoteScreenCopyCommand("Debian / Ubuntu", "sudo apt install $server")
        }
        if (desktop?.contains("gnome", ignoreCase = true) == true ||
            desktop?.contains("kde", ignoreCase = true) == true) {
            Text(stringResource(R.string.workspace_screen_desktop_validation), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun RemoteScreenCopyCommand(label: String, command: String) {
    val context = LocalContext.current
    var copied by remember(command) { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(command, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, command))
            copied = true
        }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HugeIcons.Copy01, contentDescription = null)
                Text(stringResource(if (copied) R.string.workspace_screen_command_copied else R.string.workspace_screen_copy_command))
            }
        }
    }
}

@Composable
internal fun RemoteScreenLog(text: String, initiallyExpanded: Boolean = false) {
    var expanded by remember(text) { mutableStateOf(initiallyExpanded) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.workspace_screen_hide_output else R.string.workspace_screen_show_output))
        }
        if (expanded) {
            SelectionContainer {
                Text(text, Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()).padding(4.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
