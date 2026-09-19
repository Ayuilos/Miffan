package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.ayuilos.miffan.R
import me.ayuilos.miffan.testutils.workspaceUiText
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ayuilos.miffan.data.db.entity.SshKeyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SshKeyManagementUiTest {
    @get:Rule val compose = createComposeRule()

    private val key = SshKeyEntity(
        id = "key-id",
        name = "Phone key",
        algorithm = "ssh-ed25519",
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExamplePublicKey phone",
        fingerprint = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
    }

    @Test
    fun hostAuthModesAreVisibleAndSelectable() {
        compose.setContent {
            var mode by remember { mutableStateOf(HostAuthMode.PASSWORD) }
            MaterialTheme { RemoteHostAuthSelector(mode) { mode = it } }
        }

        compose.onNodeWithText(workspaceUiText(R.string.setting_provider_page_auth_method)).assertExists()
        compose.onNodeWithTag("auth_mode_password").assertIsSelected()
        compose.onNodeWithTag("auth_mode_saved_key").assertIsNotSelected().performClick().assertIsSelected()
        compose.waitForIdle()
        screenshot("remote-auth-selector.png")
        compose.onNodeWithTag("auth_mode_pasted_key").performClick().assertIsSelected()
    }

    @Test
    fun offlineGenerateFlowReturnsCreatedPublicKey() {
        var requestedName: String? = null
        var created: SshKeyEntity? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                SshKeyGenerateDialog(
                    existingNames = emptySet(),
                    onGenerate = { name, callback ->
                        requestedName = name
                        callback(Result.success(key))
                    },
                    onCreated = { created = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag("ssh_key_name").performTextInput("Phone key")
        compose.waitForIdle()
        screenshot("ssh-key-generate.png")
        compose.onNodeWithText(workspaceUiText(R.string.workspace_generate)).performClick()
        compose.runOnIdle {
            assertEquals("Phone key", requestedName)
            assertEquals(key, created)
            assertTrue(dismissed)
        }

    }

    @Test
    fun publicKeyCopyActionReturnsFullOpenSshLine() {
        var copied: String? = null
        compose.setContent {
            MaterialTheme {
                SshKeyPublicKeyDialog(key = key, onCopy = { copied = it }, onExport = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        screenshot("ssh-public-key-dialog.png")
        compose.onNodeWithText(workspaceUiText(R.string.workspace_copy_public_key)).performClick()
        compose.runOnIdle { assertEquals(key.publicKey, copied) }
    }

    @Test
    fun privateKeyIsHiddenUntilRequestedAndEncryptedExportIsDefault() {
        val privatePem = "-----BEGIN OPENSSH PRIVATE KEY-----\nexample\n-----END OPENSSH PRIVATE KEY-----"
        var exportedPassphrase: String? = null
        compose.setContent {
            MaterialTheme {
                SshKeyPrivateKeyDialog(
                    key = key,
                    deviceSecure = false,
                    exportBusy = false,
                    onView = { callback -> callback(Result.success(privatePem)) },
                    onExport = { exportedPassphrase = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(workspaceUiText(R.string.workspace_private_key_hidden)).assertExists()
        compose.onNodeWithText(privatePem).assertDoesNotExist()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_no_screen_lock_warning)).assertExists()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_export_private_key)).assertIsNotEnabled()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_view_private_key)).performClick()
        compose.onNodeWithText(privatePem).assertExists()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_hide_private_key)).performClick()
        compose.onNodeWithText(privatePem).assertDoesNotExist()
        compose.onNodeWithTag("private_backup_passphrase").performScrollTo().performTextInput("backup-secret")
        compose.onNodeWithTag("private_backup_confirm").performScrollTo().performTextInput("backup-secret")
        compose.onNodeWithText(workspaceUiText(R.string.workspace_export_private_key)).performClick()
        compose.runOnIdle { assertEquals("backup-secret", exportedPassphrase) }
    }

    @Test
    fun unencryptedBackupRequiresExplicitChoiceAndLateViewCallbackIsDiscarded() {
        var exported = false
        var exportedPassphrase: String? = "unset"
        var viewCallback: ((Result<String>) -> Unit)? = null
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) MaterialTheme {
                SshKeyPrivateKeyDialog(
                    key = key,
                    deviceSecure = true,
                    exportBusy = false,
                    onView = { callback -> viewCallback = callback },
                    onExport = { exported = true; exportedPassphrase = it },
                    onDismiss = { visible = false },
                )
            }
        }

        compose.onNodeWithText(workspaceUiText(R.string.workspace_unencrypted_backup)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_unencrypted_backup_warning)).assertExists()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_export_private_key)).performClick()
        compose.runOnIdle {
            assertTrue(exported)
            assertEquals(null, exportedPassphrase)
        }
        compose.onNodeWithText(workspaceUiText(R.string.workspace_view_private_key)).performClick()
        compose.onNodeWithText(workspaceUiText(R.string.workspace_close)).performClick()
        compose.runOnIdle { viewCallback?.invoke(Result.success("late-private-key")) }
        compose.onNodeWithText("late-private-key").assertDoesNotExist()
    }
}
