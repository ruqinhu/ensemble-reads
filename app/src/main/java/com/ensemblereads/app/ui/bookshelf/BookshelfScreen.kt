package com.ensemblereads.app.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ensemblereads.app.data.db.BookEntity

/** 书架排序方式。 */
enum class ShelfSort { RECENT, TITLE, PROGRESS }

/** 书架卡片上的朗读进度（AppRoot 从段数据计算后传入）。 */
data class BookProgress(val percent: Int = 0, val chapterLabel: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    progress: Map<Long, BookProgress> = emptyMap(),
    onOpen: (BookEntity) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onRename: (BookEntity, String) -> Unit = { _, _ -> },
    onDelete: (BookEntity) -> Unit = {},
) {
    var sort by remember { mutableStateOf(ShelfSort.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }
    // 书名搜索（E14）
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // 重命名/删除对话框目标
    var renameTarget by remember { mutableStateOf<BookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    val sorted = remember(books, progress, sort) {
        when (sort) {
            ShelfSort.RECENT -> books.sortedByDescending { it.createdAt }
            ShelfSort.TITLE -> books.sortedBy { it.title }
            ShelfSort.PROGRESS -> books.sortedByDescending { progress[it.id]?.percent ?: 0 }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch; searchQuery = "" }) { Icon(Icons.Default.Search, contentDescription = "搜索") }
                    // 排序菜单
                    Box {
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, contentDescription = "排序") }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(text = { Text("最近导入") }, onClick = { sort = ShelfSort.RECENT; showSortMenu = false })
                            DropdownMenuItem(text = { Text("按标题") }, onClick = { sort = ShelfSort.TITLE; showSortMenu = false })
                            DropdownMenuItem(text = { Text("按进度") }, onClick = { sort = ShelfSort.PROGRESS; showSortMenu = false })
                        }
                    }
                    IconButton(onClick = onImport) { Icon(Icons.Default.Add, contentDescription = "导入") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true, onClick = {},
                    icon = { Icon(Icons.Default.Book, null) }, label = { Text("书架") })
                NavigationBarItem(
                    selected = false, onClick = onSettings,
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (showSearch) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索书名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val filtered = if (searchQuery.isBlank()) sorted
            else sorted.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isBlank()) "点击右上角 + 导入书籍" else "未找到匹配的书",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(filtered) { _, book ->
                        BookCard(
                            book = book,
                            progress = progress[book.id],
                            onClick = { onOpen(book) },
                            onRename = { renameTarget = book; renameText = book.title },
                            onDelete = { deleteTarget = book },
                        )
                    }
                }
            }
        }
    }

    // 重命名对话框
    renameTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true, label = { Text("书名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(book, renameText.trim().ifEmpty { book.title })
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    // 删除确认对话框
    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("将删除本书的正文、音频缓存与角色配置。") },
            confirmButton = {
                TextButton(onClick = { onDelete(book); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box {
        Card(
            shape = shape,
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(book.title.take(1), fontSize = 32.sp, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(8.dp))
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${book.chapterCount} 章 · ${book.format}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 语音进度：读到第X章（整书百分比 >0 才显示「已听 N%」）
                if (progress?.chapterLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("读到 ${progress.chapterLabel}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (progress.percent > 0) {
                        Text("已听 ${progress.percent}%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 长按菜单：重命名 / 删除
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { showMenu = false; onRename() })
            DropdownMenuItem(text = { Text("删除") }, onClick = { showMenu = false; onDelete() })
        }
    }
}
