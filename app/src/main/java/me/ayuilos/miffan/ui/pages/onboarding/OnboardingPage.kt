package me.ayuilos.miffan.ui.pages.onboarding

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.ui.components.ui.MiffanMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun OnboardingPage(
    authService: OpenRouterAuthService = koinInject(),
) {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val authState by authService.state.collectAsStateWithLifecycle()
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()

    LaunchedEffect(settings.isNotConfigured()) {
        if (!settings.isNotConfigured()) {
            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
        }
    }

    val mascotState = when (authState) {
        OpenRouterAuthState.Idle -> MiffanMascotState.Idle
        OpenRouterAuthState.Authorizing -> MiffanMascotState.Thinking
        OpenRouterAuthState.Connected -> MiffanMascotState.Happy
        is OpenRouterAuthState.Error -> MiffanMascotState.Error
    }
    val authorizing = authState == OpenRouterAuthState.Authorizing

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
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MiffanMascot(
                state = mascotState,
                interactive = !authorizing,
                modifier = Modifier.size(168.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onboarding_page_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_page_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OpenRouter",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "openrouter/free",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_page_free_badge),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.onboarding_page_free_limit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        onClick = { authService.startAuthorization(languageTag) },
                        enabled = !authorizing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        if (authorizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            when {
                                authorizing -> stringResource(R.string.onboarding_page_openrouter_connecting)
                                authState is OpenRouterAuthState.Error -> stringResource(R.string.onboarding_page_retry)
                                else -> stringResource(R.string.onboarding_page_openrouter_button)
                            }
                        )
                    }
                    if (authorizing) {
                        TextButton(
                            onClick = authService::cancelAuthorization,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(R.string.onboarding_page_cancel))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = { navController.navigate(Screen.SettingProvider) },
                enabled = !authorizing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_page_manual_button))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_page_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
