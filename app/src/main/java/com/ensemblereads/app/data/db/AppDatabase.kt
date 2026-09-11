package com.ensemblereads.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, RoleEntity::class,
        SegmentEntity::class, ParseCacheEntity::class, SettingsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun roleDao(): RoleDao
    abstract fun segmentDao(): SegmentDao
    abstract fun parseCacheDao(): ParseCacheDao
    abstract fun settingsDao(): SettingsDao
}
