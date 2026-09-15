package com.ensemblereads.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ensemblereads.app.data.SettingsManager
import com.ensemblereads.app.data.db.BookEntity
import com.ensemblereads.app.data.repo.AppContainer
import com.ensemblereads.app.player.AudioPlaybackService
import com.ensemblereads.app.player.ChapterSynthesizer
import com.ensemblereads.app.player.ConnectivityRetry
import com.ensemblereads.app.player.PlaybackController
import com.ensemblereads.app.tts.DeepSeekTtsEngine
import com.ensemblereads.app.tts.FallbackTtsEngine
import com.ensemblereads.app.tts.SystemTtsEngine
import com.ensemblereads.app.ui.bookshelf.BookProgress
import com.ensemblereads.app.ui.bookshelf.BookProgressCalculator
import com.ensemblereads.app.ui.bookshelf.BookshelfScreen
import com.ensemblereads.app.ui.bookshelf.importBook
import com.ensemblereads.app.ui.chapters.ChaptersScreen
import com.ensemblereads.app.ui.reader.ReaderScreen
import com.ensemblereads.app.ui.roles.RolesScreen
import com.ensemblereads.app.ui.search.SearchSheet
import com.ensemblereads.app.ui.settings.SettingsScreen
import com.ensemblereads.app.ui.theme.AppTheme
import com.ensemblereads.app.ui.theme.EnsembleTheme
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import java.io.File

/** 书架一次性加载的数据：书列表 + 每本书的朗读进度 + 无进度书的首章（点书直接进入）。 */
private data class ShelfData(
    val books: List<BookEntity> = emptyList(),
    val progress: Map<Long, BookProgress> = emptyMap(),
    val firstChapterId: Map<Long, Long> = emptyMap(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer) {
    // 主题设置需在 EnsembleTheme 之前读取
    val settings by container.settings.all().collectAsState(initial = emptyList())
    val appTheme = settings.firstOrNull { it.key == SettingsManager.KEY_THEME }?.value
        ?.let { v -> AppTheme.entries.firstOrNull { it.name.lowercase() == v.lowercase() } }
        ?: AppTheme.SYSTEM
    EnsembleTheme(theme = appTheme) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val nav = rememberNavController()

        // 启动前台播放服务（用于后台/锁屏朗读）；Android 12+ 后台启动受限时降级不崩溃
        LaunchedEffect(Unit) {
            try {
                val intent = Intent(context, AudioPlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException 等：受限时忽略，播放时再触发
            }
        }
        // Android 13+ 通知权限：后台/锁屏朗读的通知需要用户授权
        val notifPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 引擎依赖用户自配的 DeepSeek key（跟随设置 Flow，保存后无需重启即生效）；
        // 未配置时引擎为 null → 阅读器降级纯文本并提示去设置页配置
        val apiKey = settings.firstOrNull { it.key == SettingsManager.KEY_DEEPSEEK_KEY }?.value
            ?.trim()?.takeIf { it.isNotBlank() } ?: ""
        // 解析分块大小：长章角色识别依赖更大上下文（默认 5000），可配且保存即生效
        val parseChunk = settings.firstOrNull { it.key == SettingsManager.KEY_PARSE_CHUNK }?.value
            ?.toIntOrNull()?.takeIf { it >= 1000 } ?: SettingsManager.DEFAULT_PARSE_CHUNK.toInt()
        // 引擎链：DeepSeek(角色解析) + EdgeTTS(合成) 为主，失败降级系统 TTS（离线兜底）
        val engine = remember(apiKey, parseChunk) {
            if (apiKey.isBlank()) null
            else FallbackTtsEngine(DeepSeekTtsEngine(apiKey, parseChunk), SystemTtsEngine(context))
        }
        val synthesizer = remember(engine) {
            engine?.let { ChapterSynthesizer(container, it, File(context.filesDir, "audio")) }
        }
        val controller = remember(synthesizer) {
            synthesizer?.let { PlaybackController(context, it, container.statsRepo) }
        }

        // 断网恢复自动补合成当前章未就绪段（FAILED/PENDING）
        DisposableEffect(controller) {
            val retry = controller?.let { ConnectivityRetry(context) { it.retryCurrentChapter() } }
            retry?.register()
            onDispose { retry?.unregister() }
        }

        // SAF 导入
        var shelfTick by remember { mutableIntStateOf(0) }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        importBook(context, uri, container)
                        shelfTick++
                    } catch (e: CancellationException) {
                        throw e // 导入协程被取消：不弹误导提示
                    } catch (e: Exception) {
                        Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                    nav.navigate("bookshelf") { popUpTo("bookshelf") { inclusive = false }; launchSingleTop = true }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "bookshelf") {
            composable("bookshelf") {
                val shelf by produceState<ShelfData>(key1 = shelfTick, initialValue = ShelfData()) {
                    val books = container.bookRepo.all()
                    // 每本书的朗读进度：整书百分比（当前章前段数和 + 当前章段位置）÷ 全书段数
                    val segCounts = container.segmentRepo.segCountByChapterAll()
                    val progress = books.associate { b ->
                        b.id to (BookProgressCalculator.calc(b, segCounts) ?: BookProgress())
                    }
                    // 无进度书：记录首章，点书直接从第一章进入
                    val first = books.filter { it.lastChapterId == null }
                        .mapNotNull { b -> container.chapterRepo.chapters(b.id).firstOrNull()?.let { b.id to it.id } }
                        .toMap()
                    value = ShelfData(books, progress, first)
                }
                BookshelfScreen(
                    books = shelf.books,
                    progress = shelf.progress,
                    onOpen = { book ->
                        // 点书直接进入上次阅读位置（或首章），目录通过阅读器顶部进入
                        val target = book.lastChapterId ?: shelf.firstChapterId[book.id]
                        if (target != null) nav.navigate("reader/${book.id}/$target")
                        else nav.navigate("chapters/${book.id}")
                    },
                    onImport = { importLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*")) },
                    onSettings = { nav.navigate("settings") },
                    onRename = { book, newTitle ->
                        scope.launch {
                            container.bookRepo.update(book.copy(title = newTitle))
                            shelfTick++
                        }
                    },
                    onDelete = { book ->
                        scope.launch {
                            // 若正在播放本书，先停止再级联删除
                            if (controller?.currentBook?.id == book.id) {
                                controller?.stopCurrent()
                                AudioPlaybackService.instance?.stopSelf()
                            }
                            container.deleteBook(book)
                            shelfTick++
                        }
                    },
                )
            }

            composable(
                "chapters/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { back ->
                val bookId = back.arguments?.getLong("bookId") ?: return@composable
                val book by produceState<BookEntity?>(initialValue = null) { value = container.bookRepo.byId(bookId) }
                var chaptersTick by remember { mutableIntStateOf(0) }
                val chapters by produceState<List<com.ensemblereads.app.data.db.ChapterEntity>>(
                    key1 = chaptersTick, initialValue = emptyList(),
                ) { value = container.chapterRepo.chapters(bookId) }
                ChaptersScreen(
                    chapters = chapters,
                    onOpen = { ch -> nav.navigate("reader/$bookId/${ch.id}") },
                    onBack = { nav.popBackStack() },
                    onCacheAll = {
                        val b = book
                        if (b != null) scope.launch {
                            for (ch in chapters) {
                                runCatching { synthesizer?.ensureChapter(b, ch) }
                            }
                            chaptersTick++
                        }
                    },
                    onCacheOne = { ch ->
                        val b = book
                        if (b != null) scope.launch {
                            runCatching { synthesizer?.ensureChapter(b, ch) }
                            chaptersTick++
                        }
                    },
                )
            }

            composable(
                "reader/{bookId}/{chapterId}",
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("chapterId") { type = NavType.LongType },
                ),
            ) { back ->
                val bookId = back.arguments?.getLong("bookId") ?: return@composable
                val chapterId = back.arguments?.getLong("chapterId") ?: return@composable
                // 全部 produceState 以参数为 key：同一 reader route 换书/换章（launchSingleTop）时强制重载
                val book by produceState<BookEntity?>(key1 = bookId, initialValue = null) { value = container.bookRepo.byId(bookId) }
                val chapter by produceState<com.ensemblereads.app.data.db.ChapterEntity?>(key1 = chapterId, initialValue = null) {
                    value = container.chapterRepo.byBookAndId(bookId, chapterId)
                }
                val segments by produceState<List<com.ensemblereads.app.data.db.SegmentEntity>>(key1 = chapterId, initialValue = emptyList()) {
                    value = container.segmentRepo.byChapter(chapterId)
                }
                // 全书章节列表：供上一章/下一章导航 + 目录抽屉缓存状态；cacheTick 缓存后刷新
                var cacheTick by remember { mutableIntStateOf(0) }
                val chaptersState by produceState<List<com.ensemblereads.app.data.db.ChapterEntity>>(
                    key1 = bookId, key2 = cacheTick, initialValue = emptyList(),
                ) { value = container.chapterRepo.chapters(bookId) }
                // 当前章标注（书签/高亮/笔记），annTick 增删后刷新
                var annTick by remember { mutableIntStateOf(0) }
                val annotations by produceState<List<com.ensemblereads.app.data.db.AnnotationEntity>>(
                    key1 = chapterId, key2 = annTick, initialValue = emptyList(),
                ) { value = container.annotationRepo.byChapter(chapterId) }
                // 书内搜索（E14）
                var searchOpen by remember { mutableStateOf(false) }
                val ctrl = controller
                if (book != null && chapter != null) {
                    // 正文始终可读：朗读引擎缺失或启动失败时降级为纯文本，不崩溃
                    var segs by remember { mutableStateOf(segments) }
                    // 跟随 produceState 的 segments 更新（修复竞态：打开时列表可能晚于 book 加载完成）
                    LaunchedEffect(segments) { segs = segments }
                    // 阅读排版设置（字号/行距/字体），跟随设置 Flow 实时生效
                    val fontSize = settings.firstOrNull { it.key == SettingsManager.KEY_FONT_SIZE }?.value
                        ?.toFloatOrNull()?.coerceIn(16f, 24f) ?: 17f
                    val lineHeight = settings.firstOrNull { it.key == SettingsManager.KEY_LINE_HEIGHT }?.value
                        ?.toFloatOrNull()?.coerceIn(26f, 40f) ?: 28f
                    val fontFamily = settings.firstOrNull { it.key == SettingsManager.KEY_FONT_FAMILY }?.value ?: "default"
                    ReaderScreen(
                        chapterTitle = chapter!!.title,
                        content = chapter!!.content,
                        segments = segs,
                        controller = ctrl,
                        chapterId = chapterId,
                        chapters = chaptersState,
                        currentChapterId = chapterId,
                        onOpenChapter = { nav.navigate("reader/$bookId/${it.id}") { launchSingleTop = true } },
                        fontSizeSp = fontSize,
                        lineHeightSp = lineHeight,
                        fontFamilyName = fontFamily,
                        annotations = annotations,
                        onAddAnnotation = { seg, type, noteText, color ->
                            scope.launch {
                                container.annotationRepo.add(
                                    com.ensemblereads.app.data.db.AnnotationEntity(
                                        bookId = bookId, chapterId = chapterId, segIndex = seg.segIndex,
                                        type = type, noteText = noteText, color = color,
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                                annTick++
                            }
                        },
                        onDeleteAnnotation = { id ->
                            scope.launch { container.annotationRepo.delete(id); annTick++ }
                        },
                        onSearch = { searchOpen = true },
                        // 手动播放：用户点「播放」才启动（解析+合成+播放），打开书不自动播放/缓存
                        onStartPlayback = {
                            val c = ctrl
                            val b = book
                            val ch = chapter
                            if (c != null && b != null && ch != null) {
                                scope.launch {
                                    try {
                                        // 仅当前进度所在章续播 lastSegIndex；切到其他章从头播（修复切章后播放异常）
                                        val fromSeg = if (ch.id == b.lastChapterId) b.lastSegIndex else 0
                                        c.startFrom(b, ch, fromSeg)
                                        // 合成完成后刷新分段，让正文/高亮跟读生效
                                        segs = container.segmentRepo.byChapter(chapterId)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "朗读启动失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "未配置 DeepSeek Key，仅显示正文；可在设置中配置后开启朗读", Toast.LENGTH_LONG).show()
                            }
                        },
                        onCacheChapter = { ch ->
                            val b = book
                            if (b != null) {
                                scope.launch {
                                    runCatching { synthesizer?.ensureChapter(b, ch) }
                                    cacheTick++
                                }
                            }
                        },
                        onCacheAll = {
                            val b = book
                            if (b != null) {
                                scope.launch {
                                    chaptersState.forEach { ch -> runCatching { synthesizer?.ensureChapter(b, ch) } }
                                    cacheTick++
                                }
                            }
                        },
                        onBack = { nav.popBackStack() },
                        onOpenChapters = { nav.navigate("chapters/$bookId") },
                        onOpenRoles = { nav.navigate("roles/$bookId/$chapterId") },
                        onPlayFromSeg = { segIndex ->
                            val c = ctrl
                            val b = book
                            val ch = chapter
                            if (c != null && b != null && ch != null) c.playFromSeg(b, ch, segIndex)
                        },
                        onSeekToMs = { ms ->
                            val c = ctrl
                            val b = book
                            val ch = chapter
                            if (c != null && b != null && ch != null) c.seekToChapterMs(b, ch, ms)
                        },
                        onPrevChapter = {
                            val idx = chaptersState.indexOfFirst { it.id == chapterId }
                            if (idx > 0) nav.navigate("reader/$bookId/${chaptersState[idx - 1].id}") { launchSingleTop = true }
                        },
                        onNextChapter = {
                            val idx = chaptersState.indexOfFirst { it.id == chapterId }
                            if (idx >= 0 && idx < chaptersState.size - 1) {
                                nav.navigate("reader/$bookId/${chaptersState[idx + 1].id}") { launchSingleTop = true }
                            }
                        },
                    )
                    // 打开阅读器不自动播放/缓存（参考 Readest 手动 TTS）：
                    // 仅重置旧播放状态；播放由用户点击「播放」触发，缓存在目录抽屉手动执行
                    LaunchedEffect(bookId, chapterId) {
                        if (ctrl == null) {
                            Toast.makeText(context, "未配置 DeepSeek Key，仅显示正文；可在设置中配置后开启朗读", Toast.LENGTH_LONG).show()
                        } else {
                            ctrl.stopCurrent()
                        }
                    }
                }
                // 书内搜索面板（E14）
                if (searchOpen) {
                    ModalBottomSheet(onDismissRequest = { searchOpen = false }) {
                        SearchSheet(
                            container = container,
                            bookId = bookId,
                            onOpenChapter = { cid -> nav.navigate("reader/$bookId/$cid") { launchSingleTop = true } },
                            onDismiss = { searchOpen = false },
                        )
                    }
                }
            }

            composable(
                "roles/{bookId}/{chapterId}",
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("chapterId") { type = NavType.LongType },
                ),
            ) { back ->
                val bookId = back.arguments?.getLong("bookId") ?: return@composable
                val chapterId = back.arguments?.getLong("chapterId") ?: return@composable
                val book by produceState<BookEntity?>(initialValue = null) { value = container.bookRepo.byId(bookId) }
                val segments by produceState<List<com.ensemblereads.app.data.db.SegmentEntity>>(initialValue = emptyList()) {
                    value = container.segmentRepo.byChapter(chapterId)
                }
                val syn = synthesizer
                RolesScreen(
                    bookId = bookId,
                    segments = segments,
                    container = container,
                    onRolesChanged = {
                        // 用户改了角色音色：先暂停播放（避免删除正在播放的音频），再重置重合成
                        scope.launch {
                            val b = book ?: return@launch
                            val ch = container.chapterRepo.byBookAndId(bookId, chapterId) ?: return@launch
                            AudioPlaybackService.instance?.pause()
                            try {
                                syn?.resetChapter(b, ch)
                                syn?.ensureChapter(b, ch)
                            } catch (e: Exception) {
                                Toast.makeText(context, "重合成失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDone = { nav.popBackStack() },
                )
            }

            composable("settings") {
                SettingsScreen(container = container, synthesizer = synthesizer)
            }
        }
            // 全局悬浮按钮 + 后台请求日志面板（所有页面可见，可查看/返回）
            RequestLogFloatingPanel()
        }
    }
}
