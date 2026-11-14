package com.example.monoplayer.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.monoplayer.data.*
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.MonoAlgorithm
import com.example.monoplayer.service.MonoPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

class MonoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefsRepo = AppPreferencesRepository(context)

    private var mediaController: MediaController? = null
    private var positionPollJob: Job? = null
    private var isCrossfadingTransition = false

    // Library state
    private val _allTracks = MutableStateFlow<List<TrackItem>>(prefsRepo.loadCachedLibraryTracks())
    val allTracks: StateFlow<List<TrackItem>> = _allTracks.asStateFlow()

    private val _safFolderUris = MutableStateFlow(prefsRepo.loadSafFolders())
    val safFolderUris: StateFlow<List<String>> = _safFolderUris.asStateFlow()

    private val _customArtistAliases = MutableStateFlow(prefsRepo.loadCustomArtistAliases())
    val customArtistAliases: StateFlow<Map<String, String>> = _customArtistAliases.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Derived Album & Normalized Artist groups (computed on Default background dispatcher, kept Eagerly hot)
    val albumGroups: StateFlow<List<AlbumGroup>> = _allTracks.map { tracks ->
        tracks.groupBy { it.album }
            .map { (albumName, list) ->
                val sorted = list.sortedWith(compareBy({ it.trackNumber }, { it.title }))
                AlbumGroup(
                    albumName = albumName,
                    artistName = sorted.first().canonicalArtistName,
                    tracks = sorted,
                    representativeTrack = sorted.first()
                )
            }
            .sortedBy { it.albumName.lowercase() }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val artistGroups: StateFlow<List<ArtistGroup>> = _allTracks.map { tracks ->
        tracks.groupBy { it.normalizedArtistKey }
            .map { (normKey, list) ->
                ArtistGroup(
                    normalizedKey = normKey,
                    canonicalName = list.first().canonicalArtistName,
                    rawVariations = list.map { it.rawArtist }.toSet(),
                    tracks = list.sortedWith(compareBy({ it.album }, { it.trackNumber }, { it.title })),
                    albumsCount = list.map { it.album }.distinct().size
                )
            }
            .sortedBy { it.canonicalName.lowercase() }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folderGroups: StateFlow<Map<String, List<TrackItem>>> = _allTracks.map { tracks ->
        tracks.groupBy { it.folderPath.ifEmpty { "内蔵ライブラリ" } }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Playlists (supports multiple playlists, adding 1 track to multiple playlists)
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    // Active Playback Scope & Queue
    private val _scopeType = MutableStateFlow(PlaybackScopeType.ALL_LIBRARY)
    val scopeType: StateFlow<PlaybackScopeType> = _scopeType.asStateFlow()

    private val _scopeTitle = MutableStateFlow("全曲ライブラリ")
    val scopeTitle: StateFlow<String> = _scopeTitle.asStateFlow()

    private val _activeQueue = MutableStateFlow<List<TrackItem>>(emptyList())
    val activeQueue: StateFlow<List<TrackItem>> = _activeQueue.asStateFlow()

    private val _currentTrack = MutableStateFlow<TrackItem?>(null)
    val currentTrack: StateFlow<TrackItem?> = _currentTrack.asStateFlow()

    private val _currentArtwork = MutableStateFlow<Bitmap?>(null)
    val currentArtwork: StateFlow<Bitmap?> = _currentArtwork.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(1L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatScopeMode.REPEAT_SCOPE)
    val repeatMode: StateFlow<RepeatScopeMode> = _repeatMode.asStateFlow()

    // Crossfade, Visualizer style & Display Customization
    private val _crossfadeSec = MutableStateFlow(prefsRepo.loadCrossfadeSeconds())
    val crossfadeSec: StateFlow<Float> = _crossfadeSec.asStateFlow()

    private val _visualizerStyle = MutableStateFlow(prefsRepo.loadVisualizerStyle())
    val visualizerStyle: StateFlow<VisualizerStyle> = _visualizerStyle.asStateFlow()

    private val _displayConfig = MutableStateFlow(prefsRepo.loadDisplayConfig())
    val displayConfig: StateFlow<PlayerDisplayConfig> = _displayConfig.asStateFlow()

    private val _autoPlayOnBtConnect = MutableStateFlow(prefsRepo.loadAutoPlayOnBluetoothConnect())
    val autoPlayOnBtConnect: StateFlow<Boolean> = _autoPlayOnBtConnect.asStateFlow()

    private val _activeQueueIndex = MutableStateFlow(0)
    val activeQueueIndex: StateFlow<Int> = _activeQueueIndex.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    // Library navigation & drill-down state memory
    private val _librarySubTab = MutableStateFlow(LibrarySubTab.SONGS)
    val librarySubTab: StateFlow<LibrarySubTab> = _librarySubTab.asStateFlow()

    private val _lastSelectedTrackId = MutableStateFlow<String?>(null)
    val lastSelectedTrackId: StateFlow<String?> = _lastSelectedTrackId.asStateFlow()

    private val _lastExpandedAlbumName = MutableStateFlow<String?>(null)
    val lastExpandedAlbumName: StateFlow<String?> = _lastExpandedAlbumName.asStateFlow()

    private val _lastExpandedArtistKey = MutableStateFlow<String?>(null)
    val lastExpandedArtistKey: StateFlow<String?> = _lastExpandedArtistKey.asStateFlow()

    private var lastSavedPosTimestampMs = 0L

    fun setLibrarySubTab(tab: LibrarySubTab) {
        _librarySubTab.value = tab
        persistCurrentPlaybackSession()
    }

    fun setExpandedAlbum(albumName: String?) {
        _lastExpandedAlbumName.value = albumName
        if (albumName != null) {
            _librarySubTab.value = LibrarySubTab.ALBUMS
        }
        persistCurrentPlaybackSession()
    }

    fun setExpandedArtist(artistKey: String?) {
        _lastExpandedArtistKey.value = artistKey
        if (artistKey != null) {
            _librarySubTab.value = LibrarySubTab.ARTISTS
        }
        persistCurrentPlaybackSession()
    }

    init {
        if (!DspStateHolder.isInitializedFromPrefs) {
            DspStateHolder.crossfadeSec = _crossfadeSec.value
            DspStateHolder.updateLoudnessConfig(prefsRepo.loadLoudnessConfig())
            DspStateHolder.updateDynamicEqConfig(prefsRepo.loadDynamicEqConfig())
            DspStateHolder.updateSpatialConfig(prefsRepo.loadSpatialConfig())
            DspStateHolder.setManualSyncOffsetMs(prefsRepo.loadSyncOffsetMs())
            DspStateHolder.isInitializedFromPrefs = true
        }
        viewModelScope.launch {
            DspStateHolder.loudnessConfigFlow.drop(1).collect { prefsRepo.saveLoudnessConfig(it) }
        }
        viewModelScope.launch {
            DspStateHolder.dynamicEqConfigFlow.drop(1).collect { prefsRepo.saveDynamicEqConfig(it) }
        }
        viewModelScope.launch {
            DspStateHolder.spatialConfigFlow.drop(1).collect { prefsRepo.saveSpatialConfig(it) }
        }
        viewModelScope.launch {
            DspStateHolder.syncOffsetMsFlow.drop(1).collect { prefsRepo.saveSyncOffsetMs(it) }
        }
        restorePersistedSessionIfAvailable(_allTracks.value, syncPlayerIfIdle = false)
        connectMediaSession()
        rescanLibrary(autoStartDemoIfIdle = true)
    }

    private fun persistCurrentPlaybackSession() {
        val current = _currentTrack.value ?: return
        val queueIds = _activeQueue.value.map { it.id }
        val session = PersistedPlaybackSession(
            scopeTypeName = _scopeType.value.name,
            scopeTitle = _scopeTitle.value,
            queueTrackIds = queueIds,
            activeQueueIndex = _activeQueueIndex.value,
            currentTrackId = current.id,
            positionMs = _currentPositionMs.value.coerceAtLeast(0L),
            shuffleEnabled = _shuffleEnabled.value,
            repeatModeName = _repeatMode.value.name,
            librarySubTabName = _librarySubTab.value.name,
            lastExpandedAlbumName = _lastExpandedAlbumName.value,
            lastExpandedArtistKey = _lastExpandedArtistKey.value
        )
        prefsRepo.savePlaybackSession(session)
    }

    private fun restorePersistedSessionIfAvailable(
        availableTracks: List<TrackItem>,
        syncPlayerIfIdle: Boolean
    ): Boolean {
        if (availableTracks.isEmpty()) return false
        val saved = prefsRepo.loadPlaybackSession() ?: return false
        val byId = availableTracks.associateBy { it.id }

        val restoredQueue = saved.queueTrackIds.mapNotNull { byId[it] }
            .ifEmpty {
                val userTracks = availableTracks.filter { !it.isDemoTrack }
                userTracks.ifEmpty { availableTracks }
            }
        val restoredTrack = saved.currentTrackId?.let { byId[it] }
            ?: restoredQueue.getOrNull(saved.activeQueueIndex.coerceIn(0, restoredQueue.lastIndex))
            ?: return false

        val restoredIdx = restoredQueue.indexOfFirst { it.id == restoredTrack.id }.coerceAtLeast(0)

        _scopeType.value = PlaybackScopeType.entries.find { it.name == saved.scopeTypeName }
            ?: PlaybackScopeType.ALL_LIBRARY
        _scopeTitle.value = saved.scopeTitle
        _activeQueue.value = restoredQueue
        _activeQueueIndex.value = restoredIdx
        _currentTrack.value = restoredTrack
        _durationMs.value = restoredTrack.durationMs.coerceAtLeast(1000L)
        _currentPositionMs.value = saved.positionMs.coerceIn(0L, restoredTrack.durationMs.coerceAtLeast(0L))
        _shuffleEnabled.value = saved.shuffleEnabled
        _repeatMode.value = RepeatScopeMode.entries.find { it.name == saved.repeatModeName }
            ?: RepeatScopeMode.REPEAT_SCOPE
        _librarySubTab.value = LibrarySubTab.entries.find { it.name == saved.librarySubTabName }
            ?: LibrarySubTab.SONGS
        _lastSelectedTrackId.value = restoredTrack.id
        _lastExpandedAlbumName.value = saved.lastExpandedAlbumName
        _lastExpandedArtistKey.value = saved.lastExpandedArtistKey
        loadTrackArtwork(restoredTrack)

        if (syncPlayerIfIdle) {
            val controller = mediaController
            if (controller != null && controller.mediaItemCount == 0) {
                prepareQueueInPlayer(
                    queue = restoredQueue,
                    startIndex = restoredIdx,
                    playImmediately = false,
                    startPositionMs = _currentPositionMs.value
                )
            }
        }
        return true
    }

    private fun connectMediaSession() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MonoPlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    syncExistingControllerStateOrRestore(controller)
                    setupPlayerListener(controller)
                    startPositionAndCrossfadeLoop()
                } catch (_: Exception) {
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun syncExistingControllerStateOrRestore(controller: MediaController) {
        val activeMediaId = controller.currentMediaItem?.mediaId
        if (controller.mediaItemCount > 0 && activeMediaId != null) {
            // The background service already has an active queue/track (e.g., resumed via Bluetooth or notification)
            val all = _allTracks.value
            val byId = all.associateBy { it.id }
            val serviceQueue = (0 until controller.mediaItemCount).mapNotNull { i ->
                val item = controller.getMediaItemAt(i)
                byId[item.mediaId]
            }
            if (serviceQueue.isNotEmpty()) {
                _activeQueue.value = serviceQueue
                val idx = controller.currentMediaItemIndex.coerceIn(0, serviceQueue.lastIndex)
                _activeQueueIndex.value = idx
                val track = serviceQueue[idx]
                _currentTrack.value = track
                _lastSelectedTrackId.value = track.id
                _durationMs.value = track.durationMs.coerceAtLeast(1000L)
                _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                _isPlaying.value = controller.isPlaying
                loadTrackArtwork(track)
                return
            }
        }
        // Otherwise, push the ViewModel's restored queue into the service if available
        val queue = _activeQueue.value
        if (queue.isNotEmpty()) {
            val idx = _activeQueueIndex.value.coerceIn(0, queue.lastIndex)
            prepareQueueInPlayer(
                queue = queue,
                startIndex = idx,
                playImmediately = false,
                startPositionMs = _currentPositionMs.value
            )
        }
    }

    private fun setupPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying) {
                    prefsRepo.saveLastPlaybackPositionMs(controller.currentPosition.coerceAtLeast(0L))
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId ?: return
                val idx = controller.currentMediaItemIndex.coerceAtLeast(0)
                val queue = _activeQueue.value
                if (idx in queue.indices) {
                    _activeQueueIndex.value = idx
                } else {
                    val found = queue.indexOfFirst { it.id == mediaId }
                    if (found >= 0) _activeQueueIndex.value = found
                }
                val track = queue.getOrNull(_activeQueueIndex.value)?.takeIf { it.id == mediaId }
                    ?: _allTracks.value.find { it.id == mediaId }
                if (track != null) {
                    _currentTrack.value = track
                    _lastSelectedTrackId.value = track.id
                    _durationMs.value = track.durationMs.coerceAtLeast(1000L)
                    _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                    loadTrackArtwork(track)
                    persistCurrentPlaybackSession()
                }
            }
        })
        syncRepeatAndShuffleToController(controller)
    }

    private fun startPositionAndCrossfadeLoop() {
        positionPollJob?.cancel()
        positionPollJob = viewModelScope.launch {
            while (isActive) {
                val controller = mediaController
                if (controller != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = controller.duration.takeIf { it > 0L }
                        ?: _currentTrack.value?.durationMs
                        ?: 8000L
                    // StateFlow de-duplicates equal values; round to 250ms so the UI is not recomposed needlessly.
                    _currentPositionMs.value = (pos / 250L) * 250L
                    _durationMs.value = dur.coerceAtLeast(1000L)
                    _isPlaying.value = controller.isPlaying

                    val now = System.currentTimeMillis()
                    if (controller.isPlaying && now - lastSavedPosTimestampMs > 5000L) {
                        lastSavedPosTimestampMs = now
                        prefsRepo.saveLastPlaybackPositionMs(pos)
                    }
                }
                delay(100L)
            }
        }
    }

    fun rescanLibrary(autoStartDemoIfIdle: Boolean = false) {
        viewModelScope.launch {
            _isScanning.value = true
            val scanned = LibraryScanner.scanAllSources(
                context = context,
                safFolderUris = _safFolderUris.value,
                customAliasMap = _customArtistAliases.value
            )
            _allTracks.value = scanned

            if (_playlists.value.isEmpty()) {
                val demoIds = scanned.filter { it.isDemoTrack }.map { it.id }
                _playlists.value = prefsRepo.loadPlaylists(demoIds)
            }

            if (scanned.isNotEmpty()) {
                val current = _currentTrack.value
                val nonDemoTracks = scanned.filter { !it.isDemoTrack }

                // 1. Try restoring persisted playback session if we haven't loaded a user track yet
                if (current == null || (current.isDemoTrack && nonDemoTracks.isNotEmpty())) {
                    val restored = restorePersistedSessionIfAvailable(scanned, syncPlayerIfIdle = autoStartDemoIfIdle)
                    if (!restored) {
                        val preferredList = nonDemoTracks.ifEmpty { scanned }
                        val first = preferredList.first()
                        _currentTrack.value = first
                        _lastSelectedTrackId.value = first.id
                        _activeQueue.value = preferredList
                        _activeQueueIndex.value = 0
                        _durationMs.value = first.durationMs
                        loadTrackArtwork(first)
                        persistCurrentPlaybackSession()
                        if (autoStartDemoIfIdle) {
                            prepareQueueInPlayer(preferredList, 0, playImmediately = false)
                        }
                    }
                } else {
                    // 2. Refresh metadata references in activeQueue and currentTrack without losing playback state
                    val byId = scanned.associateBy { it.id }
                    byId[current.id]?.let { updatedTrack ->
                        _currentTrack.value = updatedTrack
                    }
                    if (_activeQueue.value.isNotEmpty()) {
                        val updatedQueue = _activeQueue.value.mapNotNull { byId[it.id] ?: it }
                        _activeQueue.value = updatedQueue
                    }
                }
            }
            _isScanning.value = false
        }
    }

    private fun loadTrackArtwork(track: TrackItem) {
        viewModelScope.launch {
            _currentArtwork.value = ArtworkLoader.loadArtwork(context, track)
        }
    }

    /**
     * Play a specific scope (All Library, Album, Artist, Playlist, or Folder) starting from [startTrack].
     *Shuffle & Repeat operate strictly within this chosen scope!
     */
    fun playScope(
        scope: PlaybackScopeType,
        scopeTitle: String,
        scopeTracks: List<TrackItem>,
        startTrack: TrackItem? = null
    ) {
        if (scopeTracks.isEmpty()) return
        _scopeType.value = scope
        _scopeTitle.value = scopeTitle
        _activeQueue.value = scopeTracks

        val target = startTrack ?: if (_shuffleEnabled.value) scopeTracks.random() else scopeTracks.first()
        val startIdx = scopeTracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        _activeQueueIndex.value = startIdx
        _currentTrack.value = scopeTracks[startIdx]
        loadTrackArtwork(scopeTracks[startIdx])

        // Remember selection memory so returning to Library opens exactly here
        _lastSelectedTrackId.value = target.id
        when (scope) {
            PlaybackScopeType.ALL_LIBRARY -> {
                _librarySubTab.value = LibrarySubTab.SONGS
            }
            PlaybackScopeType.ALBUM -> {
                _librarySubTab.value = LibrarySubTab.ALBUMS
                _lastExpandedAlbumName.value = target.album.ifEmpty { scopeTitle.removePrefix("Album: ") }
            }
            PlaybackScopeType.ARTIST -> {
                _librarySubTab.value = LibrarySubTab.ARTISTS
                _lastExpandedArtistKey.value = target.normalizedArtistKey.ifEmpty {
                    target.canonicalArtistName.ifEmpty { scopeTitle.removePrefix("Artist: ") }
                }
            }
            PlaybackScopeType.PLAYLIST -> {
                _librarySubTab.value = LibrarySubTab.PLAYLISTS
            }
            PlaybackScopeType.FOLDER -> {
                _librarySubTab.value = LibrarySubTab.FOLDERS
            }
        }

        prepareQueueInPlayer(scopeTracks, startIdx, playImmediately = true)
        persistCurrentPlaybackSession()
    }

    /**
     * Shuffles all tracks in the specified album, artist, or playlist scope and starts playback immediately.
     */
    fun playScopeShuffled(
        scope: PlaybackScopeType,
        scopeTitle: String,
        scopeTracks: List<TrackItem>
    ) {
        if (scopeTracks.isEmpty()) return
        val shuffledList = scopeTracks.shuffled()
        playScope(
            scope = scope,
            scopeTitle = "$scopeTitle (シャッフル)",
            scopeTracks = shuffledList,
            startTrack = shuffledList.first()
        )
    }

    private fun prepareQueueInPlayer(
        queue: List<TrackItem>,
        startIndex: Int,
        playImmediately: Boolean,
        startPositionMs: Long = 0L
    ) {
        val controller = mediaController
        if (controller == null) {
            // Retry shortly once MediaSessionService finishes binding
            viewModelScope.launch {
                delay(250L)
                mediaController?.let { prepareQueueInPlayer(queue, startIndex, playImmediately, startPositionMs) }
            }
            return
        }
        if (!playImmediately && controller.mediaItemCount > 0) {
            return
        }

        // Cheap: no artwork is decoded here; the service resolves artwork lazily via ArtBitmapLoader.
        val mediaItems = queue.map { track ->
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.canonicalArtistName)
                .setSubtitle(track.rawArtist)
                .setAlbumTitle(track.album)
                .setTrackNumber(track.trackNumber)
                .setArtworkUri(ArtworkLoader.artworkUriFor(track))
                .build()

            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.parse(track.uriString))
                .setMediaMetadata(metadata)
                .build()
        }

        controller.setMediaItems(mediaItems, startIndex, startPositionMs.coerceAtLeast(0L))
        syncRepeatAndShuffleToController(controller)
        controller.prepare()
        controller.playWhenReady = playImmediately
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0 && _activeQueue.value.isNotEmpty()) {
            val idx = _activeQueueIndex.value.coerceIn(0, _activeQueue.value.lastIndex)
            prepareQueueInPlayer(
                queue = _activeQueue.value,
                startIndex = idx,
                playImmediately = true,
                startPositionMs = _currentPositionMs.value
            )
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0L)
            }
            controller.play()
        }
    }

    fun skipNext() {
        val controller = mediaController ?: return
        controller.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000L) {
            controller.seekTo(0L)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    fun seekToFraction(fraction: Float) {
        val controller = mediaController ?: return
        val dur = _durationMs.value.coerceAtLeast(1000L)
        val targetMs = (dur * fraction.coerceIn(0f, 1f)).toLong()
        controller.seekTo(targetMs)
        _currentPositionMs.value = targetMs
        prefsRepo.saveLastPlaybackPositionMs(targetMs)
    }

    fun toggleShuffle() {
        val newShuffle = !_shuffleEnabled.value
        _shuffleEnabled.value = newShuffle
        mediaController?.let { syncRepeatAndShuffleToController(it) }
        persistCurrentPlaybackSession()
        showStatusMessage(if (newShuffle) "シャッフル再生 ON (${_scopeTitle.value})" else "シャッフル再生 OFF")
    }

    fun cycleRepeatMode() {
        val next = when (_repeatMode.value) {
            RepeatScopeMode.REPEAT_SCOPE -> RepeatScopeMode.REPEAT_ONE
            RepeatScopeMode.REPEAT_ONE -> RepeatScopeMode.OFF
            RepeatScopeMode.OFF -> RepeatScopeMode.REPEAT_SCOPE
        }
        _repeatMode.value = next
        mediaController?.let { syncRepeatAndShuffleToController(it) }
        persistCurrentPlaybackSession()
        showStatusMessage("${next.label} (${_scopeTitle.value})")
    }

    private fun syncRepeatAndShuffleToController(controller: MediaController) {
        controller.shuffleModeEnabled = _shuffleEnabled.value
        controller.repeatMode = when (_repeatMode.value) {
            RepeatScopeMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatScopeMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            RepeatScopeMode.REPEAT_SCOPE -> Player.REPEAT_MODE_ALL
        }
    }

    // Folder exploration addition
    fun addSafFolderUri(uri: Uri) {
        val uriStr = uri.toString()
        if (uriStr !in _safFolderUris.value) {
            val updated = _safFolderUris.value + uriStr
            _safFolderUris.value = updated
            prefsRepo.saveSafFolders(updated)
            rescanLibrary()
            showStatusMessage("フォルダを追加しライブラリを更新しました")
        }
    }

    fun removeSafFolderUri(uriStr: String) {
        val updated = _safFolderUris.value - uriStr
        _safFolderUris.value = updated
        prefsRepo.saveSafFolders(updated)
        rescanLibrary()
    }

    // Artist Normalization Custom Alias Mapping
    fun setCustomArtistAlias(rawVariation: String, canonicalTargetName: String) {
        val updated = _customArtistAliases.value.toMutableMap()
        if (canonicalTargetName.isBlank()) {
            updated.remove(rawVariation.trim())
        } else {
            updated[rawVariation.trim()] = canonicalTargetName.trim()
        }
        _customArtistAliases.value = updated
        prefsRepo.saveCustomArtistAliases(updated)
        _allTracks.value = LibraryScanner.applyArtistNormalization(_allTracks.value, updated)
        showStatusMessage("アーティスト表記「$rawVariation」を「$canonicalTargetName」に統合しました")
    }

    // Multi-Playlist Management (1 song can belong to any number of playlists)
    fun createPlaylist(name: String, description: String = "", initialTrackIds: List<String> = emptyList()) {
        val cleanName = name.trim().ifEmpty { "新規プレイリスト ${_playlists.value.size + 1}" }
        val newPl = Playlist(
            id = "pl_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            description = description.trim(),
            trackIds = initialTrackIds.distinct()
        )
        val updated = _playlists.value + newPl
        _playlists.value = updated
        prefsRepo.savePlaylists(updated)
        showStatusMessage("プレイリスト「$cleanName」を作成しました")
    }

    fun deletePlaylist(playlistId: String) {
        val updated = _playlists.value.filterNot { it.id == playlistId }
        _playlists.value = updated
        prefsRepo.savePlaylists(updated)
    }

    /**
     * Updates which playlists contain [trackId]. Allows adding a single song to multiple playlists at once!
     */
    fun setTrackPlaylistsMembership(trackId: String, selectedPlaylistIds: Set<String>) {
        val updated = _playlists.value.map { pl ->
            val currentSet = pl.trackIds.toMutableList()
            if (pl.id in selectedPlaylistIds) {
                if (trackId !in currentSet) currentSet.add(trackId)
            } else {
                currentSet.remove(trackId)
            }
            pl.copy(trackIds = currentSet)
        }
        _playlists.value = updated
        prefsRepo.savePlaylists(updated)
        showStatusMessage("プレイリストへの登録を更新しました（${selectedPlaylistIds.size}件のプレイリスト）")
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val updated = _playlists.value.map { pl ->
            if (pl.id == playlistId) pl.copy(trackIds = pl.trackIds - trackId) else pl
        }
        _playlists.value = updated
        prefsRepo.savePlaylists(updated)
    }

    // Live Playback Queue Management
    fun skipToQueueIndex(index: Int) {
        val queue = _activeQueue.value
        if (index !in queue.indices) return
        val target = queue[index]
        _activeQueueIndex.value = index
        _currentTrack.value = target
        _lastSelectedTrackId.value = target.id
        _durationMs.value = target.durationMs.coerceAtLeast(1000L)
        _currentPositionMs.value = 0L
        loadTrackArtwork(target)
        persistCurrentPlaybackSession()

        val controller = mediaController
        if (controller != null && controller.mediaItemCount == queue.size) {
            controller.seekToDefaultPosition(index)
            controller.play()
        } else {
            prepareQueueInPlayer(queue, index, playImmediately = true)
        }
    }

    fun moveQueueTrack(fromIndex: Int, toIndex: Int) {
        val currentList = _activeQueue.value.toMutableList()
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices || fromIndex == toIndex) return
        val moved = currentList.removeAt(fromIndex)
        currentList.add(toIndex, moved)
        _activeQueue.value = currentList

        val controller = mediaController
        if (controller != null && controller.mediaItemCount == currentList.size) {
            controller.moveMediaItem(fromIndex, toIndex)
            _activeQueueIndex.value = controller.currentMediaItemIndex.coerceAtLeast(0)
        } else {
            val curId = _currentTrack.value?.id
            _activeQueueIndex.value = currentList.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        }
        persistCurrentPlaybackSession()
    }

    fun removeQueueTrack(index: Int) {
        val currentList = _activeQueue.value.toMutableList()
        if (index !in currentList.indices || currentList.size <= 1) return
        val removed = currentList.removeAt(index)
        _activeQueue.value = currentList

        val controller = mediaController
        if (controller != null && index < controller.mediaItemCount) {
            controller.removeMediaItem(index)
            val newIdx = controller.currentMediaItemIndex.coerceIn(0, currentList.lastIndex)
            _activeQueueIndex.value = newIdx
            _currentTrack.value = currentList[newIdx]
            loadTrackArtwork(currentList[newIdx])
        }
        persistCurrentPlaybackSession()
        showStatusMessage("再生キューから「${removed.title}」を削除しました")
    }

    fun enqueueTrackNext(track: TrackItem) {
        val currentList = _activeQueue.value.toMutableList()
        val insertIdx = if (currentList.isEmpty()) 0 else (_activeQueueIndex.value + 1).coerceIn(0, currentList.size)
        currentList.add(insertIdx, track)
        _activeQueue.value = currentList
        persistCurrentPlaybackSession()

        val controller = mediaController
        if (controller != null) {
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.canonicalArtistName)
                .setSubtitle(track.rawArtist)
                .setAlbumTitle(track.album)
                .setTrackNumber(track.trackNumber)
                .setArtworkUri(ArtworkLoader.artworkUriFor(track))
                .build()
            val item = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.parse(track.uriString))
                .setMediaMetadata(metadata)
                .build()
            controller.addMediaItem(insertIdx.coerceAtMost(controller.mediaItemCount), item)
        }
        showStatusMessage("次に再生:「${track.title}」をキューに追加しました")
    }

    fun saveActiveQueueAsPlaylist(name: String) {
        val ids = _activeQueue.value.map { it.id }
        if (ids.isEmpty()) return
        createPlaylist(name = name, description = "再生キュー (${_scopeTitle.value}) から保存", initialTrackIds = ids)
    }

    // Settings & Customization
    fun setAutoPlayOnBtConnect(enabled: Boolean) {
        _autoPlayOnBtConnect.value = enabled
        prefsRepo.saveAutoPlayOnBluetoothConnect(enabled)
        showStatusMessage(if (enabled) "Bluetooth接続時の自動再生・優先権をONにしました" else "Bluetooth接続時の自動再生をOFFにしました")
    }
    fun setCrossfadeSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(0f, 12f)
        _crossfadeSec.value = clamped
        DspStateHolder.crossfadeSec = clamped
        prefsRepo.saveCrossfadeSeconds(clamped)
    }

    fun setVisualizerStyle(style: VisualizerStyle) {
        _visualizerStyle.value = style
        prefsRepo.saveVisualizerStyle(style)
    }

    fun updateDisplayConfig(config: PlayerDisplayConfig) {
        val normalized = config.copy(
            showVisualizer = config.showVisualizer,
            showJacketArt = !config.showVisualizer
        )
        _displayConfig.value = normalized
        prefsRepo.saveDisplayConfig(normalized)
    }

    fun setHeroStageVisualizer(useVisualizer: Boolean) {
        updateDisplayConfig(
            _displayConfig.value.copy(
                showVisualizer = useVisualizer,
                showJacketArt = !useVisualizer
            )
        )
    }

    fun showStatusMessage(msg: String) {
        viewModelScope.launch {
            _statusBannerMessage.value = msg
            delay(3200L)
            if (_statusBannerMessage.value == msg) {
                _statusBannerMessage.value = null
            }
        }
    }

    override fun onCleared() {
        positionPollJob?.cancel()
        mediaController?.release()
        mediaController = null
        super.onCleared()
    }
}
