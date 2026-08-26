package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.ai.tools.WorkspaceArtifact
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import java.io.File
import java.security.MessageDigest

internal suspend fun WorkspaceRepository.exportArtifactToCache(
    context: Context,
    artifact: WorkspaceArtifact,
): File = withContext(Dispatchers.IO) {
    val safeName = artifact.name
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: "file"
    val key = MessageDigest.getInstance("SHA-256")
        .digest("${artifact.workspaceId}\u0000${artifact.path}".toByteArray())
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }
    val directory = File(context.cacheDir, "workspace_preview/$key").apply { mkdirs() }
    val file = File(directory, safeName)
    file.outputStream().use { output ->
        exportRootfsArtifact(artifact.workspaceId, artifact.path, output)
    }
    file
}

internal fun Context.openWorkspaceFileExternally(
    file: File,
    mimeType: String,
): Result<Unit> = runCatching {
    val uri = FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null))
}

internal fun Context.shareWorkspaceFile(
    file: File,
    mimeType: String,
): Result<Unit> = runCatching {
    val uri = FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null))
}

internal fun WorkspaceArtifact.parentDirectory(): String =
    location().relativePath.substringBeforeLast('/', missingDelimiterValue = "")
