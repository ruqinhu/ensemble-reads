package com.ensemblereads.app.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

        // 启动前台播放服务（用于后台/锁屏朗读）
        LaunchedEffect(Unit) {
            val intent = Intent(context, AudioPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        // 引擎依赖 DeepSeek key
        val apiKey by produceState<String?>(initialValue = null) {
            value = container.settings.get(SettingsManager.KEY_DEEPSEEK_KEY)
        }
        val engine = remember(apiKey) { apiKey?.takeIf { it.isNotBlank() }?.let { DeepSeekTtsEngine(it) } }
        val synthesizer = remember(engine) {
            engine?.let { ChapterSynthesizer(container, it, File(context.filesDir, "audio")) }
        }
        val controller = remember(synthesizer) { synthesizer?.let { PlaybackController(it) } }

        // SAF 导入
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    runCatching { importBook(context, uri, container) }
                    nav.navigate("bookshelf") { popUpTo("bookshelf") { inclusive = false }; launchSingleTop = true }
                }
            }
        }

        NavHost(nav, startDestination = "bookshelf") {
            composable("bookshelf") {
                val books by produceState<List<BookEntity>>(initialValue = emptyList()) {
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
                if (book != null && chapter != null && ctrl != null) {
                    ReaderScreen(
                        chapterTitle = chapter!!.title,
                        segments = segments,
                        controller = ctrl,
                        onOpenRoles = { nav.navigate("roles/$bookId/$chapterId") },
                    )
                    // 从当前进度段开始听（MVP 简化：默认从头；进度持久化后续增强）
                    LaunchedEffect(Unit) {
                        scope.launch { ctrl.startFrom(book!!, chapter!!, book!!.lastSegIndex) }
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
                            syn?.let { it.resetChapter(b, ch); it.ensureChapter(b, ch) }
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
