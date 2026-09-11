package com.ensemblereads.app.ui.bookshelf

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ensemblereads.app.data.db.BookEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    books: List<BookEntity>,
    onOpen: (BookEntity) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("书架") },
                actions = { IconButton(onClick = onImport) { Icon(Icons.Default.Add, contentDescription = "导入") } },
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
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("点击右上角 + 导入书籍", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(books) { _, book -> BookCard(book, onClick = { onOpen(book) }) }
            }
        }
    }
}

@Composable
fun BookCard(book: BookEntity, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Card(
        shape = shape,
        onClick = onClick,
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
        }
    }
}
