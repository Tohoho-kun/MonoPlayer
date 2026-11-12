package com.example.monoplayer.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.RepeatScopeMode
import com.example.monoplayer.data.TrackItem
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.LoudnessMode

@Composable
fun NowPlayingScreen(
    viewModel: MonoPlayerViewModel,
    onOpenPlaylistAssignDialog: (TrackItem) -> Unit,
    onOpenQueue: () -> Unit = {}
) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val artworkBitmap by viewModel.currentArtwork.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val scopeTitle by viewModel.scopeTitle.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val displayConfig by viewModel.displayConfig.collectAsState()
    val visualizerStyle by viewModel.visualizerStyle.collectAsState()
    val telemetry by DspStateHolder.telemetry.collectAsState()
    val loudnessConfig by DspStateHolder.loudnessConfigFlow.collectAsState()
    val dynamicEqConfig by DspStateHolder.dynamicEqConfigFlow.collectAsState()

    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderFraction by remember { mutableFloatStateOf(0f) }
    var carStageOverride by remember { mutableStateOf<Boolean?>(null) }

    val isCarMode = loudnessConfig.mode == LoudnessMode.NOISY_CAR
    val effectiveCarCockpit = isCarMode && (carStageOverride ?: true)
    val effectiveVisualizer = if (effectiveCarCockpit) false else displayConfig.showVisualizer

    val currentStageMode = when {
        effectiveCarCockpit -> "CAR_COCKPIT"
        effectiveVisualizer -> "VISUALIZER"
        else -> "JACKET"
    }

    // Dynamic De-clipping Restoration Frame Pulse & Flash
    val declipActive = telemetry.declipRate > 0.003f
    val declipFlashGlow by animateFloatAsState(
        targetValue = if (declipActive) (telemetry.declipRate * 2.5f + 0.45f).coerceIn(0.40f, 1.0f) else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "declip_stage_flash"
    )

    val antiqueGold = Color(0xFFC8A24A)
    val deepPurple = Color(0xFF2D1654)
    val deepMidnightBlue = Color(0xFF0E1E48)
    val ivoryText = Color(0xFFF4F1EA)

    val bassGlow = telemetry.spectrumDb.getOrElse(1) { 0.06f }.coerceIn(0.04f, 1f)
    val rmsGlow = (telemetry.rmsLinear * 1.8f).coerceIn(0.04f, 1f)

    // Calm, luxurious single-screen Player: Deep Black + Antique Gold + Royal Purple + Midnight Blue
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040408))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            deepPurple.copy(alpha = 0.28f + bassGlow * 0.16f),
                            deepMidnightBlue.copy(alpha = 0.22f + rmsGlow * 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.30f),
                        radius = size.minDimension * 0.90f
                    ),
                    radius = size.minDimension * 0.90f,
                    center = Offset(size.width * 0.5f, size.height * 0.30f)
                )
            }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Minimalist Top Row (No app name, no setting clutter)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (displayConfig.showScopeAndPlaylistBar) {
                    Text(
                        text = scopeTitle,
                        color = antiqueGold.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "再生キューを表示",
                            tint = antiqueGold.copy(alpha = 0.85f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isCarMode) {
                                when (currentStageMode) {
                                    "CAR_COCKPIT" -> {
                                        carStageOverride = false
                                        viewModel.setHeroStageVisualizer(true)
                                    }
                                    "VISUALIZER" -> {
                                        carStageOverride = false
                                        viewModel.setHeroStageVisualizer(false)
                                    }
                                    else -> {
                                        carStageOverride = true
                                    }
                                }
                            } else {
                                viewModel.setHeroStageVisualizer(!displayConfig.showVisualizer)
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        val stageIcon = when (currentStageMode) {
                            "CAR_COCKPIT" -> Icons.Default.DirectionsCar
                            "VISUALIZER" -> Icons.Default.Album
                            else -> Icons.Default.GraphicEq
                        }
                        Icon(
                            imageVector = stageIcon,
                            contentDescription = "表示ステージ切替 (HUD / ビジュアライザー / ジャケット)",
                            tint = if (currentStageMode == "CAR_COCKPIT") Color(0xFFD4AF37) else antiqueGold.copy(alpha = 0.82f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    currentTrack?.let { track ->
                        IconButton(
                            onClick = { onOpenPlaylistAssignDialog(track) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "プレイリストに追加",
                                tint = Color(0xFF8E9CC0),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 2. Central Hero Stage (Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = currentStageMode, label = "stage_exclusive") { stageMode ->
                    when (stageMode) {
                        "CAR_COCKPIT" -> {
                            CarCockpitStage(
                                config = loudnessConfig,
                                telemetry = telemetry,
                                artworkBitmap = artworkBitmap,
                                declipFlashGlow = declipFlashGlow,
                                dynEqEnabled = dynamicEqConfig.enabled,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        "VISUALIZER" -> {
                            RetroVisualizerDeck(
                                style = visualizerStyle,
                                onStyleChange = { viewModel.setVisualizerStyle(it) },
                                artworkBitmap = artworkBitmap,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        else -> {
                            LuxuryJacketStage(
                                artworkBitmap = artworkBitmap,
                                bassGlow = bassGlow,
                                declipFlashGlow = declipFlashGlow
                            )
                        }
                    }
                }
            }

            // 3. Dignified Track Metadata
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = currentTrack?.title ?: "楽曲を選択してください",
                    color = ivoryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (displayConfig.showArtistAndAlbum) {
                    Text(
                        text = if (currentTrack != null) {
                            "${currentTrack!!.rawArtist}  —  ${currentTrack!!.album}"
                        } else {
                            "ライブラリから再生を開始"
                        },
                        color = antiqueGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (displayConfig.showAudioFormatBadge && currentTrack != null) {
                    Text(
                        text = "${currentTrack!!.sampleRate} Hz / ${currentTrack!!.bitrateKbps} kbps",
                        color = Color(0xFF6E7C9E),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 4. Calm Luxury Obsidian, Midnight Blue, Purple & Gold Transport Deck
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF0C1024), Color(0xFF070710))
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                antiqueGold.copy(alpha = 0.42f),
                                Color(0xFF4C1D95).copy(alpha = 0.40f),
                                Color(0xFF1E3A8A).copy(alpha = 0.45f),
                                antiqueGold.copy(alpha = 0.42f)
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val progress = if (isDraggingSlider) {
                    sliderFraction
                } else if (durationMs > 0L) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = progress,
                    onValueChange = {
                        isDraggingSlider = true
                        sliderFraction = it
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        viewModel.seekToFraction(sliderFraction)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = antiqueGold,
                        activeTrackColor = antiqueGold,
                        inactiveTrackColor = Color(0xFF1A223D)
                    ),
                    modifier = Modifier.height(22.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationMs(positionMs),
                        color = Color(0xFF8592B2),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatDurationMs(durationMs),
                        color = Color(0xFF8592B2),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "シャッフル",
                            tint = if (shuffleEnabled) antiqueGold else Color(0xFF56617E)
                        )
                    }

                    IconButton(onClick = { viewModel.skipPrevious() }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "前の曲",
                            tint = ivoryText,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2B1752), Color(0xFF102254))
                                )
                            )
                            .border(1.5.dp, antiqueGold, CircleShape)
                            .clickable { viewModel.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "再生/一時停止",
                            tint = antiqueGold,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.skipNext() }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "次の曲",
                            tint = ivoryText,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.cycleRepeatMode() }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = if (repeatMode == RepeatScopeMode.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "リピート",
                            tint = if (repeatMode != RepeatScopeMode.OFF) antiqueGold else Color(0xFF56617E)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LuxuryJacketStage(
    artworkBitmap: Bitmap?,
    bassGlow: Float,
    declipFlashGlow: Float = 0f
) {
    val coralFlash = Color(0xFFFF5252)
    val jacketBorderBrush = if (declipFlashGlow > 0.04f) {
        val a = (0.45f + declipFlashGlow * 0.55f).coerceIn(0f, 1f)
        Brush.linearGradient(
            listOf(coralFlash.copy(alpha = a), Color(0xFFFF7A00).copy(alpha = a), coralFlash.copy(alpha = a))
        )
    } else {
        Brush.linearGradient(
            listOf(
                Color(0xFFC8A24A).copy(alpha = 0.55f),
                Color(0xFF4C1D95).copy(alpha = 0.40f),
                Color(0xFF1E3A8A).copy(alpha = 0.50f)
            )
        )
    }
    val jacketBorderWidth = if (declipFlashGlow > 0.04f) (1.2f + declipFlashGlow * 1.8f).dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1F123B),
                        Color(0xFF0A132C),
                        Color(0xFF05050A)
                    )
                )
            )
            .border(
                width = jacketBorderWidth,
                brush = jacketBorderBrush,
                shape = RoundedCornerShape(20.dp)
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFC8A24A).copy(alpha = 0.12f + bassGlow * 0.14f),
                            Color(0xFF3B1D70).copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.52f
                    ),
                    radius = size.minDimension * 0.52f,
                    center = center
                )
            }
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (artworkBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.88f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFFC8A24A), Color(0xFF5B21B6), Color(0xFF1E3A8A))
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Image(
                    bitmap = artworkBitmap.asImageBitmap(),
                    contentDescription = "アルバムジャケット",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
