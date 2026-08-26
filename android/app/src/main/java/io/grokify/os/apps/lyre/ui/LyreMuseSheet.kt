package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.LyreMuseMessage
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreMuseSheet(
    messages: List<LyreMuseMessage>,
    busy: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val sendLatest = rememberUpdatedState(onSend)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        val last = messages.lastIndex + if (busy) 1 else 0
        if (last >= 0) runCatching { listState.scrollToItem(last) }
    }
    Column(
        modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "Muse",
            color = GrokifyColors.GlowRose,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(messages, key = { i, m -> "$i-${m.role}-${m.text.hashCode()}" }) { _, msg ->
                val muse = msg.role != "user"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (muse) GrokifyColors.AssistantBubble else GrokifyColors.UserBubble,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        when (msg.role) {
                            "user" -> "You"
                            "error" -> "Error"
                            else -> "Muse"
                        },
                        color = if (msg.role == "error") GrokifyColors.GlowRose else GrokifyColors.TextMuted,
                        fontSize = 10.sp,
                    )
                    Text(
                        msg.text,
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (busy) {
                item {
                    Text("Thinking…", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                }
            }
            if (messages.isEmpty() && !busy) {
                item {
                    Text(
                        "Ask about this board’s rails, leftovers, or scenes.",
                        color = GrokifyColors.TextMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Muse", color = GrokifyColors.TextDim) },
                enabled = !busy,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GrokifyColors.TextPrimary,
                    unfocusedTextColor = GrokifyColors.TextPrimary,
                    focusedBorderColor = GrokifyColors.GlowRose,
                    unfocusedBorderColor = GrokifyColors.PanelBorder,
                    cursorColor = GrokifyColors.GlowRose,
                    focusedContainerColor = GrokifyColors.PanelSoft,
                    unfocusedContainerColor = GrokifyColors.PanelSoft,
                    disabledTextColor = GrokifyColors.TextDim,
                    disabledBorderColor = GrokifyColors.PanelBorder,
                    disabledContainerColor = GrokifyColors.Panel,
                ),
            )
            TextButton(
                onClick = {
                    val t = draft.trim()
                    if (t.isEmpty() || busy) return@TextButton
                    sendLatest.value(t)
                    draft = ""
                },
                enabled = !busy && draft.isNotBlank(),
            ) {
                Text("Send", color = GrokifyColors.GlowRose, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
