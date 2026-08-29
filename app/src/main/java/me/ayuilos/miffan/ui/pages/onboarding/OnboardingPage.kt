package me.ayuilos.miffan.ui.pages.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthState
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterSavedKeyState
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.ui.components.ui.MiffanMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.miffanColors
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.theme.LocalDarkMode
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun OnboardingPage(
    authService: OpenRouterAuthService = koinInject(),
) {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val authState by authService.state.collectAsStateWithLifecycle()
    val savedKeyState by authService.savedKeyState.collectAsStateWithLifecycle()
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    var detailsVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.isNotConfigured()) {
        if (!settings.isNotConfigured()) {
            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
        } else {
            authService.checkExistingKey()
        }
    }

    val mascotState = when {
        authState is OpenRouterAuthState.Error ||
            savedKeyState == OpenRouterSavedKeyState.CheckFailed -> MiffanMascotState.Error
        authState == OpenRouterAuthState.Authorizing ||
            savedKeyState == OpenRouterSavedKeyState.Unchecked ||
            savedKeyState == OpenRouterSavedKeyState.Checking ||
            savedKeyState == OpenRouterSavedKeyState.Restoring -> MiffanMascotState.Thinking
        authState == OpenRouterAuthState.Connected ||
            savedKeyState is OpenRouterSavedKeyState.Valid -> MiffanMascotState.Happy
        else -> MiffanMascotState.Idle
    }
    val authorizing = authState == OpenRouterAuthState.Authorizing
    val checkingSavedKey = savedKeyState == OpenRouterSavedKeyState.Unchecked ||
        savedKeyState == OpenRouterSavedKeyState.Checking
    val restoringSavedKey = savedKeyState == OpenRouterSavedKeyState.Restoring
    val busy = authorizing || checkingSavedKey || restoringSavedKey
    val darkMode = LocalDarkMode.current
    val classicColors = MiffanPalette.CLASSIC.miffanColors()
    val classicContainerColor = if (darkMode) {
        lerp(classicColors.bowl, Color.Black, 0.72f)
    } else {
        classicColors.cueSurface
    }
    val classicInsetColor = if (darkMode) {
        lerp(classicColors.bowl, Color.Black, 0.52f)
    } else {
        classicColors.rice
    }
    val classicTextColor = if (darkMode) {
        classicColors.rice
    } else {
        lerp(classicColors.cueInk, Color.Black, 0.36f)
    }
    val classicButtonColor = lerp(classicColors.bowl, Color.Black, 0.18f)
    val classicLinkColor = if (darkMode) classicColors.rim else classicButtonColor
    val classicBorderColor = classicColors.rim.copy(alpha = if (darkMode) 0.62f else 0.38f)
    val connectionSummary = when (savedKeyState) {
        OpenRouterSavedKeyState.Unchecked,
        OpenRouterSavedKeyState.Checking -> R.string.onboarding_page_saved_key_checking
        OpenRouterSavedKeyState.Restoring -> R.string.onboarding_page_saved_key_restoring
        is OpenRouterSavedKeyState.Valid -> R.string.onboarding_page_saved_key_valid
        OpenRouterSavedKeyState.Invalid -> R.string.onboarding_page_saved_key_invalid
        OpenRouterSavedKeyState.CheckFailed -> R.string.onboarding_page_saved_key_check_failed
        OpenRouterSavedKeyState.Missing -> R.string.onboarding_page_openrouter_brief
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MiffanMascot(
                state = mascotState,
                interactive = !busy,
                modifier = Modifier.size(124.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_page_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.onboarding_page_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = classicContainerColor,
                    contentColor = classicTextColor,
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = classicBorderColor,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = classicInsetColor,
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_page_recommended_badge),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = classicTextColor,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = stringResource(R.string.onboarding_page_free_start_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = classicTextColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Text(
                        text = stringResource(connectionSummary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = classicTextColor.copy(alpha = 0.84f),
                    )

                    if (authState is OpenRouterAuthState.Error) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = stringResource(R.string.onboarding_page_error_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = (authState as OpenRouterAuthState.Error).message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            when (savedKeyState) {
                                is OpenRouterSavedKeyState.Valid -> authService.restoreFreeModel()
                                OpenRouterSavedKeyState.CheckFailed -> authService.checkExistingKey()
                                else -> authService.startAuthorization(languageTag)
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = classicButtonColor,
                            contentColor = classicColors.rice,
                            disabledContainerColor = classicButtonColor.copy(alpha = 0.62f),
                            disabledContentColor = classicColors.rice.copy(alpha = 0.82f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = classicColors.rice,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            when {
                                authorizing -> stringResource(R.string.onboarding_page_openrouter_connecting)
                                checkingSavedKey -> stringResource(R.string.onboarding_page_saved_key_checking_button)
                                restoringSavedKey -> stringResource(R.string.onboarding_page_saved_key_restoring_button)
                                savedKeyState is OpenRouterSavedKeyState.Valid -> {
                                    stringResource(R.string.onboarding_page_restore_free_model_button)
                                }
                                savedKeyState == OpenRouterSavedKeyState.CheckFailed -> {
                                    stringResource(R.string.onboarding_page_verify_saved_key_button)
                                }
                                savedKeyState == OpenRouterSavedKeyState.Invalid -> {
                                    stringResource(R.string.onboarding_page_reconnect_openrouter_button)
                                }
                                authState is OpenRouterAuthState.Error -> stringResource(R.string.onboarding_page_retry)
                                else -> stringResource(R.string.onboarding_page_openrouter_button)
                            }
                        )
                    }
                    if (
                        savedKeyState == OpenRouterSavedKeyState.Missing ||
                        savedKeyState == OpenRouterSavedKeyState.Invalid
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_page_privacy),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = classicTextColor.copy(alpha = 0.76f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (authorizing) {
                        TextButton(
                            onClick = authService::cancelAuthorization,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = classicLinkColor,
                            ),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(R.string.onboarding_page_cancel))
                        }
                    }
                    TextButton(
                        onClick = { detailsVisible = !detailsVisible },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = classicLinkColor,
                        ),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            stringResource(
                                if (detailsVisible) {
                                    R.string.onboarding_page_details_hide
                                } else {
                                    R.string.onboarding_page_details_show
                                }
                            )
                        )
                    }
                    AnimatedVisibility(visible = detailsVisible) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = classicInsetColor,
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_page_openrouter_about_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = classicTextColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_page_openrouter_about),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = classicTextColor.copy(alpha = 0.84f),
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_page_free_limit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = classicTextColor.copy(alpha = 0.76f),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { navController.navigate(Screen.SettingProvider) },
                enabled = !authorizing && !restoringSavedKey,
            ) {
                Text(stringResource(R.string.onboarding_page_manual_button))
            }
        }
    }
}
