package com.foldbook.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldbook.App
import com.foldbook.ConnType
import com.foldbook.Connection

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(app: App, onOpen: (Connection) -> Unit) {
    val connections by app.connections.flow.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Connection?>(null) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("탐색") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("드라이브 추가") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(connections, key = { it.id }) { c ->
                Row {
                    ListItem(
                        headlineContent = { Text(c.label) },
                        supportingContent = { Text(c.subtitle, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(iconFor(c.type), null, Modifier.size(28.dp)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpen(c) },
                                onLongClick = { if (c.type != ConnType.LOCAL) menuFor = c.id },
                            ),
                    )
                    DropdownMenu(expanded = menuFor == c.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text("별칭 변경") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { renaming = c; menuFor = null },
                        )
                        DropdownMenuItem(
                            text = { Text("이 저장소 삭제") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { app.connections.remove(c.id); menuFor = null },
                        )
                    }
                }
            }
            item {
                Text(
                    "저장소를 길게 누르면 별칭 변경·삭제. 같은 종류를 여러 개 추가할 수 있어요 " +
                        "(공유폴더 여러 경로, 드라이브 여러 계정).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showAdd) AddConnectionSheet(app = app, onDismiss = { showAdd = false })

    renaming?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton(onClick = {
                    app.connections.rename(c.id, text)
                    renaming = null
                }) { Text("변경") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } },
            title = { Text("별칭 변경") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        )
    }
}

private fun iconFor(t: ConnType) = when (t) {
    ConnType.LOCAL -> AppIcons.PhoneAndroid
    ConnType.SMB -> AppIcons.Computer
    ConnType.DRIVE -> AppIcons.Cloud
}
