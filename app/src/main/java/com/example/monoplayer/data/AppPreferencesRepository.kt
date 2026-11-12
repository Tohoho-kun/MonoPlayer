package com.example.monoplayer.data

import android.content.Context
import com.example.monoplayer.dsp.DynamicEqConfig
import com.example.monoplayer.dsp.LoudnessConfig
import com.example.monoplayer.dsp.SpatialStageConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("mono_player_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadPlaylists(defaultDemoTrackIds: List<String>): List<Playlist> {
        val raw = prefs.getString("playlists_json", null)
        if (raw.isNullOrBlank()) {
            val starter = listOf(
                Playlist(
                    id = "pl_starter_dsp",
                    name = "高域キラキラ検証コレクション",
                    description = "単純モノラルと高音質DSPの差が分かりやすい倍音・位相差トラック集",
                    trackIds = defaultDemoTrackIds
                ),
                Playlist(
                    id = "pl_starter_90s",
                    name = "90s Night Wangan Drive",
                    description = "カーステレオVFD・90s〜00sレトロ世代向けドライビングリスト",
                    trackIds = defaultDemoTrackIds.takeLast(2)
                )
            )
            savePlaylists(starter)
            return starter
        }
        return try {
            json.decodeFromString<List<Playlist>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(playlists: List<Playlist>) {
        prefs.edit().putString("playlists_json", json.encodeToString(playlists)).apply()
    }

    fun loadSafFolders(): List<String> {
        val raw = prefs.getString("saf_folders_json", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSafFolders(uris: List<String>) {
        prefs.edit().putString("saf_folders_json", json.encodeToString(uris)).apply()
    }

    fun loadCustomArtistAliases(): Map<String, String> {
        val raw = prefs.getString("artist_aliases_json", null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveCustomArtistAliases(map: Map<String, String>) {
        prefs.edit().putString("artist_aliases_json", json.encodeToString(map)).apply()
    }

    fun loadDisplayConfig(): PlayerDisplayConfig {
        val raw = prefs.getString("display_config_json", null) ?: return PlayerDisplayConfig()
        return try {
            json.decodeFromString<PlayerDisplayConfig>(raw)
        } catch (_: Exception) {
            PlayerDisplayConfig()
        }
    }

    fun saveDisplayConfig(config: PlayerDisplayConfig) {
        prefs.edit().putString("display_config_json", json.encodeToString(config)).apply()
    }

    fun loadCrossfadeSeconds(): Float = prefs.getFloat("crossfade_sec", 2.0f)
    fun saveCrossfadeSeconds(sec: Float) {
        prefs.edit().putFloat("crossfade_sec", sec).apply()
    }

    fun loadVisualizerStyle(): VisualizerStyle {
        val name = prefs.getString("visualizer_style", VisualizerStyle.RAINY_GLASS_JACKET.name)
        return VisualizerStyle.entries.find { it.name == name } ?: VisualizerStyle.RAINY_GLASS_JACKET
    }

    fun saveVisualizerStyle(style: VisualizerStyle) {
        prefs.edit().putString("visualizer_style", style.name).apply()
    }

    fun loadLoudnessConfig(): LoudnessConfig {
        val raw = prefs.getString("loudness_config_json", null) ?: return LoudnessConfig()
        return try {
            val decoded = json.decodeFromString<LoudnessConfig>(raw)
            // Migrate legacy v1 configs that had no headroom schema version and used -11 LUFS default
            if (!raw.contains("\"headroomSchemaVersion\"")) {
                val migratedTarget = if (decoded.carTargetLufs > -13.0f) -19.0f else decoded.carTargetLufs
                val migrated = decoded.copy(carTargetLufs = migratedTarget, headroomSchemaVersion = 2)
                saveLoudnessConfig(migrated)
                migrated
            } else {
                decoded
            }
        } catch (_: Exception) {
            LoudnessConfig()
        }
    }

    fun saveLoudnessConfig(config: LoudnessConfig) {
        prefs.edit().putString("loudness_config_json", json.encodeToString(config)).apply()
    }

    fun loadDynamicEqConfig(): DynamicEqConfig {
        val raw = prefs.getString("dynamic_eq_config_json", null) ?: return DynamicEqConfig()
        return try {
            json.decodeFromString<DynamicEqConfig>(raw)
        } catch (_: Exception) {
            DynamicEqConfig()
        }
    }

    fun saveDynamicEqConfig(config: DynamicEqConfig) {
        prefs.edit().putString("dynamic_eq_config_json", json.encodeToString(config)).apply()
    }

    fun loadSpatialConfig(): SpatialStageConfig {
        val defaultStage = SpatialStageConfig.PRESETS.getValue("Mono Z-Depth (標準・前後立体)")
        val raw = prefs.getString("spatial_stage_config_json", null) ?: return defaultStage
        return try {
            json.decodeFromString<SpatialStageConfig>(raw)
        } catch (_: Exception) {
            defaultStage
        }
    }

    fun saveSpatialConfig(config: SpatialStageConfig) {
        prefs.edit().putString("spatial_stage_config_json", json.encodeToString(config)).apply()
    }

    fun loadSyncOffsetMs(): Int = prefs.getInt("audio_sync_offset_ms", 0)

    fun saveSyncOffsetMs(offsetMs: Int) {
        prefs.edit().putInt("audio_sync_offset_ms", offsetMs).apply()
    }

    fun loadCachedLibraryTracks(): List<TrackItem> {
        val raw = prefs.getString("cached_library_tracks_json", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TrackItem>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedLibraryTracks(tracks: List<TrackItem>) {
        prefs.edit().putString("cached_library_tracks_json", json.encodeToString(tracks)).apply()
    }

    fun loadPlaybackSession(): PersistedPlaybackSession? {
        val raw = prefs.getString("playback_session_json", null) ?: return null
        return try {
            json.decodeFromString<PersistedPlaybackSession>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun savePlaybackSession(session: PersistedPlaybackSession) {
        prefs.edit().putString("playback_session_json", json.encodeToString(session)).apply()
    }

    fun saveLastPlaybackPositionMs(positionMs: Long) {
        val current = loadPlaybackSession() ?: return
        savePlaybackSession(current.copy(positionMs = positionMs.coerceAtLeast(0L)))
    }

    fun loadAutoPlayOnBluetoothConnect(): Boolean =
        prefs.getBoolean("auto_play_on_bt_connect", true)

    fun saveAutoPlayOnBluetoothConnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play_on_bt_connect", enabled).apply()
    }
}
