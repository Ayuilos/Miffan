package me.ayuilos.miffan.ui.pages.extensions.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import me.ayuilos.miffan.data.db.entity.RemoteHostEntity
import me.rerere.workspace.RemoteHostKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RemoteHostVerificationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun changedKeyRequiresExplicitConfirmationBeforeTrustAndTest() {
        val oldFingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val newFingerprint = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        val discovered = RemoteHostKey("ssh-ed25519", newFingerprint)
        var trustedFingerprint: String? = null
        var tested = false
        compose.setContent {
            MaterialTheme {
                RemoteHostVerificationDialog(
                    host = RemoteHostEntity(
                        id = "host-id",
                        name = "Dev machine",
                        host = "100.64.0.2",
                        port = 22,
                        username = "dev",
                        authType = "PASSWORD",
                        trustedHostKeySha256 = oldFingerprint,
                        createdAt = 0L,
                        updatedAt = 0L,
                    ),
                    discover = { it(Result.success(discovered)) },
                    trust = { fingerprint, callback ->
                        trustedFingerprint = fingerprint
                        callback(Result.success(true))
                    },
                    test = { callback ->
                        tested = true
                        callback(Result.success(true))
                    },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("主机密钥已变化。请先通过可信渠道核对新指纹，确认后才更新信任记录。")
            .assertIsDisplayed()
        compose.onNodeWithText(newFingerprint).assertIsDisplayed()
        compose.onNodeWithText("原已信任：$oldFingerprint").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(null, trustedFingerprint)
            assertFalse(tested)
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = File(instrumentation.targetContext.cacheDir, "remote-host-verification.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        compose.onNodeWithText("更新信任并测试").performClick()
        compose.onNodeWithText("主机可连接").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(newFingerprint, trustedFingerprint)
            assertTrue(tested)
        }
    }

    @Test
    fun trustedKeyTestsWithoutRetrustingAndFailedTestCanRetry() {
        val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        var trustCalls = 0
        var testCalls = 0
        var testCallback: ((Result<Boolean>) -> Unit)? = null
        compose.setContent {
            MaterialTheme {
                RemoteHostVerificationDialog(
                    host = RemoteHostEntity(
                        id = "host-id", name = "Dev machine", host = "100.64.0.2", port = 22,
                        username = "dev", authType = "PASSWORD", trustedHostKeySha256 = fingerprint,
                        createdAt = 0L, updatedAt = 0L,
                    ),
                    discover = { it(Result.success(RemoteHostKey("ssh-ed25519", fingerprint))) },
                    trust = { _, callback -> trustCalls++; callback(Result.success(true)) },
                    test = { callback -> testCalls++; testCallback = callback },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("测试连接").performClick()
        compose.onNodeWithText("正在测试连接…").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsNotEnabled()
        compose.onNodeWithText("测试连接").assertIsNotEnabled()
        compose.runOnIdle { testCallback?.invoke(Result.failure(IllegalStateException("连接失败"))) }
        compose.onNodeWithText("关闭").assertIsEnabled()
        compose.onNodeWithText("测试连接").assertIsEnabled().performClick()
        compose.runOnIdle { testCallback?.invoke(Result.success(true)) }
        compose.onNodeWithText("主机可连接").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, trustCalls)
            assertEquals(2, testCalls)
        }
    }

    @Test
    fun failedFingerprintDiscoveryCanRetryAndClose() {
        val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        var discoverCalls = 0
        var discoverCallback: ((Result<RemoteHostKey>) -> Unit)? = null
        var dismissCalls = 0
        compose.setContent {
            MaterialTheme {
                RemoteHostVerificationDialog(
                    host = RemoteHostEntity(
                        id = "host-id", name = "Dev machine", host = "100.64.0.2", port = 22,
                        username = "dev", authType = "PASSWORD", trustedHostKeySha256 = null,
                        createdAt = 0L, updatedAt = 0L,
                    ),
                    discover = { callback -> discoverCalls++; discoverCallback = callback },
                    trust = { _, _ -> }, test = { }, onDismiss = { dismissCalls++ },
                )
            }
        }

        compose.onNodeWithText("正在读取主机指纹…").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsNotEnabled()
        compose.runOnIdle { discoverCallback?.invoke(Result.failure(IllegalStateException("读取失败"))) }
        compose.onNodeWithText("重新读取指纹").performClick()
        compose.runOnIdle {
            assertEquals(2, discoverCalls)
            discoverCallback?.invoke(Result.success(RemoteHostKey("ssh-ed25519", fingerprint)))
        }
        compose.onNodeWithText(fingerprint).assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, dismissCalls) }
    }
}
