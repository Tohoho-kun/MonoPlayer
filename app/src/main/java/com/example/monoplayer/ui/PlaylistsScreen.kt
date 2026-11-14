package com.example.monoplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.*

@Composable
fun PlaylistsSection(
    viewModel: MonoPlayerViewModel,
    onOpenPlaylistAssignDialog: (TrackItem) -> Unit,
    onOpenPlayerTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playlists by viewModel.playlists.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AcousticPebbleColors.SurfaceContainerLow)
                    .border(1.dp, AcousticPebbleColors.OutlineVariant.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "マイ・プレイリスト (${playlists.size}件)",
                        color = AcousticPebbleColors.OnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1曲を複数のプレイリストに重複登録可能",
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = { showCreatePlaylistDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AcousticPebbleColors.PrimaryContainer,
                        contentColor = AcousticPebbleColors.OnPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新規作成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        items(playlists, key = { it.id }, contentType = { "playlist_card" }) { playlist ->
            val playlistTracks = remember(playlist, allTracks) {
                val trackMap = allTracks.associateBy { it.id }
                playlist.trackIds.mapNotNull { trackMap[it] }
            }

            PlaylistCard(
                playlist = playlist,
                tracks = playlistTracks,
                onPlayPlaylist = { startTrack ->
                    viewModel.playScope(
                        scope = PlaybackScopeType.PLAYLIST,
                        scopeTitle = "Playlist: ${playlist.name}",
                        scopeTracks = playlistTracks,
                        startTrack = startTrack
                    )
                    onOpenPlayerTab()
                },
                onShufflePlaylist = {
                    if (playlistTracks.isNotEmpty()) {
                        viewModel.playScopeShuffled(
                            scope = PlaybackScopeType.PLAYLIST,
                            scopeTitle = "Playlist: ${playlist.name}",
                            scopeTracks = playlistTracks
                        )
                        onOpenPlayerTab()
                    }
                },
                onEnqueueNext = { track ->
                    viewModel.enqueueTrackNext(track)
                },
                onRemoveTrack = { trackId ->
                    viewModel.removeTrackFromPlaylist(playlist.id, trackId)
                },
                onDeletePlaylist = {
                    viewModel.deletePlaylist(playlist.id)
                },
                onOpenMultiPlaylistAssign = onOpenPlaylistAssignDialog
            )
        }
    }

    if (showCreatePlaylistDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        val selectedTrackIds = remember { mutableStateListOf<String>() }

        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            containerColor = AcousticPebbleColors.SurfaceContainerHigh,
            titleContentColor = AcousticPebbleColors.OnSurface,
            textContentColor = AcousticPebbleColors.OnSurface,
            title = { Text("新規プレイリスト作成", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("プレイリスト名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("メモ・説明 (任意)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "初期追加する曲を選択 (${selectedTrackIds.size}曲選択中):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        allTracks.forEach { t ->
                            val checked = t.id in selectedTrackIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selectedTrackIds.remove(t.id)
                                        else selectedTrackIds.add(t.id)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (it) selectedTrackIds.add(t.id)
                                        else selectedTrackIds.remove(t.id)
                                    }
                                )
                                Column {
                                    Text(t.title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    Text(t.canonicalArtistName, fontSize = 10.sp, color = AcousticPebbleColors.OnSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createPlaylist(name, desc, selectedTrackIds.toList())
                        showCreatePlaylistDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AcousticPebbleColors.PrimaryContainer,
                        contentColor = AcousticPebbleColors.OnPrimary
                    )
                ) {
                    Text("作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("キャンセル", color = AcousticPebbleColors.OnSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    tracks: List<TrackItem>,
    onPlayPlaylist: (TrackItem?) -> Unit,
    onShufflePlaylist: () -> Unit,
    onEnqueueNext: (TrackItem) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onOpenMultiPlaylistAssign: (TrackItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = playlist.name,
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (playlist.description.isNotBlank()) {
                    Text(
                        text = playlist.description,
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "${tracks.size} 曲",
                    color = AcousticPebbleColors.PrimaryContainer,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShufflePlaylist) {
                    Icon(Icons.Default.Shuffle, contentDescription = "シャッフル", tint = AcousticPebbleColors.PrimaryContainer)
                }
                FilledTonalButton(
                    onClick = { onPlayPlaylist(null) },
                    enabled = tracks.isNotEmpty(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AcousticPebbleColors.PrimaryContainer,
                        contentColor = AcousticPebbleColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "再生", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDeletePlaylist) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "削除", tint = AcousticPebbleColors.OnSurfaceVariant)
                }
            }
        }

        if (expanded) {
            HorizontalDivider(color = AcousticPebbleColors.SurfaceContainerHigh)
            if (tracks.isEmpty()) {
                Text(
                    text = "曲がありません。ライブラリまたはキューの「＋」ボタンから追加してください。",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                tracks.forEach { t ->
                    TrackRowCard(
                        track = t,
                        isCurrent = false,
                        onPlay = { onPlayPlaylist(t) },
                        onAddToPlaylist = { onOpenMultiPlaylistAssign(t) },
                        trailingAction = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onEnqueueNext(t) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueuePlayNext,
                                        contentDescription = "次に再生",
                                        tint = AcousticPebbleColors.PrimaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveTrack(t.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "このプレイリストから外す",
                                        tint = AcousticPebbleColors.Error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
