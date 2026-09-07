package com.foldbook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foldbook.App
import com.foldbook.BuildConfig
import com.foldbook.Prefs
import com.foldbook.ReadingDirection
import com.foldbook.SpreadMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var dir by remember { mutableStateOf(prefs.direction) }
    var spread by remember { mutableStateOf(prefs.spreadMode) }
    var fold by remember { mutableStateOf(prefs.foldFlip) }
    var split by remember { mutableStateOf(prefs.splitWideScans) }
    var analyzeRemote by remember { mutableStateOf(prefs.analyzeRemote) }
    var remoteThumbs by remember { mutableStateOf(prefs.remoteThumbnails) }
    var prefetch by remember { mutableFloatStateOf(prefs.prefetchForward.toFloat()) }
    var tapWide by remember { mutableFloatStateOf(prefs.tapZoneWide) }
    var tapNarrow by remember { mutableFloatStateOf(prefs.tapZoneNarrow) }

    var update by remember { mutableStateOf<UpdateCheck.Result?>(null) }
    LaunchedEffect(Unit) { update = UpdateCheck.check(BuildConfig.VERSION_NAME) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("설정") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Section("읽기 방향 (기본값)")
            Desc("새로 여는 책에 적용되는 기본 넘김 방향입니다. 일본 만화는 보통 오른쪽에서 왼쪽으로 읽습니다. " +
                "책마다 뷰어의 '뷰어 설정'에서 따로 바꿀 수 있습니다.")
            RadioRow("오른쪽 → 왼쪽 (일본 만화)", dir == ReadingDirection.RTL) {
                dir = ReadingDirection.RTL; prefs.direction = dir
            }
            RadioRow("왼쪽 → 오른쪽 (서양 만화·웹툰)", dir == ReadingDirection.LTR) {
                dir = ReadingDirection.LTR; prefs.direction = dir
            }

            Divider()
            Section("페이지 표시 방식")
            Desc(
                "한 번에 몇 쪽을 보여줄지 정합니다.\n" +
                    "• 자동: 화면이 가로로 넓으면(태블릿·폴드 펼침·가로 회전) 두 쪽, 좁으면 한 쪽.\n" +
                    "• 항상 한 페이지씩: 화면과 무관하게 한 쪽만.\n" +
                    "• 항상 양쪽 페이지: 화면이 가로일 때 두 쪽씩(세로일 땐 한 쪽으로 표시)."
            )
            RadioRow("자동 (화면 크기에 맞춤)", spread == SpreadMode.AUTO) {
                spread = SpreadMode.AUTO; prefs.spreadMode = spread
            }
            RadioRow("항상 한 페이지씩", spread == SpreadMode.SINGLE) {
                spread = SpreadMode.SINGLE; prefs.spreadMode = spread
            }
            RadioRow("항상 양쪽 페이지", spread == SpreadMode.DOUBLE) {
                spread = SpreadMode.DOUBLE; prefs.spreadMode = spread
            }

            Divider()
            Section("좌우 양면 스캔본 분할")
            ToggleRow(
                "양면 스캔 이미지를 반으로 나눠 보기", split,
                "가로로 긴(양쪽 페이지가 한 파일에 스캔된) 이미지를 왼/오른쪽으로 잘라서 표시합니다.\n" +
                    "• 폴드를 접은 세로 화면: 반쪽씩 넘겨가며 크게 봅니다.\n" +
                    "• 양쪽 페이지 모드(가로): 두 반쪽이 한 화면에 원래 스프레드처럼 배치됩니다.\n" +
                    "끄면 양면 이미지를 통째로(작게) 표시합니다.",
            ) { split = it; prefs.splitWideScans = it }
            ToggleRow(
                "네트워크 드라이브에서도 분할 분석", analyzeRemote,
                "SMB·구글 드라이브의 이미지도 파일 앞부분(헤더)만 미리 받아 가로/세로 비율을 분석해 " +
                    "양면 스캔본을 자동으로 나눕니다. 폴더를 열 때 잠깐 분석 시간이 걸릴 수 있습니다. " +
                    "끄면 원격 이미지는 항상 통짜로 표시합니다.",
            ) { analyzeRemote = it; prefs.analyzeRemote = it }

            Divider()
            Section("미리 불러오기")
            Text(
                "현재 페이지 기준 앞으로 ${prefetch.roundToInt()}장 미리 로딩",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = prefetch,
                onValueChange = { prefetch = it },
                onValueChangeFinished = { prefs.prefetchForward = prefetch.roundToInt() },
                valueRange = 1f..12f,
                steps = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Desc(
                "다음에 볼 페이지를 미리 받아 두는 장수입니다. 값이 클수록 빠르게 넘겨도 회색(로딩) 화면이 " +
                    "덜 뜨지만, 네트워크 데이터와 메모리를 더 사용합니다. 네트워크가 느리면 6~10, " +
                    "데이터가 아까우면 2~3을 권장합니다. 세션이 끝나면 받아둔 파일은 자동으로 정리됩니다.",
            )

            Divider()
            Section("터치로 페이지 넘기기")
            Desc(
                "화면을 그냥 톡 눌렀을 때 페이지를 넘길 좌·우 가장자리 영역의 너비입니다(0 = 끔, 가운데를 " +
                    "누르면 항상 메뉴가 뜹니다). 서양 만화는 오른쪽=다음, 일본 만화는 왼쪽=다음입니다. " +
                    "폴드 펼침(넓은 화면)과 접힘(좁은 화면) 값을 따로 저장합니다.",
            )
            Text("펼침(넓은 화면): 화면 폭의 ${(tapWide * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = tapWide,
                onValueChange = { tapWide = it },
                onValueChangeFinished = { prefs.tapZoneWide = tapWide },
                valueRange = 0f..0.5f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("접힘(좁은 화면): 화면 폭의 ${(tapNarrow * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = tapNarrow,
                onValueChange = { tapNarrow = it },
                onValueChangeFinished = { prefs.tapZoneNarrow = tapNarrow },
                valueRange = 0f..0.5f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )

            Divider()
            Section("탐색")
            ToggleRow(
                "원격 폴더에서 이미지 썸네일 표시", remoteThumbs,
                "SMB·드라이브 폴더를 탐색할 때 이미지 미리보기를 보여줍니다. 화면에 보이는 항목만 받아오지만 " +
                    "파일 전체를 내려받으므로 데이터를 씁니다(한 번 받으면 캐시). 로컬 저장소는 항상 썸네일을 표시합니다.",
            ) { remoteThumbs = it; prefs.remoteThumbnails = it }

            Divider()
            Section("폴더블")
            ToggleRow(
                "접힘 제스처로 페이지 넘기기", fold,
                "갤럭시 폴드류에서 기기를 살짝 접었다 펴면 다음 페이지로 넘어갑니다. " +
                    "경첩 센서 상태(평평 → 반 접힘 → 평평)를 감지해 동작합니다.",
            ) { fold = it; prefs.foldFlip = it }

            Divider()
            Section("정보")
            Text("현재 버전  v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            val u = update
            if (u != null && u.updateAvailable) {
                Text(
                    "새 버전 ${u.latest} 이 있습니다. 눌러서 다운로드",
                    color = Color(0xFFFF8C00),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_URL)))
                        },
                )
            } else if (u != null) {
                Text("최신 버전입니다.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                UpdateCheck.RELEASES_URL,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_URL)))
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )

@Composable
private fun Desc(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, desc: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
