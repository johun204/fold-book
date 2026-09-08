package com.foldbook.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.App
import com.foldbook.ConnType
import com.foldbook.Connection
import com.foldbook.DownloadService
import com.foldbook.Entry
import com.foldbook.ReaderActivity
import com.foldbook.StorageBackend
import com.foldbook.backendFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseFolderScreen(
    app: App,
    connId: String,
    folderId: String,
    folderName: String,
    crumb: String,
    onOpenFolder: (folderId: String, folderName: String) -> Unit,
    onBack: () -> Unit,
    onConnectionRemoved: () -> Unit,
) {
    val ctx = LocalContext.current
    val conn = app.connections.get(connId)
    if (conn == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val backend = remember(connId) { backendFor(conn, ctx) }
    val showThumbs = conn.type == ConnType.LOCAL || app.prefs.remoteThumbnails

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var curId by remember { mutableStateOf(folderId) }
    var curName by remember { mutableStateOf(folderName) }
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val download by DownloadService.state.collectAsStateWithLifecycle()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val needsPermission = conn.type == ConnType.LOCAL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        !Environment.isExternalStorageManager()

    LaunchedEffect(folderId, reload, needsPermission) {
        if (needsPermission) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            val id = folderId.ifBlank { backend.root().id }
            curId = id
            curName = folderName.ifBlank { backend.root().name }
            entries = backend.list(id)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val picked = entries.filter { selected[it.id] == true }
        selecting = false
        selected.clear()
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        DownloadService.start(ctx, connId, uri, picked, curName.ifBlank { conn.label })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (selecting) "${selected.count { it.value }}개 선택" else curName.ifBlank { conn.label },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (!selecting) Text(
                            fullPath(conn, curId, crumb),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selecting) { selecting = false; selected.clear() } else onBack()
                    }) {
                        Icon(
                            if (selecting) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            null,
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(
                            enabled = selected.any { it.value },
                            onClick = { treePicker.launch(null) },
                        ) { Text("저장 위치 선택") }
                    } else {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (conn.type != ConnType.LOCAL) {
                                DropdownMenuItem(
                                    text = { Text("별칭 변경") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { menu = false; renaming = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("파일/폴더 다운로드") },
                                leadingIcon = { Icon(AppIcons.Download, null) },
                                onClick = {
                                    menu = false; selecting = true; selected.clear()
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                            )
                            if (conn.type != ConnType.LOCAL) {
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
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selecting && !loading && error == null && entries.any { !it.isDir }) {
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
                            Intent(
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
                        EntryRow(
                            entry = e,
                            backend = backend,
                            connId = connId,
                            showThumb = showThumbs,
                            selecting = selecting,
                            checked = selected[e.id] == true,
                            onCheck = { selected[e.id] = it },
                            onClick = {
                                when {
                                    selecting -> selected[e.id] = !(selected[e.id] ?: false)
                                    e.isDir -> onOpenFolder(e.id, e.name)
                                    else -> ReaderActivity.start(ctx, connId, curId, curName, startEntryId = e.id)
                                }
                            },
                        )
                    }
                }
            }
            download?.let { DownloadCard(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }

    if (renaming) RenameDialog(conn.label) { new ->
        renaming = false
        if (new != null) app.connections.rename(connId, new)
    }
}

@Composable
private fun EntryRow(
    entry: Entry,
    backend: StorageBackend,
    connId: String,
    showThumb: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val thumb by produceState<android.graphics.Bitmap?>(
        initialValue = if (!entry.isDir) ThumbLoader.cached("$connId|${entry.id}") else null,
        entry.id, showThumb,
    ) {
        if (!entry.isDir && showThumb && value == null) {
            value = ThumbLoader.load("$connId|${entry.id}", backend, entry.id)
        }
    }

    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = onCheck)
            } else {
                val t = thumb
                if (entry.isDir) {
                    Icon(AppIcons.Folder, null, Modifier.size(30.dp))
                } else if (t != null) {
                    Image(
                        t.asImageBitmap(), null,
                        Modifier.size(38.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(AppIcons.Image, null, Modifier.size(30.dp))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        confirmButton = { TextButton(onClick = { onDone(text.trim().ifBlank { null }) }) { Text("변경") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("취소") } },
        title = { Text("별칭 변경") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
    )
}

@Composable
private fun PermissionGate(onGrant: () -> Unit, onRecheck: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("기기 저장소를 탐색하려면 ‘모든 파일 접근’ 권한이 필요합니다.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) { Text("권한 허용") }
        TextButton(onClick = onRecheck) { Text("허용했어요, 다시 확인") }
    }
}

@Composable
private fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("불러오기 실패", style = MaterialTheme.typography.titleMedium)
        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("다시 시도") }
    }
}

private fun fullPath(conn: Connection, curId: String, crumb: String): String = when (conn.type) {
    ConnType.LOCAL -> curId
    ConnType.SMB -> "smb://${conn.host}/${conn.share}" + (if (curId.isNotBlank()) "/${curId.trim('/')}" else "")
    ConnType.DRIVE -> crumb
}
