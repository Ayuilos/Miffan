package me.ayuilos.miffan.data.repository

import kotlinx.coroutines.flow.Flow
import me.ayuilos.miffan.data.db.dao.FavoriteDAO
import me.ayuilos.miffan.data.db.entity.FavoriteEntity
import me.ayuilos.miffan.data.favorite.NodeFavoriteAdapter
import me.ayuilos.miffan.data.model.FavoriteType
import me.ayuilos.miffan.data.model.NodeFavoriteTarget
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
) {
    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int = dao.deleteByRefKey(refKey)

    suspend fun deleteById(id: String): Int = dao.deleteById(id)

    suspend fun upsert(entity: FavoriteEntity) = dao.upsert(entity)

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        dao.upsert(favorite)
        return favorite
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return dao.deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }
}
