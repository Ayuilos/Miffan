package me.ayuilos.miffan.data.bugreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class DiagnosticError(val at: String, val title: String, val message: String, val kind: String)

/** One app-scoped instance; private file, bounded records, serialized atomic replacement. */
class RecentErrorStore(private val file: File) {
    private val mutex = Mutex()
    private fun read(): List<DiagnosticError> = runCatching {
        Json.decodeFromString<List<DiagnosticError>>(file.readText()).takeLast(20)
    }.getOrDefault(emptyList())

    suspend fun record(error: Throwable, title: String? = null, at: Instant = Instant.now()) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val record = DiagnosticError(
                    at = at.toString(),
                    title = redactDiagnosticText(title.orEmpty()),
                    message = redactDiagnosticText(error.message.orEmpty()).take(500),
                    kind = error.javaClass.simpleName,
                )
                val records = (read() + record).takeLast(20)
                file.parentFile?.mkdirs()
                val temporary = File(file.path + ".tmp")
                temporary.outputStream().use { stream ->
                    stream.write(Json.encodeToString(records).toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                check(temporary.renameTo(file)) { "Cannot save recent app errors" }
            }
        }

    suspend fun recent(limit: Int = 10): List<DiagnosticError> = withContext(Dispatchers.IO) {
        mutex.withLock { read().asReversed().take(limit.coerceIn(0, 20)) }
    }
}
