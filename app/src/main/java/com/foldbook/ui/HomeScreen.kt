package com.foldbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.App
import com.foldbook.ReaderActivity
import com.foldbook.Session
import com.foldbook.SessionCache
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(app: App) {
    val ctx = LocalContext.current
    val sessions by app.sessions.flow.collectAsStateWithLifecycle()
    val active = sessions.filter { !it.finished }
    val done = sessions.filter { it.finished }

    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var actionFor by remember { mutableStateOf<Session?>(null) }
    var confirmClearDone by remember { mutableStateOf(false) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("홈") }) }) { pad ->
        if (sessions.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    AppIcons.MenuBook, null,
                    Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "최근에 본 폴더가 없습니다.\n‘탐색’ 에서 만화를 열어보세요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GroupHeader("이어보기", null) }
            if (active.isEmpty()) {
                item {
                    Text(
                        "이어볼 항목이 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            items(active, key = { it.id }) { s ->
                SessionCard(
                    s = s,
                    onOpen = { open(ctx, s) },
                    onLongPress = { actionFor = s },
                )
            }

            if (done.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    GroupHeader("완료", onClearAll = { confirmClearDone = true })
                }
                items(done, key = { it.id }) { s ->
                    SessionCard(
                        s = s,
                        onOpen = { open(ctx, s) },
                        onLongPress = { pendingDelete = s.id },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    app.sessions.remove(id)
                    SessionCache.clear(ctx, id)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("이 항목 삭제") },
            text = { Text("목록에서 지우고 받아둔 캐시도 정리합니다.") },
        )
    }

    actionFor?.let { s ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            confirmButton = {
                TextButton(onClick = {
                    // 완료 목록으로 이동 + 받아둔 미리불러오기 캐시 정리
                    app.sessions.upsert(s.copy(finished = true))
                    SessionCache.clear(ctx, s.id)
                    actionFor = null
                }) { Text("완료 목록으로 이동") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingDelete = s.id; actionFor = null }) { Text("삭제") }
                    TextButton(onClick = { actionFor = null }) { Text("취소") }
                }
            },
            title = { Text(s.folderName.ifBlank { "이 항목" }) },
            text = { Text("완료 목록으로 옮기거나 목록에서 삭제합니다.") },
        )
    }

    if (confirmClearDone) {
        AlertDialog(
            onDismissRequest = { confirmClearDone = false },
            confirmButton = {
                TextButton(onClick = {
                    app.sessions.finishedIds().forEach { SessionCache.clear(ctx, it) }
                    app.sessions.clearFinished()
                    confirmClearDone = false
                }) { Text("모두 삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmClearDone = false }) { Text("취소") } },
            icon = { Icon(AppIcons.DeleteSweep, null) },
            title = { Text("완료 목록 비우기") },
            text = { Text("‘완료’ 그룹의 모든 항목을 삭제합니다.") },
        )
    }
}

@Composable
private fun GroupHeader(title: String, onClearAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (onClearAll != null) {
            IconButton(onClick = onClearAll) {
                Icon(AppIcons.DeleteSweep, "완료 목록 비우기")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(s: Session, onOpen: () -> Unit, onLongPress: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer(alpha = if (s.finished) 0.45f else 1f)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
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
                if (s.finished) {
                    Text("다 봄 · 눌러서 다시 보기", style = MaterialTheme.typography.labelSmall)
                } else {
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

private fun open(ctx: android.content.Context, s: Session) {
    ReaderActivity.start(ctx, s.connectionId, s.folderId, s.folderName, sessionId = s.id)
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
