package com.foldbook.ui

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete



import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foldbook.App
import com.foldbook.ConnType
import com.foldbook.Entry
import com.foldbook.ReaderActivity
import com.foldbook.backendFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseFolderScreen(
    app: App,
    connId: String,
    folderId: String,
    folderName: String,
    onOpenFolder: (connId: String, folderId: String, folderName: String) -> Unit,
    onBack: () -> Unit,
    onConnectionRemoved: () -> Unit,
) {
    val ctx = LocalContext.current
    val conn = app.connections.get(connId)
    if (conn == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var curId by remember { mutableStateOf(folderId) }
    var curName by remember { mutableStateOf(folderName) }
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }

    val needsPermission = conn.type == ConnType.LOCAL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        !Environment.isExternalStorageManager()

    LaunchedEffect(folderId, reload, needsPermission) {
        if (needsPermission) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            val backend = backendFor(conn, ctx)
            val id = folderId.ifBlank { backend.root().id }
            curId = id
            curName = folderName.ifBlank { backend.root().name }
            entries = backend.list(id)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(curName.ifBlank { conn.label }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (conn.type != ConnType.LOCAL) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("이 저장소 삭제") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = {
                                    menu = false
                                    app.connections.remove(connId)
                                    onConnectionRemoved()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!loading && error == null && entries.any { !it.isDir }) {
                ExtendedFloatingActionButton(
                    onClick = { ReaderActivity.start(ctx, connId, curId, curName) },
                    icon = { Icon(AppIcons.MenuBook, null) },
                    text = { Text("이 폴더 열기") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                needsPermission -> PermissionGate(
                    onGrant = {
                        ctx.startActivity(
                            android.content.Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"),
                            )
                        )
                    },
                    onRecheck = { reload++ },
                )
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> ErrorBox(error!!) { reload++ }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (entries.isEmpty()) {
                        item { Text("이 폴더에 하위폴더/이미지가 없습니다.", Modifier.padding(16.dp)) }
                    }
                    items(entries, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = {
                                Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Icon(
                                    if (e.isDir) AppIcons.Folder else AppIcons.Image,
                                    null, Modifier.size(28.dp),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (e.isDir) onOpenFolder(connId, e.id, e.name)
                                else ReaderActivity.start(ctx, connId, curId, curName, startEntryId = e.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit, onRecheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "기기 저장소를 탐색하려면 ‘모든 파일 접근’ 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) { Text("권한 허용") }
        androidx.compose.material3.TextButton(onClick = onRecheck) { Text("허용했어요, 다시 확인") }
    }
}

@Composable
private fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("불러오기 실패", style = MaterialTheme.typography.titleMedium)
        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("다시 시도") }
    }
}
