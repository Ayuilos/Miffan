package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import me.ayuilos.miffan.data.repository.WindowsSetupScript
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tick01

/** Windows: run in PowerShell to see the host key fingerprints the verify step shows. */
internal const val WINDOWS_FINGERPRINT_COMMAND =
    "Get-ChildItem \"\$env:ProgramData\\ssh\\ssh_host_*_key.pub\" | ForEach-Object { ssh-keygen -lf \$_.FullName }"

/**
 * Windows has no SSH server or authorized key by default, so the address step starts with the setup
 * script: the user runs it once as administrator and fills in what it prints. The phone's clipboard
 * rarely reaches the computer, so the script can also be sent to an app the user has on both.
 */
@Composable
internal fun WindowsSetupCard(key: SshKeyEntity?, keyPicker: @Composable () -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<String?>(null, key?.publicKey) {
        val publicKey = key?.publicKey ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { WindowsSetupScript.fromAssets(context.assets, publicKey) }.getOrNull() }
    }
    var copied by remember(loaded) { mutableStateOf(false) }
    SetupCard {
        Text(stringResource(R.string.im_computer_windows_setup_title), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowsSetupLine(1, stringResource(R.string.im_computer_windows_setup_admin))
            WindowsSetupLine(2, stringResource(R.string.im_computer_windows_setup_paste))
            WindowsSetupLine(3, stringResource(R.string.im_computer_windows_setup_fill))
        }
        val script = loaded
        if (script == null) {
            // Only while the first key is being made; an unusable key would be a bug, not a user error.
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@SetupCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Miffan Windows setup", script))
                copied = true
            }) {
                Icon(if (copied) HugeIcons.Tick01 else HugeIcons.Copy01, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (copied) R.string.im_computer_windows_script_copied else R.string.im_computer_windows_copy_script))
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Miffan Windows setup")
                    .putExtra(Intent.EXTRA_TEXT, script)
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(HugeIcons.Share08, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.im_computer_windows_send_script))
            }
        }
        Text(stringResource(R.string.im_computer_windows_send_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupDisclosure(stringResource(R.string.im_computer_windows_show_script)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                SelectionContainer {
                    Text(script, Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
            }
        }
        keyPicker()
    }
}

@Composable
private fun WindowsSetupLine(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$number.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
