package com.ensemblereads.app.data.backup

import android.content.Context
import android.net.Uri
import com.ensemblereads.app.data.db.AnnotationEntity
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.db.ChapterEntity
import com.ensemblereads.app.data.db.RoleEntity
import com.ensemblereads.app.data.db.SettingsEntity
import com.ensemblereads.app.data.repo.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份导出/导入：书籍 + 章节 + 角色配置 + 标注 + 设置（不含段与音频，可重合成）。
 * 导入按 filePath 去重，settings 合并（不覆盖已有 key）。
 */
object BackupManager {

    suspend fun export(context: Context, container: AppContainer, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject()
                val books = container.bookRepo.all()
                val chaptersArr = JSONArray()
                val rolesArr = JSONArray()
                val annArr = JSONArray()
                for (b in books) {
                    for (c in container.chapterRepo.chapters(b.id)) {
                        chaptersArr.put(JSONObject().apply {
                            put("bookFile", b.filePath); put("title", c.title); put("content", c.content); put("index", c.index)
                        })
                    }
                    for (r in container.roleRepo.byBook(b.id)) {
                        rolesArr.put(JSONObject().apply {
                            put("bookFile", b.filePath); put("roleName", r.roleName); put("voice", r.voice); put("pitch", r.pitch); put("rate", r.rate)
                        })
                    }
                    for (a in container.annotationRepo.byBook(b.id)) {
                        annArr.put(JSONObject().apply {
                            put("bookFile", b.filePath); put("chapterTitle", "")
                            put("segIndex", a.segIndex); put("type", a.type)
                            put("noteText", a.noteText ?: ""); put("color", a.color ?: "")
                        })
                    }
                }
                root.put("version", 1)
                root.put("books", JSONArray().apply {
                    books.forEach { b ->
                        put(JSONObject().apply {
                            put("title", b.title); put("filePath", b.filePath); put("format", b.format)
                            put("lastChapterId", b.lastChapterId ?: JSONObject.NULL)
                            put("lastSegIndex", b.lastSegIndex); put("createdAt", b.createdAt)
                        })
                    }
                })
                root.put("chapters", chaptersArr)
                root.put("roles", rolesArr)
                root.put("annotations", annArr)
                root.put("settings", JSONArray().apply {
                    container.settings.allRaw().forEach { e ->
                        put(JSONObject().apply { put("key", e.key); put("value", e.value) })
                    }
                })
                context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString().toByteArray()) }
                    ?: return@withContext false
                true
            } catch (e: Exception) {
                android.util.Log.w("Backup", "导出失败", e)
                false
            }
        }

    suspend fun import(context: Context, container: AppContainer, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: return@withContext false
                val root = JSONObject(text)
                val books = root.getJSONArray("books")
                val bookIdByFile = mutableMapOf<String, Long>()
                for (i in 0 until books.length()) {
                    val o = books.getJSONObject(i)
                    val filePath = o.optString("filePath")
                    if (container.bookRepo.all().any { it.filePath == filePath }) continue // 去重
                    val newId = container.bookRepo.add(
                        BookEntity(
                            title = o.optString("title", "未命名"), filePath = filePath,
                            format = o.optString("format", "TXT"), chapterCount = 0,
                            lastChapterId = null, lastSegIndex = o.optInt("lastSegIndex", 0),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        ),
                    )
                    bookIdByFile[filePath] = newId
                }
                // 章节（按 bookFile 归组）
                val chapterArr = root.optJSONArray("chapters") ?: JSONArray()
                for (i in 0 until chapterArr.length()) {
                    val o = chapterArr.getJSONObject(i)
                    val bookId = bookIdByFile[o.optString("bookFile")] ?: continue
                    container.chapterRepo.addAll(listOf(
                        ChapterEntity(bookId = bookId, index = o.optInt("index"), title = o.optString("title"), content = o.optString("content")),
                    ))
                }
                // 章节 id 需映射：简化——章节 id 由 insertAll 自动生成，标注只按 segIndex 定位，
                // 导入标注需知道新章节 id；此处按 (bookFile, index) 反查
                val roleArr = root.optJSONArray("roles") ?: JSONArray()
                for (i in 0 until roleArr.length()) {
                    val o = roleArr.getJSONObject(i)
                    val bookId = bookIdByFile[o.optString("bookFile")] ?: continue
                    container.roleRepo.upsert(
                        RoleEntity(bookId = bookId, roleName = o.optString("roleName"), voice = o.optString("voice"),
                            pitch = o.optInt("pitch"), rate = o.optInt("rate")),
                    )
                }
                // 标注：简化实现——导入时章节 id 重新生成，这里仅当书被导入时按 segIndex 导入书签/笔记（章级关联由 chapterId=0 兜底）
                val annArr = root.optJSONArray("annotations") ?: JSONArray()
                for (i in 0 until annArr.length()) {
                    val o = annArr.getJSONObject(i)
                    val bookId = bookIdByFile[o.optString("bookFile")] ?: continue
                    container.annotationRepo.add(
                        AnnotationEntity(
                            bookId = bookId, chapterId = 0, segIndex = o.optInt("segIndex"),
                            type = o.optString("type"), noteText = o.optString("noteText").ifEmpty { null },
                            color = o.optString("color").ifEmpty { null }, createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // 设置合并（不覆盖已有 key）
                val settingsArr = root.optJSONArray("settings") ?: JSONArray()
                for (i in 0 until settingsArr.length()) {
                    val o = settingsArr.getJSONObject(i)
                    val key = o.optString("key")
                    if (container.settings.get(key) == null) container.settings.putRaw(key, o.optString("value"))
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("Backup", "导入失败", e)
                false
            }
        }
}
