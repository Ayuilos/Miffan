package me.ayuilos.miffan.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.markWhaleThemeSeen
import me.ayuilos.miffan.data.model.restoreWhaleThemeTrial
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.components.ui.CardGroup
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.hooks.rememberAmoledDarkMode
import me.ayuilos.miffan.ui.theme.CustomColors
import me.ayuilos.miffan.ui.theme.presets.WHALE_THEME_ID
import me.ayuilos.miffan.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var openingWhale by rememberSaveable { mutableStateOf(false) }
    var trialError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openingWhale, settings.assistantId, settings.themeId, settings.whaleThemeDiscovery) {
        if (openingWhale && settings.themeId == WHALE_THEME_ID &&
            settings.assistantId == settings.whaleThemeDiscovery.dedicatedAssistantId) {
            openingWhale = false
            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
        }
    }
    LaunchedEffect(settings.init) {
        if (!settings.init) vm.updateSettings { it.markWhaleThemeSeen() }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WhaleThemeCard(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    themeApplied = settings.themeId == WHALE_THEME_ID && !settings.dynamicColor,
                    enabled = !settings.init && !openingWhale,
                    onTryTheme = {
                        openingWhale = true
                        trialError = null
                        scope.launch {
                            try { vm.experienceWhaleTheme() }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                openingWhale = false
                                trialError = "暂时无法创建助手，请重试。"
                            }
                        }
                    },
                    onRestoreTheme = if (settings.whaleThemeDiscovery.previousAppearance != null) {
                        { vm.updateSettings { it.restoreWhaleThemeTrial() } }
                    } else null,
                    onApplyTheme = {
                        vm.updateSettings { current ->
                            current.copy(themeId = WHALE_THEME_ID, dynamicColor = false)
                        }
                    },

                )
            }
            trialError?.let { message -> item { Text(message) } }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                    )
                }
            }
            item {
                LauncherIconPicker()
            }
        }
    }
}
