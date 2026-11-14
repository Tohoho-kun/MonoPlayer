package com.example.monoplayer.service

import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.example.monoplayer.MainActivity
import com.example.monoplayer.data.AppPreferencesRepository
import com.example.monoplayer.data.ArtworkLoader
import com.example.monoplayer.data.LibraryScanner
import com.example.monoplayer.data.RepeatScopeMode
import com.example.monoplayer.data.TrackItem
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.LoudnessMode
import com.example.monoplayer.dsp.MonoDspAudioProcessor
import com.example.monoplayer.dsp.VehicleSpeedTracker
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * OS-Integrated MediaSessionService for Android:
 * - Provides lock-screen transport controls, notification media player with album art,
 *   Bluetooth AVRCP / car stereo integration, headphone button controls, and audio focus handling.
 * - Implements Media3 [MediaSession.Callback.onPlaybackResumption] and active Bluetooth A2DP / BLE
 *   sink detection so MonoPlayer wins Bluetooth auto-play priority and resumes the last played track.
 * - Injects [MonoDspAudioProcessor] directly into ExoPlayer's [DefaultAudioSink] pipeline.
 */
@OptIn(UnstableApi::class)
class MonoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackedFlushGen = -1
    private var flushStartMediaPosMs = 0L
    private var gaplessAccumulatedMs = 0L

    private var speedTracker: VehicleSpeedTracker? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefsRepo: AppPreferencesRepository

    private var audioManager: AudioManager? = null
    private var lastBtSinkTriggerTimeMs = 0L
    private var hadBtSinkOnInit = false

    private val btAudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!hadBtSinkOnInit) {
                hadBtSinkOnInit = true
                return
            }
            val addedBtSink = addedDevices?.any { dev ->
                dev.isSink && (
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    )
            } == true
            if (addedBtSink) {
                handleBluetoothSinkConnected()
            }
        }
    }

    private val btBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                if (state == BluetoothProfile.STATE_CONNECTED && prevState != BluetoothProfile.STATE_CONNECTED) {
                    handleBluetoothSinkConnected()
                }
            }
        }
    }

    private val syncClockRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                val gen = DspStateHolder.syncBuffer.flushGeneration
                val posMs = p.currentPosition.coerceAtLeast(0L)
                if (gen != trackedFlushGen) {
                    trackedFlushGen = gen
                    flushStartMediaPosMs = posMs
                    gaplessAccumulatedMs = 0L
                }
                val playedStreamMs = (gaplessAccumulatedMs + (posMs - flushStartMediaPosMs)).coerceAtLeast(0L)
                DspStateHolder.syncBuffer.updatePlayedStreamClock(
                    playedStreamMs = playedStreamMs,
                    nowRealtimeMs = System.nanoTime() / 1_000_000L,
                    gen = gen
                )

                // Service-level deterministic track tail fade-out (when crossfade is active)
                val cfSec = DspStateHolder.crossfadeSec
                val durMs = p.duration
                if (cfSec > 0.05f && durMs > 3000L) {
                    val cfMs = (cfSec * 1000f).toLong().coerceAtMost(durMs / 3)
                    val remainingMs = durMs - posMs
                    DspStateHolder.crossfadeMultiplier = when {
                        remainingMs in 1..cfMs -> (remainingMs.toFloat() / cfMs.toFloat()).coerceIn(0f, 1f)
                        else -> 1.0f
                    }
                } else {
                    DspStateHolder.crossfadeMultiplier = 1.0f
                }
            }
            mainHandler.postDelayed(this, 45L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefsRepo = AppPreferencesRepository(this)
        if (!DspStateHolder.isInitializedFromPrefs) {
            DspStateHolder.crossfadeSec = prefsRepo.loadCrossfadeSeconds()
            DspStateHolder.updateLoudnessConfig(prefsRepo.loadLoudnessConfig())
            DspStateHolder.updateDynamicEqConfig(prefsRepo.loadDynamicEqConfig())
            DspStateHolder.updateSpatialConfig(prefsRepo.loadSpatialConfig())
            DspStateHolder.setManualSyncOffsetMs(prefsRepo.loadSyncOffsetMs())
            DspStateHolder.isInitializedFromPrefs = true
        }
        serviceScope.launch {
            DspStateHolder.loudnessConfigFlow.drop(1).collect { prefsRepo.saveLoudnessConfig(it) }
        }
        serviceScope.launch {
            DspStateHolder.dynamicEqConfigFlow.drop(1).collect { prefsRepo.saveDynamicEqConfig(it) }
        }
        serviceScope.launch {
            DspStateHolder.spatialConfigFlow.drop(1).collect { prefsRepo.saveSpatialConfig(it) }
        }
        serviceScope.launch {
            DspStateHolder.syncOffsetMsFlow.drop(1).collect { prefsRepo.saveSyncOffsetMs(it) }
        }

        val dspProcessor = MonoDspAudioProcessor()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(dspProcessor))
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // Automatically pause when headphones are unplugged
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                evaluateSpeedTrackerState()
                if (!isPlaying) {
                    prefsRepo.saveLastPlaybackPositionMs(exoPlayer.currentPosition.coerceAtLeast(0L))
                }
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                DspStateHolder.resetCrossfadeOnTrackBoundary()
                val mediaId = mediaItem?.mediaId ?: return
                val session = prefsRepo.loadPlaybackSession()
                if (session != null) {
                    prefsRepo.savePlaybackSession(
                        session.copy(
                            currentTrackId = mediaId,
                            activeQueueIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0),
                            positionMs = 0L
                        )
                    )
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                DspStateHolder.resetCrossfadeOnTrackBoundary()
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    val finishedSpan = (oldPosition.positionMs - flushStartMediaPosMs).coerceAtLeast(0L)
                    gaplessAccumulatedMs += finishedSpan
                    flushStartMediaPosMs = newPosition.positionMs.coerceAtLeast(0L)
                } else {
                    flushStartMediaPosMs = newPosition.positionMs.coerceAtLeast(0L)
                    gaplessAccumulatedMs = 0L
                }
            }
        })

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionCallback = object : MediaSession.Callback {
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaItemsWithStartPosition> {
                val future = SettableFuture.create<MediaItemsWithStartPosition>()
                serviceScope.launch {
                    try {
                        val restored = loadPersistedOrScannedMediaItems()
                        future.set(restored)
                    } catch (e: Exception) {
                        future.setException(e)
                    }
                }
                return future
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val p = player
                if (p != null && p.mediaItemCount == 0) {
                    // Pre-populate queue from persisted state so play button immediately starts music
                    val quickItems = buildMediaItemsFromCacheSynchronously()
                    if (quickItems != null && quickItems.mediaItems.isNotEmpty()) {
                        p.setMediaItems(
                            quickItems.mediaItems,
                            quickItems.startIndex,
                            quickItems.startPositionMs
                        )
                        p.prepare()
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setBitmapLoader(ArtBitmapLoader(this))
            .setCallback(sessionCallback)
            .build()

        player = exoPlayer
        preloadPersistedQueueIntoPlayerIfIdle(exoPlayer)
        mainHandler.post(syncClockRunnable)

        registerBluetoothAutoPlayMonitor()

        speedTracker = VehicleSpeedTracker(this)
        serviceScope.launch {
            DspStateHolder.loudnessConfigFlow.collectLatest {
                evaluateSpeedTrackerState()
            }
        }
    }

    private fun preloadPersistedQueueIntoPlayerIfIdle(exoPlayer: ExoPlayer) {
        if (exoPlayer.mediaItemCount > 0) return
        val restored = buildMediaItemsFromCacheSynchronously() ?: return
        if (restored.mediaItems.isEmpty()) return
        val savedSession = prefsRepo.loadPlaybackSession()
        exoPlayer.shuffleModeEnabled = savedSession?.shuffleEnabled ?: false
        exoPlayer.repeatMode = when (
            RepeatScopeMode.entries.find { it.name == savedSession?.repeatModeName }
        ) {
            RepeatScopeMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatScopeMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_ALL
        }
        exoPlayer.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
        exoPlayer.prepare()
    }

    private fun buildMediaItemsFromCacheSynchronously(): MediaItemsWithStartPosition? {
        val cachedTracks = prefsRepo.loadCachedLibraryTracks()
        if (cachedTracks.isEmpty()) return null
        return buildResumptionItemsFromTracks(cachedTracks)
    }

    private suspend fun loadPersistedOrScannedMediaItems(): MediaItemsWithStartPosition {
        var tracks = prefsRepo.loadCachedLibraryTracks()
        if (tracks.isEmpty()) {
            tracks = LibraryScanner.scanAllSources(
                context = this@MonoPlaybackService,
                safFolderUris = prefsRepo.loadSafFolders(),
                customAliasMap = prefsRepo.loadCustomArtistAliases()
            )
        }
        return buildResumptionItemsFromTracks(tracks)
            ?: MediaItemsWithStartPosition(emptyList(), 0, 0L)
    }

    private fun buildResumptionItemsFromTracks(tracks: List<TrackItem>): MediaItemsWithStartPosition? {
        if (tracks.isEmpty()) return null
        val saved = prefsRepo.loadPlaybackSession()
        val byId = tracks.associateBy { it.id }
        val nonDemo = tracks.filter { !it.isDemoTrack }

        val queueTracks = saved?.queueTrackIds
            ?.mapNotNull { byId[it] }
            ?.takeIf { it.isNotEmpty() }
            ?: nonDemo.ifEmpty { tracks }

        val targetTrack = saved?.currentTrackId?.let { byId[it] }
            ?: queueTracks.getOrNull((saved?.activeQueueIndex ?: 0).coerceIn(0, queueTracks.lastIndex))
            ?: queueTracks.first()

        val startIndex = queueTracks.indexOfFirst { it.id == targetTrack.id }.coerceAtLeast(0)
        val startPosMs = (saved?.positionMs ?: 0L).coerceIn(0L, targetTrack.durationMs.coerceAtLeast(0L))

        val mediaItems = queueTracks.map { track ->
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

        return MediaItemsWithStartPosition(mediaItems, startIndex, startPosMs)
    }

    private fun registerBluetoothAutoPlayMonitor() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        hadBtSinkOnInit = false
        audioManager?.registerAudioDeviceCallback(btAudioDeviceCallback, mainHandler)

        try {
            val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                btBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    private fun handleBluetoothSinkConnected() {
        if (!prefsRepo.loadAutoPlayOnBluetoothConnect()) return
        val now = System.currentTimeMillis()
        if (now - lastBtSinkTriggerTimeMs < 2500L) return
        lastBtSinkTriggerTimeMs = now

        // Resume or claim priority shortly after BT audio route settles
        mainHandler.postDelayed({
            val p = player ?: return@postDelayed
            if (!prefsRepo.loadAutoPlayOnBluetoothConnect()) return@postDelayed
            if (p.mediaItemCount == 0) {
                preloadPersistedQueueIntoPlayerIfIdle(p)
            }
            if (p.mediaItemCount > 0 && !p.isPlaying) {
                p.play()
            }
        }, 450L)
    }

    private fun evaluateSpeedTrackerState() {
        val cfg = DspStateHolder.loudnessConfig
        val shouldTrack = cfg.mode == LoudnessMode.NOISY_CAR &&
                cfg.autoSpeedEnabled &&
                (player?.isPlaying == true)
        if (shouldTrack) {
            speedTracker?.startTracking()
        } else {
            speedTracker?.stopTracking()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p != null) {
            prefsRepo.saveLastPlaybackPositionMs(p.currentPosition.coerceAtLeast(0L))
        }
        // Keep service & MediaSession alive if BT auto-play priority is enabled and a queue is loaded
        if (prefsRepo.loadAutoPlayOnBluetoothConnect() && p != null && p.mediaItemCount > 0) {
            return
        }
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(syncClockRunnable)
        audioManager?.unregisterAudioDeviceCallback(btAudioDeviceCallback)
        try {
            unregisterReceiver(btBroadcastReceiver)
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        speedTracker?.stopTracking()
        speedTracker = null
        mediaSession?.run {
            prefsRepo.saveLastPlaybackPositionMs(player.currentPosition.coerceAtLeast(0L))
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
