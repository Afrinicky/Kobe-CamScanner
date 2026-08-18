package com.kobe.camscanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        FolderEntity::class,
        DocumentSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KobeDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun folderDao(): FolderDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val NAME = "kobe-library.db"

        /** Seeded on first run so the library is never an empty screen with no way forward. */
        val DEFAULT_FOLDERS = listOf("Work", "School", "Finance", "Personal")
    }
}
