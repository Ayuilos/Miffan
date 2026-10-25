package me.ayuilos.miffan.ui.im

import android.Manifest
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.PartnerAbility
import me.ayuilos.miffan.ui.components.ui.permission.PermissionInfo
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.PermissionState
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.ayuilos.miffan.utils.hasUsageStatsPermission
import me.ayuilos.miffan.utils.openUsageAccessSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Hourglass
import me.rerere.hugeicons.stroke.VolumeHigh

@get:StringRes
internal val PartnerAbility.title: Int
    get() = when (this) {
        PartnerAbility.ASK_USER -> R.string.im_ability_ask
        PartnerAbility.CALENDAR -> R.string.im_ability_calendar
        PartnerAbility.CLIPBOARD -> R.string.im_ability_clipboard
        PartnerAbility.SCREEN_TIME -> R.string.im_ability_screen_time
        PartnerAbility.READ_ALOUD -> R.string.im_ability_read_aloud
    }

@get:StringRes
internal val PartnerAbility.description: Int
    get() = when (this) {
        PartnerAbility.ASK_USER -> R.string.im_ability_ask_desc
        PartnerAbility.CALENDAR -> R.string.im_ability_calendar_desc
        PartnerAbility.CLIPBOARD -> R.string.im_ability_clipboard_desc
        PartnerAbility.SCREEN_TIME -> R.string.im_ability_screen_time_desc
        PartnerAbility.READ_ALOUD -> R.string.im_ability_read_aloud_desc
    }

internal val PartnerAbility.icon: ImageVector
    get() = when (this) {
        PartnerAbility.ASK_USER -> HugeIcons.BubbleChatQuestion
        PartnerAbility.CALENDAR -> HugeIcons.Calendar03
        PartnerAbility.CLIPBOARD -> HugeIcons.Clipboard
        PartnerAbility.SCREEN_TIME -> HugeIcons.Hourglass
        PartnerAbility.READ_ALOUD -> HugeIcons.VolumeHigh
    }

/**
 * Gets from Android what an ability needs before it is switched on (calendar permission, usage access),
 * then runs the switch. Usage access is granted in system settings, so it is checked again on return.
 */
@Stable
internal class AbilityGate(private val calendar: PermissionState, private val context: Context) {
    /** The ability waiting for Android, while its permission is being asked for. */
    var waitingFor by mutableStateOf<PartnerAbility?>(null)
        private set
    private var then: (() -> Unit)? = null

    fun ready(ability: PartnerAbility): Boolean = when (ability) {
        PartnerAbility.CALENDAR -> calendar.allPermissionsGranted
        PartnerAbility.SCREEN_TIME -> context.hasUsageStatsPermission()
        else -> true
    }

    fun request(ability: PartnerAbility, onReady: () -> Unit) {
        if (ready(ability)) {
            onReady()
            return
        }
        waitingFor = ability
        then = onReady
        when (ability) {
            PartnerAbility.CALENDAR -> calendar.requestPermissions()
            PartnerAbility.SCREEN_TIME -> context.openUsageAccessSettings()
            else -> Unit
        }
    }

    internal fun recheck() {
        calendar.updatePermissionStates()
        val ability = waitingFor ?: return
        if (ready(ability)) {
            val action = then
            waitingFor = null
            then = null
            action?.invoke()
        }
    }
}

@Composable
internal fun rememberAbilityGate(): AbilityGate {
    val context = LocalContext.current
    val calendar = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true,
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true,
            ),
        )
    )
    PermissionManager(permissionState = calendar)
    val gate = remember(calendar) { AbilityGate(calendar, context) }
    // The permission dialog and the usage access page both return through a resume.
    LifecycleResumeEffect(gate) {
        gate.recheck()
        onPauseOrDispose { }
    }
    LaunchedEffect(gate, calendar.allPermissionsGranted) { gate.recheck() }
    return gate
}
