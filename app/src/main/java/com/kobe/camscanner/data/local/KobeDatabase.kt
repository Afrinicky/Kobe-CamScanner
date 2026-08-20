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
    // The library is a cache over files that remain the source of truth and it is rebuilt
    // destructively on any schema change, so there are no migrations to export. Exporting them
    // also made debug and release KSP race for the same schema file.
    exportSchema = false,
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
