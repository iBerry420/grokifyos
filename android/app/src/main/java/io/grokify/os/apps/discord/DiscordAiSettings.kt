package io.grokify.os.apps.discord

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.GROK_VOICES
import io.grokify.os.ui.theme.GrokifyColors

@Composable
internal fun DiscordAiSettingsTab(
    settings: DiscordAiSettings,
    busy: Boolean,
    voiceId: String,
    preferDeviceTts: Boolean,
    hasXaiKey: Boolean,
    voicePreviewMsg: String?,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onRefresh: () -> Unit,
    onVoiceId: (String) -> Unit,
    onPreferDeviceTts: (Boolean) -> Unit,
    onPreviewVoice: () -> Unit,
) {
    var keyDraft by remember(settings.keyHint, settings.keySource) { mutableStateOf("") }
    var modelQuery by remember { mutableStateOf("") }
    val listing = settings.listingProvider.ifBlank { settings.provider }
    val selected = settings.models.firstOrNull { it.id == settings.model }
    val efforts = selected?.reasoningEfforts.orEmpty().ifEmpty {
        settings.models.firstOrNull { it.id == settings.model }?.reasoningEfforts.orEmpty()
    }
    val filtered = settings.models.filter { model ->
        val q = modelQuery.trim()
        q.isEmpty() || model.id.contains(q, ignoreCase = true) || model.name.contains(q, ignoreCase = true)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Provider", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DiscordFilterChip("SpaceXAI API", listing == "spacexai") { onProvider("spacexai") }
                DiscordFilterChip("Bridge", listing == "bridge") { onProvider("bridge") }
            }
            Spacer(Modifier.height(6.dp))
            DiscordMeta(
                if (listing == "bridge") {
                    if (settings.bridgeHealthy) "Grok Build via the host bridge — same path as Chat."
                    else settings.bridgeError.ifBlank { "Bridge unreachable — showing last known models." }
                } else {
                    "Direct api.x.ai chat completions. Text models are listed live from the API."
                },
            )
        }
        if (listing == "spacexai") {
            item {
                DiscordCard {
                    Text("SpaceXAI API key", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    DiscordMeta(
                        when {
                            !settings.keySet -> "Required for tagging and analysis on this provider."
                            settings.keySource == "settings" -> "Saved in Discord settings ${settings.keyHint}".trim()
                            settings.keySource == "env" -> "Using host env ${settings.keyHint}".trim()
                            settings.keySource == "avalynn" -> "Using Avalynn llm_xai_key ${settings.keyHint}".trim()
                            else -> "Key ${settings.keyHint}".trim()
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it.take(256) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("xai-…", color = GrokifyColors.TextDim) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = discordFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row {
                        TextButton(
                            onClick = {
                                val next = keyDraft.trim()
                                if (next.isNotEmpty()) onSaveKey(next)
                            },
                            enabled = !busy && keyDraft.trim().length >= 12,
                        ) {
                            Text("Save key", color = GrokifyColors.GlowCyan)
                        }
                        if (settings.keySource == "settings") {
                            TextButton(onClick = onClearKey, enabled = !busy) {
                                Text("Clear saved key", color = GrokifyColors.GlowRose)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Model", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            DiscordMeta("Live list from ${if (listing == "bridge") "grok models (bridge)" else "api.x.ai/v1/language-models"}. New models show up here automatically.")
            Spacer(Modifier.height(8.dp))
            DiscordSearchRow(modelQuery, "Search models", { modelQuery = it }, {})
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text("Reload list", color = GrokifyColors.GlowCyan)
                }
                DiscordMeta("${settings.models.size} text models")
            }
        }
        if (filtered.isEmpty()) {
            item {
                DiscordEmpty(
                    if (listing == "spacexai" && !settings.keySet) {
                        "Add a SpaceXAI API key to load text models."
                    } else {
                        "No models match."
                    },
                )
            }
        } else {
            items(filtered, key = { it.id }) { model ->
                DiscordCard(onClick = { onModel(model.id) }) {
                    Text(
                        model.name,
                        color = if (model.id == settings.model) GrokifyColors.GlowCyan else GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    DiscordMeta(
                        buildList {
                            add(model.id)
                            if (model.reasoningEfforts.isNotEmpty()) add("reasoning")
                            if (model.id == settings.model) add("selected")
                        }.joinToString(" · "),
                    )
                }
            }
        }
        if (efforts.isNotEmpty()) {
            item {
                Text("Reasoning", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    efforts.forEach { effort ->
                        DiscordFilterChip(effort, settings.reasoningEffort == effort) { onEffort(effort) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                DiscordMeta("Tag and Analyze use this. grok-4.6 defaults to high; xhigh is slower and deeper.")
            }
        }
        item {
            DiscordCard {
                Text("Active", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                DiscordMeta(
                    listOf(
                        if (settings.provider == "bridge") "Bridge" else "SpaceXAI",
                        settings.model,
                        settings.reasoningEffort,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    color = GrokifyColors.GlowMint,
                )
            }
        }
        item {
            Text("Voice · TTS", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            DiscordMeta(
                if (hasXaiKey) {
                    "Grok Voice · xAI key found in the phone vault (same as Live DJ)"
                } else {
                    "Grok Voice · add spacexai_api_key in Settings, or use device TTS"
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GROK_VOICES.forEach { v ->
                    DiscordFilterChip(v.label, voiceId.equals(v.id, ignoreCase = true)) { onVoiceId(v.id) }
                }
            }
            val selectedVoice = GROK_VOICES.firstOrNull { it.id.equals(voiceId, ignoreCase = true) }
            if (selectedVoice != null) {
                Spacer(Modifier.height(4.dp))
                DiscordMeta("${selectedVoice.label} — ${selectedVoice.tone}")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Prefer device TTS",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    preferDeviceTts,
                    onPreferDeviceTts,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = GrokifyColors.Void,
                        checkedTrackColor = GrokifyColors.GlowMint,
                        uncheckedThumbColor = GrokifyColors.TextMuted,
                        uncheckedTrackColor = GrokifyColors.PanelSoft,
                    ),
                )
            }
            DiscordMeta("Skip Grok Voice / xAI and speak summaries on-device.")
            TextButton(onClick = onPreviewVoice, enabled = !busy) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = GrokifyColors.GlowMint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Preview voice", color = GrokifyColors.GlowMint)
            }
            if (!voicePreviewMsg.isNullOrBlank()) {
                DiscordMeta(voicePreviewMsg)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
