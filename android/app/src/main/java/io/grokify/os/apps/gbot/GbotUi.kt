package io.grokify.os.apps.gbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.chat.MarkdownText
import io.grokify.os.ui.theme.GrokifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun gbotFieldColors() = OutlinedTextFieldDefaults.colors(
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

internal fun gbotAvatarColor(name: String): Color = when (name.lowercase()) {
    "violet", "purple" -> GrokifyColors.GlowViolet
    "cyan" -> GrokifyColors.GlowCyan
    "mint", "green" -> GrokifyColors.GlowMint
    "amber", "yellow" -> GrokifyColors.GlowAmber
    "rose", "red" -> GrokifyColors.GlowRose
    "blue" -> GrokifyColors.GlowBlue
    else -> GrokifyColors.GlowViolet
}

internal fun formatGbotTs(ms: Long): String {
    if (ms <= 0L) return ""
    return try {
        SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()).format(Date(ms))
    } catch (_: Exception) {
        ""
    }
}

internal fun formatGbotWhen(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return "—"
    val delta = nowMs - ms
    val abs = kotlin.math.abs(delta)
    if (abs >= 86_400_000L) return formatGbotTs(ms).ifBlank { "—" }
    val rel = when {
        abs < 45_000L -> "just now"
        abs < 3_600_000L -> "${abs / 60_000L}m"
        else -> "${abs / 3_600_000L}h"
    }
    return when {
        rel == "just now" && delta >= 0L -> rel
        rel == "just now" -> "soon"
        delta >= 0L -> "$rel ago"
        else -> "in $rel"
    }
}

@Composable
internal fun GbotToolbarChip(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    alert: Boolean = false,
) {
    val accent = if (alert) GrokifyColors.GlowAmber else GrokifyColors.GlowCyan
    val border = if (active) accent.copy(alpha = 0.55f) else GrokifyColors.PanelBorder
    val bg = if (active) accent.copy(alpha = 0.12f) else GrokifyColors.Panel
    val fg = if (active) accent else if (alert) GrokifyColors.GlowAmber else GrokifyColors.TextMuted
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
internal fun GbotAvatar(agent: GbotAgent?, size: Int = 36) {
    val color = gbotAvatarColor(agent?.avatarColor.orEmpty())
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}

@Composable
internal fun GbotStatusDot(agent: GbotAgent) {
    val (label, color) = when {
        agent.isRunning || agent.isComposing -> "live" to GrokifyColors.GlowMint
        agent.isActive -> "active" to GrokifyColors.GlowCyan
        else -> "idle" to GrokifyColors.TextDim
    }
    Text(
        buildString {
            append(label)
            if (agent.hasUnread && agent.unreadCount > 0) append(" · ${agent.unreadCount}")
        },
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
internal fun GbotPanelScaffold(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .navigationBarsPadding()
            .imePadding(),
    ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = GrokifyColors.TextMuted)
                }
            }
            content()
    }
}

@Composable
private fun GbotConnectorBubble(bubble: GbotBubble) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.PanelSoft)
            .border(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text("CONNECTOR", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowViolet)
        Text(
            bubble.connectorName.ifBlank { bubble.text }.ifBlank { "Connector" },
            color = GrokifyColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        val status = bubble.connectorStatus.ifBlank { "tap Add connector in Bots → Details to manage" }
        Text(
            status,
            color = GrokifyColors.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GbotComposer(
    draft: String,
    placeholder: String,
    busy: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    running: Boolean = false,
    onStop: () -> Unit = {},
    onAttach: () -> Unit = {},
    pendingNames: List<String> = emptyList(),
    onRemoveAttach: (Int) -> Unit = {},
    replyLabel: String? = null,
    onClearReply: () -> Unit = {},
) {
    val canSend = (draft.isNotBlank() || pendingNames.isNotEmpty()) && !busy
    Column(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.VoidElevated.copy(alpha = 0.95f))
            .border(0.5.dp, GrokifyColors.PanelBorder),
    ) {
        if (!replyLabel.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = GrokifyColors.GlowCyan,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Reply to $replyLabel",
                    color = GrokifyColors.GlowCyan,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Clear reply", tint = GrokifyColors.TextDim)
                }
            }
        }
        if (pendingNames.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pendingNames.forEachIndexed { index, name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GrokifyColors.PanelSoft)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(999.dp))
                            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Text(name, color = GrokifyColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
                        IconButton(onClick = { onRemoveAttach(index) }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $name", tint = GrokifyColors.TextDim)
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttach, enabled = !busy, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = if (pendingNames.isNotEmpty()) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder, color = GrokifyColors.TextDim) },
                minLines = 1,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                colors = gbotFieldColors(),
                shape = RoundedCornerShape(16.dp),
            )
            if (running) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GrokifyColors.GlowRose.copy(alpha = 0.2f))
                        .border(1.dp, GrokifyColors.GlowRose.copy(alpha = 0.55f), CircleShape)
                        .clickable(enabled = !busy, onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = GrokifyColors.GlowRose,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) {
                            Brush.linearGradient(listOf(GrokifyColors.GlowCyan, GrokifyColors.GlowMint))
                        } else {
                            Brush.linearGradient(listOf(GrokifyColors.PanelSoft, GrokifyColors.PanelSoft))
                        },
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color(0xFF041016) else GrokifyColors.TextDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GbotBubbleView(
    bubble: GbotBubble,
    agentName: String,
    pending: GbotPendingCard?,
    busy: Boolean,
    answerDraft: String,
    secretDraft: String,
    onAnswerDraft: (String) -> Unit,
    onSecretDraft: (String) -> Unit,
    onApprove: (GbotPendingCard, Boolean) -> Unit,
    onDeny: (GbotPendingCard, Boolean) -> Unit,
    onAnswer: (GbotPendingCard, String) -> Unit,
    onDismiss: (GbotPendingCard) -> Unit,
    onSecret: (GbotPendingCard, String) -> Unit,
    onHandback: (GbotPendingCard, Boolean) -> Unit,
    onReply: (GbotBubble) -> Unit = {},
    onVote: (GbotBubble, String) -> Unit = { _, _ -> },
    voteState: String = "",
) {
    when (bubble.kind) {
        GbotBubbleKind.User -> UserGbotBubble(bubble, onReply = onReply)
        GbotBubbleKind.Assistant -> AssistantGbotBubble(
            bubble,
            agentName,
            onReply = onReply,
            onVote = onVote,
            voteState = voteState.ifBlank { bubble.feedback },
            busy = busy,
        )
        GbotBubbleKind.Event -> {
            Text(
                bubble.text,
                color = GrokifyColors.TextDim,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 24.dp),
            )
        }
        GbotBubbleKind.Widget, GbotBubbleKind.LocalTool, GbotBubbleKind.AutoReview, GbotBubbleKind.Secret -> {
            if (pending != null) {
                GbotPendingCardView(
                    card = pending,
                    agentName = agentName,
                    busy = busy,
                    answerDraft = answerDraft,
                    secretDraft = secretDraft,
                    onAnswerDraft = onAnswerDraft,
                    onSecretDraft = onSecretDraft,
                    onApprove = onApprove,
                    onDeny = onDeny,
                    onAnswer = onAnswer,
                    onDismiss = onDismiss,
                    onSecret = onSecret,
                    onHandback = onHandback,
                )
            } else {
                AssistantGbotBubble(bubble, agentName, kindLabel = bubble.kind.name)
            }
        }
        GbotBubbleKind.Connector -> GbotConnectorBubble(bubble)
        GbotBubbleKind.Other -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GrokifyColors.CodeBg)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text("TOOL", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowViolet)
                Text(
                    bubble.text,
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserGbotBubble(bubble: GbotBubble, onReply: (GbotBubble) -> Unit = {}) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(shape)
                .background(GrokifyColors.UserBubble)
                .border(1.dp, GrokifyColors.GlowBlue.copy(alpha = 0.2f), shape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { copyText(context, bubble.text) },
                    role = Role.Button,
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (bubble.fromAgentName.isNotBlank()) bubble.fromAgentName.uppercase() else "YOU",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bubble.fromAgentName.isNotBlank()) GrokifyColors.GlowViolet else GrokifyColors.GlowBlue,
                )
                Spacer(Modifier.weight(1f))
                val ts = formatGbotTs(bubble.timestampMs)
                if (ts.isNotBlank()) {
                    Text(ts, color = GrokifyColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(4.dp))
            MarkdownText(bubble.text.ifBlank { "…" }, textColor = GrokifyColors.TextPrimary)
            TextButton(onClick = { onReply(bubble) }, contentPadding = PaddingValues(0.dp)) {
                Text("Reply", color = GrokifyColors.GlowBlue, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantGbotBubble(
    bubble: GbotBubble,
    agentName: String,
    kindLabel: String? = null,
    onReply: (GbotBubble) -> Unit = {},
    onVote: (GbotBubble, String) -> Unit = { _, _ -> },
    voteState: String = "",
    busy: Boolean = false,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GrokifyColors.AssistantBubble)
            .border(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.18f), shape)
            .combinedClickable(
                onClick = {},
                onLongClick = { copyText(context, bubble.text) },
                role = Role.Button,
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append((kindLabel ?: agentName.ifBlank { "GROK" }).uppercase())
                    if (bubble.toAgentName.isNotBlank()) {
                        append(" → ")
                        append(bubble.toAgentName.uppercase())
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = GrokifyColors.GlowMint,
            )
            if (bubble.streaming) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = GrokifyColors.GlowMint,
                )
            }
            Spacer(Modifier.weight(1f))
            val ts = formatGbotTs(bubble.timestampMs)
            if (ts.isNotBlank()) {
                Text(ts, color = GrokifyColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(6.dp))
        MarkdownText(bubble.text.ifBlank { if (bubble.streaming) "…" else "" }, textColor = GrokifyColors.TextPrimary)
        if (kindLabel == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                TextButton(onClick = { onReply(bubble) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Reply", color = GrokifyColors.GlowCyan, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onVote(bubble, if (voteState == "up") "revert" else "up") },
                    enabled = !busy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ThumbUp,
                        contentDescription = "Thumbs up",
                        tint = if (voteState == "up") GrokifyColors.GlowMint else GrokifyColors.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { onVote(bubble, if (voteState == "down") "revert" else "down") },
                    enabled = !busy,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ThumbDown,
                        contentDescription = "Thumbs down",
                        tint = if (voteState == "down") GrokifyColors.GlowRose else GrokifyColors.TextDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GbotPendingCardView(
    card: GbotPendingCard,
    agentName: String,
    busy: Boolean,
    answerDraft: String,
    secretDraft: String,
    onAnswerDraft: (String) -> Unit,
    onSecretDraft: (String) -> Unit,
    onApprove: (GbotPendingCard, Boolean) -> Unit,
    onDeny: (GbotPendingCard, Boolean) -> Unit,
    onAnswer: (GbotPendingCard, String) -> Unit,
    onDismiss: (GbotPendingCard) -> Unit,
    onSecret: (GbotPendingCard, String) -> Unit,
    onHandback: (GbotPendingCard, Boolean) -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val accent = when (card.kind) {
        "local-tool-permission" -> GrokifyColors.GlowAmber
        "auto-review-approval" -> GrokifyColors.GlowCyan
        "secret-request" -> GrokifyColors.GlowRose
        "forever-box.handoff" -> GrokifyColors.GlowMint
        else -> GrokifyColors.GlowViolet
    }
    val kindLabel = when (card.kind) {
        "local-tool-permission" -> "LOCAL TOOL"
        "auto-review-approval" -> "AUTO-REVIEW"
        "secret-request" -> "SECRET"
        "forever-box.handoff" -> "COMPUTER"
        "widget" -> "QUESTION"
        else -> card.kind.replace('-', ' ').uppercase()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel, style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(
                agentName,
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val body = card.prompt.ifBlank { card.toolTarget.ifBlank { card.detail } }
        if (body.isNotBlank()) {
            Text(
                body,
                color = GrokifyColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = if (card.kind == "local-tool-permission") FontFamily.Monospace else FontFamily.Default,
            )
        } else {
            Text("Loading details…", color = GrokifyColors.TextDim, fontSize = 13.sp)
        }
        if (card.toolAction.isNotBlank()) {
            Text(card.toolAction, color = GrokifyColors.TextDim, fontSize = 11.sp)
        }
        if (card.skipped) {
            Text(
                "Conversation continued — still waiting on this card.",
                color = GrokifyColors.GlowAmber,
                fontSize = 11.sp,
            )
        }
        when (card.kind) {
            "local-tool-permission" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GbotPrimaryBtn("Once", enabled = !busy, color = GrokifyColors.GlowCyan) { onApprove(card, false) }
                GbotPrimaryBtn("Always", enabled = !busy, color = GrokifyColors.GlowMint) { onApprove(card, true) }
                GbotGhostBtn("Deny", enabled = !busy, danger = true) { onDeny(card, false) }
                GbotGhostBtn("Never", enabled = !busy, danger = true) { onDeny(card, true) }
            }
            "auto-review-approval" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GbotPrimaryBtn("Approve", enabled = !busy) { onApprove(card, false) }
                GbotPrimaryBtn("Always", enabled = !busy, color = GrokifyColors.GlowMint) { onApprove(card, true) }
                GbotGhostBtn("Deny", enabled = !busy, danger = true) { onDeny(card, false) }
            }
            "widget" -> {
                card.options.forEach { opt ->
                    val primary = opt.primary
                    if (primary) {
                        GbotPrimaryBtn(opt.label, enabled = !busy, fill = true) { onAnswer(card, opt.value) }
                    } else {
                        GbotGhostBtn(opt.label, enabled = !busy, fill = true) { onAnswer(card, opt.value) }
                    }
                }
                if (card.allowCustom || card.options.isEmpty()) {
                    OutlinedTextField(
                        value = answerDraft,
                        onValueChange = onAnswerDraft,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Custom answer", color = GrokifyColors.TextDim) },
                        singleLine = true,
                        colors = gbotFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GbotPrimaryBtn("Send", enabled = !busy && answerDraft.isNotBlank()) {
                            onAnswer(card, answerDraft.trim())
                        }
                        GbotGhostBtn("Dismiss", enabled = !busy) { onDismiss(card) }
                    }
                } else {
                    GbotGhostBtn("Dismiss", enabled = !busy) { onDismiss(card) }
                }
            }
            "secret-request" -> {
                OutlinedTextField(
                    value = secretDraft,
                    onValueChange = onSecretDraft,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Secret", color = GrokifyColors.TextDim) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = gbotFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                )
                GbotPrimaryBtn("Submit", enabled = !busy && secretDraft.isNotBlank()) {
                    onSecret(card, secretDraft)
                }
            }
            "forever-box.handoff" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GbotPrimaryBtn("Hand back", enabled = !busy, color = GrokifyColors.GlowMint) {
                    onHandback(card, false)
                }
                GbotGhostBtn("Dismiss", enabled = !busy) { onHandback(card, true) }
            }
            else -> GbotGhostBtn("Dismiss", enabled = !busy) { onDismiss(card) }
        }
        if (onOpen != null) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
                Text("Open chat", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GbotPrimaryBtn(
    label: String,
    enabled: Boolean,
    color: Color = GrokifyColors.GlowCyan,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF041016)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun GbotGhostBtn(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (danger) GrokifyColors.GlowRose.copy(alpha = 0.5f) else GrokifyColors.PanelBorder,
        ),
    ) {
        Text(
            label,
            color = if (danger) GrokifyColors.GlowRose else GrokifyColors.TextMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun GbotInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, color = GrokifyColors.TextDim, fontSize = 13.sp, modifier = Modifier.width(104.dp))
        Text(value, color = GrokifyColors.TextPrimary, fontSize = 13.sp)
    }
}

@Composable
internal fun GbotSectionLabel(text: String) {
    Text(
        text,
        color = GrokifyColors.GlowCyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

internal fun copyText(context: Context, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("gbot", text))
}
