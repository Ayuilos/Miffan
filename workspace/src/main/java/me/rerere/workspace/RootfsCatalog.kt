package me.rerere.workspace

data class RootfsArchiveSource(
    val version: String,
    val androidAbi: String,
    val url: String,
    val sha256: String,
    val format: RootfsInstaller.ArchiveFormat = RootfsInstaller.ArchiveFormat.TAR_GZ,
) {
    init {
        require(url.startsWith("https://")) { "Rootfs source must use HTTPS" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Rootfs SHA-256 must be lowercase hex" }
    }
}

/** Pinned Ubuntu Base artifacts supported by the native PRoot binaries packaged by the app. */
object RootfsCatalog {
    const val VERSION = "Ubuntu Base 24.04.4"

    private val ARM64 = RootfsArchiveSource(
        version = VERSION,
        androidAbi = "arm64-v8a",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
        sha256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
    )

    private val X86_64 = RootfsArchiveSource(
        version = VERSION,
        androidAbi = "x86_64",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
        sha256 = "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58",
    )

    fun forAndroidAbis(abis: List<String>): RootfsArchiveSource =
        abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                ARM64.androidAbi -> ARM64
                X86_64.androidAbi -> X86_64
                else -> null
            }
        } ?: error(
            "No pinned Rootfs is available for Android ABIs: " +
                abis.joinToString().ifBlank { "unknown" }
        )
}

data class RootfsInstallLimits(
    val maxDownloadBytes: Long = 128L * 1024 * 1024,
    val maxExtractedBytes: Long = 1024L * 1024 * 1024,
    val maxSingleEntryBytes: Long = 256L * 1024 * 1024,
    val maxMetadataEntryBytes: Long = 1024L * 1024,
    val maxEntries: Int = 100_000,
)
