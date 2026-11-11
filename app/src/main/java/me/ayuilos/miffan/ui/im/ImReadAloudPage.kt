package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.appsettings.displayName
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.setting.SettingVM
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel

/**
 * Easy chat's read aloud: whether replies are read out by themselves, what is skipped, and which voices are used.
 * The services themselves are set up on the full speech page.
 */
@Composable
fun ImReadAloudPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val display = settings.displaySetting
    val navController = LocalNavController.current
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_read_aloud)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("switches") {
                ImCardGroup {
                    ImSwitchRow(stringResource(R.string.im_read_auto), display.autoPlayTTSAfterGeneration, { on ->
                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(autoPlayTTSAfterGeneration = on)) }
                    }, supporting = stringResource(R.string.im_read_auto_desc))
                    ImCardDivider(start = 16.dp)
                    ImSwitchRow(stringResource(R.string.im_settings_read_quoted), display.ttsOnlyReadQuoted, { on ->
                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(ttsOnlyReadQuoted = on)) }
                    }, supporting = stringResource(R.string.im_read_quoted_desc))
                    ImCardDivider(start = 16.dp)
                    ImSwitchRow(stringResource(R.string.im_settings_skip_brackets), display.ttsOnlyReadOutsideBrackets, { on ->
                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(ttsOnlyReadOutsideBrackets = on)) }
                    }, supporting = stringResource(R.string.im_read_brackets_desc))
                }
            }
            item("services") {
                ImCardGroup {
                    ImListRow(HugeIcons.VolumeHigh, stringResource(R.string.im_read_voice), { navController.navigate(Screen.SettingSpeech()) },
                        trailing = { ImRowValue(readAloudServiceName(settings)) })
                    ImCardDivider()
                    ImListRow(HugeIcons.Mic01, stringResource(R.string.im_read_voice_input),
                        { navController.navigate(Screen.SettingSpeech(recognition = true)) },
                        trailing = {
                            ImRowValue(settings.asrProviders.firstOrNull { it.id == settings.selectedASRProviderId }?.displayName()
                                ?: stringResource(R.string.im_read_voice_input_none))
                        })
                }
            }
        }
    }
}

/** The read-aloud service in use, with the phone's own voice called that rather than by its blank name. */
@Composable
internal fun readAloudServiceName(settings: Settings): String {
    val service = settings.ttsProviders.firstOrNull { it.id == settings.selectedTTSProviderId }
    return when {
        service == null || (service is TTSProviderSetting.SystemTTS && service.name.isBlank()) -> stringResource(R.string.im_read_voice_system)
        else -> service.displayName()
    }
}
