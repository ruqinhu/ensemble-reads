package com.ensemblereads.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert suspend fun insert(book: BookEntity): Long
    @Query("SELECT * FROM book ORDER BY createdAt DESC") suspend fun all(): List<BookEntity>
    @Query("SELECT * FROM book WHERE id=:id") suspend fun byId(id: Long): BookEntity?
    @Update suspend fun update(book: BookEntity)
    @Delete suspend fun delete(book: BookEntity)
}

@Dao
interface ChapterDao {
    @Insert suspend fun insert(chapter: ChapterEntity): Long
    @Insert suspend fun insertAll(chapters: List<ChapterEntity>)
    @Query("SELECT * FROM chapter WHERE bookId=:bookId ORDER BY \"index\"") suspend fun byBook(bookId: Long): List<ChapterEntity>
    @Query("SELECT * FROM chapter WHERE id=:id") suspend fun byId(id: Long): ChapterEntity?
    @Query("SELECT * FROM chapter WHERE bookId=:bookId AND id=:id") suspend fun byBookAndId(bookId: Long, id: Long): ChapterEntity?
    @Query("UPDATE chapter SET cached=:cached WHERE id=:id") suspend fun setCached(id: Long, cached: Boolean)
    @Update suspend fun update(chapter: ChapterEntity)
    @Query("DELETE FROM chapter WHERE bookId=:bookId") suspend fun deleteByBook(bookId: Long)
    @Query("SELECT * FROM chapter WHERE bookId=:bookId AND content LIKE '%'||:q||'%' ORDER BY \"index\"") suspend fun searchChapters(bookId: Long, q: String): List<ChapterEntity>
}

@Dao
interface RoleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(role: RoleEntity)
    @Query("SELECT * FROM role WHERE bookId=:bookId") suspend fun byBook(bookId: Long): List<RoleEntity>
    @Query("DELETE FROM role WHERE bookId=:bookId AND roleName=:roleName") suspend fun delete(bookId: Long, roleName: String)
    @Query("DELETE FROM role WHERE bookId=:bookId") suspend fun deleteByBook(bookId: Long)
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(segments: List<SegmentEntity>)
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId ORDER BY segIndex") suspend fun byChapter(chapterId: Long): List<SegmentEntity>
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId AND status='READY' ORDER BY segIndex") suspend fun readyByChapter(chapterId: Long): List<SegmentEntity>
    @Query("UPDATE segment SET status=:status WHERE id=:id") suspend fun setStatus(id: Long, status: String)
    @Query("UPDATE segment SET status='READY', audioPath=:path, cachedAt=:at, durationMs=:durationMs WHERE id=:id") suspend fun markReady(id: Long, path: String, at: Long, durationMs: Long)
    @Query("DELETE FROM segment WHERE chapterId IN (SELECT id FROM chapter WHERE bookId=:bookId)") suspend fun deleteByBook(bookId: Long)
    @Query("DELETE FROM segment WHERE chapterId=:chapterId") suspend fun deleteByChapter(chapterId: Long)
    /** 每章已解析段数（LEFT JOIN 保留 0 段章），供整书进度计算。 */
    @Query(
        "SELECT c.bookId AS bookId, c.id AS chapterId, c.\"index\" AS chapterIndex, c.title AS chapterTitle, " +
            "COUNT(s.id) AS cnt FROM chapter c LEFT JOIN segment s ON s.chapterId = c.id GROUP BY c.id",
    )
    suspend fun segCountByChapterAll(): List<SegCountByChapter>
    @Query("SELECT * FROM segment WHERE chapterId IN (SELECT id FROM chapter WHERE bookId=:bookId) AND text LIKE '%'||:q||'%' ORDER BY chapterId, segIndex") suspend fun searchSegments(bookId: Long, q: String): List<SegmentEntity>
}

@Dao
interface ParseCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(cache: ParseCacheEntity)
    @Query("SELECT * FROM parse_cache WHERE chapterId=:chapterId") suspend fun byChapter(chapterId: Long): ParseCacheEntity?
    @Query("SELECT * FROM parse_cache WHERE chapterId=:chapterId AND version=:version") suspend fun byChapterVersioned(chapterId: Long, version: Int): ParseCacheEntity?
    @Query("DELETE FROM parse_cache WHERE chapterId=:chapterId") suspend fun deleteByChapter(chapterId: Long)
    @Query("DELETE FROM parse_cache WHERE chapterId IN (SELECT id FROM chapter WHERE bookId=:bookId)") suspend fun deleteByBook(bookId: Long)
}

@Dao
interface AnnotationDao {
    @Insert suspend fun insert(annotation: AnnotationEntity): Long
    @Query("SELECT * FROM annotation WHERE bookId=:bookId ORDER BY createdAt DESC") suspend fun byBook(bookId: Long): List<AnnotationEntity>
    @Query("SELECT * FROM annotation WHERE chapterId=:chapterId ORDER BY segIndex") suspend fun byChapter(chapterId: Long): List<AnnotationEntity>
    @Query("DELETE FROM annotation WHERE id=:id") suspend fun delete(id: Long)
    @Query("DELETE FROM annotation WHERE bookId=:bookId") suspend fun deleteByBook(bookId: Long)
}

@Dao
interface ReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(stats: ReadingStatsEntity)
    @Query("SELECT * FROM reading_stats WHERE bookId=:bookId") suspend fun byBook(bookId: Long): ReadingStatsEntity?
    @Query("SELECT COALESCE(SUM(listenedMs),0) FROM reading_stats") suspend fun totalMs(): Long
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entity: SettingsEntity)
    @Query("SELECT * FROM settings WHERE \"key\"=:key") suspend fun get(key: String): SettingsEntity?
    @Query("SELECT * FROM settings") suspend fun all(): List<SettingsEntity>
    @Query("SELECT * FROM settings") fun allFlow(): Flow<List<SettingsEntity>>
}
