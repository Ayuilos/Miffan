package me.rerere.workspace

data class Workspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: WorkspaceShellStatus = WorkspaceShellStatus.DISABLED,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
)

enum class WorkspaceShellStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

enum class WorkspaceStorageArea {
    FILES,
    LINUX,
    HOME,
    TEMP,
    VAR_TEMP,
}

/**
 * Selects the host-side file view mounted at `/workspace` for one execution.
 *
 * A null id is the explicit compatibility mode for workspaces created before assistant scopes
 * existed. It continues to expose the historical `files/` directory without moving any data.
 * Non-null ids are stable opaque identities (normally an Assistant UUID), never display names.
 */
class WorkspaceScope private constructor(
    val id: String?,
) {
    val isLegacyWholeWorkspace: Boolean
        get() = id == null

    override fun equals(other: Any?): Boolean = other is WorkspaceScope && id == other.id

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String = id ?: "legacy-whole-workspace"

    companion object {
        val LEGACY_WHOLE_WORKSPACE = WorkspaceScope(null)

        fun assistant(id: String): WorkspaceScope {
            require(id.matches(SCOPE_ID_REGEX) && id != "." && id != "..") {
                "Invalid workspace scope id: $id"
            }
            return WorkspaceScope(id)
        }

        fun fromNullableId(id: String?): WorkspaceScope =
            id?.let(::assistant) ?: LEGACY_WHOLE_WORKSPACE

        private val SCOPE_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

data class WorkspaceScopeDirectories(
    val files: java.io.File,
    val home: java.io.File,
    val temp: java.io.File,
    val varTemp: java.io.File,
    val prootTemp: java.io.File,
)

enum class RootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

data class WorkspaceConfig(
    val maxReadBytes: Long = 512 * 1024,
    val maxWriteBytes: Long = 2 * 1024 * 1024,
    val maxListEntries: Int = 500,
    val maxSearchResults: Int = 100,
    val resourceLimits: WorkspaceResourceLimits = WorkspaceResourceLimits(),
)

data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class WorkspaceSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val resourceLimitExceeded: Boolean = false,
)
