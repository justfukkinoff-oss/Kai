package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.data.ObjectiveState
import com.inspiredandroid.kai.data.ObjectiveStatus
import com.inspiredandroid.kai.ui.kaiAdaptiveCardBorder
import com.inspiredandroid.kai.ui.kaiAdaptiveCardColors
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.chat_objective_cancel
import kai.composeapp.generated.resources.chat_objective_hint
import kai.composeapp.generated.resources.chat_objective_pause
import kai.composeapp.generated.resources.chat_objective_remove
import kai.composeapp.generated.resources.chat_objective_resume
import kai.composeapp.generated.resources.chat_objective_set
import kai.composeapp.generated.resources.chat_objective_status_done
import kai.composeapp.generated.resources.chat_objective_status_failed
import kai.composeapp.generated.resources.chat_objective_status_paused
import kai.composeapp.generated.resources.chat_objective_status_running
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ObjectiveBanner(
    objective: ObjectiveState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    objective ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = kaiAdaptiveCardColors(),
        border = kaiAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (objective.status) {
                    ObjectiveStatus.RUNNING, ObjectiveStatus.FAILED -> PulsingDot(objective.statusColor())
                    else -> StaticDot(objective.statusColor())
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = objectiveStatusLabel(objective.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = objective.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (objective.summary.isNotBlank()) {
                Text(
                    text = objective.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Spacer(Modifier.weight(1f))
                if (objective.status == ObjectiveStatus.PAUSED) {
                    TextButton(onClick = onResume) {
                        Text(stringResource(Res.string.chat_objective_resume))
                    }
                } else if (objective.status in ObjectiveStatus.ACTIVE) {
                    TextButton(onClick = onPause) {
                        Text(stringResource(Res.string.chat_objective_pause))
                    }
                }
                TextButton(onClick = onRemove) {
                    Text(stringResource(Res.string.chat_objective_remove))
                }
            }
        }
    }
}

@Composable
internal fun ObjectiveDialog(
    visible: Boolean,
    initialGoal: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var goal by remember { mutableStateOf(initialGoal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.chat_objective_set)) },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.chat_objective_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(goal.trim()) },
                enabled = goal.isNotBlank(),
            ) {
                Text(stringResource(Res.string.chat_objective_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.chat_objective_cancel))
            }
        },
    )
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Dot(color = color, scale = scale)
}

@Composable
private fun StaticDot(color: Color) {
    Dot(color = color)
}

@Composable
private fun Dot(color: Color, scale: Float = 1f) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun objectiveStatusLabel(status: String): String = stringResource(
    when (status) {
        ObjectiveStatus.DONE -> Res.string.chat_objective_status_done
        ObjectiveStatus.FAILED -> Res.string.chat_objective_status_failed
        ObjectiveStatus.PAUSED -> Res.string.chat_objective_status_paused
        else -> Res.string.chat_objective_status_running
    },
)

private fun ObjectiveState.statusColor(): Color = when (status) {
    ObjectiveStatus.DONE -> Color(0xFF4CAF50)
    ObjectiveStatus.FAILED -> Color(0xFFE53935)
    ObjectiveStatus.PAUSED -> Color(0xFFFFB300)
    else -> Color(0xFF00E5FF)
}
