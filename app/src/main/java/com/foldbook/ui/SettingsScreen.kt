package com.foldbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.foldbook.App
import com.foldbook.Prefs
import com.foldbook.ReadingDirection
import com.foldbook.SpreadMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var dir by remember { mutableStateOf(prefs.direction) }
    var spread by remember { mutableStateOf(prefs.spreadMode) }
    var fold by remember { mutableStateOf(prefs.foldFlip) }

    Scaffold(topBar = { TopAppBar(title = { Text("설정") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("읽기 방향")
            RadioRow("오른쪽 → 왼쪽 (일본 만화)", dir == ReadingDirection.RTL) {
                dir = ReadingDirection.RTL; prefs.direction = dir
            }
            RadioRow("왼쪽 → 오른쪽 (서양 만화)", dir == ReadingDirection.LTR) {
                dir = ReadingDirection.LTR; prefs.direction = dir
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("페이지 표시 방식")
            RadioRow("자동 (화면 크기에 맞춤)", spread == SpreadMode.AUTO) {
                spread = SpreadMode.AUTO; prefs.spreadMode = spread
            }
            RadioRow("항상 한 페이지씩", spread == SpreadMode.SINGLE) {
                spread = SpreadMode.SINGLE; prefs.spreadMode = spread
            }
            RadioRow("항상 양쪽 페이지", spread == SpreadMode.DOUBLE) {
                spread = SpreadMode.DOUBLE; prefs.spreadMode = spread
            }
            Text(
                "좌우 양면 스캔본 자동 분할은 로컬 저장소에서만 동작합니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("폴더블 접힘으로 페이지 넘기기", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "살짝 접었다 펴면 다음 페이지",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = fold, onCheckedChange = { fold = it; prefs.foldFlip = it })
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
