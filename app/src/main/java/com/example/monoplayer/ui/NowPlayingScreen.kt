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

    val bassGlow = telemetry.spectrumDb.getOrElse(1) { 0.06f }.coerceIn(0.04f, 1f)
    val rmsGlow = (telemetry.rmsLinear * 1.8f).coerceIn(0.04f, 1f)

    // Acoustic Pebble: Obsidian Glass + Smoked Acrylic + Submerged Glacial Cyan Glow
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AcousticPebbleColors.Surface)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.08f + bassGlow * 0.10f),
                            AcousticPebbleColors.SurfaceContainerHigh.copy(alpha = 0.45f + rmsGlow * 0.10f),
                            AcousticPebbleColors.Surface.copy(alpha = 0f)
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
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = scopeTitle,
                            color = AcousticPebbleColors.PrimaryContainer,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AcousticPebbleColors.SurfaceContainerLow)
                                .border(
                                    1.dp,
                                    AcousticPebbleColors.OutlineVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
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
                            tint = AcousticPebbleColors.OnSurfaceVariant,
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
                            tint = if (currentStageMode == "CAR_COCKPIT") AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.OnSurfaceVariant,
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
                                tint = AcousticPebbleColors.OnSurfaceVariant,
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
                    color = AcousticPebbleColors.OnSurface,
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
                        color = AcousticPebbleColors.OnSurfaceVariant,
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
                        color = AcousticPebbleColors.PrimaryContainer,
                        fontSize = 10.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                }
                if (displayConfig.showCpuUsage) {
                    var cpuPercent by remember { mutableFloatStateOf(0f) }
                    LaunchedEffect(Unit) {
                        var lastWall = android.os.SystemClock.elapsedRealtime()
                        var lastCpu = android.os.Process.getElapsedCpuTime()
                        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                        while (true) {
                            kotlinx.coroutines.delay(1000L)
                            val nowWall = android.os.SystemClock.elapsedRealtime()
                            val nowCpu = android.os.Process.getElapsedCpuTime()
                            val dWall = (nowWall - lastWall).coerceAtLeast(1L)
                            val dCpu = (nowCpu - lastCpu).coerceAtLeast(0L)
                            cpuPercent = ((dCpu.toFloat() / dWall.toFloat()) * 100f / cores).coerceIn(0f, 100f)
                            lastWall = nowWall
                            lastCpu = nowCpu
                        }
                    }
                    Text(
                        text = "CPU ${String.format(java.util.Locale.US, "%.1f", cpuPercent)}%",
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // 4. Smoked Acrylic Transport Deck
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(AcousticPebbleColors.SurfaceContainerLow.copy(alpha = 0.85f))
                    .border(
                        width = 1.dp,
                        color = AcousticPebbleColors.OnSurface.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(24.dp)
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
                        thumbColor = AcousticPebbleColors.Primary,
                        activeTrackColor = AcousticPebbleColors.PrimaryContainer,
                        inactiveTrackColor = AcousticPebbleColors.SurfaceContainerHigh
                    ),
                    modifier = Modifier.height(22.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDurationMs(positionMs),
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = formatDurationMs(durationMs),
                        color = AcousticPebbleColors.OnSurfaceVariant,
                        fontSize = 10.sp,
                        fontFamily = PlusJakartaSansFamily,
                        letterSpacing = 0.5.sp
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
                            tint = if (shuffleEnabled) AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.OnSurface.copy(alpha = 0.5f)
                        )
                    }

                    IconButton(onClick = { viewModel.skipPrevious() }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "前の曲",
                            tint = AcousticPebbleColors.OnSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AcousticPebbleColors.SurfaceContainerHigh)
                            .border(1.dp, AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.45f), CircleShape)
                            .clickable { viewModel.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "再生/一時停止",
                            tint = AcousticPebbleColors.PrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.skipNext() }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "次の曲",
                            tint = AcousticPebbleColors.OnSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = { viewModel.cycleRepeatMode() }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = if (repeatMode == RepeatScopeMode.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "リピート",
                            tint = if (repeatMode != RepeatScopeMode.OFF) AcousticPebbleColors.PrimaryContainer else AcousticPebbleColors.OnSurface.copy(alpha = 0.5f)
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
    val declipErrorColor = AcousticPebbleColors.Error
    val jacketBorderBrush = if (declipFlashGlow > 0.04f) {
        val a = (0.45f + declipFlashGlow * 0.55f).coerceIn(0f, 1f)
        Brush.linearGradient(
            listOf(declipErrorColor.copy(alpha = a), AcousticPebbleColors.PrimaryContainer.copy(alpha = a), declipErrorColor.copy(alpha = a))
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.03f)
            )
        )
    }
    val jacketBorderWidth = if (declipFlashGlow > 0.04f) (1.2f + declipFlashGlow * 1.8f).dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        AcousticPebbleColors.SurfaceContainer,
                        AcousticPebbleColors.SurfaceContainerLow,
                        AcousticPebbleColors.SurfaceContainerLowest
                    )
                )
            )
            .border(
                width = jacketBorderWidth,
                brush = jacketBorderBrush,
                shape = RoundedCornerShape(32.dp)
            )
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AcousticPebbleColors.PrimaryContainer.copy(alpha = 0.10f + bassGlow * 0.12f),
                            AcousticPebbleColors.SurfaceContainerHigh.copy(alpha = 0.16f),
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
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        width = 1.dp,
                        color = AcousticPebbleColors.OutlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(20.dp)
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
