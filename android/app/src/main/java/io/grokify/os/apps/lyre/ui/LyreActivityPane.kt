package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.theme.GrokifyColors

@Composable
fun LyreActivityPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "Activity log in a later build",
            color = GrokifyColors.TextMuted,
            fontSize = 13.sp,
        )
    }
}
