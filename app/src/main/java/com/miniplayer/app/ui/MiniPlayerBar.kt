package com.miniplayer.app.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.player.PlaybackSnapshot
import com.miniplayer.app.ui.components.MediaArtwork
import com.miniplayer.app.ui.theme.tnum

@Composable
fun MiniPlayerBar(
    snapshot: PlaybackSnapshot,
    practiceModeActive: Boolean,
    practiceState: PracticeState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = snapshot.currentItem ?: return
    val progress = if (snapshot.durationMs > 0L) {
        (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        onClick = onExpand,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -14f) {
                        onExpand()
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaArtwork(
                    coverPathOrUri = item.coverPathOrUri,
                    mediaDocumentUri = item.documentUri,
                    isVideo = item.isVideo,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(46.dp),
                    iconSize = 22.dp,
                    contentDescription = item.title,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.title} · ${item.artist}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (practiceModeActive) {
                        val loopActive = practiceState.loopEnabled &&
                            practiceState.activeAMs != null &&
                            practiceState.activeBMs != null
                        val statusText = buildString {
                            append("🎯 ${String.format("%.2fx", practiceState.practiceSpeed)}")
                            if (practiceState.pitchSemitones != 0) {
                                val sign = if (practiceState.pitchSemitones > 0) "+" else ""
                                append(" · $sign${practiceState.pitchSemitones}st")
                            }
                            if (loopActive) {
                                append(" · A-B 循环中")
                            } else {
                                append(" · 练习模式")
                            }
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium.tnum(),
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = item.displayAlbum,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (snapshot.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (snapshot.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = "下一首",
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (practiceModeActive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
