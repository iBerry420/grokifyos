package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.grokify.os.apps.lyre.LyreStorageKeys
import io.grokify.os.ui.theme.GrokifyColors
import java.io.File

@Composable
fun LyreStillImage(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: String? = null,
) {
    val usable = file != null && file.isFile && file.length() > 0L && LyreStorageKeys.isStillFile(file)
    if (usable) {
        val ctx = LocalContext.current
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(file)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            loading = { ThumbFallback(fallback ?: contentDescription, Modifier.fillMaxSize()) },
            error = { ThumbFallback(fallback ?: contentDescription, Modifier.fillMaxSize()) },
        )
    } else {
        ThumbFallback(fallback ?: contentDescription, modifier)
    }
}

@Composable
private fun ThumbFallback(label: String?, modifier: Modifier) {
    Box(
        modifier.background(GrokifyColors.PanelSoft),
        contentAlignment = Alignment.Center,
    ) {
        val text = label?.trim()?.ifEmpty { null }
        if (!text.isNullOrEmpty()) {
            Text(
                text,
                color = GrokifyColors.TextDim,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(3.dp).fillMaxSize(),
            )
        }
    }
}
