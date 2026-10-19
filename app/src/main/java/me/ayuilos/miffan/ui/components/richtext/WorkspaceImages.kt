package me.ayuilos.miffan.ui.components.richtext

import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Images the assistant left in a workspace (for example a screenshot taken over SSH) are named
 * by workspace paths such as `/workspace/shot.png`, which mean nothing on the phone. Replies are
 * rewritten to [WORKSPACE_IMAGE_SCHEME] URIs, and [WorkspaceImageFetcher] downloads the file
 * through the workspace (SSH for remote ones) when an image view asks for it.
 */
const val WORKSPACE_IMAGE_SCHEME = "miffan-workspace"

private const val MAX_WORKSPACE_IMAGE_BYTES = 25L * 1024 * 1024

/** Where a message's workspace paths point. [remoteRoot] also accepts real remote paths below it. */
data class WorkspaceImageContext(
    val workspaceId: String,
    val scopeId: String?,
    val remoteRoot: String?,
    /** Part of the cache key: a later reply that overwrites the same file shows the new image. */
    val messageId: String,
)

/** The workspace a reply's paths refer to: its tools' recorded target, else the assistant's binding. */
fun UIMessage.workspaceImageContext(assistant: Assistant?, fallbackWorkspaceId: String? = null): WorkspaceImageContext? {
    val target = parts.asSequence().filterIsInstance<UIMessagePart.Tool>().mapNotNull { it.workspaceTarget }.lastOrNull()
    if (target != null) return WorkspaceImageContext(target.workspaceId, target.scopeId, target.remoteRoot, id.toString())
    val workspaceId = assistant?.workspaceId?.toString() ?: fallbackWorkspaceId ?: return null
    return WorkspaceImageContext(workspaceId, assistant?.workspaceScopeId?.toString(), null, id.toString())
}

fun workspaceImageUri(context: WorkspaceImageContext, path: String): String {
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    val query = listOfNotNull("w" to context.workspaceId, context.scopeId?.let { "s" to it }, "p" to path, "m" to context.messageId)
        .joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    return "$WORKSPACE_IMAGE_SCHEME://file?$query"
}

/** True for paths that name a file in the workspace rather than on the phone or the web. */
fun WorkspaceImageContext.ownsPath(path: String): Boolean =
    path.startsWith("/workspace/") || remoteRoot?.let { root -> path.startsWith(root.trimEnd('/') + "/") } == true

private val MARKDOWN_IMAGE = Regex("""(!\[[^\]]*]\()(<?)(/[^)\s>]+)(>?)((?:\s+"[^"]*")?\))""")

private val MARKDOWN_LINK = Regex("""(?<!!)(\[[^\]\n]*]\()(<[^>\n]+>|[^)\s]+)((?:\s+"[^"]*")?\))""")
private val CODE_SPANS = Regex("```[\\s\\S]*?```|`[^`\n]*`")
private val LINK_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")
// A scheme-less link whose first segment ends like a web domain ("example.com/docs") is a website, not a file.
private val WEB_DOMAIN_ENDINGS = setOf(
    "com", "net", "org", "cn", "io", "ai", "dev", "app", "me", "co", "info", "edu", "gov", "xyz", "top", "jp", "uk", "de", "tw", "hk",
)

/**
 * Points Markdown images and links at workspace files through [workspaceImageUri]: images load through
 * [WorkspaceImageFetcher], links open the file preview. Web links and paths elsewhere are untouched.
 */
fun String.withWorkspaceFiles(context: WorkspaceImageContext?): String {
    if (context == null || !contains("](")) return this
    val images = MARKDOWN_IMAGE.replace(this) { match ->
        val path = match.groupValues[3]
        if (!context.ownsPath(path)) match.value
        else match.groupValues[1] + workspaceImageUri(context, path) + match.groupValues[5]
    }
    val code = CODE_SPANS.findAll(images).map { it.range }.toList()
    return MARKDOWN_LINK.replace(images) { match ->
        val path = context.linkedWorkspacePath(match.groupValues[2])
        if (path == null || code.any { match.range.first in it }) match.value
        else match.groupValues[1] + workspaceImageUri(context, path) + match.groupValues[3]
    }
}

/**
 * The workspace file a link destination names. Relative paths are relative to `/workspace`, as for the
 * workspace tools; `file://` and `sandbox:` prefixes some models add are dropped.
 */
internal fun WorkspaceImageContext.linkedWorkspacePath(destination: String): String? {
    val raw = destination.removeSurrounding("<", ">").trim().removePrefix("file://").removePrefix("sandbox:")
        .substringBefore('#').substringBefore('?')
    if (raw.isEmpty() || raw.startsWith("//") || LINK_SCHEME.containsMatchIn(raw)) return null
    var path = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(raw)
    if (!path.startsWith('/')) {
        val first = path.substringBefore('/')
        if (first.startsWith("www.", ignoreCase = true) || first.substringAfterLast('.', "").lowercase() in WEB_DOMAIN_ENDINGS) return null
        path = "/workspace/" + path.removePrefix("./")
    }
    if (path.split('/').any { it == "." || it == ".." }) return null
    return path.takeIf(::ownsPath)
}

/** Workspace paths embedded as images in [text], so published files are not shown twice. */
fun embeddedWorkspaceImagePaths(text: String, context: WorkspaceImageContext?): Set<String> {
    if (context == null) return emptySet()
    return MARKDOWN_IMAGE.findAll(text).map { it.groupValues[3] }.filter(context::ownsPath).toSet()
}

class WorkspaceImageFetcher(
    private val uri: coil3.Uri,
    private val options: Options,
    private val workspaces: () -> WorkspaceRepository,
    private val cacheDir: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri.toString())
        val workspaceId = requireNotNull(parsed.getQueryParameter("w")) { "Missing workspace" }
        val path = requireNotNull(parsed.getQueryParameter("p")) { "Missing path" }
        val scopeId = parsed.getQueryParameter("s")
        val file = File(cacheDir, sha256(uri.toString()))
        if (!file.exists()) {
            cacheDir.mkdirs()
            val partial = File(cacheDir, file.name + ".part")
            try {
                partial.outputStream().use { out ->
                    workspaces().exportRootfsArtifact(workspaceId, path, LimitedOutputStream(out, MAX_WORKSPACE_IMAGE_BYTES), scopeId)
                }
                check(partial.renameTo(file)) { "Could not cache $path" }
            } finally {
                partial.delete()
            }
        }
        SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    // Resolve the repository inside fetch() on IO, only for an uncached workspace image.
    class Factory(private val workspaces: () -> WorkspaceRepository, private val cacheDir: File) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == WORKSPACE_IMAGE_SCHEME) WorkspaceImageFetcher(data, options, workspaces, cacheDir) else null
    }

    private class LimitedOutputStream(out: OutputStream, private val limit: Long) : FilterOutputStream(out) {
        private var written = 0L
        override fun write(b: Int) { grow(1); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { grow(len); out.write(b, off, len) }
        private fun grow(count: Int) {
            written += count
            if (written > limit) throw IOException("Image is larger than ${limit / 1024 / 1024} MB")
        }
    }

    private companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
