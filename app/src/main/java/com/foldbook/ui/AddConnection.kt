package com.foldbook.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons


import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.foldbook.App
import com.foldbook.ConnType
import com.foldbook.Connection
import com.foldbook.DRIVE_SCOPE_URL
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionSheet(app: App, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0=선택, 1=SMB

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (mode) {
                0 -> {
                    Text("저장소 추가", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    ListItem(
                        headlineContent = { Text("윈도우 공유폴더 (SMB)") },
                        supportingContent = { Text("PC/NAS 의 공유 폴더") },
                        leadingContent = { Icon(AppIcons.Computer, null) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .let { it }, // clickable below
                    )
                    Button(onClick = { mode = 1 }, modifier = Modifier.fillMaxWidth()) {
                        Text("SMB 연결 설정")
                    }
                    ListItem(
                        headlineContent = { Text("구글 드라이브") },
                        supportingContent = { Text("계정별로 각각 추가 가능") },
                        leadingContent = { Icon(AppIcons.Cloud, null) },
                    )
                    DriveAddButton(app = app, onDone = onDismiss)
                }
                1 -> SmbForm(app = app, onDone = onDismiss, onBack = { mode = 0 })
            }
        }
    }
}

@Composable
private fun DriveAddButton(app: App, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(res.data).getResult(ApiException::class.java)
            val email = acct.email ?: "google"
            if (app.connections.flow.value.any { it.type == ConnType.DRIVE && it.accountEmail == email }) {
                Toast.makeText(ctx, "이미 추가된 계정입니다", Toast.LENGTH_SHORT).show()
            } else {
                app.connections.add(
                    Connection(
                        id = UUID.randomUUID().toString(),
                        type = ConnType.DRIVE,
                        label = email,
                        accountEmail = email,
                    )
                )
            }
            onDone()
        } catch (e: ApiException) {
            Toast.makeText(ctx, "로그인 실패 (코드 ${e.statusCode})", Toast.LENGTH_LONG).show()
        }
    }
    Button(
        onClick = {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_SCOPE_URL))
                .build()
            // 매번 계정 선택기를 띄우기 위해 먼저 로그아웃
            val client = GoogleSignIn.getClient(ctx, gso)
            client.signOut().addOnCompleteListener { launcher.launch(client.signInIntent) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("구글 계정 추가") }
}

@Composable
private fun SmbForm(app: App, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    Text("SMB 연결", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
    Field("표시 이름 (선택)", label) { label = it }
    Field("호스트 / IP  (예: 192.168.0.10)", host, KeyboardType.Uri) { host = it }
    Field("공유 이름  (예: Comics)", share) { share = it }
    Field("시작 폴더  (예: 원피스, 없으면 비움)", path) { path = it }
    Field("사용자 (게스트면 비움)", user) { user = it }
    Field("비밀번호", pass, KeyboardType.Password, isPassword = true) { pass = it }
    Field("도메인 (선택)", domain) { domain = it }

    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("뒤로") }
        Button(
            onClick = {
                if (host.isBlank() || share.isBlank()) {
                    Toast.makeText(ctx, "호스트와 공유 이름은 필수입니다", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                app.connections.add(
                    Connection(
                        id = UUID.randomUUID().toString(),
                        type = ConnType.SMB,
                        label = label.ifBlank { "$host/$share" },
                        host = host.trim(), share = share.trim(), basePath = path.trim(),
                        user = user.trim(), pass = pass, domain = domain.trim(),
                    )
                )
                onDone()
            },
            modifier = Modifier.weight(1f),
        ) { Text("추가") }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
