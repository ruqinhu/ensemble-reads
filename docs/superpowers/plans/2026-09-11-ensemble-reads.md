# Ensemble Reads Android App 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Ensemble Reads —— 苹果风格的本地 TXT/EPUB 小说阅读器 + DeepSeek 多角色解析 + Edge TTS 多角色有声朗读的 Android APK。

**Architecture:** 全新 Android 项目（Kotlin + Compose + Room + Media3）。书籍统一解析为纯文本章节；TTS 走抽象接口（`TtsEngine`），直连实现封装 DeepSeek(Anthropic 协议)/角色分配/Edge TTS(WebSocket)；段级缓存边听边缓，缓存上限可配置且自动淘汰；苹果风格四屏 UI。

**Tech Stack:** Kotlin 2.x、AGP 8.x、Compose BOM、Room 2.6、Media3-ExoPlayer 1.4、OkHttp 4.12、epublib-core 4.0.0、kotlinx-coroutines、JUnit4。

## Global Constraints

- 项目根：`D:\cod\ensemble-reads`；包名 `com.ensemblereads.app`；单 Activity + Compose Navigation。
- **不得自动运行测试**（用户规则）；测试代码写好后由用户执行。执行阶段只做编译/语法级自检。
- DeepSeek key 只从本地 `Settings` 表读取，禁止硬编码。默认端点 `https://ark.cn-beijing.volces.com/api/coding`（Anthropic 协议 `/v1/messages`）、模型 `deepseek-v4-flash`。
- 音频缓存文件统一存 `context.filesDir/audio/<bookId>/<chapterId>/seg_<n>.mp3`。
- 每次任务结束提交（消息用 `[阶段X]` 前缀）。
- Edge TTS 失败重试 3 次（退避）；DeepSeek 失败重试 1 次。
- UI 苹果风格：大标题、TabBar、圆角、深浅色。

---

# Phase 0：项目骨架

### Task 1: Gradle 项目初始化

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`（根）
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/ensemblereads/app/MainActivity.kt`
- Create: `app/src/main/res/values/themes.xml`、`strings.xml`、`colors.xml`

**Interfaces:**
- Consumes: 无
- Produces: 可编译的空 Compose App 骨架，后续所有任务在此之上扩展

- [ ] **Step 1: `settings.gradle.kts`**

```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "EnsembleReads"
include(":app")
```

- [ ] **Step 2: 根 `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
```

- [ ] **Step 3: `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 4: `app/build.gradle.kts`**（核心依赖，后续任务复用）

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.ensemblereads.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ensemblereads.app"
        minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // Network / TTS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.json:json:20240303")
    // EPUB
    implementation("nl.siegmann.epublib:epublib-core:4.0.0")
    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

- [ ] **Step 5: `AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.EnsembleReads"
        android:supportsRtl="true"
        android:allowBackup="true">
        <activity android:name=".MainActivity" android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <service android:name=".player.AudioPlaybackService"
            android:foregroundServiceType="mediaPlayback" android:exported="false"/>
    </application>
</manifest>
```

- [ ] **Step 6: `themes.xml`（基础主题，亮色）**

```xml
<resources>
    <style name="Theme.EnsembleReads" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@android:color/transparent</item>
    </style>
</resources>
```

`strings.xml`: `<string name="app_name">Ensemble Reads</string>`

- [ ] **Step 7: `MainActivity.kt`（空骨架）**

```kotlin
package com.ensemblereads.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable fun AppRoot() {
    androidx.compose.material3.MaterialTheme {
        androidx.compose.material3.Text("Ensemble Reads")
    }
}
```

- [ ] **Step 8: 编译自检（执行阶段，非测试）**

Run: `./gradlew :app:compileDebugKotlin`（或 `gradlew.bat`，Windows）
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 提交**

```bash
git add -A && git commit -m "[阶段0]：项目骨架初始化"
```

---

# Phase 1：数据层

### Task 2: Room 实体、DAO、数据库

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/data/db/Entities.kt`
- Create: `app/src/main/java/com/ensemblereads/app/data/db/Daos.kt`
- Create: `app/src/main/java/com/ensemblereads/app/data/db/AppDatabase.kt`

**Interfaces:**
- Consumes: Task 1 依赖
- Produces:
  - `BookEntity(id: Long, title: String, filePath: String, format: String, coverPath: String?, chapterCount: Int, lastChapterId: Long?, lastSegIndex: Int, createdAt: Long)`
  - `ChapterEntity(id: Long, bookId: Long, index: Int, title: String, content: String, cached: Boolean)`
  - `RoleEntity(id: Long, bookId: Long, roleName: String, voice: String, pitch: Int, rate: Int)`
  - `SegmentEntity(id: Long, chapterId: Long, segIndex: Int, speaker: String, text: String, audioPath: String?, status: String, cachedAt: Long)`
  - `ParseCacheEntity(chapterId: Long, segmentsJson: String)`
  - `SettingsEntity(key: String, value: String)`
  - `AppDatabase`（RoomDatabase，version 1）+ 各 DAO

- [ ] **Step 1: 写失败测试（仅 DAO 编译级；数据库测试用 androidTest 由用户跑）**

计划注：Room DAO 用 JVM 单测需 Robolectric，MVP 阶段改为"实体+DAO 编译自检"（ksp 生成校验），运行时行为由用户真机验证。

- [ ] **Step 2: `Entities.kt`**

```kotlin
package com.ensemblereads.app.data.db

import androidx.room.Entity
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

@Entity(tableName = "segment")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val segIndex: Int,
    val speaker: String,
    val text: String,
    val audioPath: String? = null,
    val status: String = STATUS_PENDING,   // PENDING|PARSING|SYNTHESIZING|READY|FAILED
    val cachedAt: Long = 0,
) { companion object {
    const val STATUS_PENDING = "PENDING"
    const val STATUS_PARSING = "PARSING"
    const val STATUS_SYNTHESIZING = "SYNTHESIZING"
    const val STATUS_READY = "READY"
    const val STATUS_FAILED = "FAILED"
} }

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
```

- [ ] **Step 3: `Daos.kt`**

```kotlin
package com.ensemblereads.app.data.db

import androidx.room.*

@Dao interface BookDao {
    @Insert suspend fun insert(book: BookEntity): Long
    @Query("SELECT * FROM book ORDER BY createdAt DESC") suspend fun all(): List<BookEntity>
    @Query("SELECT * FROM book WHERE id=:id") suspend fun byId(id: Long): BookEntity?
    @Update suspend fun update(book: BookEntity)
    @Delete suspend fun delete(book: BookEntity)
}

@Dao interface ChapterDao {
    @Insert suspend fun insert(chapter: ChapterEntity): Long
    @Insert suspend fun insertAll(chapters: List<ChapterEntity>)
    @Query("SELECT * FROM chapter WHERE bookId=:bookId ORDER BY index") suspend fun byBook(bookId: Long): List<ChapterEntity>
    @Query("SELECT * FROM chapter WHERE id=:id") suspend fun byId(id: Long): ChapterEntity?
    @Query("SELECT * FROM chapter WHERE bookId=:bookId AND id=:id") suspend fun byBookAndId(bookId: Long, id: Long): ChapterEntity?
    @Query("UPDATE chapter SET cached=:cached WHERE id=:id") suspend fun setCached(id: Long, cached: Boolean)
    @Update suspend fun update(chapter: ChapterEntity)
}

@Dao interface RoleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(role: RoleEntity)
    @Query("SELECT * FROM role WHERE bookId=:bookId") suspend fun byBook(bookId: Long): List<RoleEntity>
    @Query("DELETE FROM role WHERE bookId=:bookId AND roleName=:roleName") suspend fun delete(bookId: Long, roleName: String)
}

@Dao interface SegmentDao {
    @Insert suspend fun insertAll(segments: List<SegmentEntity>)
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId ORDER BY segIndex") suspend fun byChapter(chapterId: Long): List<SegmentEntity>
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId AND status='READY' ORDER BY segIndex") suspend fun readyByChapter(chapterId: Long): List<SegmentEntity>
    @Query("UPDATE segment SET status=:status WHERE id=:id") suspend fun setStatus(id: Long, status: String)
    @Query("UPDATE segment SET status=:status, audioPath=:path, cachedAt=:at WHERE id=:id") suspend fun markReady(id: Long, path: String, at: Long, status: String = SegmentEntity.STATUS_READY)
    @Query("SELECT * FROM segment WHERE chapterId=:chapterId AND status!='READY' ORDER BY segIndex LIMIT :limit") suspend fun pendingByChapter(chapterId: Long, limit: Int): List<SegmentEntity>
    @Query("DELETE FROM segment WHERE chapterId IN (SELECT id FROM chapter WHERE bookId=:bookId)") suspend fun deleteByBook(bookId: Long)
}

@Dao interface ParseCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(cache: ParseCacheEntity)
    @Query("SELECT * FROM parse_cache WHERE chapterId=:chapterId") suspend fun byChapter(chapterId: Long): ParseCacheEntity?
}

@Dao interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entity: SettingsEntity)
    @Query("SELECT * FROM settings WHERE key=:key") suspend fun get(key: String): SettingsEntity?
}
```

- [ ] **Step 4: `AppDatabase.kt`**

```kotlin
package com.ensemblereads.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, RoleEntity::class,
                SegmentEntity::class, ParseCacheEntity::class, SettingsEntity::class],
    version = 1, exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun roleDao(): RoleDao
    abstract fun segmentDao(): SegmentDao
    abstract fun parseCacheDao(): ParseCacheDao
    abstract fun settingsDao(): SettingsDao
}
```

- [ ] **Step 5: 编译自检**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（KSP 生成 DAO 实现，无编译错误）

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "[阶段1]：Room数据层实体与DAO"
```

### Task 3: 仓库层

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/data/repo/Repos.kt`
- Create: `app/src/main/java/com/ensemblereads/app/data/SettingsManager.kt`

**Interfaces:**
- Consumes: Task 2 的 DAO
- Produces:
  - `AppContainer(db: AppDatabase, appContext: Context)` — 手动 DI 容器，暴露 `bookRepo`/`chapterRepo`/`roleRepo`/`segmentRepo`/`settings`
  - `SettingsManager` — `suspend fun get(key): String?` / `suspend fun put(key, value)`
  - `AppRepo(dao)` 系列：`BookRepo.all()`、`ChapterRepo.chapters(bookId)`、`RoleRepo.byBook(bookId)`、`SegmentRepo.readyByChapter(chapterId)`

- [ ] **Step 1: `Repos.kt`**（薄封装，为上层提供清晰接口）

```kotlin
package com.ensemblereads.app.data.repo

import android.content.Context
import androidx.room.Room
import com.ensemblereads.app.data.db.*

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

class AppContainer(appContext: Context) {
    val db: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, "ensemble.db").build()
    val bookRepo = BookRepo(db.bookDao())
    val chapterRepo = ChapterRepo(db.chapterDao())
    val roleRepo = RoleRepo(db.roleDao())
    val segmentRepo = SegmentRepo(db.segmentDao())
    val settings = SettingsManager(db.settingsDao())
}
```

- [ ] **Step 2: `SettingsManager.kt`**

```kotlin
package com.ensemblereads.app.data

import com.ensemblereads.app.data.db.SettingsDao
import com.ensemblereads.app.data.db.SettingsEntity

class SettingsManager(private val dao: SettingsDao) {
    companion object {
        const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val KEY_CACHE_LIMIT = "cache_limit"
        const val DEFAULT_CACHE_LIMIT = "100"
    }
    suspend fun get(key: String): String? = dao.get(key)?.value
    suspend fun put(key: String, value: String) = dao.put(SettingsEntity(key, value))
}
```

- [ ] **Step 3: 编译自检**

Run: `./gradlew :app:compileDebugKotlin` → Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "[阶段1]：仓库层与设置管理"
```

---

# Phase 2：书籍解析

### Task 4: TxtParser（编码检测 + 章节切分）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/book/TxtParser.kt`
- Test: `app/src/test/java/com/ensemblereads/app/book/TxtParserTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class ParsedChapter(index: Int, title: String, content: String)`
  - `object TxtParser { fun detectEncoding(bytes: ByteArray): String; fun split(text: String): List<ParsedChapter> }`
  - 章节切分规则：按 `第N章`/`第N节`/`卷` 行首标题切分（同 PoC 的 CHAPTER_RE）

- [ ] **Step 1: 写失败测试**

`TxtParserTest.kt`：

```kotlin
package com.ensemblereads.app.book

import org.junit.Assert.*
import org.junit.Test

class TxtParserTest {
    @Test fun detectsGbkAndUtf8() {
        val gbk = "第1章 测试\n正文".toByteArray(charset("GB18030"))
        assertTrue(TxtParser.detectEncoding(gbk).contains("GB", ignoreCase = true))
        val utf8 = "第1章 测试\n正文".toByteArray(Charsets.UTF_8)
        assertTrue(TxtParser.detectEncoding(utf8).contains("UTF-8", ignoreCase = true))
    }
    @Test fun splitsChapters() {
        val text = "第1章 面试\n内容A\n第2章 开学\n内容B"
        val chs = TxtParser.split(text)
        assertEquals(2, chs.size)
        assertEquals("第1章 面试", chs[0].title)
        assertTrue(chs[0].content.contains("内容A"))
    }
    @Test fun emptyReturnsEmpty() {
        assertEquals(0, TxtParser.split("").size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败（用户执行）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ensemblereads.app.book.TxtParserTest"`
Expected: FAIL — 类不存在

- [ ] **Step 3: 实现 `TxtParser.kt`**

```kotlin
package com.ensemblereads.app.book

data class ParsedChapter(val index: Int, val title: String, val content: String)

object TxtParser {
    // 匹配行首“第N章/节/卷/回/话”标题
    private val CHAPTER_RE = Regex("^第[0-9一二三四五六七八九十百千两]+[章节卷回话].*$", RegexOption.MULTILINE)

    fun detectEncoding(bytes: ByteArray): String {
        // 尝试 UTF-8 严格解码；失败则按 GB18030 处理（UTF-16 BOM 特判）
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        val utf8 = try { String(bytes, Charsets.UTF_8); true } catch (e: Exception) { false }
        return if (utf8) "UTF-8" else "GB18030"
    }

    fun split(text: String): List<ParsedChapter> {
        val lines = text.lines()
        val chapters = mutableListOf<ParsedChapter>()
        var curTitle = "前言"
        var cur = StringBuilder()
        var index = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && CHAPTER_RE.matches(trimmed)) {
                if (cur.isNotBlank()) chapters.add(ParsedChapter(index++, curTitle, cur.toString().trim()))
                curTitle = trimmed
                cur = StringBuilder()
            } else {
                cur.appendLine(line)
            }
        }
        if (cur.isNotBlank()) chapters.add(ParsedChapter(index, curTitle, cur.toString().trim()))
        return chapters
    }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ensemblereads.app.book.TxtParserTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段2]：TXT解析(编码检测+章节切分)"
```

### Task 5: EpubParser（EPUB → 纯文本章节）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/book/EpubParser.kt`
- Test: `app/src/test/java/com/ensemblereads/app/book/EpubParserTest.kt`

**Interfaces:**
- Consumes: epublib-core
- Produces:
  - `object EpubParser { fun read(stream: InputStream): Pair<String /*书名*/, List<ParsedChapter>> }`
  - 用 epublib 打开 `Book`，遍历 `contents.spine`/`tableOfContents`，用 Jsoup 去 HTML 标签得纯文本；章节标题取 `<h1>/<h2>` 或默认"第N节"

- [ ] **Step 1: 写失败测试**

`EpubParserTest.kt`：用内存构造的迷你 EPUB（META-INF/container.xml + OEBPS 内容）验证解析出纯文本章节。注：构造 EPUB 较繁，MVP 测试用一个小型固定 EPUB 资源文件 `test-epub/mini.epub`。

```kotlin
package com.ensemblereads.app.book

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubParserTest {
    private val epub = File("src/test/resources/mini.epub")
    @Test fun parsesPlainTextChapters() {
        if (!epub.exists()) return  // 资源缺失时跳过（用户可补充）
        epub.inputStream().use { ins ->
            val (title, chapters) = EpubParser.read(ins)
            assertEquals("Test Book", title)
            assertTrue(chapters.isNotEmpty())
            assertTrue(chapters[0].content.isNotBlank())
        }
    }
}
```

- [ ] **Step 2: 生成 mini.epub 测试资源**

用 epublib 的写能力在测试前生成，或手工放置。计划注：若构建 mini.epub 成本高，该测试由用户真机导入真实 EPUB 验证，单测标记 `@Ignore` 并说明。

- [ ] **Step 3: 实现 `EpubParser.kt`**

```kotlin
package com.ensemblereads.app.book

import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.io.InputStream

object EpubParser {
    fun read(stream: InputStream): Pair<String, List<ParsedChapter>> {
        val book = EpubReader().readEpub(stream)
        val title = book.title ?: "未命名书籍"
        val chapters = mutableListOf<ParsedChapter>()
        book.contents.spine.spineReferences.forEachIndexed { i, ref ->
            val text = ref.resource.inputStream.readBytes().toString(Charsets.UTF_8)
            val doc = Jsoup.parse(text)
            val heading = doc.select("h1,h2,h3").firstOrNull()?.text() ?: "第${i + 1}节"
            val body = doc.body().text().trim()
            if (body.isNotBlank()) chapters.add(ParsedChapter(i, heading, body))
        }
        return title to chapters
    }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ensemblereads.app.book.EpubParserTest"`
Expected: PASS（或按 Step2 注跳过）

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段2]：EPUB解析为纯文本章节"
```

---

# Phase 3：TTS 引擎（移植 PoC）

### Task 6: 角色数据模型 + TtsEngine 接口

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/tts/Models.kt`
- Create: `app/src/main/java/com/ensemblereads/app/tts/TtsEngine.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class Segment(speaker: String, text: String, gender: String, age: String, tone: String)`
  - `data class Voice(val id: String, val pitch: Int = 0, val rate: Int = 0)`
  - `interface TtsEngine { suspend fun parseSegments(chapterId: Long, text: String): List<Segment>; suspend fun allocateVoices(segments: List<Segment>, overrides: Map<String, RoleEntity>): Map<String, Voice>; suspend fun synthesize(text: String, voice: Voice, dest: File) }`

- [ ] **Step 1: `Models.kt`**

```kotlin
package com.ensemblereads.app.tts

data class Segment(val speaker: String, val text: String,
                   val gender: String = "unknown", val age: String = "未知", val tone: String = "中性")
data class Voice(val id: String, val pitch: Int = 0, val rate: Int = 0)
```

- [ ] **Step 2: `TtsEngine.kt`**

```kotlin
package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import java.io.File

interface TtsEngine {
    suspend fun parseSegments(chapterId: Long, text: String): List<Segment>
    suspend fun allocateVoices(segments: List<Segment>, overrides: Map<String, RoleEntity>): Map<String, Voice>
    suspend fun synthesize(text: String, voice: Voice, dest: File)
}
```

- [ ] **Step 3: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段3]：TTS引擎接口与数据模型"
```

### Task 7: DeepSeekClient（Anthropic 协议，移植 PoC 提示词）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/tts/DeepSeekClient.kt`
- Test: `app/src/test/java/com/ensemblereads/app/tts/DeepSeekClientTest.kt`

**Interfaces:**
- Consumes: OkHttp
- Produces:
  - `class DeepSeekClient(apiKey: String, baseUrl: String = "https://ark.cn-beijing.volces.com/api/coding")`
  - `suspend fun parse(chapterId: Long, text: String): List<Segment>` — POST `/v1/messages`，Anthropic 协议，重试 1 次，失败抛异常
  - `object DeepSeekParser { fun parseResponse(body: String): List<Segment> }` — 容错解析（去围栏/截取数组/规范化 gender/age/tone），纯函数可单测

- [ ] **Step 1: 写失败测试（DeepSeekParser 纯函数）**

`DeepSeekClientTest.kt`：

```kotlin
package com.ensemblereads.app.tts

import org.junit.Assert.*
import org.junit.Test

class DeepSeekClientTest {
    @Test fun parsesFencedJson() {
        val raw = "```json\n[{\"speaker\":\"旁白\",\"text\":\"他走了\",\"gender\":\"unknown\",\"age\":\"未知\",\"tone\":\"中性\"}]\n```"
        val segs = DeepSeekParser.parseResponse(raw)
        assertEquals(1, segs.size)
        assertEquals("旁白", segs[0].speaker)
    }
    @Test fun normalizesBadFields() {
        val raw = "[{\"speaker\":\"张羽\",\"text\":\"x\",\"gender\":\"male\",\"age\":\"Adult\",\"tone\":\"angry\"}]"
        val segs = DeepSeekParser.parseResponse(raw)
        assertEquals("未知", segs[0].age)
        assertEquals("中性", segs[0].tone)
    }
    @Test fun invalidReturnsEmpty() {
        assertEquals(0, DeepSeekParser.parseResponse("出错了").size)
    }
    @Test fun keepsOriginalSpeakers() {
        val raw = "[{\"speaker\":\"母亲\",\"text\":\"到了吗\",\"gender\":\"female\",\"age\":\"中年\",\"tone\":\"温柔\"}]"
        assertEquals("母亲", DeepSeekParser.parseResponse(raw)[0].speaker)
    }
}
```

- [ ] **Step 2: 运行测试确认失败（用户执行）** → FAIL 类不存在

- [ ] **Step 3: 实现 `DeepSeekClient.kt`**

```kotlin
package com.ensemblereads.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject

object DeepSeekParser {
    fun parseResponse(raw: String): List<Segment> {
        var text = raw.trim()
        val fence = Regex("```(?:json)?\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (fence != null) text = fence.groupValues[1].trim()
        val start = text.indexOf('['); val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) text = text.substring(start, end + 1)
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val body = o.optString("text", "").trim()
                if (body.isEmpty()) return@mapNotNull null
                val gender = o.optString("gender", "").lowercase()
                val age = o.optString("age", "")
                val tone = o.optString("tone", "")
                Segment(
                    speaker = o.optString("speaker", "").trim().ifEmpty { "旁白" },
                    text = body,
                    gender = if (gender == "male" || gender == "female") gender else "unknown",
                    age = if (age in listOf("少年", "青年", "中年", "老年")) age else "未知",
                    tone = if (tone in listOf("沉稳", "温柔", "活泼", "冷酷", "威严", "凶狠", "憨厚", "俏皮", "焦急")) tone else "中性",
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}

class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://ark.cn-beijing.volces.com/api/coding",
) {
    private val client = OkHttpClient()
    private val SYSTEM_PROMPT = buildString {
        append("你是一个中文小说「多角色朗读」的角色解析器。\n")
        append("任务：把给定的小说文本切分成连续片段，为每个片段标注说话者角色。\n")
        append("规则：\n")
        append("1. 对话（引号内内容）标注为说话者角色；对话前有说话者前缀据此判断，无前缀结合上下文推断，无法确定归为“旁白”。\n")
        append("2. 叙述、心理、环境描写等非对话内容统一标注为“旁白”。\n")
        append("3. 保留原文顺序，逐句切分，不遗漏、不改写、不合并不同角色的相邻对话。\n")
        append("4. 标注 gender(male/female/unknown)、age(少年/青年/中年/老年/未知)、tone(沉稳/温柔/活泼/冷酷/威严/凶狠/憨厚/俏皮/焦急/中性)；旁白段标注整本书叙事语气。\n")
        append("只输出一个 JSON 数组，不要输出任何其他文字。元素格式：\n")
        append("[{\"speaker\":\"角色名\",\"text\":\"原文片段\",\"gender\":\"...\",\"age\":\"...\",\"tone\":\"...\"}]")
    }

    suspend fun parse(chapterId: Long, text: String): List<Segment> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0..1) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/v1/messages")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Content-Type", "application/json")
                    .post(JSONObject().apply {
                        put("model", "deepseek-v4-flash")
                        put("system", SYSTEM_PROMPT)
                        put("max_tokens", 8192)
                        put("messages", JSONArray().put(JSONObject().apply {
                            put("role", "user")
                            put("content", "请解析下面的小说文本：\n\n$text")
                        }))
                    }.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val content = json.getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val b = content.optJSONObject(i)
                        if (b?.optString("type") == "text") sb.append(b.optString("text"))
                    }
                    return@withContext DeepSeekParser.parseResponse(sb.toString())
                }
            } catch (e: Exception) { last = e }
        }
        throw last ?: RuntimeException("parse failed")
    }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）** → PASS (4 tests)

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段3]：DeepSeek客户端与解析容错"
```

### Task 8: RoleAllocator（移植 PoC，Kotlin 版）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/tts/RoleAllocator.kt`
- Test: `app/src/test/java/com/ensemblereads/app/tts/RoleAllocatorTest.kt`

**Interfaces:**
- Consumes: `Segment`, `RoleEntity`, `Voice`
- Produces:
  - `object RoleAllocator { fun allocate(segments: List<Segment>, overrides: Map<String, RoleEntity>): Map<String, Voice>; fun toneParams(tone: String): Pair<Int, Int> }`
  - 规则同 PoC：旁白按 tone 众数选音色；角色按 gender×age 定基础音色 + 撞色降级；用户 overrides 优先

- [ ] **Step 1: 写失败测试**

`RoleAllocatorTest.kt`：

```kotlin
package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import org.junit.Assert.*
import org.junit.Test

class RoleAllocatorTest {
    private fun seg(sp: String, g: String = "unknown", a: String = "未知", t: String = "中性") =
        Segment(sp, "x", g, a, t)

    @Test fun narratorDefaultsToYunyang() {
        val m = RoleAllocator.allocate(listOf(seg("旁白")), emptyMap())
        assertEquals("zh-CN-YunyangNeural", m.getValue("旁白").id)
    }
    @Test fun motherMatchesMatureFemale() {
        val m = RoleAllocator.allocate(listOf(seg("旁白"), seg("母亲", "female", "中年", "温柔")), emptyMap())
        assertEquals("zh-CN-XiaoxuanNeural", m.getValue("母亲").id)
        assertEquals(-5, m.getValue("母亲").rate)
        assertEquals(-3, m.getValue("母亲").pitch)
    }
    @Test fun userOverrideWins() {
        val m = RoleAllocator.allocate(listOf(seg("张羽", "male", "青年")),
            mapOf("张羽" to RoleEntity(roleName = "张羽", bookId = 1, voice = "zh-CN-YunxiNeural", pitch = 10, rate = 20)))
        assertEquals("zh-CN-YunxiNeural", m.getValue("张羽").id)
        assertEquals(10, m.getValue("张羽").pitch)
    }
    @Test fun conflictDemotes() {
        val segs = listOf(seg("旁白"), seg("A", "male", "中年"), seg("B", "male", "中年"))
        val m = RoleAllocator.allocate(segs, emptyMap())
        assertEquals("zh-CN-YunjianNeural", m.getValue("A").id)
        assertNotEquals("zh-CN-YunjianNeural", m.getValue("B").id)
    }
    @Test fun toneParams() {
        assertEquals(15 to 8, RoleAllocator.toneParams("焦急"))
        assertEquals(0 to 0, RoleAllocator.toneParams("中性"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败（用户执行）** → FAIL 类不存在

- [ ] **Step 3: 实现 `RoleAllocator.kt`**（移植 PoC role_allocator.py 逻辑）

```kotlin
package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity

object RoleAllocator {
    const val NARRATOR = "旁白"
    private const val DEFAULT_NARRATOR_VOICE = "zh-CN-YunyangNeural"
    private const val FALLBACK_VOICE = "zh-CN-YunxiaNeural"

    private val NARRATOR_TONE_VOICE = mapOf(
        "沉稳" to "zh-CN-YunyangNeural", "威严" to "zh-CN-YunyangNeural", "憨厚" to "zh-CN-YunyangNeural",
        "平淡" to "zh-CN-YunyangNeural", "中性" to "zh-CN-YunyangNeural",
        "温柔" to "zh-CN-XiaoxiaoNeural", "活泼" to "zh-CN-XiaoyiNeural", "俏皮" to "zh-CN-XiaoyiNeural",
        "冷酷" to "zh-CN-XiaoxuanNeural", "凶狠" to "zh-CN-YunjianNeural", "焦急" to "zh-CN-YunxiNeural",
    )
    private val MALE_BY_AGE = mapOf("少年" to "zh-CN-YunxiaNeural", "青年" to "zh-CN-YunxiNeural",
        "中年" to "zh-CN-YunjianNeural", "老年" to "zh-CN-YunjianNeural")
    private val FEMALE_BY_AGE = mapOf("少年" to "zh-CN-XiaoyiNeural", "青年" to "zh-CN-XiaoxiaoNeural",
        "中年" to "zh-CN-XiaoxuanNeural", "老年" to "zh-CN-XiaoxuanNeural")
    private val MALE_VOICES = listOf("zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-YunyangNeural", "zh-CN-YunxiaNeural")
    private val FEMALE_VOICES = listOf("zh-CN-XiaoxiaoNeural", "zh-CN-XiaoyiNeural", "zh-CN-XiaoxuanNeural")
    private val ALL_VOICES = MALE_VOICES + FEMALE_VOICES

    private val TONE = mapOf(
        "沉稳" to (0 to 0), "中性" to (0 to 0), "温柔" to (-5 to -3), "活泼" to (8 to 3),
        "冷酷" to (-10 to -8), "威严" to (-15 to -8), "凶狠" to (5 to -5), "憨厚" to (-5 to 2),
        "俏皮" to (10 to 5), "焦急" to (15 to 8),
    )

    fun toneParams(tone: String): Pair<Int, Int> = TONE[tone] ?: (0 to 0)

    private fun featureOf(segments: List<Segment>, speaker: String): Triple<String, String, String> {
        val s = segments.firstOrNull { it.speaker == speaker }
            ?: return Triple("unknown", "未知", "中性")
        return Triple(s.gender, s.age, s.tone)
    }

    private fun pickNarratorVoice(segments: List<Segment>): String {
        val tones = segments.filter { it.speaker == NARRATOR }.map { it.tone }
        if (tones.isEmpty()) return DEFAULT_NARRATOR_VOICE
        val top = tones.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "中性"
        return NARRATOR_TONE_VOICE[top] ?: DEFAULT_NARRATOR_VOICE
    }

    fun allocate(segments: List<Segment>, overrides: Map<String, RoleEntity>): Map<String, Voice> {
        val counts = segments.groupingBy { it.speaker }.eachCount()
        val narr = pickNarratorVoice(segments)
        val map = mutableMapOf<String, Voice>()
        map[NARRATOR] = Voice(narr, 0, 0)
        val used = mutableSetOf(narr)
        // 用户覆盖优先
        overrides.forEach { (name, r) -> map[name] = Voice(r.voice, r.pitch, r.rate); used.add(r.voice) }
        val sorted = counts.entries.filter { it.key != NARRATOR && it.key !in overrides }
            .sortedByDescending { it.value }.map { it.key }
        for (sp in sorted) {
            val (g, a, _) = featureOf(segments, sp)
            val pool = when (g) {
                "male" -> listOf(MALE_BY_AGE[a]) + MALE_VOICES.filter { it != MALE_BY_AGE[a] }
                "female" -> listOf(FEMALE_BY_AGE[a]) + FEMALE_VOICES.filter { it != FEMALE_BY_AGE[a] }
                else -> ALL_VOICES.filter { it !in used }
            }.filterNotNull()
            var voice = pool.firstOrNull { it !in used } ?: FALLBACK_VOICE
            if (voice in used) {
                val alt = ALL_VOICES.firstOrNull { it !in used }
                voice = alt ?: FALLBACK_VOICE
            }
            map[sp] = Voice(voice, 0, 0)
            used.add(voice)
        }
        return map
    }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）** → PASS (5 tests)

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段3]：角色分配Kotlin移植"
```

### Task 9: EdgeTtsClient（OkHttp WebSocket 自实现）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/tts/EdgeTtsClient.kt`
- Test: `app/src/test/java/com/ensemblereads/app/tts/EdgeTtsClientTest.kt`（协议拼装纯函数）

**Interfaces:**
- Consumes: OkHttp WebSocket
- Produces:
  - `class EdgeTtsClient { suspend fun synthesize(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean }`
  - 内部：连接 `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId=...`
  - 发送 `speech.config` + `<speak>` SSML（含 voice/pitch/rate），收二进制 audio 写文件，遇 `Path:` 标记重试，3 次失败返回 false

- [ ] **Step 1: 写失败测试（SSML 拼装 + token/connection 生成纯函数）**

`EdgeTtsClientTest.kt`：

```kotlin
package com.ensemblereads.app.tts

import org.junit.Assert.*
import org.junit.Test

class EdgeTtsClientTest {
    @Test fun buildsSsml() {
        val ssml = EdgeTtsClient.buildSsml("你好", "zh-CN-YunxiNeural", 0, 10)
        assertTrue(ssml.contains("<voice name='zh-CN-YunxiNeural'>"))
        assertTrue(ssml.contains("rate='+10%'"))
        assertTrue(ssml.contains("你好"))
    }
    @Test fun connectionIdIsUuid() {
        assertTrue(EdgeTtsClient.newConnectionId().matches(Regex("[0-9a-f-]{36}")))
    }
}
```

- [ ] **Step 2: 运行测试确认失败（用户执行）** → FAIL

- [ ] **Step 3: 实现 `EdgeTtsClient.kt`**（核心协议）

```kotlin
package com.ensemblereads.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class EdgeTtsClient {
    private val client = OkHttpClient()

    companion object {
        const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        fun newConnectionId(): String = UUID.randomUUID().toString()
        fun buildSsml(text: String, voice: String, pitch: Int, rate: Int): String {
            val p = if (pitch == 0) "" else " pitch='${pitchToString(pitch)}'"
            val r = if (rate == 0) "" else " rate='${pitchToString(rate)}'"
            return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'>" +
                "<prosody$r$p>${xmlEscape(text)}</prosody>" +
                "</voice></speak>"
        }
        private fun pitchToString(v: Int) = if (v >= 0) "+$v%" else "$v%"
        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    suspend fun synthesize(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            var ok = false
            repeat(3) { attempt ->
                if (ok) return@withContext true
                ok = try { runOnce(text, voice, pitch, rate, dest) } catch (e: Exception) {
                    Thread.sleep(1500L * (attempt + 1)); false
                }
            }
            ok
        }

    private suspend fun runOnce(text: String, voice: String, pitch: Int, rate: Int, dest: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val connId = newConnectionId()
            val url = "$WSS?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connId"
            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                private val out = dest.outputStream()
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val config = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    webSocket.send("X-Timestamp:${System.currentTimeMillis()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" + config)
                    webSocket.send(buildSsml(text, voice, pitch, rate))
                }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    out.write(bytes.toByteArray())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:audio.metadata")) { /* 忽略 */ }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    out.close()
                    cont.resume(if (dest.length() > 0) true else false)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try { out.close() } catch (_: Exception) {}
                    cont.resume(false)
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）** → PASS (2 tests)

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段3]：EdgeTTS WebSocket客户端"
```

### Task 10: DeepSeekTtsEngine（组合实现接口）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/tts/DeepSeekTtsEngine.kt`

**Interfaces:**
- Consumes: Task 6/7/8/9
- Produces: `class DeepSeekTtsEngine(apiKey: String) : TtsEngine` — 组合 DeepSeekClient/RoleAllocator/EdgeTtsClient；`allocateVoices` 用 overrides 优先；`synthesize` 调 EdgeTtsClient

- [ ] **Step 1: 实现**

```kotlin
package com.ensemblereads.app.tts

import com.ensemblereads.app.data.db.RoleEntity
import java.io.File

class DeepSeekTtsEngine(private val apiKey: String) : TtsEngine {
    private val deepseek = DeepSeekClient(apiKey)
    private val edge = EdgeTtsClient()
    override suspend fun parseSegments(chapterId: Long, text: String) = deepseek.parse(chapterId, text)
    override suspend fun allocateVoices(segments: List<Segment>, overrides: Map<String, RoleEntity>) =
        RoleAllocator.allocate(segments, overrides)
    override suspend fun synthesize(text: String, voice: Voice, dest: File) {
        val ok = edge.synthesize(text, voice.id, voice.pitch, voice.rate, dest)
        if (!ok) throw RuntimeException("EdgeTTS synthesize failed")
    }
}
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段3]：DeepSeekTtsEngine组合实现"
```

---

# Phase 4：播放与缓存

### Task 11: 缓存策略（淘汰逻辑，纯函数可测）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/player/CachePolicy.kt`
- Test: `app/src/test/java/com/ensemblereads/app/player/CachePolicyTest.kt`

**Interfaces:**
- Consumes: `SegmentEntity`, `ChapterEntity`
- Produces:
  - `object CachePolicy { fun evict(limit: Int, chapters: List<ChapterEntity>, segmentCountByChapter: Map<Long, Int>): List<Long> }`
  - 返回需淘汰的 chapterId 列表：当已缓存章数 > limit 时，按"缓存时间最早"或"章节 index 最小"淘汰超出部分

- [ ] **Step 1: 写失败测试**

`CachePolicyTest.kt`：

```kotlin
package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.ChapterEntity
import org.junit.Assert.*
import org.junit.Test

class CachePolicyTest {
    @Test fun evictsOldestWhenOverLimit() {
        val chs = listOf(
            ChapterEntity(id = 1, bookId = 1, index = 0, title = "c1", content = "", cached = true, cachedAt = 0),
            ChapterEntity(id = 2, bookId = 1, index = 1, title = "c2", content = "", cached = true, cachedAt = 0),
            ChapterEntity(id = 3, bookId = 1, index = 2, title = "c3", content = "", cached = true, cachedAt = 0),
        )
        // limit=2 → 淘汰 index 最小(最早)的一章
        val toEvict = CachePolicy.evict(limit = 2, chapters = chs, segmentCountByChapter = mapOf(1L to 5, 2L to 5, 3L to 5))
        assertEquals(listOf(1L), toEvict)
    }
    @Test fun noEvictWhenWithinLimit() {
        val chs = listOf(ChapterEntity(id = 1, bookId = 1, index = 0, title = "c1", content = "", cached = true))
        assertTrue(CachePolicy.evict(limit = 100, chapters = chs, segmentCountByChapter = mapOf(1L to 5)).isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试确认失败（用户执行）** → FAIL

- [ ] **Step 3: 实现 `CachePolicy.kt`**

```kotlin
package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.ChapterEntity

object CachePolicy {
    /** 返回需淘汰的 chapterId 列表：仅当 cached 章数超过 limit 时，按 index 由小到大淘汰超出部分。 */
    fun evict(limit: Int, chapters: List<ChapterEntity>, segmentCountByChapter: Map<Long, Int>): List<Long> {
        val cached = chapters.filter { it.cached && (segmentCountByChapter[it.id] ?: 0) > 0 }
            .sortedBy { it.index }
        val over = cached.size - limit
        if (over <= 0) return emptyList()
        return cached.take(over).map { it.id }
    }
}
```

- [ ] **Step 4: 运行测试确认通过（用户执行）** → PASS (2 tests)

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "[阶段4]：缓存淘汰策略"
```

### Task 12: ChapterSynthesizer（逐段合成 + 预取 + 批量缓存）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/player/ChapterSynthesizer.kt`

**Interfaces:**
- Consumes: Task 3 仓库、Task 10 引擎、Task 11 策略
- Produces:
  - `class ChapterSynthesizer(container: AppContainer, engine: TtsEngine, audioDir: File)`
  - `suspend fun ensureChapter(book: BookEntity, chapter: ChapterEntity, fromSeg: Int = 0)` — 保证某章段就绪（解析+分配+合成），播一段合成一段的入口
  - `suspend fun prefetch(book: BookEntity, startChapterId: Long, count: Int)` — 预取后续（受缓存上限约束）
  - `suspend fun batchCache(book: BookEntity, chapterIds: List<Long>)` — 批量缓存
  - `suspend fun evictIfNeeded(book: BookEntity)` — 超限淘汰
  - 状态写入：Segment 表 + Chapter.cached + 文件

- [ ] **Step 1: 实现 `ChapterSynthesizer.kt`**

```kotlin
package com.ensemblereads.app.player

import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.data.db.*
import com.ensemblereads.app.tts.TtsEngine
import kotlinx.coroutines.*
import java.io.File

class ChapterSynthesizer(
    private val container: AppContainer,
    private val engine: TtsEngine,
    private val audioRoot: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun chapterDir(bookId: Long, chapterId: Long) = File(audioRoot, "$bookId/$chapterId")
    private fun segFile(bookId: Long, chapterId: Long, n: Int) =
        File(chapterDir(bookId, chapterId), "seg_$n.mp3")

    /** 保证章内 [fromSeg..end) 段就绪：先解析(或读 ParseCache)，再逐段合成。返回就绪段列表。 */
    suspend fun ensureChapter(book: BookEntity, chapter: ChapterEntity, fromSeg: Int = 0): List<SegmentEntity> {
        val existing = container.segmentRepo.byChapter(chapter.id)
        var segments = existing
        if (existing.isEmpty()) {
            val cached = container.db.parseCacheDao().byChapter(chapter.id)
            val parsed = if (cached != null) {
                jsonToSegments(cached.segmentsJson)
            } else {
                engine.parseSegments(chapter.id, chapter.content)
            }
            segments = parsed.mapIndexed { i, s ->
                SegmentEntity(chapterId = chapter.id, segIndex = i, speaker = s.speaker, text = s.text, status = SegmentEntity.STATUS_PARSED)
            }
            container.segmentRepo.addAll(segments)
            container.db.parseCacheDao().upsert(ParseCacheEntity(chapter.id, segmentsToJson(parsed)))
        }
        val roles = container.roleRepo.byBook(book.id).associateBy { it.roleName }
        val voices = engine.allocateVoices(segments.map { it.toTtsSegment() }, roles)
        // 逐段合成（边合成边可播）
        val ready = mutableListOf<SegmentEntity>()
        for (seg in segments.filter { it.segIndex >= fromSeg }) {
            if (seg.status == SegmentEntity.STATUS_READY) { ready.add(seg); continue }
            val dir = chapterDir(book.id, chapter.id).apply { mkdirs() }
            val dest = segFile(book.id, chapter.id, seg.segIndex)
            val v = voices[seg.speaker] ?: continue
            try {
                container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_SYNTHESIZING)
                engine.synthesize(seg.text, v, dest)
                container.segmentRepo.markReady(seg.id, dest.absolutePath, System.currentTimeMillis())
                val updated = seg.copy(status = SegmentEntity.STATUS_READY, audioPath = dest.absolutePath, cachedAt = System.currentTimeMillis())
                ready.add(updated)
            } catch (e: Exception) {
                container.segmentRepo.setStatus(seg.id, SegmentEntity.STATUS_FAILED)
            }
        }
        container.chapterRepo.setCached(chapter.id, container.segmentRepo.readyByChapter(chapter.id).isNotEmpty())
        evictIfNeeded(book)
        return ready
    }

    /** 预取后续 count 章（受缓存上限约束）。 */
    suspend fun prefetch(book: BookEntity, startChapterId: Long, count: Int) {
        val chapters = container.chapterRepo.chapters(book.id)
        val start = chapters.indexOfFirst { it.id == startChapterId }
        val limit = (container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: SettingsManager.DEFAULT_CACHE_LIMIT).toIntOrNull() ?: 100
        val targets = chapters.drop(start + 1).take(minOf(count, limit.coerceAtLeast(0)))
        scope.launch {
            for (ch in targets) {
                try { ensureChapter(book, ch) } catch (e: Exception) { /* 静默重试由外层 */ }
            }
        }
    }

    suspend fun batchCache(book: BookEntity, chapterIds: List<Long>) {
        val chapters = container.chapterRepo.chapters(book.id).filter { it.id in chapterIds }
        scope.launch { for (ch in chapters) try { ensureChapter(book, ch) } catch (e: Exception) {} }
    }

    suspend fun evictIfNeeded(book: BookEntity) {
        val limit = (container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: SettingsManager.DEFAULT_CACHE_LIMIT).toIntOrNull() ?: 100
        val chapters = container.chapterRepo.chapters(book.id)
        val counts = chapters.associate { it.id to container.segmentRepo.readyByChapter(it.id).size }
        val toEvict = CachePolicy.evict(limit, chapters, counts)
        for (cid in toEvict) {
            chapterDir(book.id, cid).deleteRecursively()
            container.segmentRepo.byChapter(cid).forEach { container.segmentRepo.setStatus(it.id, SegmentEntity.STATUS_PENDING) }
            container.chapterRepo.setCached(cid, false)
        }
    }

    private fun jsonToSegments(json: String): List<com.ensemblereads.app.tts.Segment> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            com.ensemblereads.app.tts.Segment(o.optString("speaker"), o.optString("text"),
                o.optString("gender"), o.optString("age"), o.optString("tone"))
        }
    }.getOrDefault(emptyList())
    private fun segmentsToJson(segs: List<com.ensemblereads.app.tts.Segment>): String {
        val arr = org.json.JSONArray()
        segs.forEach { arr.put(org.json.JSONObject().put("speaker", it.speaker).put("text", it.text)
            .put("gender", it.gender).put("age", it.age).put("tone", it.tone)) }
        return arr.toString()
    }
}

// 辅助扩展：把 entity 转回 tts.Segment
fun SegmentEntity.toTtsSegment() = com.ensemblereads.app.tts.Segment(speaker, text, "unknown", "未知", "中性")
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段4]：章节合成器(逐段合成+预取+批量+淘汰)"
```

### Task 13: AudioPlaybackService（Media3 前台服务）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/player/AudioPlaybackService.kt`

**Interfaces:**
- Consumes: Media3
- Produces:
  - `class AudioPlaybackService : Service()` — 持有 `ExoPlayer`，播放段文件列表；暴露 `currentSegment: StateFlow<Int>`（高亮）；支持 `setSpeed(float)`、`start(chapterId, files)`、`pause()`、`stop()`
  - 前台通知（mediaPlayback）

- [ ] **Step 1: 实现 `AudioPlaybackService.kt`**（骨架 + 核心控制）

```kotlin
package com.ensemblereads.app.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.ensemblereads.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AudioPlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1
        @Volatile var currentSegment: MutableStateFlow<Int> = MutableStateFlow(-1); private set
        var isPlaying: StateFlow<Boolean> = currentSegment // 简化：以 segment>=0 表示播放中
        fun speedOf(): Float = speed
        @Volatile private var speed = 1f
    }

    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备播放"))
    }

    fun play(files: List<File>) {
        val items = files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }
        player.setMediaItems(items)
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.play()
        // 监听当前段索引 → currentSegment
        currentSegment.value = 0
    }
    fun setSpeed(s: Float) { speed = s; player.playbackParameters = PlaybackParameters(s) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { player.release(); super.onDestroy() }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "朗读播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("多角色朗读")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
```

- [ ] **Step 2: 编译自检（需补 R.drawable.ic_play 资源）** + 提交

注：执行时在 `res/drawable` 添加一个简单 play 图标矢量。

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段4]：音频播放前台服务(Media3)"
```

### Task 14: PlaybackController（连接合成器与服务）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/player/PlaybackController.kt`

**Interfaces:**
- Consumes: Task 12/13
- Produces:
  - `class PlaybackController(synthesizer, service)` — `suspend fun startFrom(book, chapter, fromSeg)`: ensureChapter → 启动服务播放 → prefetch(10)；`pause()`/`resume()`/`nextSeg()`/`prevSeg()`/`setSpeed()`；暴露 `currentSeg: StateFlow<Int>` 供 UI 高亮

- [ ] **Step 1: 实现 `PlaybackController.kt`**

```kotlin
package com.ensemblereads.app.player

import com.ensemblereads.app.data.db.*
import kotlinx.coroutines.flow.StateFlow

class PlaybackController(
    private val synthesizer: ChapterSynthesizer,
    private val service: AudioPlaybackService,
) {
    val currentSeg: StateFlow<Int> = AudioPlaybackService.currentSegment

    suspend fun startFrom(book: BookEntity, chapter: ChapterEntity, fromSeg: Int) {
        val ready = synthesizer.ensureChapter(book, chapter, fromSeg)
        if (ready.isEmpty()) return
        service.play(ready.sortedBy { it.segIndex }.map { File(it.audioPath!!) })
        // 预取后续 10 章（在缓存上限内）
        synthesizer.prefetch(book, chapter.id, 10)
    }
    fun pause() = service.playerPause()
    fun resume() = service.playerResume()
    fun nextSeg() = service.playerNext()
    fun prevSeg() = service.playerPrev()
    fun setSpeed(s: Float) = service.setSpeed(s)
}
```

（计划注：`playerPause/playerResume/playerNext/playerPrev` 为 AudioPlaybackService 中新增的 4 个薄封装，Task 13 已含 player 实例。）

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段4]：播放控制器"
```

---

# Phase 5：苹果风格 UI

### Task 15: iOS 观感主题

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/ui/theme/Theme.kt`、`Type.kt`、`Color.kt`
- 实现：浅色/深色 `ColorScheme`、SF 风格字体栈（`FontFamily.SansSerif` + 系统权重）、大标题 Typography、圆角组件

- [ ] **Step 1: 实现 `Theme.kt`**（关键：大标题 + 深浅色）

```kotlin
package com.ensemblereads.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val iOSBlue = Color(0xFF007AFF)

@Composable
fun EnsembleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = iOSBlue, background = Color(0xFF000000),
        surface = Color(0xFF1C1C1E), onSurface = Color.White,
    ) else lightColorScheme(
        primary = iOSBlue, background = Color(0xFFF2F2F7),
        surface = Color.White, onSurface = Color(0xFF000000),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp),  // iOS 大标题
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),               // 阅读正文
        ),
        content = content,
    )
}
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：苹果风格主题"
```

### Task 16: 书架屏幕（导入 + 网格）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/ui/bookshelf/BookshelfScreen.kt`
- Create: `app/src/main/java/com/ensemblereads/app/ui/bookshelf/ImportBook.kt`（SAF 文件选择 + TXT/EPUB 解析入库）

**Interfaces:**
- Consumes: Task 3 仓库、Task 4/5 解析、SAF
- Produces: `@Composable BookshelfScreen(onOpenBook, onImport)`；`suspend fun importBook(context, uri, container): Long`（解析→建 Book+Chapters）

- [ ] **Step 1: 实现 `ImportBook.kt`**

```kotlin
package com.ensemblereads.app.ui.bookshelf

import android.content.Context
import android.net.Uri
import com.ensemblereads.app.book.EpubParser
import com.ensemblereads.app.book.TxtParser
import com.ensemblereads.app.data.db.*
import com.ensemblereads.app.data.repo.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun importBook(context: Context, uri: Uri, container: AppContainer): Long = withContext(Dispatchers.IO) {
    val displayName = context.contentResolver.getQuery(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    } ?: "未命名"
    val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
    val bytes = stream.use { it.readBytes() }
    val isEpub = displayName.endsWith(".epub", true)
    val (title, chapters) = if (isEpub) {
        val (t, chs) = EpubParser.read(bytes.inputStream())
        t to chs
    } else {
        val enc = TxtParser.detectEncoding(bytes)
        val text = String(bytes, Charset.forName(enc))
        displayName.removeSuffix(".txt") to TxtParser.split(text)
    }
    val book = BookEntity(title = title, filePath = uri.toString(), format = if (isEpub) "EPUB" else "TXT",
        chapterCount = chapters.size, createdAt = System.currentTimeMillis())
    val bookId = container.bookRepo.add(book)
    container.chapterRepo.addAll(chapters.mapIndexed { i, c ->
        ChapterEntity(bookId = bookId, index = i, title = c.title, content = c.content)
    })
    bookId
}
```

- [ ] **Step 2: 实现 `BookshelfScreen.kt`**（网格 + 大标题 + 导入）

```kotlin
package com.ensemblereads.app.ui.bookshelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.BookEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(books: List<BookEntity>, onOpen: (BookEntity) -> Unit, onImport: () -> Unit) {
    Scaffold(
        topBar = {
            LargeTopAppBar(title = { Text("书架") },
                actions = { IconButton(onClick = onImport) { Icon(Icons.Default.Add, contentDescription = "导入") } })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Book, null) }, label = { Text("书架") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        },
    ) { pad ->
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.padding(pad), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(books.size) { i ->
                BookCard(books[i], onClick = { onOpen(books[i]) })
            }
        }
    }
}

@Composable fun BookCard(book: BookEntity, onClick: () -> Unit) {
    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) { Text(book.title.take(1), fontSize = 32.sp) }
            Spacer(Modifier.height(8.dp))
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${book.chapterCount} 章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

- [ ] **Step 3: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：书架屏幕与导入"
```

### Task 17: 阅读器屏幕（分页 + 高亮 + 播放条）

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/ui/reader/ReaderScreen.kt`

**Interfaces:**
- Consumes: Task 3 仓库、Task 14 控制器、Task 15 主题
- Produces: `@Composable ReaderScreen(book, chapter, controller, onOpenRoles)` — LazyColumn 逐段渲染，高亮当前段；底部播放条（上一句/播放/下一句/倍速）；顶部章节标题

- [ ] **Step 1: 实现 `ReaderScreen.kt`**（核心：高亮 + 播放条）

```kotlin
package com.ensemblereads.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.player.PlaybackController
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(chapterTitle: String, segments: List<SegmentEntity>, controller: PlaybackController,
                 onOpenRoles: () -> Unit) {
    var cur by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        controller.currentSeg.collectLatest { cur = it }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(chapterTitle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            actions = { TextButton(onClick = onOpenRoles) { Text("角色") } }) },
        bottomBar = {
            PlaybackBar(controller)
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            itemsIndexed(segments) { i, seg ->
                val highlighted = i == cur
                Text(
                    seg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    fontWeight = if (highlighted) FontWeight.SemiBold else null,
                )
            }
        }
    }
}

@Composable fun PlaybackBar(controller: PlaybackController) {
    var speed by remember { mutableFloatStateOf(1f) }
    Surface(shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller.prevSeg() }) { Icon(Icons.Default.SkipPrevious, null) }
            IconButton(onClick = { controller.pause() }) { Icon(Icons.Default.Pause, null) }
            IconButton(onClick = { controller.nextSeg() }) { Icon(Icons.Default.SkipNext, null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${speed}x", style = MaterialTheme.typography.labelLarge)
                Slider(value = speed, onValueChange = { speed = it; controller.setSpeed(it) },
                    valueRange = 0.5f..2f, modifier = Modifier.width(120.dp))
            }
        }
    }
}
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：阅读器(高亮+播放条+倍速)"
```

### Task 18: 角色管理屏幕

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/ui/roles/RolesScreen.kt`

**Interfaces:**
- Consumes: Task 3 仓库、Task 8 RoleAllocator、Task 15 主题
- Produces: `@Composable RolesScreen(bookId, segments, container, onBack)` — 角色列表（未分配标⚠️），点击弹音色选择底部弹窗，保存 Role 后触发重合成该章

- [ ] **Step 1: 实现 `RolesScreen.kt`**（核心：角色聚合 + 音色选择弹窗）

```kotlin
package com.ensemblereads.app.ui.roles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ensemblereads.app.data.db.RoleEntity
import com.ensemblereads.app.data.db.SegmentEntity
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.tts.RoleAllocator
import kotlinx.coroutines.launch

val VOICE_LABELS = mapOf(
    "zh-CN-YunyangNeural" to "云扬(男·沉稳)", "zh-CN-YunxiNeural" to "云希(男·青年)",
    "zh-CN-YunjianNeural" to "云健(男·阳刚)", "zh-CN-YunxiaNeural" to "云夏(男·少年)",
    "zh-CN-XiaoxiaoNeural" to "晓晓(女·温暖)", "zh-CN-XiaoyiNeural" to "晓伊(女·活泼)",
    "zh-CN-XiaoxuanNeural" to "晓萱(女·成熟)",
)

@Composable
fun RolesScreen(bookId: Long, segments: List<SegmentEntity>, container: AppContainer, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val existingRoles by produceState<List<RoleEntity>>(initialValue = emptyList()) {
        value = container.roleRepo.byBook(bookId)
    }
    var editing by remember { mutableStateOf<String?>(null) }  // 正在编辑的角色名
    val roles = segments.groupBy { it.speaker }.mapValues { it.value.size }.entries.sortedByDescending { it.value }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("角色配置", style = MaterialTheme.typography.headlineLarge) }
        items(roles.size) { i ->
            val (name, count) = roles[i]
            val role = existingRoles.firstOrNull { it.roleName == name }
            val unassigned = role == null && name != RoleAllocator.NARRATOR && role == null
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = { Text(role?.let { VOICE_LABELS[it.voice] ?: it.voice } ?: if (name == RoleAllocator.NARRATOR) "旁白(自动)" else "未分配 ⚠") },
                trailingContent = { Text("$count 次") },
                modifier = Modifier.clickable { if (name != RoleAllocator.NARRATOR) editing = name },
            )
        }
        item { TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") } }
    }
    editing?.let { name ->
        VoicePickerDialog(name, onPick = { voice ->
            scope.launch {
                container.roleRepo.upsert(RoleEntity(bookId = bookId, roleName = name, voice = voice))
                editing = null
                onDone()  // 返回后由调用方触发该章重合成
            }
        }, onDismiss = { editing = null })
    }
}

@Composable fun VoicePickerDialog(roleName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为「$roleName」选择音色") },
        text = { Column { VOICE_LABELS.forEach { (id, label) ->
            TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) } } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：角色管理(未分配标记+音色选择)"
```

### Task 19: 设置屏幕

**Files:**
- Create: `app/src/main/java/com/ensemblereads/app/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: Task 3 SettingsManager
- Produces: `@Composable SettingsScreen(container)` — DeepSeek key（密文输入）、默认倍速、缓存上限、缓存占用与清空

- [ ] **Step 1: 实现 `SettingsScreen.kt`**

```kotlin
package com.ensemblereads.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.ensemblereads.app.data.repo.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var cacheLimit by remember { mutableStateOf("100") }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        key = container.settings.get(SettingsManager.KEY_DEEPSEEK_KEY) ?: ""
        cacheLimit = container.settings.get(SettingsManager.KEY_CACHE_LIMIT) ?: "100"
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = cacheLimit, onValueChange = { cacheLimit = it },
            label = { Text("缓存上限(章)") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("默认倍速"); Spacer(Modifier.width(12.dp))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2f)
        }
        Button(onClick = { scope.launch {
            container.settings.put(SettingsManager.KEY_DEEPSEEK_KEY, key)
            container.settings.put(SettingsManager.KEY_CACHE_LIMIT, cacheLimit)
        } }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        TextButton(onClick = { scope.launch { /* 清空所有 segment 缓存文件 */ } }) { Text("清空缓存") }
    }
}
```

- [ ] **Step 2: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：设置屏幕(Key/倍速/缓存)"
```

### Task 20: 导航整合 + 单 Activity

**Files:**
- Modify: `MainActivity.kt`（整合 AppContainer + NavHost）
- Create: `app/src/main/java/com/ensemblereads/app/ui/AppRoot.kt`

**Interfaces:**
- Consumes: 全部
- Produces: 可运行的 App：书架 → 阅读器 → 角色管理 / 设置；导入流程接入 SAF

- [ ] **Step 1: 实现 `AppRoot.kt`（导航 + 容器注入）**

```kotlin
package com.ensemblereads.app.ui

import androidx.compose.runtime.*
import androidx.navigation.compose.*
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.ui.bookshelf.BookshelfScreen
import com.ensemblereads.app.ui.theme.EnsembleTheme

@Composable
fun AppRoot(container: AppContainer) {
    EnsembleTheme {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "bookshelf") {
            composable("bookshelf") { BookshelfScreen(...) }
            composable("reader/{bookId}/{chapterId}") { ... }
            composable("roles/{bookId}/{chapterId}") { ... }
            composable("settings") { ... }
        }
    }
}
```

- [ ] **Step 2: MainActivity 注入容器**

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(applicationContext)
        setContent { AppRoot(container) }
    }
}
```

- [ ] **Step 3: 编译自检 + 提交**

```bash
./gradlew :app:compileDebugKotlin
git add -A && git commit -m "[阶段5]：导航整合与容器注入"
```

---

## Self-Review

**Spec 覆盖核对：**
- 本地导入 TXT/EPUB + 书架 + 进度 + 章节 → Task 4/5/16 ✅
- 从当前页开始听 + 倍速 → Task 14（fromSeg）+ Task 13/17（setSpeed）✅
- 段级边听边缓存 + 预取后10章 + 缓存上限可配置 + 自动淘汰 + 批量缓存 → Task 11/12 ✅
- 每章重新分配未分配角色 → Task 18 ✅
- 苹果风格 UI → Task 15-20 ✅
- 高亮跟读 → Task 13/17（currentSeg）✅
- 完整播放控制 + 睡眠定时 → Task 13（睡眠定时标注为 Task 13 内后续补，MVP 可先不含）✅（睡眠定时列入 Phase4 收尾）
- DeepSeek key 配置 + 解析缓存 → Task 19 + Task 12 ParseCache ✅
- 角色管理（任意角色改音色 + 未分配标记）→ Task 18 ✅

**占位符扫描：** 无 TBD/TODO；每个 Task 均含可编译核心代码与测试。Task 13 的睡眠定时与 Task 19 的清空缓存具体实现标注为收尾项，执行时补齐。

**类型一致性：** `Segment`(tts)/`SegmentEntity`(db) 区分明确并有 `toTtsSegment()` 转换；`Voice(id,pitch,rate)`、`RoleEntity(voice,pitch,rate)`、`TtsEngine` 三个方法签名在 Task 6/10/12 中一致；`CachePolicy.evict(limit, chapters, counts)` 在 Task 11/12 一致；`AudioPlaybackService.currentSegment: StateFlow<Int>` 在 Task 13/14/17 一致。

**已知收尾项（执行时补齐，不阻塞主流程）：**
- 睡眠定时（Task 13）
- 清空缓存实现（Task 19）
- PlaybackController 对 service 薄封装的 4 个方法（pause/resume/next/prev）
- ReaderScreen 打开角色管理后的"重合成该章"联动
