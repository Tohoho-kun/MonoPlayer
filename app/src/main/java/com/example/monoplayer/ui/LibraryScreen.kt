package com.example.monoplayer.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.*

@Composable
fun LibraryScreen(
    viewModel: MonoPlayerViewModel,
    onRequestAddFolder: () -> Unit,
    onOpenPlaylistAssignDialog: (TrackItem) -> Unit,
    onOpenPlayerTab: () -> Unit
) {
    val allTracks by viewModel.allTracks.collectAsState()
    val albumGroups by viewModel.albumGroups.collectAsState()
    val artistGroups by viewModel.artistGroups.collectAsState()
    val folderGroups by viewModel.folderGroups.collectAsState()
    val safFolders by viewModel.safFolderUris.collectAsState()
    val excludedFolders by viewModel.excludedFolders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()

    val selectedSubTab by viewModel.librarySubTab.collectAsState()
    val lastSelectedTrackId by viewModel.lastSelectedTrackId.collectAsState()
    val lastExpandedAlbumName by viewModel.lastExpandedAlbumName.collectAsState()
    val lastExpandedArtistKey by viewModel.lastExpandedArtistKey.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var editingArtistAliasFor by remember { mutableStateOf<ArtistGroup?>(null) }

    // Retained scroll states for seamless back-navigation
    val songsListState = rememberLazyListState()
    val albumsListState = rememberLazyListState()
    val artistsListState = rememberLazyListState()

    var lastScrolledSongId by remember { mutableStateOf<String?>(null) }
    var lastScrolledAlbum by remember { mutableStateOf<String?>(null) }
    var lastScrolledArtist by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Sub-Tab Selector
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab.ordinal,
            containerColor = AcousticPebbleColors.SurfaceContainerLow,
            contentColor = AcousticPebbleColors.PrimaryContainer,
            edgePadding = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        ) {
            LibrarySubTab.entries.forEach { tab ->
                Tab(
                    selected = selectedSubTab == tab,
                    onClick = { viewModel.setLibrarySubTab(tab) },
                    text = {
                        Text(
                            text = tab.title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedSubTab == tab) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Search & Rescan Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("曲名・アーティスト・アルバム検索", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AcousticPebbleColors.PrimaryContainer,
                    unfocusedBorderColor = AcousticPebbleColors.OutlineVariant,
                    focusedTextColor = AcousticPebbleColors.OnSurface,
                    unfocusedTextColor = AcousticPebbleColors.OnSurface
                )
            )

            FilledTonalIconButton(
                onClick = { viewModel.rescanLibrary() },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = AcousticPebbleColors.SurfaceContainerHigh,
                    contentColor = AcousticPebbleColors.PrimaryContainer
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "再スキャン")
            }
        }

        if (isScanning) {
            LinearProgressIndicator(
                color = AcousticPebbleColors.PrimaryContainer,
                modifier = Modifier.fillMaxWidth()
            )
        }

        when (selectedSubTab) {
            LibrarySubTab.SONGS -> {
                val filtered = remember(allTracks, searchQuery) {
                    if (searchQuery.isBlank()) allTracks
                    else allTracks.filter {
                        it.title.contains(searchQuery, true) ||
                                it.canonicalArtistName.contains(searchQuery, true) ||
                                it.rawArtist.contains(searchQuery, true) ||
                                it.album.contains(searchQuery, true)
                    }
                }

                // Restore scroll position to the last selected track without blocking touch with animation
                LaunchedEffect(selectedSubTab, lastSelectedTrackId) {
                    val targetId = lastSelectedTrackId
                    if (selectedSubTab == LibrarySubTab.SONGS && targetId != null && targetId != lastScrolledSongId) {
                        val idx = filtered.indexOfFirst { it.id == targetId }
                        if (idx >= 0) {
                            lastScrolledSongId = targetId
                            val visible = songsListState.layoutInfo.visibleItemsInfo.any { it.index == idx }
                            if (!visible) {
                                songsListState.scrollToItem(idx)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = songsListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }, contentType = { "track_row" }) { track ->
                        val onPlay = remember(track.id, filtered) {
                            {
                                viewModel.playScope(
                                    scope = PlaybackScopeType.ALL_LIBRARY,
                                    scopeTitle = "全曲ライブラリ (${filtered.size}曲)",
                                    scopeTracks = filtered,
                                    startTrack = track
                                )
                                onOpenPlayerTab()
                            }
                        }
                        val onAddToPlaylist = remember(track.id) {
                            { onOpenPlaylistAssignDialog(track) }
                        }
                        val onEnqueueNext = remember(track.id) {
                            { viewModel.enqueueTrackNext(track) }
                        }
                        TrackRowCard(
                            track = track,
                            isCurrent = currentTrack?.id == track.id,
                            onPlay = onPlay,
                            onAddToPlaylist = onAddToPlaylist,
                            onEnqueueNext = onEnqueueNext
                        )
                    }
                }
            }

            LibrarySubTab.ALBUMS -> {
                val filteredAlbums = remember(albumGroups, searchQuery) {
                    if (searchQuery.isBlank()) albumGroups
                    else albumGroups.filter {
                        it.albumName.contains(searchQuery, true) ||
                                it.artistName.contains(searchQuery, true)
                    }
                }

                // Restore scroll position to the last selected/expanded album
                LaunchedEffect(selectedSubTab, lastExpandedAlbumName) {
                    val targetAlbum = lastExpandedAlbumName
                    if (selectedSubTab == LibrarySubTab.ALBUMS && targetAlbum != null && targetAlbum != lastScrolledAlbum) {
                        val idx = filteredAlbums.indexOfFirst { it.albumName == targetAlbum }
                        if (idx >= 0) {
                            lastScrolledAlbum = targetAlbum
                            val visible = albumsListState.layoutInfo.visibleItemsInfo.any { it.index == idx }
                            if (!visible) {
                                albumsListState.scrollToItem(idx)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = albumsListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredAlbums, key = { it.albumName }, contentType = { "album_group" }) { album ->
                        val isExpanded = album.albumName == lastExpandedAlbumName
                        AlbumGroupCard(
                            album = album,
                            isExpanded = isExpanded,
                            currentTrackId = currentTrack?.id,
                            onToggleExpand = {
                                viewModel.setExpandedAlbum(if (isExpanded) null else album.albumName)
                            },
                            onPlayAlbum = { startTrack ->
                                viewModel.playScope(
                                    scope = PlaybackScopeType.ALBUM,
                                    scopeTitle = "Album: ${album.albumName}",
                                    scopeTracks = album.tracks,
                                    startTrack = startTrack
                                )
                                onOpenPlayerTab()
                            },
                            onShuffleAlbum = {
                                viewModel.playScopeShuffled(
                                    scope = PlaybackScopeType.ALBUM,
                                    scopeTitle = "Album: ${album.albumName}",
                                    scopeTracks = album.tracks
                                )
                                onOpenPlayerTab()
                            },
                            onAddToPlaylist = onOpenPlaylistAssignDialog
                        )
                    }
                }
            }

            LibrarySubTab.ARTISTS -> {
                val filteredArtists = remember(artistGroups, searchQuery) {
                    if (searchQuery.isBlank()) artistGroups
                    else artistGroups.filter {
                        it.canonicalName.contains(searchQuery, true) ||
                                it.rawVariations.any { raw -> raw.contains(searchQuery, true) }
                    }
                }

                // Restore scroll position to the last selected/expanded artist
                LaunchedEffect(selectedSubTab, lastExpandedArtistKey) {
                    val targetArtist = lastExpandedArtistKey
                    if (selectedSubTab == LibrarySubTab.ARTISTS && targetArtist != null && targetArtist != lastScrolledArtist) {
                        val idx = filteredArtists.indexOfFirst {
                            it.normalizedKey == targetArtist || it.canonicalName == targetArtist
                        }
                        if (idx >= 0) {
                            lastScrolledArtist = targetArtist
                            val listIdx = idx + 1
                            val visible = artistsListState.layoutInfo.visibleItemsInfo.any { it.index == listIdx }
                            if (!visible) {
                                artistsListState.scrollToItem(listIdx)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = artistsListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(contentType = "artist_banner") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AcousticPebbleColors.SurfaceContainerLow)
                                .border(1.dp, AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "アーティスト表記揺れ自動吸収エンジン稼働中：全角/半角・大文字/小文字・ハイフン/波線・「, The」の語順・feat.表記などを自動的に正規化して同一アーティストへ統合しています。手動での統合も可能です。",
                                color = AcousticPebbleColors.OnSurface,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    items(filteredArtists, key = { it.normalizedKey }, contentType = { "artist_group" }) { artist ->
                        val isExpanded = artist.normalizedKey == lastExpandedArtistKey ||
                                artist.canonicalName == lastExpandedArtistKey
                        ArtistGroupCard(
                            artist = artist,
                            isExpanded = isExpanded,
                            currentTrackId = currentTrack?.id,
                            onToggleExpand = {
                                viewModel.setExpandedArtist(if (isExpanded) null else artist.normalizedKey)
                            },
                            onPlayArtist = { startTrack ->
                                viewModel.playScope(
                                    scope = PlaybackScopeType.ARTIST,
                                    scopeTitle = "Artist: ${artist.canonicalName}",
                                    scopeTracks = artist.tracks,
                                    startTrack = startTrack
                                )
                                onOpenPlayerTab()
                            },
                            onShuffleArtist = {
                                viewModel.playScopeShuffled(
                                    scope = PlaybackScopeType.ARTIST,
                                    scopeTitle = "Artist: ${artist.canonicalName}",
                                    scopeTracks = artist.tracks
                                )
                                onOpenPlayerTab()
                            },
                            onEditAlias = { editingArtistAliasFor = artist },
                            onAddToPlaylist = onOpenPlaylistAssignDialog
                        )
                    }
                }
            }

            LibrarySubTab.PLAYLISTS -> {
                PlaylistsSection(
                    viewModel = viewModel,
                    onOpenPlaylistAssignDialog = onOpenPlaylistAssignDialog,
                    onOpenPlayerTab = onOpenPlayerTab
                )
            }

            LibrarySubTab.FOLDERS -> {
                val groupedByFolder = folderGroups

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Button(
                            onClick = onRequestAddFolder,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AcousticPebbleColors.PrimaryContainer,
                                contentColor = AcousticPebbleColors.OnPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("音楽フォルダを選択して探索・追加 (SAF)")
                        }
                    }

                    if (safFolders.isNotEmpty()) {
                        item {
                            Text(
                                text = "登録済み探索フォルダ (${safFolders.size}件)",
                                color = AcousticPebbleColors.OnSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(safFolders) { uriStr ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AcousticPebbleColors.SurfaceContainerLow)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uriStr,
                                    color = AcousticPebbleColors.OnSurface,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.removeSafFolderUri(uriStr) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "削除", tint = AcousticPebbleColors.Error)
                                }
                            }
                        }
                    }

                    groupedByFolder.filterKeys { it !in excludedFolders }.forEach { (folderPath, tracksInFolder) ->
                        item(key = "folder_$folderPath") {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.excludeFolder(folderPath)
                                        true
                                    } else false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(AcousticPebbleColors.ErrorContainer)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "フォルダを除外",
                                            tint = AcousticPebbleColors.OnErrorContainer
                                        )
                                    }
                                }
                            ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AcousticPebbleColors.SurfaceContainerLow)
                                    .border(1.dp, AcousticPebbleColors.SurfaceContainerHigh, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folderPath.substringAfterLast('/'),
                                            color = AcousticPebbleColors.OnSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "$folderPath (${tracksInFolder.size}曲)",
                                            color = AcousticPebbleColors.OnSurfaceVariant,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.playScopeShuffled(
                                                    scope = PlaybackScopeType.FOLDER,
                                                    scopeTitle = "Folder: ${folderPath.substringAfterLast('/')}",
                                                    scopeTracks = tracksInFolder
                                                )
                                                onOpenPlayerTab()
                                            },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = AcousticPebbleColors.SurfaceContainerHigh,
                                                contentColor = AcousticPebbleColors.PrimaryContainer
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Shuffle, contentDescription = "フォルダシャッフル", modifier = Modifier.size(16.dp))
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.playScope(
                                                    scope = PlaybackScopeType.FOLDER,
                                                    scopeTitle = "Folder: ${folderPath.substringAfterLast('/')}",
                                                    scopeTracks = tracksInFolder
                                                )
                                                onOpenPlayerTab()
                                            },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = AcousticPebbleColors.PrimaryContainer,
                                                contentColor = AcousticPebbleColors.OnPrimary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "フォルダ再生", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }

    // Artist Alias Custom Override Dialog
    editingArtistAliasFor?.let { artistGroup ->
        var targetCanonical by remember(artistGroup) { mutableStateOf(artistGroup.canonicalName) }
        AlertDialog(
            onDismissRequest = { editingArtistAliasFor = null },
            title = { Text("アーティスト表記揺れの統合・編集", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "検出された元タグ表記:\n${artistGroup.rawVariations.joinToString(" / ")}",
                        fontSize = 12.sp,
                        color = AcousticPebbleColors.OnSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetCanonical,
                        onValueChange = { targetCanonical = it },
                        label = { Text("統一後の表示アーティスト名") },
                        singleLine = true
                    )
                    Text(
                        text = "※別のアーティスト名と同じ名前を入力すると、1つのアーティストとして統合再生できるようになります。",
                        fontSize = 11.sp,
                        color = AcousticPebbleColors.OnSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        artistGroup.rawVariations.forEach { raw ->
                            viewModel.setCustomArtistAlias(raw, targetCanonical)
                        }
                        editingArtistAliasFor = null
                    }
                ) {
                    Text("保存して統合")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingArtistAliasFor = null }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
fun TrackRowCard(
    track: TrackItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEnqueueNext: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) AcousticPebbleColors.SurfaceContainerHigh else AcousticPebbleColors.SurfaceContainerLow)
            .border(
                1.dp,
                if (isCurrent) AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.SurfaceContainerHigh,
                RoundedCornerShape(10.dp)
            )
            .clickable { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = track.title,
                color = if (isCurrent) AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.OnSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val artistSubtitle = if (track.rawArtist != track.canonicalArtistName) {
                "${track.canonicalArtistName} [元:${track.rawArtist}] • ${track.album}"
            } else {
                "${track.canonicalArtistName} • ${track.album}"
            }
            Text(
                text = artistSubtitle,
                color = AcousticPebbleColors.OnSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDurationMs(track.durationMs),
                color = AcousticPebbleColors.OnSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = PlusJakartaSansFamily,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
            onEnqueueNext?.let { enqueue ->
                IconButton(onClick = enqueue, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Default.QueuePlayNext,
                        contentDescription = "次に再生（キュー追加）",
                        tint = AcousticPebbleColors.PrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.PlaylistAdd,
                    contentDescription = "プレイリストに追加",
                    tint = AcousticPebbleColors.PrimaryContainer
                )
            }
            trailingAction?.invoke()
        }
    }
}

@Composable
private fun AlbumGroupCard(
    album: AlbumGroup,
    isExpanded: Boolean,
    currentTrackId: String?,
    onToggleExpand: () -> Unit,
    onPlayAlbum: (TrackItem?) -> Unit,
    onShuffleAlbum: () -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(
                1.dp,
                if (isExpanded) AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.5f) else AcousticPebbleColors.SurfaceContainerHigh,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.albumName,
                    color = AcousticPebbleColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${album.artistName} • ${album.tracks.size}曲",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = onShuffleAlbum,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AcousticPebbleColors.SurfaceContainerHigh,
                        contentColor = AcousticPebbleColors.PrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = "アルバムシャッフル", modifier = Modifier.size(15.dp))
                }
                FilledTonalButton(
                    onClick = { onPlayAlbum(null) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AcousticPebbleColors.PrimaryContainer,
                        contentColor = AcousticPebbleColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "アルバム再生", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (isExpanded) {
            HorizontalDivider(color = AcousticPebbleColors.SurfaceContainerHigh)
            album.tracks.forEach { t ->
                TrackRowCard(
                    track = t,
                    isCurrent = currentTrackId == t.id,
                    onPlay = { onPlayAlbum(t) },
                    onAddToPlaylist = { onAddToPlaylist(t) }
                )
            }
        }
    }
}

@Composable
private fun ArtistGroupCard(
    artist: ArtistGroup,
    isExpanded: Boolean,
    currentTrackId: String?,
    onToggleExpand: () -> Unit,
    onPlayArtist: (TrackItem?) -> Unit,
    onShuffleArtist: () -> Unit,
    onEditAlias: () -> Unit,
    onAddToPlaylist: (TrackItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AcousticPebbleColors.SurfaceContainerLow)
            .border(
                1.dp,
                if (isExpanded) AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.5f) else AcousticPebbleColors.SurfaceContainerHigh,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = artist.canonicalName,
                        color = AcousticPebbleColors.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (artist.rawVariations.size > 1) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "表記揺れ${artist.rawVariations.size}種を統合済",
                                color = AcousticPebbleColors.PrimaryContainer,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "吸収した表記: ${artist.rawVariations.joinToString(" / ")}",
                    color = AcousticPebbleColors.PrimaryContainer,
                    fontSize = 11.sp
                )
                Text(
                    text = "${artist.albumsCount} アルバム • 全 ${artist.tracks.size} 曲",
                    color = AcousticPebbleColors.OnSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEditAlias, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "名寄せ編集", tint = AcousticPebbleColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                FilledTonalButton(
                    onClick = onShuffleArtist,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AcousticPebbleColors.SurfaceContainerHigh,
                        contentColor = AcousticPebbleColors.PrimaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = "アーティスト全曲シャッフル", modifier = Modifier.size(15.dp))
                }
                FilledTonalButton(
                    onClick = { onPlayArtist(null) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = AcousticPebbleColors.PrimaryContainer,
                        contentColor = AcousticPebbleColors.OnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "アーティスト再生", modifier = Modifier.size(16.dp))
                }
            }
        }

        if (isExpanded) {
            HorizontalDivider(color = AcousticPebbleColors.SurfaceContainerHigh)
            artist.tracks.forEach { t ->
                TrackRowCard(
                    track = t,
                    isCurrent = currentTrackId == t.id,
                    onPlay = { onPlayArtist(t) },
                    onAddToPlaylist = { onAddToPlaylist(t) }
                )
            }
        }
    }
}
