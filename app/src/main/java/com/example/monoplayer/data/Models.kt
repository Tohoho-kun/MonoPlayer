package com.example.monoplayer.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class TrackItem(
    val id: String,
    val uriString: String,
    val title: String,
    val rawArtist: String,
    val normalizedArtistKey: String,
    val canonicalArtistName: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int = 0,
    val sampleRate: Int = 44100,
    val bitrateKbps: Int = 1411,
    val channels: Int = 2,
    val folderPath: String = "",
    val genre: String = "",
    val isDemoTrack: Boolean = false
)

@Immutable
data class AlbumGroup(
    val albumName: String,
    val artistName: String,
    val tracks: List<TrackItem>,
    val representativeTrack: TrackItem
)

@Immutable
data class ArtistGroup(
    val normalizedKey: String,
    val canonicalName: String,
    val rawVariations: Set<String>,
    val tracks: List<TrackItem>,
    val albumsCount: Int
)

@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String = "",
    val trackIds: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedPlaybackSession(
    val scopeTypeName: String = PlaybackScopeType.ALL_LIBRARY.name,
    val scopeTitle: String = "全曲ライブラリ",
    val queueTrackIds: List<String> = emptyList(),
    val activeQueueIndex: Int = 0,
    val currentTrackId: String? = null,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatModeName: String = RepeatScopeMode.REPEAT_SCOPE.name,
    val librarySubTabName: String = LibrarySubTab.SONGS.name,
    val lastExpandedAlbumName: String? = null,
    val lastExpandedArtistKey: String? = null
)

enum class PlaybackScopeType(val label: String) {
    ALL_LIBRARY("ALL LIBRARY"),
    ALBUM("ALBUM SCOPE"),
    ARTIST("ARTIST SCOPE"),
    PLAYLIST("PLAYLIST SCOPE"),
    FOLDER("FOLDER SCOPE")
}

enum class LibrarySubTab(val title: String) {
    SONGS("曲一覧"),
    ALBUMS("アルバム"),
    ARTISTS("アーティスト(名寄せ)"),
    PLAYLISTS("プレイリスト"),
    FOLDERS("フォルダ探索")
}

enum class RepeatScopeMode(val label: String) {
    OFF("リピートOFF"),
    REPEAT_ONE("1曲リピート"),
    REPEAT_SCOPE("スコープ全体リピート") // Repeats current Album, Artist, or Playlist
}

enum class VisualizerStyle(
    val label: String,
    val eraTag: String,
    val description: String = ""
) {
    RAINY_GLASS_JACKET(
        label = "Rainy Window & Refracted Jacket",
        eraTag = "RAINY GLASS",
        description = "雨粒の屈折レンズ・結露の滲み・流れ落ちる水滴軌跡越しに透けるジャケット"
    ),
    CLASSIC_CLUB_SPECTRUM(
        label = "Royal Club Gold & Amethyst Mirror",
        eraTag = "LUXURY MIRROR",
        description = "黒・金・紫・濃紺のスポットライトと鏡面フロア反射スペクトラム"
    ),
    CAR_STEREO_VFD(
        label = "Velvet Indigo Radial Halo & Twin VU",
        eraTag = "LUXURY HALO",
        description = "濃紺とシャンパンゴールドの放射状ハロー＆ツインアークVU"
    ),
    CYBER_OSCILLOSCOPE(
        label = "Midnight Purple & Gold Aurora Ribbon",
        eraTag = "LUXURY AURORA",
        description = "深い紫と濃紺の3Dリング＆ゴールド波形リボン"
    ),
    BUBBLE_VFD_1992(
        label = "'92 バブルコンポ＆2DIN 蛍光表示管 (VFD)",
        eraTag = "'92 VFD DSP",
        description = "青色LED普及前の蛍光グリーン・アンバー・赤ピークセグメント＆SFC音場グリッド"
    ),
    AMBER_GREEN_LED_1995(
        label = "'95 緑・橙・赤 3色LED ＆ STNドット液晶",
        eraTag = "'95 LED & STN",
        description = "黄緑バックライトSTN液晶とエポキシ3色LED（緑・橙・赤）のステップレベルメーター"
    ),
    Y2K_FIRESTORM_2000(
        label = "2000年初期レトロPC「FireStorm 炎スペクトラム」",
        eraTag = "'00 FIRE WAVE",
        description = "2000年初期PC風・低解像度256色パレット炎セルオートマトン＆1pxギザギザ波形"
    ),
    Y2K_ORBITAL_2000(
        label = "2000年初期レトロPC「Orbital 幾何学トレイル」",
        eraTag = "'00 ORBITAL",
        description = "初期デジタル特有の原色ケバケバしい回転ポリゴン残像＆スパイク幾何学エフェクト"
    );

    fun nextStyle(): VisualizerStyle {
        val list = entries
        return list[(ordinal + 1) % list.size]
    }
}

/**
 * Customizable Now-Playing UI Display Config ("再生画面表示項目のカスタマイズを可能にする")
 * Defaults keep the Now Playing screen calm, minimal, and free of setting clutter.
 */
@Serializable
data class PlayerDisplayConfig(
    val showJacketArt: Boolean = false,
    val showVisualizer: Boolean = true,
    val showArtistAndAlbum: Boolean = true,
    val showAudioFormatBadge: Boolean = false,
    val showDspFormulaAndMetrics: Boolean = false,
    val showInlineQuickEqBar: Boolean = false,
    val showScopeAndPlaylistBar: Boolean = false,
    val showCpuUsage: Boolean = false
) {
    val isVisualizerStage: Boolean
        get() = showVisualizer || !showJacketArt
}
