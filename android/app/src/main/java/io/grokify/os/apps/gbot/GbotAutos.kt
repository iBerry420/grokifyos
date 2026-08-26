package io.grokify.os.apps.gbot

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.theme.GrokifyColors

@Composable
internal fun GbotAutosTab(
    agentName: String,
    automations: List<GbotAutomation>,
    busy: Boolean,
    timeZone: String,
    onToggle: (String, Boolean) -> Unit,
    onRun: (String) -> Unit,
    onSave: (GbotAutomation, prompt: String, cron: String) -> Unit,
    onWebhook: (GbotAutomation) -> Unit = {},
    onCreateWebhook: () -> Unit = {},
    onDelete: (GbotAutomation) -> Unit = {},
) {
    var openPromptIds by remember { mutableStateOf(setOf<String>()) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (agentName.isBlank()) "Automations" else "$agentName automations",
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        if (automations.isEmpty()) {
            Text(
                "No automations on this bot. Create a webhook here, or make a cron job in gbot and it will show up so you can set frequency and edit the prompt.",
                color = GrokifyColors.TextDim,
                fontSize = 13.sp,
            )
            TextButton(onClick = onCreateWebhook, enabled = !busy) {
                Text("New webhook automation", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
            }
        } else {
            automations.forEach { auto ->
                GbotAutoCard(
                    auto = auto,
                    busy = busy,
                    timeZone = timeZone,
                    promptOpen = auto.id in openPromptIds,
                    onTogglePrompt = {
                        openPromptIds = if (auto.id in openPromptIds) {
                            openPromptIds - auto.id
                        } else {
                            openPromptIds + auto.id
                        }
                    },
                    onToggle = { onToggle(auto.id, it) },
                    onRun = { onRun(auto.id) },
                    onSave = { prompt, cron -> onSave(auto, prompt, cron) },
                    onWebhook = { onWebhook(auto) },
                    onDelete = { onDelete(auto) },
                )
            }
            TextButton(onClick = onCreateWebhook, enabled = !busy) {
                Text("New webhook automation", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GbotAutoCard(
    auto: GbotAutomation,
    busy: Boolean,
    timeZone: String,
    promptOpen: Boolean,
    onTogglePrompt: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onSave: (prompt: String, cron: String) -> Unit,
    onWebhook: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val context = LocalContext.current
    var showPromptEditor by remember(auto.id, auto.prompt) { mutableStateOf(false) }
    var showScheduleEditor by remember(auto.id) { mutableStateOf(false) }
    val cronExpr = auto.cron.ifBlank {
        if (GbotCron.looksLikeCron(auto.schedule)) auto.schedule else ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.92f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (auto.isRunning) "${auto.name} · running" else auto.name,
                    color = if (auto.isRunning) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    buildString {
                        if (auto.triggerType == "webhook") {
                            append("webhook")
                            if (auto.schedule.isNotBlank() && auto.schedule != "webhook") {
                                append(" · ")
                                append(auto.schedule)
                            }
                        } else {
                            append(auto.schedule.ifBlank { "manual" })
                            if (cronExpr.isNotBlank() && cronExpr != auto.schedule) {
                                append(" · ")
                                append(cronExpr)
                            }
                        }
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = !busy) { showScheduleEditor = true },
                )
            }
            Switch(
                checked = auto.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
                colors = SwitchDefaults.colors(checkedTrackColor = GrokifyColors.GlowMint),
            )
        }
        GbotInfoRow("Last", formatGbotWhen(auto.lastRunAt.takeIf { it > 0L } ?: auto.latestRun?.startedAt ?: 0L))
        GbotInfoRow("Next", if (auto.enabled) formatGbotWhen(auto.nextRunAt) else "paused")
        val latest = auto.latestRun
        if (latest != null) {
            GbotInfoRow(
                "Latest",
                buildString {
                    append(
                        when {
                            latest.isActive -> "in progress"
                            latest.ok -> "ok"
                            else -> latest.status.ifBlank { "ended" }
                        },
                    )
                    if (!latest.isActive && latest.startedAt > 0L && latest.finishedAt >= latest.startedAt) {
                        append(" · ")
                        append(GbotWatchEval.formatDuration(latest.finishedAt - latest.startedAt))
                    }
                },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TextButton(onClick = onRun, enabled = !busy && auto.enabled) {
                Text("Run now", color = GrokifyColors.GlowMint, fontSize = 12.sp)
            }
            TextButton(onClick = { showScheduleEditor = true }, enabled = !busy) {
                Text("Schedule", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
            }
            TextButton(onClick = onTogglePrompt) {
                Text(
                    if (promptOpen) "Hide prompt" else "Prompt",
                    color = GrokifyColors.GlowCyan,
                    fontSize = 12.sp,
                )
            }
            TextButton(
                onClick = {
                    if (auto.prompt.isBlank()) return@TextButton
                    copyText(context, auto.prompt)
                    Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                },
                enabled = auto.prompt.isNotBlank(),
            ) {
                Text("Copy", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
            }
            TextButton(onClick = { showPromptEditor = true }, enabled = !busy) {
                Text("Edit", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
            }
            TextButton(onClick = onWebhook, enabled = !busy) {
                Text(
                    if (auto.triggerType == "webhook") "Webhook key" else "Webhook",
                    color = GrokifyColors.GlowViolet,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text("Delete", color = GrokifyColors.GlowRose, fontSize = 12.sp)
            }
        }
        if (promptOpen) {
            Text(
                auto.prompt.ifBlank { "Prompt isn’t in this snapshot — pull to refresh this sheet." },
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.CodeBg)
                    .padding(10.dp),
            )
        }
        val history = auto.runs.ifEmpty { listOfNotNull(auto.latestRun) }
        if (history.isNotEmpty()) {
            GbotSectionLabel("RUNS")
            history.take(12).forEach { run ->
                val statusColor = when {
                    run.isActive -> GrokifyColors.GlowMint
                    run.ok -> GrokifyColors.TextMuted
                    else -> GrokifyColors.GlowRose
                }
                val line = buildString {
                    append(formatGbotWhen(run.startedAt))
                    append(" · ")
                    append(
                        when {
                            run.isActive -> "running"
                            run.ok -> "ok"
                            else -> run.status.ifBlank { "ended" }
                        },
                    )
                    if (!run.isActive && run.startedAt > 0L && run.finishedAt >= run.startedAt) {
                        append(" · ")
                        append(GbotWatchEval.formatDuration(run.finishedAt - run.startedAt))
                    }
                    if (run.trigger.isNotBlank()) {
                        append(" · ")
                        append(run.trigger)
                    }
                }
                Text(
                    line,
                    color = statusColor,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    if (showPromptEditor) {
        GbotPromptEditorDialog(
            initial = auto.prompt,
            busy = busy,
            onDismiss = { showPromptEditor = false },
            onSave = { prompt ->
                showPromptEditor = false
                onSave(prompt, cronExpr)
            },
        )
    }
    if (showScheduleEditor) {
        GbotScheduleEditorDialog(
            initialCron = cronExpr,
            timeZone = timeZone,
            busy = busy,
            onDismiss = { showScheduleEditor = false },
            onSave = { cron ->
                showScheduleEditor = false
                onSave(auto.prompt, cron)
            },
        )
    }
}

@Composable
private fun GbotPromptEditorDialog(
    initial: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onSave(draft.trim()) },
                enabled = !busy && draft.trim().isNotEmpty(),
            ) { Text("Save", color = GrokifyColors.GlowCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = GrokifyColors.TextMuted) }
        },
        title = { Text("Edit prompt", color = GrokifyColors.TextPrimary) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                minLines = 8,
                colors = gbotFieldColors(),
            )
        },
        containerColor = GrokifyColors.Panel,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GbotScheduleEditorDialog(
    initialCron: String,
    timeZone: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var edit by remember {
        mutableStateOf(GbotCron.parse(initialCron.ifBlank { "*/15 * * * *" }))
    }
    fun apply(transform: (GbotCronEdit) -> GbotCronEdit) {
        val next = transform(edit).copy(custom = false)
        edit = next.copy(cronText = composeCron(next.everyMinutes, next.allDay, next.startHour, next.endHour))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val cron = GbotCron.compose(edit)
                    if (GbotCron.looksLikeCron(cron)) onSave(cron)
                },
                enabled = !busy && GbotCron.looksLikeCron(GbotCron.compose(edit)),
            ) { Text("Save", color = GrokifyColors.GlowCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = GrokifyColors.TextMuted) }
        },
        title = { Text("Schedule", color = GrokifyColors.TextPrimary) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    edit.describe() + if (timeZone.isNotBlank()) " · $timeZone" else "",
                    color = GrokifyColors.GlowMint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text("FREQUENCY", color = GrokifyColors.GlowCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GbotCron.frequencies.forEach { minutes ->
                        val selected = !edit.custom && edit.everyMinutes == minutes
                        GbotChoiceChip(
                            label = when (minutes) {
                                1440 -> "daily"
                                60 -> "1h"
                                120 -> "2h"
                                240 -> "4h"
                                360 -> "6h"
                                else -> "${minutes}m"
                            },
                            selected = selected,
                            onClick = { apply { it.copy(everyMinutes = minutes) } },
                        )
                    }
                }
                Text("HOURS", color = GrokifyColors.GlowCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GbotChoiceChip(
                        label = "24 hours",
                        selected = !edit.custom && edit.allDay,
                        onClick = { apply { it.copy(allDay = true) } },
                    )
                    GbotChoiceChip(
                        label = "8–21",
                        selected = !edit.custom && !edit.allDay && edit.startHour == 8 && edit.endHour == 21,
                        onClick = { apply { it.copy(allDay = false, startHour = 8, endHour = 21) } },
                    )
                    GbotChoiceChip(
                        label = "9–17",
                        selected = !edit.custom && !edit.allDay && edit.startHour == 9 && edit.endHour == 17,
                        onClick = { apply { it.copy(allDay = false, startHour = 9, endHour = 17) } },
                    )
                    GbotChoiceChip(
                        label = "Custom",
                        selected = !edit.custom && !edit.allDay &&
                            !(edit.startHour == 8 && edit.endHour == 21) &&
                            !(edit.startHour == 9 && edit.endHour == 17),
                        onClick = { apply { it.copy(allDay = false) } },
                    )
                }
                if (!edit.allDay) {
                    Text("From", color = GrokifyColors.TextDim, fontSize = 12.sp)
                    GbotHourStrip(selected = edit.startHour) { hour ->
                        apply { it.copy(allDay = false, startHour = hour) }
                    }
                    Text("To", color = GrokifyColors.TextDim, fontSize = 12.sp)
                    GbotHourStrip(selected = edit.endHour) { hour ->
                        apply { it.copy(allDay = false, endHour = hour) }
                    }
                }
                OutlinedTextField(
                    value = edit.cronText,
                    onValueChange = { text ->
                        val parsed = GbotCron.parse(text)
                        edit = if (GbotCron.looksLikeCron(text) && !parsed.custom) {
                            parsed
                        } else {
                            edit.copy(cronText = text, custom = true)
                        }
                    },
                    label = { Text("Cron") },
                    singleLine = true,
                    colors = gbotFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (timeZone.isBlank()) {
                        "Hours are in the bot timezone (Setup → Override)."
                    } else {
                        "Hours are $timeZone. 8–21 means 08:00 through 21:59."
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
        },
        containerColor = GrokifyColors.Panel,
    )
}

@Composable
private fun GbotChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.2f),
            selectedLabelColor = GrokifyColors.GlowCyan,
        ),
    )
}

@Composable
private fun GbotHourStrip(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..23).forEach { hour ->
            GbotChoiceChip(
                label = hourLabel(hour),
                selected = selected == hour,
                onClick = { onSelect(hour) },
            )
        }
    }
}
