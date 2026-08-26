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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import io.grokify.os.apps.plugin.PluginFaviconImage
import io.grokify.os.apps.plugin.PluginIconKey
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyrePane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        onRequestPermissions()
    }

    Column(Modifier.fillMaxSize()) {
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
        Text(
            "Native storyboard editor — rails, local cut, Imagine.",
            color = GrokifyColors.TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
