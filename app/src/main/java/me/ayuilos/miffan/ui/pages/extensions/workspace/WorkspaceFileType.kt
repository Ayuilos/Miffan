package me.ayuilos.miffan.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 交给系统应用 (视频/音频/文档等) 打开
 */
enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

enum class WorkspacePreviewKind {
    TEXT,
    MARKDOWN,
    JSON,
    DELIMITED_TEXT,
    HTML,
    IMAGE,
    PDF,
    DOCUMENT_TEXT,
    EXTERNAL,
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

private val EXTENSIONLESS_TEXT_FILE_NAMES = setOf(
    "dockerfile", "makefile", "license", "readme", "notice", "changelog", "procfile",
)

private val DOCUMENT_TEXT_EXTENSIONS = setOf("docx", "pptx", "epub")

fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    val ext = name.workspaceExtension()
    return when {
        name.lowercase() in EXTENSIONLESS_TEXT_FILE_NAMES -> WorkspaceFileType.TEXT
        ext in IMAGE_EXTENSIONS -> WorkspaceFileType.IMAGE
        ext in TEXT_EXTENSIONS -> WorkspaceFileType.TEXT
        else -> WorkspaceFileType.OTHER
    }
}

fun detectWorkspacePreviewKind(fileName: String, mimeType: String? = null): WorkspacePreviewKind {
    val extension = fileName.workspaceExtension()
    val normalizedMime = mimeType?.substringBefore(';')?.lowercase().orEmpty()
    return when {
        extension in IMAGE_EXTENSIONS || normalizedMime.startsWith("image/") -> WorkspacePreviewKind.IMAGE
        extension == "pdf" || normalizedMime == "application/pdf" -> WorkspacePreviewKind.PDF
        extension in DOCUMENT_TEXT_EXTENSIONS -> WorkspacePreviewKind.DOCUMENT_TEXT
        extension in setOf("md", "markdown") || normalizedMime == "text/markdown" -> WorkspacePreviewKind.MARKDOWN
        extension in setOf("json", "json5") || normalizedMime == "application/json" -> WorkspacePreviewKind.JSON
        extension in setOf("csv", "tsv") || normalizedMime in setOf("text/csv", "text/tab-separated-values") -> {
            WorkspacePreviewKind.DELIMITED_TEXT
        }
        extension in setOf("html", "htm") || normalizedMime == "text/html" -> WorkspacePreviewKind.HTML
        fileName.lowercase() in EXTENSIONLESS_TEXT_FILE_NAMES ||
            extension in TEXT_EXTENSIONS ||
            normalizedMime.startsWith("text/") -> WorkspacePreviewKind.TEXT
        else -> WorkspacePreviewKind.EXTERNAL
    }
}

fun workspaceCodeLanguage(fileName: String): String = when (fileName.workspaceExtension()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs" -> "javascript"
    "ts" -> "typescript"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "py" -> "python"
    "rb" -> "ruby"
    "go" -> "go"
    "rs" -> "rust"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp", "hxx" -> "cpp"
    "cs" -> "csharp"
    "swift" -> "swift"
    "php" -> "php"
    "sh", "bash", "zsh" -> "bash"
    "json", "json5" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css" -> "css"
    "scss" -> "scss"
    "yaml", "yml" -> "yaml"
    "toml" -> "toml"
    "md", "markdown" -> "markdown"
    "sql" -> "sql"
    "gradle" -> "groovy"
    else -> "plaintext"
}

private fun String.workspaceExtension(): String = substringAfterLast('.', "").lowercase()
