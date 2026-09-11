package com.ensemblereads.app.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.ensemblereads.app.player.PlaybackController
import com.ensemblereads.app.tts.DeepSeekTtsEngine
import com.ensemblereads.app.ui.bookshelf.BookshelfScreen
import com.ensemblereads.app.ui.bookshelf.importBook
import com.ensemblereads.app.ui.chapters.ChaptersScreen
import com.ensemblereads.app.ui.reader.ReaderScreen
import com.ensemblereads.app.ui.roles.RolesScreen
import com.ensemblereads.app.ui.settings.SettingsScreen
import com.ensemblereads.app.ui.theme.EnsembleTheme
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppRoot(container: AppContainer) {
    EnsembleTheme {
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

        // 引擎依赖 DeepSeek key（跟随设置 Flow，保存后无需重启即生效）
        val settings by container.settings.all().collectAsState(initial = emptyList())
        val apiKey = settings.firstOrNull { it.key == SettingsManager.KEY_DEEPSEEK_KEY }?.value?.takeIf { it.isNotBlank() }
        val engine = remember(apiKey) { apiKey?.let { DeepSeekTtsEngine(it) } }
        val synthesizer = remember(engine) {
            engine?.let { ChapterSynthesizer(container, it, File(context.filesDir, "audio")) }
        }
        val controller = remember(synthesizer) { synthesizer?.let { PlaybackController(it) } }

        // SAF 导入
        var shelfTick by remember { mutableIntStateOf(0) }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching { importBook(context, uri, container) }
                        .onSuccess { shelfTick++ }
                        .onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
                    nav.navigate("bookshelf") { popUpTo("bookshelf") { inclusive = false }; launchSingleTop = true }
                }
            }
        }

        NavHost(nav, startDestination = "bookshelf") {
            composable("bookshelf") {
                val books by produceState<List<BookEntity>>(key1 = shelfTick, initialValue = emptyList()) {
                    value = container.bookRepo.all()
                }
                BookshelfScreen(
                    books = books,
                    onOpen = { book -> nav.navigate("chapters/${book.id}") },
                    onImport = { importLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*")) },
                    onSettings = { nav.navigate("settings") },
                )
            }

            composable(
                "chapters/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { back ->
                val bookId = back.arguments?.getLong("bookId") ?: return@composable
                val chapters by produceState<List<com.ensemblereads.app.data.db.ChapterEntity>>(initialValue = emptyList()) {
                    value = container.chapterRepo.chapters(bookId)
                }
                ChaptersScreen(
                    chapters = chapters,
                    onOpen = { ch -> nav.navigate("reader/$bookId/${ch.id}") },
                    onBack = { nav.popBackStack() },
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
                val book by produceState<BookEntity?>(initialValue = null) { value = container.bookRepo.byId(bookId) }
                val chapter by produceState<com.ensemblereads.app.data.db.ChapterEntity?>(initialValue = null) {
                    value = container.chapterRepo.byBookAndId(bookId, chapterId)
                }
                val segments by produceState<List<com.ensemblereads.app.data.db.SegmentEntity>>(initialValue = emptyList()) {
                    value = container.segmentRepo.byChapter(chapterId)
                }
                val ctrl = controller
                if (book != null && chapter != null) {
                    // 正文始终可读：朗读引擎缺失或启动失败时降级为纯文本，不崩溃
                    var segs by remember { mutableStateOf(segments) }
                    // 跟随 produceState 的 segments 更新（修复竞态：打开时列表可能晚于 book 加载完成）
                    LaunchedEffect(segments) { segs = segments }
                    ReaderScreen(
                        chapterTitle = chapter!!.title,
                        content = chapter!!.content,
                        segments = segs,
                        controller = ctrl,
                        onOpenRoles = { nav.navigate("roles/$bookId/$chapterId") },
                    )
                    LaunchedEffect(Unit) {
                        if (ctrl == null) {
                            Toast.makeText(context, "未配置 DeepSeek Key，仅显示正文；可在设置中配置后开启朗读", Toast.LENGTH_LONG).show()
                        } else {
                            try {
                                ctrl.startFrom(book!!, chapter!!, book!!.lastSegIndex)
                                // 合成完成后刷新分段，让正文/高亮跟读生效（在 try 内，Room 异常不逃逸）
                                segs = container.segmentRepo.byChapter(chapterId)
                            } catch (e: Exception) {
                                Toast.makeText(context, "朗读启动失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
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
                        // 用户改了角色音色：重置该章并重新合成（后台）
                        scope.launch {
                            val b = book ?: return@launch
                            val ch = container.chapterRepo.byBookAndId(bookId, chapterId) ?: return@launch
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
    }
}
