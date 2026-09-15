package com.ensemblereads.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.db.AnnotationDao
import com.ensemblereads.app.data.db.AnnotationEntity
import com.ensemblereads.app.data.db.AppDatabase
import com.ensemblereads.app.data.db.BookDao
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterDao
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.ReadingStatsDao
import com.ensemblereads.app.data.db.ReadingStatsEntity
import com.ensemblereads.app.data.db.RoleDao
import com.ensemblereads.app.data.db.RoleEntity
import com.ensemblereads.app.data.db.SegmentDao
import com.ensemblereads.app.data.db.SegmentEntity
import java.io.File

class BookRepo(private val dao: BookDao) {
    suspend fun all() = dao.all()
    suspend fun byId(id: Long) = dao.byId(id)
    suspend fun add(book: BookEntity) = dao.insert(book)
    suspend fun update(book: BookEntity) = dao.update(book)
    suspend fun remove(book: BookEntity) = dao.delete(book)
}

class ChapterRepo(private val dao: ChapterDao) {
    suspend fun chapters(bookId: Long) = dao.byBook(bookId)
    suspend fun byId(id: Long) = dao.byId(id)
    suspend fun byBookAndId(bookId: Long, id: Long) = dao.byBookAndId(bookId, id)
    suspend fun addAll(chapters: List<ChapterEntity>) = dao.insertAll(chapters)
    suspend fun setCached(id: Long, cached: Boolean) = dao.setCached(id, cached)
    suspend fun deleteByBook(bookId: Long) = dao.deleteByBook(bookId)
    suspend fun searchChapters(bookId: Long, q: String) = dao.searchChapters(bookId, q)
}

class RoleRepo(private val dao: RoleDao) {
    suspend fun byBook(bookId: Long) = dao.byBook(bookId)
    suspend fun upsert(role: RoleEntity) = dao.upsert(role)
    suspend fun delete(bookId: Long, roleName: String) = dao.delete(bookId, roleName)
    suspend fun deleteByBook(bookId: Long) = dao.deleteByBook(bookId)
}

class SegmentRepo(private val dao: SegmentDao) {
    suspend fun readyByChapter(chapterId: Long) = dao.readyByChapter(chapterId)
    suspend fun byChapter(chapterId: Long) = dao.byChapter(chapterId)
    suspend fun addAll(segments: List<SegmentEntity>) = dao.insertAll(segments)
    suspend fun setStatus(id: Long, status: String) = dao.setStatus(id, status)
    suspend fun markReady(id: Long, path: String, at: Long, durationMs: Long) = dao.markReady(id, path, at, durationMs)
    suspend fun deleteByBook(bookId: Long) = dao.deleteByBook(bookId)
    suspend fun deleteByChapter(chapterId: Long) = dao.deleteByChapter(chapterId)
    suspend fun segCountByChapterAll() = dao.segCountByChapterAll()
    suspend fun searchSegments(bookId: Long, q: String) = dao.searchSegments(bookId, q)
}

class AnnotationRepo(private val dao: AnnotationDao) {
    suspend fun byBook(bookId: Long) = dao.byBook(bookId)
    suspend fun byChapter(chapterId: Long) = dao.byChapter(chapterId)
    suspend fun add(annotation: AnnotationEntity) = dao.insert(annotation)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteByBook(bookId: Long) = dao.deleteByBook(bookId)
}

class ReadingStatsRepo(private val dao: ReadingStatsDao) {
    suspend fun upsert(stats: ReadingStatsEntity) = dao.upsert(stats)
    suspend fun byBook(bookId: Long) = dao.byBook(bookId)
    suspend fun totalMs(): Long = dao.totalMs()
}

/** v1→v2：清理历史重复段（并发 bug 遗留）后建 (chapterId,segIndex) 唯一索引，保留用户数据。 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM segment WHERE id NOT IN (SELECT MIN(id) FROM segment GROUP BY chapterId, segIndex)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_segment_chapterId_segIndex ON segment(chapterId, segIndex)")
    }
}

/** v2→v3：segment 增加 durationMs（段音频实测时长，供整章进度条/跨段 seek）。 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE segment ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3→v4：parse_cache 加 version（段合并缓存失效）、新增 annotation（书签/高亮/笔记）
 * 与 reading_stats（收听统计）两张表。全部非破坏性。
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE parse_cache ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS annotation (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "bookId INTEGER NOT NULL, chapterId INTEGER NOT NULL, segIndex INTEGER NOT NULL, " +
                "type TEXT NOT NULL, noteText TEXT, createdAt INTEGER NOT NULL, color TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_annotation_bookId ON annotation(bookId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reading_stats (" +
                "bookId INTEGER PRIMARY KEY NOT NULL, listenedMs INTEGER NOT NULL, lastListenedAt INTEGER NOT NULL)"
        )
    }
}

/** 手动依赖注入容器。 */
class AppContainer(appContext: Context) {
    val appContext: Context = appContext.applicationContext
    val db: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "ensemble.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
    val bookRepo = BookRepo(db.bookDao())
    val chapterRepo = ChapterRepo(db.chapterDao())
    val roleRepo = RoleRepo(db.roleDao())
    val segmentRepo = SegmentRepo(db.segmentDao())
    val annotationRepo = AnnotationRepo(db.annotationDao())
    val statsRepo = ReadingStatsRepo(db.readingStatsDao())
    val settings = SettingsManager(db.settingsDao())

    /** 删除一本书及其全部关联数据与音频文件（级联）。 */
    suspend fun deleteBook(book: BookEntity) {
        val audioDir = File(File(appContext.filesDir, "audio"), book.id.toString())
        audioDir.deleteRecursively()
        // 依赖章节存在的清理必须先于章节删除（segment/parse_cache 用 chapterId IN 子查询）
        db.parseCacheDao().deleteByBook(book.id)
        segmentRepo.deleteByBook(book.id)
        annotationRepo.deleteByBook(book.id)
        chapterRepo.deleteByBook(book.id)
        roleRepo.deleteByBook(book.id)
        bookRepo.remove(book)
    }
}
