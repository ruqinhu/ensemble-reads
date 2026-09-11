package com.ensemblereads.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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
}

@Dao
interface RoleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(role: RoleEntity)
    @Query("SELECT * FROM role WHERE bookId=:bookId") suspend fun byBook(bookId: Long): List<RoleEntity>
    @Query("DELETE FROM role WHERE bookId=:bookId AND roleName=:roleName") suspend fun delete(bookId: Long, roleName: String)
}

@Dao
interface SegmentDao {
    @Insert suspend fun insertAll(segments: List<SegmentEntity>)
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId ORDER BY segIndex") suspend fun byChapter(chapterId: Long): List<SegmentEntity>
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId AND status='READY' ORDER BY segIndex") suspend fun readyByChapter(chapterId: Long): List<SegmentEntity>
    @Query("UPDATE segment SET status=:status WHERE id=:id") suspend fun setStatus(id: Long, status: String)
    @Query("UPDATE segment SET status='READY', audioPath=:path, cachedAt=:at WHERE id=:id") suspend fun markReady(id: Long, path: String, at: Long)
    @Query("DELETE FROM segment WHERE chapterId IN (SELECT id FROM chapter WHERE bookId=:bookId)") suspend fun deleteByBook(bookId: Long)
}

@Dao
interface ParseCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(cache: ParseCacheEntity)
    @Query("SELECT * FROM parse_cache WHERE chapterId=:chapterId") suspend fun byChapter(chapterId: Long): ParseCacheEntity?
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entity: SettingsEntity)
    @Query("SELECT * FROM settings WHERE \"key\"=:key") suspend fun get(key: String): SettingsEntity?
}
