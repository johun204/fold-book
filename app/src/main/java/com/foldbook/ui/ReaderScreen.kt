package com.foldbook.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldbook.PageFlipView
import com.foldbook.ReadingDirection
import com.foldbook.SpreadMode
import kotlin.math.roundToInt

private val Scrim = Color(0xD0000000)

@Composable
fun ReaderScreen(
    ready: Boolean,
    page: Int,
    total: Int,
    title: String,
    rtl: Boolean,
    flipViewProvider: () -> View?,
    onBack: () -> Unit,
    onJump: (Int) -> Unit,
    spreadMode: SpreadMode,
    direction: ReadingDirection,
    onChangeSpread: (SpreadMode) -> Unit,
    onChangeDirection: (ReadingDirection) -> Unit,
) {
    var chrome by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    LaunchedEffect(ready) {
        (flipViewProvider() as? PageFlipView)?.onTap = { chrome = !chrome }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!ready) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else {
            AndroidView(
                factory = { flipViewProvider()!! },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = chrome && ready,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            Row(
                Modifier.fillMaxWidth().background(Scrim).statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
                }
                Text(
                    title.ifBlank { "만화" },
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IconButton(onClick = { settings = true }) {
                    Icon(Icons.Filled.Settings, "뷰어 설정", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                Modifier.fillMaxWidth().background(Scrim).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "$page / $total",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                if (total > 1) {
                    // RTL 은 값을 뒤집어 오른쪽 끝이 1페이지가 되도록 (읽기 방향과 일치)
                    fun toSlider(pg: Int) = if (rtl) (total - pg + 1).toFloat() else pg.toFloat()
                    fun fromSlider(v: Float) =
                        (if (rtl) total - v.roundToInt() + 1 else v.roundToInt()).coerceIn(1, total)

                    var v by remember(page, total, rtl) { mutableFloatStateOf(toSlider(page)) }
                    Slider(
                        value = v.coerceIn(1f, total.toFloat()),
                        onValueChange = { v = it },
                        onValueChangeFinished = { onJump(fromSlider(v)) },
                        valueRange = 1f..total.toFloat(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (rtl) "$total" else "1", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text(if (rtl) "1" else "$total", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (settings) {
        ViewerSettingsSheet(
            spread = spreadMode,
            dir = direction,
            onSpread = onChangeSpread,
            onDir = onChangeDirection,
            onDismiss = { settings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerSettingsSheet(
    spread: SpreadMode,
    dir: ReadingDirection,
    onSpread: (SpreadMode) -> Unit,
    onDir: (ReadingDirection) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("뷰어 설정", style = MaterialTheme.typography.titleLarge)
            Cap("페이지 표시")
            Opt("자동 (화면 크기에 맞춤)", spread == SpreadMode.AUTO) { onSpread(SpreadMode.AUTO) }
            Opt("항상 한 페이지씩", spread == SpreadMode.SINGLE) { onSpread(SpreadMode.SINGLE) }
            Opt("항상 양쪽 페이지", spread == SpreadMode.DOUBLE) { onSpread(SpreadMode.DOUBLE) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Cap("읽기 방향 (이 책)")
            Opt("오른쪽 → 왼쪽 (일본 만화)", dir == ReadingDirection.RTL) { onDir(ReadingDirection.RTL) }
            Opt("왼쪽 → 오른쪽 (서양 만화·웹툰)", dir == ReadingDirection.LTR) { onDir(ReadingDirection.LTR) }
            Text(
                "이 책에만 적용됩니다. 기본값은 설정 탭에서 바꿉니다. 변경하면 현재 페이지에서 다시 불러옵니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Cap(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Opt(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
