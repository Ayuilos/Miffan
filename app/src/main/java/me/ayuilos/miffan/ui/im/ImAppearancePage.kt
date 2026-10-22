package me.ayuilos.miffan.ui.im

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.markWhaleThemeSeen
import me.ayuilos.miffan.data.model.restoreWhaleThemeTrial
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.hooks.rememberAmoledDarkMode
import me.ayuilos.miffan.ui.hooks.rememberColorMode
import me.ayuilos.miffan.ui.pages.setting.LauncherIconPicker
import me.ayuilos.miffan.ui.pages.setting.SettingVM
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.ui.theme.LocalDarkMode
import me.ayuilos.miffan.ui.theme.PresetThemes
import me.ayuilos.miffan.ui.theme.findThemeById
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.data.model.Avatar
import org.koin.androidx.compose.koinViewModel

/**
 * Easy chat's appearance: a live preview, light or dark, one colour, the whale character theme and the
 * launcher icon. Building a colour from scratch stays on the full theme page.
 */
@Composable
fun ImAppearancePage(vm: SettingVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var colorMode by rememberColorMode()
    var amoled by rememberAmoledDarkMode()
    val dark = LocalDarkMode.current
    val scope = rememberCoroutineScope()
    var openingWhale by rememberSaveable { mutableStateOf(false) }
    var trialError by rememberSaveable { mutableStateOf<String?>(null) }
    val trialFailed = stringResource(R.string.whale_trial_failed)
    val whaleName = stringResource(R.string.whale_name)
    // Trying the whale theme ends in a fresh chat with the whale partner, as on the full theme page.
    LaunchedEffect(openingWhale, settings.assistantId, settings.themeId, settings.whaleThemeDiscovery) {
        if (openingWhale && settings.themeId == WHALE_THEME_ID && settings.assistantId == settings.whaleThemeDiscovery.dedicatedAssistantId) {
            openingWhale = false
            nav.openFreshChat(settings)
        }
    }
    LaunchedEffect(settings.init) { if (!settings.init) vm.updateSettings { it.markWhaleThemeSeen() } }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_appearance)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("preview") { ImAppearancePreview() }
            item("mode") {
                ImSectionLabel(stringResource(R.string.im_appearance_mode))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        ColorMode.SYSTEM to R.string.im_appearance_mode_system,
                        ColorMode.LIGHT to R.string.im_appearance_mode_light,
                        ColorMode.DARK to R.string.im_appearance_mode_dark,
                    ).forEach { (mode, label) -> ImModeTile(mode, stringResource(label), colorMode == mode) { colorMode = mode } }
                }
                if (colorMode != ColorMode.LIGHT) {
                    Spacer(Modifier.height(10.dp))
                    ImCardGroup {
                        ImSwitchRow(stringResource(R.string.im_appearance_amoled), amoled, { amoled = it },
                            supporting = stringResource(R.string.im_appearance_amoled_desc))
                    }
                }
            }
            item("color") {
                ImSectionLabel(stringResource(R.string.im_appearance_color))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(Modifier.padding(16.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ImColorSwatch(
                            label = { Text(stringResource(R.string.im_appearance_wallpaper)) },
                            fill = Brush.sweepGradient(listOf(Color(0xFFD2693F), Color(0xFF6E8F4E), Color(0xFF3E5596), Color(0xFFC95C74), Color(0xFFD2693F))),
                            selected = settings.dynamicColor,
                        ) { vm.updateSettings { it.copy(dynamicColor = true) } }
                        val themes = PresetThemes.filter { it.id != WHALE_THEME_ID }.map { it.id } + settings.customThemes.map { it.id }
                        themes.mapNotNull { findThemeById(it, settings.customThemes) }.forEach { theme ->
                            ImColorSwatch(
                                label = theme.name,
                                fill = Brush.linearGradient(List(2) { theme.getColorScheme(dark).primary }),
                                selected = !settings.dynamicColor && settings.themeId == theme.id,
                            ) { vm.updateSettings { it.copy(themeId = theme.id, dynamicColor = false) } }
                        }
                    }
                    TextButton(onClick = { nav.navigate(Screen.SettingTheme) }, Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                        Text(stringResource(R.string.im_appearance_custom))
                    }
                }
            }
            item("whale") {
                ImSectionLabel(stringResource(R.string.im_appearance_role))
                Spacer(Modifier.height(8.dp))
                val applied = settings.themeId == WHALE_THEME_ID && !settings.dynamicColor
                val canRestore = settings.whaleThemeDiscovery.previousAppearance != null
                ImCardGroup {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AssistantAvatar(whaleName, Avatar.WhaleGirl(), Modifier.size(56.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(whaleName, style = MaterialTheme.typography.titleMedium)
                            Text(trialError ?: stringResource(R.string.im_appearance_whale_desc), style = MaterialTheme.typography.bodySmall,
                                color = if (trialError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            applied && canRestore -> FilledTonalButton(onClick = { vm.updateSettings { it.restoreWhaleThemeTrial() } }) {
                                Text(stringResource(R.string.im_appearance_whale_restore))
                            }
                            applied -> Text(stringResource(R.string.im_appearance_whale_in_use), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> FilledTonalButton(enabled = !settings.init && !openingWhale, onClick = {
                                openingWhale = true
                                trialError = null
                                scope.launch {
                                    try { vm.experienceWhaleTheme(whaleName) }
                                    catch (failure: Exception) {
                                        if (failure is CancellationException) throw failure
                                        openingWhale = false
                                        trialError = trialFailed
                                    }
                                }
                            }) { Text(stringResource(R.string.im_appearance_whale_try)) }
                        }
                    }
                }
            }
            item("icon") {
                Spacer(Modifier.height(8.dp))
                LauncherIconPicker()
            }
        }
    }
}

/** A partner's line and the owner's reply in the current colours, so every choice shows at once. */
@Composable
private fun ImAppearancePreview() {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantAvatar("", Avatar.Miffan(), Modifier.size(32.dp))
                Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)) {
                    Text(stringResource(R.string.im_appearance_preview_partner), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(color = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)) {
                    Text(stringResource(R.string.im_appearance_preview_me), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun RowScope.ImModeTile(mode: ColorMode, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.weight(1f).height(84.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).clip(RoundedCornerShape(18.dp)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            val light = Color.White
            val night = Color(0xFF2A2A33)
            Box(Modifier.size(40.dp, 26.dp).clip(RoundedCornerShape(8.dp)).border(1.5.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
                .background(when (mode) {
                    ColorMode.SYSTEM -> Brush.horizontalGradient(0.5f to light, 0.5f to night)
                    ColorMode.LIGHT -> Brush.linearGradient(List(2) { light })
                    ColorMode.DARK -> Brush.linearGradient(List(2) { night })
                }))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ImColorSwatch(label: @Composable () -> Unit, fill: Brush, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Column(
        Modifier.width(60.dp).clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(46.dp).border(2.dp, ring, CircleShape).padding(4.dp).clip(CircleShape).background(fill))
        ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { label() }
        }
    }
}
