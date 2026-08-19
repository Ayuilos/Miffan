package me.ayuilos.miffan.data.favorite

import me.ayuilos.miffan.data.db.entity.FavoriteEntity
import me.ayuilos.miffan.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
