package me.ayuilos.miffan.ui.im

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

private val EDIT_LINES = listOf(
    R.string.im_edit_chip_short to R.string.im_edit_line_short,
    R.string.im_edit_chip_examples to R.string.im_edit_line_examples,
    R.string.im_edit_chip_friend to R.string.im_edit_line_friend,
    R.string.im_edit_chip_check to R.string.im_edit_line_check,
)

/** Name, look and personality on one page; nothing changes until Save, and leaving with edits asks first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImPartnerEditPage(assistantId: String, vm: ImPartnerVM = koinViewModel(key = assistantId, parameters = { parametersOf(Uuid.parse(assistantId)) })) {
    val nav = LocalNavController.current
    val partner by vm.assistant.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failure = stringResource(R.string.im_p5_failed)
    val original = partner ?: run { ImEmpty(stringResource(R.string.im_p5_missing_partner)); return }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var personality by rememberSaveable { mutableStateOf(original.systemPrompt) }
    var avatarJson by rememberSaveable { mutableStateOf(Json.encodeToString(Avatar.serializer(), original.avatar)) }
    val avatar = remember(avatarJson) { Json.decodeFromString(Avatar.serializer(), avatarJson) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original.name || personality != original.systemPrompt || avatar != original.avatar
    val leave = { if (dirty) confirmDiscard = true else nav.popBackStack() }
    BackHandler(enabled = dirty, onBack = leave)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.im_profile_edit)) },
                navigationIcon = { TextButton(onClick = leave) { Text(stringResource(R.string.chat_page_cancel)) } },
                actions = {
                    // The default partner may keep no name of its own and go on showing the fallback.
                    Button(enabled = (name.isNotBlank() || original.name.isBlank()) && !saving, modifier = Modifier.padding(end = 8.dp), onClick = {
                        saving = true
                        scope.launch {
                            try {
                                vm.update { it.copy(name = name.trim(), systemPrompt = personality, avatar = avatar) }
                                nav.popBackStack()
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { snackbar.showSnackbar(failure) }
                            finally { saving = false }
                        }
                    }) { Text(stringResource(R.string.chat_page_save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistantAvatar(name, avatar, Modifier.size(96.dp), onUpdate = { avatarJson = Json.encodeToString(Avatar.serializer(), it) })
                val miffan = avatar as? Avatar.Miffan
                if (miffan != null) ImPalettePicker(miffan.appearance.palette, { palette: MiffanPalette ->
                    val updated = miffan.copy(appearance = miffan.appearance.copy(palette = palette))
                    avatarJson = Json.encodeToString(Avatar.serializer(), updated)
                })
                Text(stringResource(R.string.im_edit_avatar_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.im_p5_name)) },
                placeholder = { if (original.name.isBlank()) Text(stringResource(R.string.assistant_page_default_assistant)) })
            Text(stringResource(R.string.im_p5_personality), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.im_edit_personality_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), minLines = 8,
                placeholder = { Text(stringResource(R.string.im_edit_personality_hint)) })
            Text(stringResource(R.string.im_edit_add_line), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EDIT_LINES.forEach { (chip, line) ->
                    val text = stringResource(line)
                    AssistChip(onClick = {
                        personality = if (personality.isBlank()) text else personality.trimEnd() + "\n" + text
                    }, label = { Text("+ " + stringResource(chip)) })
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.im_edit_discard_title)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; nav.popBackStack() }) {
            Text(stringResource(R.string.im_edit_discard), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.im_edit_keep)) } })
}
