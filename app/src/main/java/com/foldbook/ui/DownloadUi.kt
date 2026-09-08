package com.foldbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.foldbook.DownloadService
import com.foldbook.DownloadState

/** 탐색 탭·폴더 화면에서 공통으로 쓰는 다운로드 진행 카드. */
@Composable
fun DownloadCard(d: DownloadState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var applyAll by remember { mutableStateOf(false) }

    Surface(
        modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = if (d.conflict != null) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Download, null)
                Text(
                    when {
                        d.error != null -> "다운로드 ${d.error}"
                        d.finished -> "다운로드 완료"
                        d.conflict != null -> "선택이 필요합니다"
                        else -> "다운로드 중"
                    },
                    Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (d.finished) {
                    TextButton(onClick = { DownloadService.dismiss() }) { Text("닫기") }
                } else {
                    TextButton(onClick = { DownloadService.cancel(ctx) }) { Text("취소") }
                }
            }

            if (d.roots.isNotEmpty()) {
                Text(
                    d.roots.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }

            if (!d.finished && d.conflict == null) {
                Text(
                    if (d.total > 0) "${d.done} / ${d.total}" + (if (d.currentFile.isNotBlank()) " · ${d.currentFile}" else "")
                    else "목록 확인 중…",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (d.total > 0) {
                    LinearProgressIndicator(
                        progress = { d.done.toFloat() / d.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            if (d.conflict != null) {
                Text(
                    "이미 있는 파일: ${d.conflict}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text("이후 모두 적용", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { DownloadService.resolve(ctx, overwrite = false, all = applyAll) }) {
                        Text("건너뛰기")
                    }
                    androidx.compose.material3.Button(
                        onClick = { DownloadService.resolve(ctx, overwrite = true, all = applyAll) },
                    ) { Text("덮어쓰기") }
                }
            }

            if (d.finished && d.error == null) {
                Text("${d.done}개 파일", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
