package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.lyre.LyreApi
import io.grokify.os.apps.lyre.LyreCopyLinkKind
import io.grokify.os.apps.lyre.LyreProject
import io.grokify.os.apps.lyre.lyreCopyLinkDecision
import io.grokify.os.apps.lyre.lyreProjectFromJson
import io.grokify.os.apps.lyre.lyreProjectsFromJson
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LyreProjectPicker(
    visible: Boolean,
    current: LyreProject,
    api: LyreApi,
    onDismiss: () -> Unit,
    onOpen: (LyreProject) -> Unit,
    onCreated: (LyreProject) -> Unit,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var projects by remember { mutableStateOf(listOf(current)) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmRotate by remember { mutableStateOf<RotateWhy?>(null) }

    fun load() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { api.projects() }
            if (list.optBoolean("ok", false)) {
                projects = lyreProjectsFromJson(list)
            }
            val st = withContext(Dispatchers.IO) { api.mcpStatus() }
            if (st.optBoolean("ok", false)) status = st
        }
    }

    LaunchedEffect(visible) {
        if (visible) load()
    }

    fun applyStatus(json: JSONObject) {
        if (json.optBoolean("ok", false)) {
            status = json
        } else {
            notice = json.optString("error").ifBlank { "request_failed" }
        }
    }

    fun copyLink(json: JSONObject) {
        val decision = lyreCopyLinkDecision(json)
        when (decision.kind) {
            LyreCopyLinkKind.COPY -> {
                clipboard.setText(AnnotatedString(decision.link))
                notice = "Copied connector URL"
                applyStatus(json)
            }
            LyreCopyLinkKind.PROMPT_ROTATE -> {
                applyStatus(json)
                confirmRotate = RotateWhy.Copy
            }
            LyreCopyLinkKind.ERROR -> {
                notice = decision.error
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GrokifyColors.Panel,
        title = {
            Text("Projects", color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                projects.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { onOpen(p) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name.ifBlank { "Untitled" },
                                color = GrokifyColors.TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val badge = buildString {
                                if (p.isOdysseus) append("Odysseus")
                                val upd = p.updatedAt?.trim().orEmpty()
                                if (upd.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(upd.take(16))
                                }
                            }
                            if (badge.isNotEmpty()) {
                                Text(badge, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                            }
                        }
                        if (p.id == current.id) {
                            Text("✓", color = GrokifyColors.GlowCyan, fontSize = 14.sp)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("New project", color = GrokifyColors.TextDim) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowCyan,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowCyan,
                        ),
                    )
                    TextButton(
                        enabled = !busy && newName.trim().isNotEmpty(),
                        onClick = {
                            val name = newName.trim()
                            busy = true
                            scope.launch {
                                val json = withContext(Dispatchers.IO) { api.create(name) }
                                busy = false
                                if (!json.optBoolean("ok", false)) {
                                    notice = json.optString("error").ifBlank { "create_failed" }
                                    return@launch
                                }
                                val row = json.optJSONObject("project")
                                val created = row?.let { lyreProjectFromJson(it) }
                                if (created == null) {
                                    notice = "create_failed"
                                    return@launch
                                }
                                newName = ""
                                onCreated(created)
                            }
                        },
                    ) {
                        Text("New", color = GrokifyColors.GlowCyan)
                    }
                }
                HorizontalDivider(color = GrokifyColors.PanelBorder)
                Text(
                    "Connector",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
                val st = status
                val enabled = st?.optBoolean("enabled", false) == true
                val has = st?.optBoolean("has_connector", false) == true
                val prefix = st?.optString("token_prefix").orEmpty()
                Text(
                    when {
                        !has -> "No connector URL yet"
                        enabled -> "Enabled · ${prefix.ifBlank { "lyre_mcp_…" }}"
                        else -> "Disabled · ${prefix.ifBlank { "lyre_mcp_…" }}"
                    },
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
                notice?.let {
                    Text(it, color = GrokifyColors.GlowAmber, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val cached = status
                            if (cached != null && lyreCopyLinkDecision(cached).kind == LyreCopyLinkKind.COPY) {
                                copyLink(cached)
                                return@TextButton
                            }
                            busy = true
                            scope.launch {
                                val json = withContext(Dispatchers.IO) { api.mcpEnsure() }
                                busy = false
                                copyLink(json)
                            }
                        },
                    ) { Text("Copy link", color = GrokifyColors.GlowCyan, fontSize = 12.sp) }
                    TextButton(
                        enabled = !busy,
                        onClick = { confirmRotate = RotateWhy.Rotate },
                    ) { Text("Rotate", color = GrokifyColors.GlowAmber, fontSize = 12.sp) }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val turningOn = !enabled
                            busy = true
                            scope.launch {
                                val json = withContext(Dispatchers.IO) {
                                    if (turningOn) api.mcpEnable() else api.mcpDisable()
                                }
                                busy = false
                                applyStatus(json)
                                if (turningOn) {
                                    val minted = lyreCopyLinkDecision(json)
                                    if (minted.kind == LyreCopyLinkKind.COPY) {
                                        clipboard.setText(AnnotatedString(minted.link))
                                        notice = "Copied connector URL"
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            if (enabled) "Disable" else "Enable",
                            color = GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = GrokifyColors.TextMuted)
            }
        },
    )

    if (confirmRotate != null) {
        AlertDialog(
            onDismissRequest = { confirmRotate = null },
            containerColor = GrokifyColors.Panel,
            title = { Text("Rotate connector?", color = GrokifyColors.TextPrimary) },
            text = {
                Text(
                    "Rotating invalidates bots using the old URL.",
                    color = GrokifyColors.TextMuted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val why = confirmRotate
                        confirmRotate = null
                        busy = true
                        scope.launch {
                            val json = withContext(Dispatchers.IO) { api.mcpRotate() }
                            busy = false
                            if (why != null) copyLink(json) else applyStatus(json)
                        }
                    },
                ) { Text("Rotate", color = GrokifyColors.GlowAmber) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = null }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
        )
    }
}

private enum class RotateWhy { Copy, Rotate }
