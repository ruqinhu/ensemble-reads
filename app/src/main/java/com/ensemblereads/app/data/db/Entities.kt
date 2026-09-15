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
    /** 段音频实测时长(ms)：合成成功后读 mp3 写入，供整章进度条与跨段 seek。 */
    val durationMs: Long = 0,
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
    /** 解析结果版本：v2 起经 SegmentMerger 合并短段，旧缓存（version=1）自动失效重解析。 */
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

/** 阅读标注：书签/高亮/笔记，按 (bookId, chapterId, segIndex) 定位到具体段落。 */
@Entity(
    tableName = "annotation",
    indices = [Index(value = ["bookId"])],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long,
    val segIndex: Int,
    val type: String,            // "BOOKMARK" | "HIGHLIGHT" | "NOTE"
    val noteText: String? = null,
    val createdAt: Long = 0,
    val color: String? = null,   // 高亮色 ARGB 字符串
) {
    companion object {
        const val TYPE_BOOKMARK = "BOOKMARK"
        const val TYPE_HIGHLIGHT = "HIGHLIGHT"
        const val TYPE_NOTE = "NOTE"
    }
}

/** 阅读统计：按书累计收听时长。 */
@Entity(tableName = "reading_stats")
data class ReadingStatsEntity(
    @PrimaryKey val bookId: Long,
    val listenedMs: Long = 0,
    val lastListenedAt: Long = 0,
)

/** 聚合查询结果：每章已解析段数（用于整书朗读进度计算，书架一次性加载）。 */
data class SegCountByChapter(
    val bookId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val cnt: Int,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)
