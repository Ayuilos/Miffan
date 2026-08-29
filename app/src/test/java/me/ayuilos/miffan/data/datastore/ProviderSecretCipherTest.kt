package me.ayuilos.miffan.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderSecretCipherTest {
    @Test
    fun `provider secret transformation includes nested overrides`() {
        val providers = listOf(
            ProviderSetting.OpenAI(
                apiKey = "api-key",
                codexCredentials = OpenAICodexCredentials(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    accountId = "account-id",
                ),
                models = listOf(
                    Model(
                        modelId = "nested-model",
                        providerOverwrite = ProviderSetting.Google(
                            apiKey = "google-key",
                            privateKey = "service-account-key",
                        ),
                    )
                ),
            )
        )

        val transformed = providers.transformProviderSecrets { "protected:$it" }
        val openAI = transformed.single() as ProviderSetting.OpenAI
        val nested = openAI.models.single().providerOverwrite as ProviderSetting.Google

        assertEquals("protected:api-key", openAI.apiKey)
        assertEquals("protected:access-token", openAI.codexCredentials?.accessToken)
        assertEquals("protected:refresh-token", openAI.codexCredentials?.refreshToken)
        assertEquals("account-id", openAI.codexCredentials?.accountId)
        assertEquals("protected:google-key", nested.apiKey)
        assertEquals("protected:service-account-key", nested.privateKey)
    }

    @Test
    fun `portable settings remove provider credentials`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    apiKey = "api-key",
                    codexCredentials = OpenAICodexCredentials(
                        accessToken = "access-token",
                        refreshToken = "refresh-token",
                        accountId = "account-id",
                    ),
                    models = listOf(
                        Model(
                            modelId = "nested-model",
                            providerOverwrite = ProviderSetting.Google(
                                apiKey = "google-key",
                                privateKey = "service-account-key",
                            ),
                        )
                    ),
                )
            ),
        )

        val safe = settings.withoutProviderSecrets()
        val openAI = safe.providers.single() as ProviderSetting.OpenAI
        val nested = openAI.models.single().providerOverwrite as ProviderSetting.Google

        assertEquals("", openAI.apiKey)
        assertNull(openAI.codexCredentials)
        assertEquals("", nested.apiKey)
        assertEquals("", nested.privateKey)
    }
}
