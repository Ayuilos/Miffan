import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.ByteArrayOutputStream

/** Expand whole-line includes as bytes, retaining the template's BOM and all source newlines. */
@CacheableTask
abstract class AssembleRemoteHelper : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val source = sourceDirectory.get().asFile
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val remote = output.resolve("remote").apply { mkdirs() }
        listOf("miffan.sh", "windows-setup.ps1").forEach {
            source.resolve(it).copyTo(remote.resolve(it))
        }
        val windows = source.resolve("windows")
        val bytes = windows.resolve("miffan.ps1").readBytes()
        val expanded = ByteArrayOutputStream()
        var start = 0
        while (start < bytes.size) {
            val newline = (start until bytes.size).firstOrNull { bytes[it] == 10.toByte() }
            val end = newline?.plus(1) ?: bytes.size
            val line = bytes.copyOfRange(start, end)
            val directive = line.toString(Charsets.UTF_8).removeSuffix("\n")
            if (directive.startsWith("#include ")) {
                val name = directive.removePrefix("#include ")
                require(name.matches(Regex("[A-Za-z][A-Za-z0-9]*\\.cs"))) { "Invalid include: $name" }
                expanded.write(windows.resolve(name).readBytes())
            } else {
                expanded.write(line)
            }
            start = end
        }
        remote.resolve("miffan.ps1").writeBytes(expanded.toByteArray())
    }
}
