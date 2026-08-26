package io.grokify.os.apps.plugin

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.grokify.os.R

/** 1:1 inner-app marks shipped in the host APK. */
object PluginFavicon {
    @DrawableRes
    fun drawableRes(pluginId: String): Int? = when (pluginId) {
        BuiltinPluginCatalog.WIFI_SCANNER -> R.drawable.plugin_ic_wifi_scanner
        BuiltinPluginCatalog.BT_SCANNER -> R.drawable.plugin_ic_bt_scanner
        BuiltinPluginCatalog.PLACE_NOTES -> R.drawable.plugin_ic_place_notes
        BuiltinPluginCatalog.SPOTIFY_CONTROLLER -> R.drawable.plugin_ic_spotify
        BuiltinPluginCatalog.SPACEXAI_USAGE -> R.drawable.plugin_ic_spacexai_usage
        BuiltinPluginCatalog.GROK_ASSISTANT -> R.drawable.plugin_ic_grok_assistant
        BuiltinPluginCatalog.COMPANION -> R.drawable.plugin_ic_companion
        BuiltinPluginCatalog.WATCH_DEPLOY -> R.drawable.plugin_ic_watch_deploy
        BuiltinPluginCatalog.CEXBOT -> R.drawable.plugin_ic_cexbot
        BuiltinPluginCatalog.GBOT -> R.drawable.plugin_ic_gbot
        BuiltinPluginCatalog.DISCORD -> R.drawable.plugin_ic_discord
        BuiltinPluginCatalog.LYRE -> R.drawable.plugin_ic_lyre
        else -> null
    }

    fun vectorIcon(key: PluginIconKey): ImageVector = when (key) {
        PluginIconKey.Wifi -> Icons.Default.Wifi
        PluginIconKey.Bluetooth -> Icons.Default.Bluetooth
        PluginIconKey.Place -> Icons.Default.Place
        PluginIconKey.Music -> Icons.Default.MusicNote
        PluginIconKey.Apps -> Icons.Default.Apps
        PluginIconKey.Assistant -> Icons.Default.Psychology
        PluginIconKey.Avatar -> Icons.Default.Face
        PluginIconKey.Extension -> Icons.Default.Extension
        PluginIconKey.Chart -> Icons.Default.BarChart
        PluginIconKey.Watch -> Icons.Filled.SystemUpdate
        PluginIconKey.CexBot -> Icons.AutoMirrored.Filled.ShowChart
        PluginIconKey.Bot -> Icons.Default.SmartToy
        PluginIconKey.Forum -> Icons.Default.Forum
        PluginIconKey.Lyre -> Icons.Default.Theaters
    }
}

@Composable
fun PluginFaviconImage(
    pluginId: String,
    fallback: PluginIconKey,
    modifier: Modifier = Modifier,
    fallbackTint: Color = Color.Unspecified,
    vectorSize: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val res = PluginFavicon.drawableRes(pluginId)
    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = contentDescription,
            modifier = modifier.clip(RoundedCornerShape(22)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                PluginFavicon.vectorIcon(fallback),
                contentDescription = contentDescription,
                tint = fallbackTint,
                modifier = Modifier.size(vectorSize),
            )
        }
    }
}
