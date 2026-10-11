package me.ayuilos.miffan.utils

/**
 * UI-facing state of the in-app APK download. There is at most one update download
 * app-wide; [UpdateChecker.downloadState] exposes it.
 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    /** [totalBytes] is null when neither the release metadata nor Content-Length gives a size. */
    data class Downloading(
        val download: UpdateDownload,
        val sourceHost: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateDownloadState

    data class Verifying(val download: UpdateDownload) : UpdateDownloadState

    /** The verified APK is on disk; [UpdateChecker.installUpdate] hands it to the system installer. */
    data class Ready(val download: UpdateDownload) : UpdateDownloadState

    /** Every source was tried. Starting the same download again retries from the first source. */
    data class Failed(
        val download: UpdateDownload,
        val error: UpdateDownloadError,
    ) : UpdateDownloadState
}

enum class UpdateDownloadError {
    /** Connection, timeout or HTTP failure on the last source tried. */
    Network,

    /** The file did not match the published SHA-256, size or package name. */
    Verification,

    /** The APK could not be written to app storage (for example, the disk is full). */
    Storage,
}
