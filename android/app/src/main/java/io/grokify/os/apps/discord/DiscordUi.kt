package io.grokify.os.apps.discord

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.grokify.os.ui.theme.GrokifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun discordFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = GrokifyColors.GlowCyan,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    cursorColor = GrokifyColors.GlowCyan,
    focusedContainerColor = GrokifyColors.PanelSoft,
    unfocusedContainerColor = GrokifyColors.PanelSoft,
    focusedLabelColor = GrokifyColors.GlowCyan,
    unfocusedLabelColor = GrokifyColors.TextDim,
    focusedPlaceholderColor = GrokifyColors.TextDim,
    unfocusedPlaceholderColor = GrokifyColors.TextDim,
)

internal fun discordMemberChannelBots(
    bots: List<DiscordChannelBot>,
    memberBotIds: Set<Int>,
): List<DiscordChannelBot> {
    if (memberBotIds.isEmpty()) return emptyList()
    return bots.filter { it.botId in memberBotIds }
}

internal fun discordToMarkdown(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw
    s = s.replace(Regex("<a?:([A-Za-z0-9_]+):\\d+>"), ":$1:")
    s = s.replace(Regex("<@!?\\d+>"), "`@user`")
    s = s.replace(Regex("<@&\\d+>"), "`@role`")
    s = s.replace(Regex("<#\\d+>"), "`#channel`")
    s = s.replace(Regex("\\|\\|(.+?)\\|\\|"), "*$1*")
    return s
}

internal fun formatDiscordWhen(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return "—"
    val delta = nowMs - ms
    val abs = kotlin.math.abs(delta)
    if (abs >= 86_400_000L) {
        return try {
            SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (_: Exception) {
            "—"
        }
    }
    val rel = when {
        abs < 45_000L -> "just now"
        abs < 3_600_000L -> "${abs / 60_000L}m"
        else -> "${abs / 3_600_000L}h"
    }
    return if (delta >= 0L) {
        if (rel == "just now") rel else "$rel ago"
    } else {
        "in $rel"
    }
}

@Composable
internal fun DiscordToolbarChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val border = if (active) GrokifyColors.GlowCyan.copy(alpha = 0.55f) else GrokifyColors.PanelBorder
    val bg = if (active) GrokifyColors.GlowCyan.copy(alpha = 0.12f) else GrokifyColors.Panel
    val fg = if (active) GrokifyColors.GlowCyan else GrokifyColors.TextMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun DiscordCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.92f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        content = content,
    )
}

@Composable
internal fun rememberDiscordImageRequest(
    url: String,
    headers: Map<String, String> = emptyMap(),
    crossfade: Boolean = false,
): ImageRequest {
    val ctx = LocalContext.current
    val cacheKey = discordStableMediaKey(url).ifBlank { url }
    val headerSig = headers.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
    return remember(cacheKey, headerSig, crossfade) {
        ImageRequest.Builder(ctx).data(url).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
            if (cacheKey.isNotBlank()) {
                memoryCacheKey(cacheKey)
                diskCacheKey(cacheKey)
            }
            crossfade(crossfade)
        }.build()
    }
}

@Composable
internal fun DiscordAvatar(
    url: String,
    name: String,
    size: Int = 36,
    headers: Map<String, String> = emptyMap(),
    onClick: (() -> Unit)? = null,
) {
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(GrokifyColors.GlowViolet.copy(alpha = 0.22f))
            .border(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.4f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = rememberDiscordImageRequest(url, headers, crossfade = false),
                contentDescription = null,
                modifier = Modifier.size(size.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(letter, color = GrokifyColors.GlowViolet, fontSize = (size * 0.38f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DiscordEmpty(text: String) {
    Text(
        text,
        color = GrokifyColors.TextDim,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

@Composable
internal fun DiscordBusy() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = GrokifyColors.GlowCyan)
    }
}

@Composable
internal fun DiscordMeta(text: String, color: Color = GrokifyColors.TextDim) {
    Text(text, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun DiscordStatusDot(running: Boolean, active: Boolean) {
    val (label, color) = when {
        running -> "live" to GrokifyColors.GlowMint
        active -> "idle" to GrokifyColors.GlowCyan
        else -> "off" to GrokifyColors.TextDim
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun DiscordFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) GrokifyColors.GlowCyan.copy(alpha = 0.12f) else GrokifyColors.Panel)
            .border(
                1.dp,
                if (active) GrokifyColors.GlowCyan.copy(alpha = 0.5f) else GrokifyColors.PanelBorder,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
internal fun DiscordSectionHint(text: String) {
    Text(
        text,
        color = GrokifyColors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
    Spacer(Modifier.height(8.dp))
}
