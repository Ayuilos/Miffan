package me.ayuilos.miffan.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.ayuilos.miffan.BuildConfig

class AndroidUpdateEnvironment(context: Context) : UpdateEnvironment {
    private val context = context.applicationContext

    override val currentVersion: String = BuildConfig.VERSION_NAME
    override val currentVersionCode: Int = BuildConfig.VERSION_CODE.toInt()
    override val isPlayStore: Boolean by lazy { PlayStoreUtil.isInstalledFromPlayStore(this.context) }

    override val isUpgradeInstall: Boolean
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)

    override fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun isUnmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun notifyNewVersion(info: UpdateInfo) = UpdateDownloadNotifications.newVersion(context, info)

    override fun cancelNewVersionNotification() = UpdateDownloadNotifications.cancelNewVersion(context)
}
