package com.foldbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.App
import com.foldbook.ReaderActivity
import com.foldbook.SessionCache
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(app: App) {
    val ctx = LocalContext.current
    val sessions by app.sessions.flow.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("이어보기") }) }) { pad ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "최근에 본 폴더가 없습니다.\n‘탐색’ 에서 만화를 열어보세요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.id }) { s ->
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            ReaderActivity.start(ctx, s.connectionId, s.folderId, s.folderName, sessionId = s.id)
                        },
                        onLongClick = { pendingDelete = s.id },
                    )
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.MenuBook, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.folderName.ifBlank { "(폴더)" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                s.connectionLabel + " · " + relTime(s.updatedAt),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            val frac = if (s.pageCount > 0) (s.pageIndex + 1).toFloat() / s.pageCount else 0f
                            LinearProgressIndicator(
                                progress = { frac.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (s.pageCount > 0) "${s.pageIndex + 1} / ${s.pageCount}" else "이어보기",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }

    val id = pendingDelete
    if (id != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    app.sessions.remove(id)
                    SessionCache.clear(ctx, id)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("이 세션 삭제") },
            text = { Text("목록에서 지우고 받아둔 캐시도 정리합니다.") },
        )
    }
}

private fun relTime(t: Long): String {
    if (t <= 0) return ""
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "방금"
        d < 3_600_000 -> "${d / 60_000}분 전"
        d < 86_400_000 -> "${d / 3_600_000}시간 전"
        d < 7 * 86_400_000L -> "${d / 86_400_000}일 전"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
    }
}
