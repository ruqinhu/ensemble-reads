package com.ensemblereads.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.db.AppDatabase
import com.ensemblereads.app.data.db.BookDao
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterDao
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.RoleDao
import com.ensemblereads.app.data.db.RoleEntity
import com.ensemblereads.app.data.db.SegmentDao
import com.ensemblereads.app.data.db.SegmentEntity

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
}

class RoleRepo(private val dao: RoleDao) {
    suspend fun byBook(bookId: Long) = dao.byBook(bookId)
    suspend fun upsert(role: RoleEntity) = dao.upsert(role)
    suspend fun delete(bookId: Long, roleName: String) = dao.delete(bookId, roleName)
}

class SegmentRepo(private val dao: SegmentDao) {
    suspend fun readyByChapter(chapterId: Long) = dao.readyByChapter(chapterId)
    suspend fun byChapter(chapterId: Long) = dao.byChapter(chapterId)
    suspend fun addAll(segments: List<SegmentEntity>) = dao.insertAll(segments)
    suspend fun setStatus(id: Long, status: String) = dao.setStatus(id, status)
    suspend fun markReady(id: Long, path: String, at: Long) = dao.markReady(id, path, at)
    suspend fun deleteByBook(bookId: Long) = dao.deleteByBook(bookId)
}

/** v1→v2：清理历史重复段（并发 bug 遗留）后建 (chapterId,segIndex) 唯一索引，保留用户数据。 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM segment WHERE id NOT IN (SELECT MIN(id) FROM segment GROUP BY chapterId, segIndex)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_segment_chapterId_segIndex ON segment(chapterId, segIndex)")
    }
}

/** 手动依赖注入容器。 */
class AppContainer(appContext: Context) {
    val db: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "ensemble.db")
        .addMigrations(MIGRATION_1_2)
        .build()
    val bookRepo = BookRepo(db.bookDao())
    val chapterRepo = ChapterRepo(db.chapterDao())
    val roleRepo = RoleRepo(db.roleDao())
    val segmentRepo = SegmentRepo(db.segmentDao())
    val settings = SettingsManager(db.settingsDao())
}
