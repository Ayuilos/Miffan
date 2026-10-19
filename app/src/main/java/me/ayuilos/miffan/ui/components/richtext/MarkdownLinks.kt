package me.ayuilos.miffan.ui.components.richtext

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.dokar.sonner.ToastType
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster

private val CODE_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

// A URL ends at whitespace, at characters Markdown or tables give meaning to, and at CJK punctuation,
// which never appears unencoded in a real URL but often follows one directly in Chinese text.
private val BARE_URL_REGEX = Regex("""https?://[^\s<>"'`|\u3000-\u303F\uFF00-\uFFEF\u2018-\u201F]+""")
private val TRAILING_PUNCTUATION = ".,;:!?*_~"

/**
 * Marks bare URLs as explicit autolinks (`<url>`). GFM only links a bare URL after whitespace and takes
 * everything up to the next whitespace, so in Chinese text a URL right after a word was not a link at
 * all, and one followed by "，然后……" linked the rest of the sentence too.
 */
internal fun linkifyBareUrls(content: String): String {
    if (!content.contains("://")) return content
    val code = CODE_REGEX.findAll(content).map { it.range }.toList()
    return BARE_URL_REGEX.replace(content) { match ->
        val start = match.range.first
        val before = content.getOrNull(start - 1)
        // Already part of a link, an autolink, an HTML attribute, or a longer word: leave it to the parser.
        val free = before == null || before.isWhitespace() || before.code > 0x7F || before in "*_~"
        if (!free || code.any { start in it }) return@replace match.value
        val url = match.value.trimUrlEnd()
        if (url.length <= url.indexOf("://") + 3) match.value
        else "<$url>" + match.value.substring(url.length)
    }
}

/** Drops trailing sentence punctuation and closing brackets the URL did not open. */
private fun String.trimUrlEnd(): String {
    var end = length
    while (end > 0) {
        val c = this[end - 1]
        val unbalanced = (c == ')' && count(end) { it == '(' } < count(end) { it == ')' }) ||
            (c == ']' && count(end) { it == '[' } < count(end) { it == ']' })
        if (c in TRAILING_PUNCTUATION || unbalanced) end-- else break
    }
    return substring(0, end)
}

private inline fun String.count(end: Int, predicate: (Char) -> Boolean): Int = (0 until end).count { predicate(this[it]) }

/** What a tapped link should open: GFM links "www.example.com" without a scheme, which nothing opens as is. */
internal fun linkTarget(link: String): String {
    val trimmed = link.trim().removeSurrounding("<", ">")
    return if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
}

/** The file preview a workspace link (see [withWorkspaceFiles]) opens, or null for other links. */
private fun workspaceFilePreview(uri: String): Screen.WorkspaceFilePreview? {
    if (!uri.startsWith("$WORKSPACE_IMAGE_SCHEME://")) return null
    val parsed = Uri.parse(uri)
    val workspaceId = parsed.getQueryParameter("w") ?: return null
    val path = parsed.getQueryParameter("p") ?: return null
    return Screen.WorkspaceFilePreview(workspaceId, path, parsed.getQueryParameter("s"))
}

/**
 * Opens Markdown links: workspace files in the app's file preview, the rest outside it. When nothing on
 * the phone can open a link it says so instead of doing nothing.
 */
@Composable
internal fun rememberMarkdownUriHandler(): UriHandler {
    val base = LocalUriHandler.current
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    return remember(base, navigator, toaster, resources) {
        object : UriHandler {
            override fun openUri(uri: String) {
                workspaceFilePreview(uri)?.let { navigator.navigate(it); return }
                runCatching { base.openUri(linkTarget(uri)) }.onFailure {
                    toaster.show(resources.getString(R.string.markdown_link_cannot_open, uri), type = ToastType.Error)
                }
            }
        }
    }
}
