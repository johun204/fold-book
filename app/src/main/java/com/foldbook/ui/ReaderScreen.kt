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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldbook.PageFlipView
import com.foldbook.ReadingDirection
import com.foldbook.SpreadMode
import kotlin.math.roundToInt

/** 읽는 그림 위에 떠 있는 유리판 느낌의 바. */
private val GlassBar = Color(0xE61A1A20)
private val OnGlass = Color(0xFFF2F1F6)
private val OnGlassDim = Color(0xB3F2F1F6)

/** 바가 페이지 위에 떠 보이도록 화면 위/아래를 부드럽게 어둡게. */
private val TopFade = Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))
private val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))

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
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = OnGlass, strokeWidth = 3.dp)
                Spacer(Modifier.height(14.dp))
                Text("책을 펼치는 중…", color = OnGlassDim, style = MaterialTheme.typography.labelLarge)
            }
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
            Box(Modifier.fillMaxWidth().background(TopFade).padding(bottom = 20.dp)) {
                Row(
                    Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .background(GlassBar, RoundedCornerShape(24.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = OnGlass),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로")
                    }
                    Text(
                        title.ifBlank { "만화" },
                        color = OnGlass,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = { settings = true },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = OnGlass),
                    ) {
                        Icon(Icons.Filled.Settings, "뷰어 설정")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome && ready,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            // RTL 은 값을 뒤집어 오른쪽 끝이 1페이지가 되도록 (읽기 방향과 일치)
            fun toSlider(pg: Int) = if (rtl) (total - pg + 1).toFloat() else pg.toFloat()
            fun fromSlider(v: Float) =
                (if (rtl) total - v.roundToInt() + 1 else v.roundToInt()).coerceIn(1, total)

            var v by remember(page, total, rtl) { mutableFloatStateOf(toSlider(page)) }
            var scrubbing by remember { mutableStateOf(false) }

            Box(Modifier.fillMaxWidth().background(BottomFade).padding(top = 24.dp)) {
                Column(
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .background(GlassBar, RoundedCornerShape(24.dp))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // 스크럽 중에는 끌고 있는 위치의 페이지 번호를 미리 보여준다
                    PageChip(
                        page = if (scrubbing) fromSlider(v) else page,
                        total = total,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (total > 1) {
                        Slider(
                            value = v.coerceIn(1f, total.toFloat()),
                            onValueChange = { scrubbing = true; v = it },
                            onValueChangeFinished = { scrubbing = false; onJump(fromSlider(v)) },
                            valueRange = 1f..total.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = OnGlass,
                                activeTrackColor = OnGlass,
                                inactiveTrackColor = Color(0x40F2F1F6),
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (rtl) "$total" else "1",
                                color = OnGlassDim,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                if (rtl) "1" else "$total",
                                color = OnGlassDim,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
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

/** 현재 페이지 / 전체 를 보여주는 알약 칩. */
@Composable
private fun PageChip(page: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0x26F2F1F6), CircleShape)
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$page", color = OnGlass, style = MaterialTheme.typography.titleSmall)
        Text(" / $total", color = OnGlassDim, style = MaterialTheme.typography.labelMedium)
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
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("뷰어 설정", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Cap("페이지 표시")
            Opt("자동 (화면 크기에 맞춤)", spread == SpreadMode.AUTO) { onSpread(SpreadMode.AUTO) }
            Opt("항상 한 페이지씩", spread == SpreadMode.SINGLE) { onSpread(SpreadMode.SINGLE) }
            Opt("항상 양쪽 페이지", spread == SpreadMode.DOUBLE) { onSpread(SpreadMode.DOUBLE) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Cap("읽기 방향 (이 책)")
            Opt("오른쪽 → 왼쪽 (일본 만화)", dir == ReadingDirection.RTL) { onDir(ReadingDirection.RTL) }
            Opt("왼쪽 → 오른쪽 (서양 만화·웹툰)", dir == ReadingDirection.LTR) { onDir(ReadingDirection.LTR) }
            Spacer(Modifier.height(6.dp))
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
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )

@Composable
private fun Opt(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(14.dp),
            )
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
