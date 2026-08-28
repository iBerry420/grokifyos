package io.grokify.os.apps.lyre.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.ui.theme.GrokifyColors

data class LyreMenuAction(
    val id: String,
    val label: String,
    val destructive: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyreActionSheet(
    title: String,
    subtitle: String? = null,
    actions: List<LyreMenuAction>,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = GrokifyColors.VoidElevated,
        contentColor = GrokifyColors.TextPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            actions.forEach { action ->
                Text(
                    action.label,
                    color = if (action.destructive) GrokifyColors.GlowRose else GrokifyColors.TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAction(action.id) }
                        .padding(vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
