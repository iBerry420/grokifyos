package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.LyreActivity
import io.grokify.os.apps.lyre.LyreActivityLine
import io.grokify.os.ui.theme.GrokifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LyreActivityPane(
    activity: LyreActivity,
    tick: Int,
    onJump: (LyreActivityLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = remember(tick, activity) { activity.readNewestFirst() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (lines.isEmpty()) {
            item {
                Text("No activity yet", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }
        items(lines, key = { "${it.ts}-${it.type}-${it.summary}-${it.frameId}-${it.clipId}" }) { line ->
            val jumpable = line.jumpable
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(GrokifyColors.Panel, RoundedCornerShape(10.dp))
                    .then(if (jumpable) Modifier.clickable { onJump(line) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    line.displaySummary(),
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                Text(
                    listOfNotNull(
                        fmtActivityTime(line.ts),
                        line.type.takeIf { it.isNotBlank() },
                        if (jumpable) "tap to jump" else null,
                    ).joinToString(" · "),
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun fmtActivityTime(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    return when {
        delta < 45_000L -> "just now"
        delta < 90 * 60_000L -> "${delta / 60_000L}m ago"
        delta < 36 * 3600_000L -> "${delta / 3_600_000L}h ago"
        else -> SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ms))
    }
}
