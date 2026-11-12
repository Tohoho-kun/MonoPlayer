package com.example.monoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.RepeatScopeMode
import com.example.monoplayer.data.TrackItem
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    viewModel: MonoPlayerViewModel,
    onOpenPlaylistAssignDialog: (TrackItem) -> Unit,
    onOpenPlayerTab: () -> Unit
) {
    val activeQueue by viewModel.activeQueue.collectAsState()
    val activeQueueIndex by viewModel.activeQueueIndex.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val scopeType by viewModel.scopeType.collectAsState()
    val scopeTitle by viewModel.scopeTitle.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showSaveDialog by remember { mutableStateOf(false) }

    val totalDurationMs = remember(activeQueue) {
        activeQueue.sumOf { it.durationMs.coerceAtLeast(0L) }
    }

    LaunchedEffect(Unit) {
        if (activeQueueIndex in activeQueue.indices) {
            listState.scrollToItem((activeQueueIndex - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Queue Scope & Control Header Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF141028), Color(0xFF090D1C))
                    )
                )
                .border(1.dp, Color(0xFFC8A24A).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = scopeType.label,
                        color = Color(0xFFC8A24A),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = scopeTitle,
                        color = Color(0xFFF4F1EA),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val posText = if (activeQueue.isEmpty()) "0 / 0" else "${activeQueueIndex + 1} / ${activeQueue.size} 曲目"
                    Text(
                        text = "$posText • 全${activeQueue.size}曲 • 合計 ${formatDurationMs(totalDurationMs)}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }

                FilledTonalButton(
                    onClick = onOpenPlayerTab,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF1D1836),
                        contentColor = Color(0xFFC8A24A)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Album, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("再生画面", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = shuffleEnabled,
                    onClick = { viewModel.toggleShuffle() },
                    label = { Text("シャッフル", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF281B4A),
                        selectedLabelColor = Color(0xFFC8A24A),
                        selectedLeadingIconColor = Color(0xFFC8A24A)
                    )
                )

                FilterChip(
                    selected = repeatMode != RepeatScopeMode.OFF,
                    onClick = { viewModel.cycleRepeatMode() },
                    label = { Text(repeatMode.label, fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (repeatMode == RepeatScopeMode.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF281B4A),
                        selectedLabelColor = Color(0xFFC8A24A),
                        selectedLeadingIconColor = Color(0xFFC8A24A)
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = {
                        if (activeQueueIndex in activeQueue.indices) {
                            coroutineScope.launch {
                                listState.animateScrollToItem((activeQueueIndex - 1).coerceAtLeast(0))
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "再生中の曲へスクロール",
                        tint = Color(0xFFC8A24A),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { showSaveDialog = true },
                    enabled = activeQueue.isNotEmpty(),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "キューをプレイリストとして保存",
                        tint = Color(0xFFC8A24A),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (activeQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "再生キューが空です。ライブラリから曲を選択してください。",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = activeQueue,
                    key = { index, track -> "${track.id}_$index" }
                ) { index, track ->
                    val isCurrent = index == activeQueueIndex
                    val isNextUp = index == activeQueueIndex + 1

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isNextUp) {
                            Text(
                                text = "NEXT UP — 次に再生",
                                color = Color(0xFFC8A24A).copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isCurrent) {
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF22163E), Color(0xFF0E1B3A))
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF0A0D18), Color(0xFF0A0D18))
                                        )
                                    }
                                )
                                .border(
                                    width = if (isCurrent) 1.2.dp else 1.dp,
                                    color = if (isCurrent) Color(0xFFC8A24A) else Color(0xFF1A2238),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.skipToQueueIndex(index) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Queue Number / Playing Indicator
                            Box(
                                modifier = Modifier.width(30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCurrent) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.Pause,
                                        contentDescription = "再生中",
                                        tint = Color(0xFFC8A24A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = "%02d".format(index + 1),
                                        color = if (index < activeQueueIndex) Color(0xFF475569) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = track.title,
                                    color = if (isCurrent) Color(0xFFF6E7C1) else Color(0xFFF4F1EA),
                                    fontSize = 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${track.canonicalArtistName} • ${track.album} (${formatDurationMs(track.durationMs)})",
                                    color = if (isCurrent) Color(0xFFC8A24A).copy(alpha = 0.85f) else Color(0xFF8A94A8),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Reorder / Playlist / Remove Controls
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.moveQueueTrack(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "上へ移動",
                                        tint = if (index > 0) Color(0xFF94A3B8) else Color(0xFF334155),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.moveQueueTrack(index, index + 1) },
                                    enabled = index < activeQueue.lastIndex,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "下へ移動",
                                        tint = if (index < activeQueue.lastIndex) Color(0xFF94A3B8) else Color(0xFF334155),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onOpenPlaylistAssignDialog(track) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = "プレイリスト登録",
                                        tint = Color(0xFFC8A24A),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.removeQueueTrack(index) },
                                    enabled = activeQueue.size > 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "キューから削除",
                                        tint = if (activeQueue.size > 1) Color(0xFFFB7185) else Color(0xFF334155),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        var playlistName by remember { mutableStateOf("Queue: $scopeTitle") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("現在の再生キューを保存", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("新規プレイリスト名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            viewModel.saveActiveQueueAsPlaylist(playlistName.trim())
                            showSaveDialog = false
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
