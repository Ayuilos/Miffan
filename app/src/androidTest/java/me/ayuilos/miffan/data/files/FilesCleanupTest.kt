package me.ayuilos.miffan.data.files

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.db.dao.ManagedFileDAO
import me.ayuilos.miffan.data.db.entity.ManagedFileEntity
import me.ayuilos.miffan.data.repository.FilesRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesCleanupTest {
    private lateinit var root: File
    private lateinit var dao: TestFileDao
    private lateinit var scope: AppScope
    private lateinit var manager: FilesManager

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(base.cacheDir.toPath(), "scoped-cleanup-").toFile()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        dao = TestFileDao()
        scope = AppScope()
        manager = FilesManager(context, FilesRepository(dao), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun onlyOlderFilesInSelectedFolderAreRemoved() = runBlocking {
        val old = add("upload", "upload/old.txt", 99)
        val boundary = add("upload", "upload/boundary.txt", 100)
        val recent = add("upload", "upload/recent.txt", 101)
        val other = add("fonts", "fonts/other.txt", 1)
        val untracked = File(root, "upload/untracked.txt").apply { writeText("keep") }

        assertTrue(manager.deleteOlderThan("upload", 100))
        assertFalse(manager.getFile(old).exists())
        assertNull(dao.getById(old.id))
        listOf(boundary, recent, other).forEach {
            assertTrue(manager.getFile(it).exists())
            assertNotNull(dao.getById(it.id))
        }
        assertTrue(untracked.exists())
    }

    @Test
    fun missingOldFilesRemoveOnlyTheirStaleRecords() = runBlocking {
        val missing = add("upload", "upload/missing.txt", 1)
        manager.getFile(missing).delete()
        assertTrue(manager.deleteOlderThan("upload", 100))
        assertNull(dao.getById(missing.id))
    }

    @Test
    fun mismatchedRestoredPathsCannotDeleteAnotherCategory() = runBlocking {
        val outside = add("upload", "fonts/keep.txt", 1)
        assertFalse(manager.deleteOlderThan("upload", 100))
        assertTrue(manager.getFile(outside).exists())
        assertNotNull(dao.getById(outside.id))
        assertFalse(manager.deleteOlderThan(".", 100))
        assertTrue(manager.getFile(outside).exists())
    }

    @Test
    fun directoriesAndExternalSymlinksAreNotFollowed() = runBlocking {
        val directory = add("upload", "upload/directory", 1)
        manager.getFile(directory).apply {
            delete()
            mkdir()
            resolve("keep.txt").writeText("keep")
        }
        val target = add("fonts", "fonts/target.txt", 1)
        val link = add("upload", "upload/link.txt", 1)
        manager.getFile(link).delete()
        Files.createSymbolicLink(manager.getFile(link).toPath(), manager.getFile(target).toPath())

        assertFalse(manager.deleteOlderThan("upload", 100))
        assertTrue(manager.getFile(directory).resolve("keep.txt").exists())
        assertTrue(manager.getFile(target).exists())
        assertNotNull(dao.getById(directory.id))
        assertNotNull(dao.getById(link.id))
    }

    private suspend fun add(folder: String, path: String, createdAt: Long): ManagedFileEntity {
        val file = File(root, path).apply {
            parentFile!!.mkdirs()
            writeText("test file")
        }
        val entity = ManagedFileEntity(
            folder = folder, relativePath = path, displayName = file.name,
            mimeType = "text/plain", sizeBytes = file.length(),
            createdAt = createdAt, updatedAt = createdAt,
        )
        return entity.copy(id = dao.insert(entity))
    }

    private class TestFileDao : ManagedFileDAO {
        private val files = MutableStateFlow<List<ManagedFileEntity>>(emptyList())
        private var nextId = 1L
        override suspend fun insert(file: ManagedFileEntity): Long {
            val id = nextId++
            files.value += file.copy(id = id)
            return id
        }
        override suspend fun update(file: ManagedFileEntity) {
            files.value = files.value.map { if (it.id == file.id) file else it }
        }
        override suspend fun getById(id: Long) = files.value.find { it.id == id }
        override suspend fun getByPath(relativePath: String) = files.value.find { it.relativePath == relativePath }
        override fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> =
            files.map { list -> list.filter { it.folder == folder } }
        override suspend fun deleteById(id: Long) = deleteMatching { it.id == id }
        override suspend fun deleteByPath(relativePath: String) = deleteMatching { it.relativePath == relativePath }
        override suspend fun deleteByFolder(folder: String) = deleteMatching { it.folder == folder }
        private fun deleteMatching(predicate: (ManagedFileEntity) -> Boolean): Int {
            val previous = files.value
            files.value = previous.filterNot(predicate)
            return previous.size - files.value.size
        }
    }
}
