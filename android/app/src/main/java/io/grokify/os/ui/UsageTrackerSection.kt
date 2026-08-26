package io.grokify.os.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun UsageTrackerSection(tracker: UsageTrackerInfo?) {
    if (tracker == null || !tracker.ok) return
    val totals = tracker
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.PanelSoft)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "AGENT ACTIVITY",
            color = GrokifyColors.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        val cells = listOf(
            "Sessions" to totals.agentSessions.toString(),
            "Loops" to UsageFormat.compactCount(totals.modelLoops),
            "Tools" to UsageFormat.compactCount(totals.toolCalls),
            "Messages" to UsageFormat.compactCount(totals.messageCount),
            "Wall" to UsageFormat.compactDuration(totals.wallTimeS),
            "Model" to UsageFormat.compactDuration(totals.modelTimeS),
            "Context" to UsageFormat.compactTokens(totals.lastContextTokens),
            "Est. in" to UsageFormat.compactTokens(totals.estimatedInputTokens),
            "Est. out" to UsageFormat.compactTokens(totals.estimatedOutputTokens),
        )
        cells.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            value,
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(label, color = GrokifyColors.TextDim, fontSize = 10.sp, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (tracker.tools.isNotEmpty()) {
            Text(
                tracker.tools.take(12).joinToString(" · "),
                color = GrokifyColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tracker.daily.size >= 2) {
            Text(
                "BY DAY",
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            UsageDayChart(tracker.daily)
            Text(
                "Bars = estimated billed input. Context is the last window snapshot (sum).",
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun UsageDayChart(days: List<UsageDayPoint>) {
    val max = days.maxOfOrNull { it.estimatedInputTokens }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { d ->
            val frac = (d.estimatedInputTokens.toDouble() / max.toDouble())
                .toFloat()
                .coerceIn(if (d.estimatedInputTokens > 0) 0.04f else 0f, 1f)
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    UsageFormat.compactTokens(d.estimatedInputTokens),
                    color = GrokifyColors.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight(frac)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(GrokifyColors.GlowCyan.copy(alpha = 0.85f)),
                    )
                }
                Text(
                    UsageFormat.shortDay(d.day),
                    color = GrokifyColors.TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                Text(
                    UsageFormat.compactDuration(d.wallTimeS),
                    color = GrokifyColors.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}
