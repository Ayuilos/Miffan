package me.rerere.workspace

/**
 * An unambiguous absolute POSIX path as seen by a process inside the workspace Rootfs.
 *
 * Guest paths are intentionally stricter than [java.io.File] paths. In particular, aliases such
 * as `.`/`..`, repeated separators, trailing separators, and backslashes are rejected instead of
 * normalized. This lets approval checks and execution operate on exactly the same path spelling.
 */
@JvmInline
value class GuestPath private constructor(
    val value: String,
) {
    val name: String
        get() = if (value == "/") "/" else value.substringAfterLast('/')

    fun isWithin(root: GuestPath): Boolean =
        root.value == "/" || value == root.value || value.startsWith("${root.value}/")

    fun relativeTo(root: GuestPath): String {
        require(isWithin(root)) { "$value is outside ${root.value}" }
        return when {
            value == root.value -> ""
            root.value == "/" -> value.removePrefix("/")
            else -> value.removePrefix("${root.value}/")
        }
    }

    override fun toString(): String = value

    companion object {
        val ROOT: GuestPath = GuestPath("/")

        fun parse(raw: String, name: String = "path"): GuestPath {
            require(raw.isNotBlank()) { "$name is required" }
            require(!raw.contains('\u0000')) { "$name contains invalid character" }
            require(!raw.contains('\\')) { "$name must use forward slashes" }
            require(raw.startsWith('/')) { "$name must be an absolute path inside Rootfs" }
            require(raw == "/" || !raw.endsWith('/')) {
                "$name must not contain a trailing separator"
            }

            if (raw != "/") {
                val segments = raw.removePrefix("/").split('/')
                require(segments.none { it.isEmpty() }) {
                    "$name must not contain repeated separators"
                }
                require(segments.none { it == "." || it == ".." }) {
                    "$name must not contain . or .. path segments"
                }
            }
            return GuestPath(raw)
        }
    }
}
