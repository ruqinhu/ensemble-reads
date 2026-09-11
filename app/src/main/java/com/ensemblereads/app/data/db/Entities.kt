package com.ensemblereads.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val format: String,            // "TXT" | "EPUB"
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val lastChapterId: Long? = null,
    val lastSegIndex: Int = 0,
    val createdAt: Long = 0,
)

@Entity(tableName = "chapter")
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val index: Int,
    val title: String,
    val content: String,
    val cached: Boolean = false,
)

@Entity(tableName = "role")
data class RoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val roleName: String,
    val voice: String,
    val pitch: Int = 0,
    val rate: Int = 0,
)

@Entity(
    tableName = "segment",
    // 唯一索引：防止并发 ensureChapter 对同一章重复插入分段
    indices = [Index(value = ["chapterId", "segIndex"], unique = true)],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val segIndex: Int,
    val speaker: String,
    val text: String,
    val gender: String = "unknown",
    val age: String = "未知",
    val tone: String = "中性",
    val audioPath: String? = null,
    val status: String = SegmentEntity.STATUS_PENDING,
    val cachedAt: Long = 0,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_PARSED = "PARSED"
        const val STATUS_SYNTHESIZING = "SYNTHESIZING"
        const val STATUS_READY = "READY"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "parse_cache")
data class ParseCacheEntity(
    @PrimaryKey val chapterId: Long,
    val segmentsJson: String,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)
