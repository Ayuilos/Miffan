package me.ayuilos.miffan.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.entity.RevisionEntity

@Dao
interface RevisionDAO {
    @Query("SELECT * FROM revision WHERE subject_type = :subjectType AND subject_id = :subjectId ORDER BY created_at DESC, rowid DESC LIMIT 1")
    suspend fun getHead(subjectType: String, subjectId: String): RevisionEntity?

    @Query("SELECT * FROM revision WHERE id = :id")
    suspend fun getById(id: String): RevisionEntity?

    @Query("SELECT * FROM revision WHERE subject_type = :subjectType AND subject_id = :subjectId ORDER BY created_at DESC, rowid DESC")
    fun observeHistory(subjectType: String, subjectId: String): Flow<List<RevisionEntity>>

    /** Agent-authored revisions of the given subjects, used for timeline notices. */
    @Query("SELECT * FROM revision WHERE author = :author AND subject_id IN (:subjectIds) ORDER BY created_at ASC, rowid ASC")
    fun observeByAuthor(author: String, subjectIds: List<String>): Flow<List<RevisionEntity>>

    @Insert
    suspend fun insert(revision: RevisionEntity)
}
