package io.grokify.os.apps.lyre

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.lyre.ui.LyreEditor
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginIconKey
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LyrePane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { LyreStore(appCtx) }
    val session = remember {
        (appCtx as? GrokifyApp)?.lyreSession ?: LyreSession(appCtx as Application)
    }
    val board by session.board.collectAsState()
    val boardId by session.boundBoardId.collectAsState()
    val busy by session.busy.collectAsState()
    val project by session.project.collectAsState()
    val watchBusy by session.watchBusy.collectAsState()
    val watchError by session.watchError.collectAsState()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.flushSave()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            session.flushSave()
        }
    }

    fun goBack() {
        session.flushSave()
        onBack()
    }

    BackHandler(onBack = { goBack() })
    LaunchedEffect(Unit) {
        onRequestPermissions()
        val boundId = session.boundBoardId.value
        if (boundId != null) {
            val localBoard = session.board.value
                ?: withContext(Dispatchers.IO) {
                    session.cache.readBoardJson(boundId)?.let { LyreBoardCodec.decode(it) }
                }
            if (localBoard != null) {
                if (session.board.value == null) {
                    session.bind(boundId, localBoard)
                }
                if (session.project.value == null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val json = session.api.projects()
                            lyreProjectsFromJson(json).firstOrNull { it.boardId == boundId }
                        }.getOrNull()?.let { session.bindProject(it) }
                    }
                }
                loading = false
                return@LaunchedEffect
            }
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val projectsJson = session.api.projects()
                if (!projectsJson.optBoolean("ok", false)) {
                    return@runCatching LoadResult(
                        error = projectsJson.optString("error").ifBlank { "request_failed" },
                    )
                }
                val projects = lyreProjectsFromJson(projectsJson)
                val odysseus = projects.firstOrNull { it.isOdysseus || it.boardId == "lyre" }
                    ?: return@runCatching LoadResult(error = "not_found")
                store.projectId = odysseus.id
                val local = session.cache.readBoardJson(odysseus.boardId)
                if (session.cache.hasPendingSave(odysseus.boardId) && local != null) {
                    return@runCatching LoadResult(
                        board = LyreBoardCodec.decode(local),
                        boardId = odysseus.boardId,
                        project = odysseus,
                    )
                }
                val boardJson = session.api.board(odysseus.boardId)
                if (!boardJson.optBoolean("ok", false)) {
                    return@runCatching LoadResult(
                        error = boardJson.optString("error").ifBlank { "request_failed" },
                    )
                }
                val data = boardJson.optJSONObject("data") ?: JSONObject()
                session.cache.writeBoardJson(odysseus.boardId, data)
                val decoded = LyreBoardCodec.decode(data)
                LoadResult(board = decoded, boardId = odysseus.boardId, project = odysseus)
            }.getOrElse { LoadResult(error = it.message ?: "request_failed") }
        }
        if (result.board != null && result.boardId != null) {
            val already = session.boundBoardId.value
            if (already == result.boardId && session.cache.hasPendingSave(result.boardId)) {
                val local = session.cache.readBoardJson(result.boardId)
                if (local != null && session.board.value == null) {
                    session.bind(result.boardId, LyreBoardCodec.decode(local))
                }
            } else if (already != result.boardId || session.board.value == null) {
                session.bind(result.boardId, result.board)
            }
            result.project?.let { session.bindProject(it) }
        }
        error = result.error
        loading = false
    }

    val loaded = board
    val loadedId = boardId
    when {
        loading -> {
            Column(Modifier.fillMaxSize()) {
                LyreLoadBar { goBack() }
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .size(22.dp),
                    color = GrokifyColors.GlowRose,
                    strokeWidth = 2.dp,
                )
            }
        }
        error != null -> {
            Column(Modifier.fillMaxSize()) {
                LyreLoadBar { goBack() }
                Text(
                    error ?: "",
                    color = GrokifyColors.GlowRose,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        loaded != null && loadedId != null -> {
            LyreEditor(
                board = loaded,
                boardId = loadedId,
                cache = session.cache,
                store = store,
                onBack = { goBack() },
                onApply = { session.apply(it) },
                busy = busy != null,
                project = project,
                watchBusy = watchBusy,
                watchError = watchError,
                watchApi = session.api,
                onPublish = { session.publish(it) },
                onInsertAudioUri = { frameId, uri, name -> session.insertAudioUri(frameId, uri, name) },
            )
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
                LyreLoadBar { goBack() }
            }
        }
    }
}

@Composable
private fun LyreLoadBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GrokifyColors.TextPrimary,
            )
        }
        PluginFaviconImage(
            pluginId = BuiltinPluginCatalog.LYRE,
            fallback = PluginIconKey.Lyre,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "LYRE",
            color = GrokifyColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        )
    }
}

private data class LoadResult(
    val board: BoardData? = null,
    val boardId: String? = null,
    val project: LyreProject? = null,
    val error: String? = null,
)
