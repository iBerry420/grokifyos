package io.grokify.os.apps.lyre

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
import androidx.compose.runtime.LaunchedEffect
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
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.lyre.ui.LyreEditor
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginIconKey
import io.grokify.os.data.TokenStore
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val tokenStore = remember {
        if (appCtx is GrokifyApp) appCtx.tokenStore else TokenStore(appCtx)
    }
    val api = remember {
        LyreApi {
            kotlinx.coroutines.runBlocking {
                tokenStore.tokenFlow.first()
            }
        }
    }
    val cache = remember { LyreCache(appCtx, api) }

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var board by remember { mutableStateOf<BoardData?>(null) }
    var boardId by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        onRequestPermissions()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val projectsJson = api.projects()
                if (!projectsJson.optBoolean("ok", false)) {
                    return@runCatching LoadResult(
                        error = projectsJson.optString("error").ifBlank { "request_failed" },
                    )
                }
                val projects = lyreProjectsFromJson(projectsJson)
                val odysseus = projects.firstOrNull { it.isOdysseus || it.boardId == "lyre" }
                    ?: return@runCatching LoadResult(error = "not_found")
                store.projectId = odysseus.id
                val boardJson = api.board(odysseus.boardId)
                if (!boardJson.optBoolean("ok", false)) {
                    return@runCatching LoadResult(
                        error = boardJson.optString("error").ifBlank { "request_failed" },
                    )
                }
                val data = boardJson.optJSONObject("data") ?: JSONObject()
                cache.writeBoardJson(odysseus.boardId, data)
                val decoded = LyreBoardCodec.decode(data)
                LoadResult(board = decoded, boardId = odysseus.boardId)
            }.getOrElse { LoadResult(error = it.message ?: "request_failed") }
        }
        board = result.board
        boardId = result.boardId
        error = result.error
        loading = false
    }

    val loaded = board
    val loadedId = boardId
    when {
        loading -> {
            Column(Modifier.fillMaxSize()) {
                LyreLoadBar(onBack)
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
                LyreLoadBar(onBack)
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
                cache = cache,
                store = store,
                api = api,
                onBack = onBack,
                onBoardChange = { board = it },
            )
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
                LyreLoadBar(onBack)
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
    val error: String? = null,
)
