package me.ayuilos.miffan.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.ayuilos.miffan.data.db.dao.ConversationDAO
import me.ayuilos.miffan.data.db.dao.FavoriteDAO
import me.ayuilos.miffan.data.db.dao.FolderDAO
import me.ayuilos.miffan.data.db.dao.GenMediaDAO
import me.ayuilos.miffan.data.db.dao.ManagedFileDAO
import me.ayuilos.miffan.data.db.dao.MemoryDAO
import me.ayuilos.miffan.data.db.dao.MessageNodeDAO
import me.ayuilos.miffan.data.db.dao.WorkspaceDAO
import me.ayuilos.miffan.data.db.entity.ConversationEntity
import me.ayuilos.miffan.data.db.entity.FavoriteEntity
import me.ayuilos.miffan.data.db.entity.FolderEntity
import me.ayuilos.miffan.data.db.entity.GenMediaEntity
import me.ayuilos.miffan.data.db.entity.ManagedFileEntity
import me.ayuilos.miffan.data.db.entity.MemoryEntity
import me.ayuilos.miffan.data.db.entity.MessageNodeEntity
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.db.migrations.Migration_16_17
import me.ayuilos.miffan.data.db.migrations.Migration_22_23
import me.ayuilos.miffan.data.db.migrations.Migration_8_9
import me.ayuilos.miffan.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
    ],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
