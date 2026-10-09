package me.ayuilos.miffan.data.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Startup consumers must wait for persisted settings, including the missing-key migrations. */
internal suspend fun Flow<Settings>.awaitLoadedSettings(): Settings = first { !it.init }
