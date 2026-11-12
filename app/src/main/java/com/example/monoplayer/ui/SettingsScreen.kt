package com.example.monoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.PlayerDisplayConfig
import com.example.monoplayer.data.VisualizerStyle
import com.example.monoplayer.dsp.DspStateHolder

@Composable
fun SettingsScreen(
    viewModel: MonoPlayerViewModel
) {
    val displayConfig by viewModel.displayConfig.collectAsState()
    val visualizerStyle by viewModel.visualizerStyle.collectAsState()
    val crossfadeSec by viewModel.crossfadeSec.collectAsState()
    val autoPlayOnBt by viewModel.autoPlayOnBtConnect.collectAsState()
    val syncOffsetMs by DspStateHolder.syncOffsetMsFlow.collectAsState()

    val autoLeadMs = DspStateHolder.syncBuffer.autoBufferLeadMs
    val totalDelayMs = DspStateHolder.syncBuffer.getTotalDelayMs()

    // 折りたたみ状態（初期状態では誤操作防止・画面圧縮のため閉じる）
    var isVisualizerGalleryExpanded by remember { mutableStateOf(false) }
    var isSyncSliderExpanded by remember { mutableStateOf(false) }
    var isCrossfadeSliderExpanded by remember { mutableStateOf(false) }

    // ヘルプダイアログ表示用
    var activeHelpItem by remember { mutableStateOf<SettingHelpItem?>(null) }

    val gold = Color(0xFFC8A24A)
    val cardBg = Color(0xFF0C1022)
    val cardBorder = Color(0xFF1F2A4A)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05060D))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Hero Stage Exclusive Selector (二者択一: Visualizer vs Jacket)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "再生画面メインステージ表示",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        activeHelpItem = SettingHelpItem(
                            title = "メインステージ表示 (二者択一)",
                            description = "楽曲再生中の画面中央に、3Dビジュアライザーまたは高品位ジャケット額装のどちらを優先表示するかを選択します。Now Playing 画面右上のアイコンからもワンタップで切り替え可能です。"
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "ヘルプ",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageChoiceButton(
                    title = "ビジュアライザー表示",
                    subtitle = "雨窓ガラス・全8種",
                    selected = displayConfig.showVisualizer,
                    accent = gold,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setHeroStageVisualizer(true) }
                )
                StageChoiceButton(
                    title = "ジャケット表示",
                    subtitle = "高級アートワーク額装",
                    selected = !displayConfig.showVisualizer,
                    accent = gold,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setHeroStageVisualizer(false) }
                )
            }
        }

        // 2. Visualizer Style Selector (選択中スタイル表示 + 折りたたみギャラリー)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ビジュアライザー・デザイン",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            activeHelpItem = SettingHelpItem(
                                title = "ビジュアライザー・デザイン",
                                description = "雨粒と結露ガラス越しに透けるジャケット屈折エフェクトをはじめ、高級クラブ演出、90年代バブルコンポVFD、3色LEDメーター、2000年初期WMP7再現モードなど全8種類のテーマから選択できます。"
                            )
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "ヘルプ",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                TextButton(
                    onClick = { isVisualizerGalleryExpanded = !isVisualizerGalleryExpanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isVisualizerGalleryExpanded) "閉じる" else "変更する",
                        color = gold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = if (isVisualizerGalleryExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = gold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 現在選択されているスタイルのコンパクト表示
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141A32))
                    .border(1.dp, gold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = visualizerStyle.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = visualizerStyle.eraTag,
                        color = gold,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "適用中",
                    tint = gold,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 全8種のギャラリーリスト（展開時のみ表示）
            AnimatedVisibility(
                visible = isVisualizerGalleryExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    VisualizerStyle.entries.forEach { candidate ->
                        val selected = visualizerStyle == candidate
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color(0xFF1A1533) else Color(0xFF070913))
                                .border(1.dp, if (selected) gold else cardBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setVisualizerStyle(candidate)
                                    if (!displayConfig.showVisualizer) viewModel.setHeroStageVisualizer(true)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = candidate.label,
                                    color = if (selected) gold else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = candidate.eraTag,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = gold,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Audio Sync & Crossfade (スライダー折りたたみ格納・誤操作防止)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "オーディオ同期 ＆ クロスフェード",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        activeHelpItem = SettingHelpItem(
                            title = "バッファ同期 ＆ クロスフェード",
                            description = "【自動同期】スピーカーやBluetooth機器のオーディオバッファ遅延（約${autoLeadMs}ms）を自動測定し、ビジュアライザー描画と発音の瞬間を完全一致させています。\n\n【タイミング微調整】車載外部DSPや高遅延Bluetooth機器で映像と音がずれる場合のみ微調整してください。\n\n【クロスフェード】曲末尾と次曲の頭を滑らかにフェード接続します。"
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "ヘルプ",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // タイミング微調整 (折りたたみ式)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF070913))
                    .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isSyncSliderExpanded = !isSyncSliderExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "描画・音声同期微調整",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "自動補正: ${autoLeadMs} ms • 現在値: ${"%+d".format(syncOffsetMs)} ms (合計 ${totalDelayMs} ms)",
                            color = gold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(
                        onClick = { isSyncSliderExpanded = !isSyncSliderExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isSyncSliderExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.Tune,
                            contentDescription = "調整スライダーを開閉",
                            tint = gold,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isSyncSliderExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Slider(
                            value = syncOffsetMs.toFloat(),
                            onValueChange = { DspStateHolder.setManualSyncOffsetMs(it.toInt()) },
                            valueRange = -200f..600f,
                            colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
                        )
                    }
                }
            }

            // クロスフェード (折りたたみ式)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF070913))
                    .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isCrossfadeSliderExpanded = !isCrossfadeSliderExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "曲間クロスフェード時間",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (crossfadeSec <= 0.1f) "設定: OFF" else "設定: ${"%.1f".format(crossfadeSec)} 秒",
                            color = gold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(
                        onClick = { isCrossfadeSliderExpanded = !isCrossfadeSliderExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isCrossfadeSliderExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.Tune,
                            contentDescription = "調整スライダーを開閉",
                            tint = gold,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isCrossfadeSliderExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Slider(
                            value = crossfadeSec,
                            onValueChange = { viewModel.setCrossfadeSeconds(it) },
                            valueRange = 0f..12f,
                            colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
                        )
                    }
                }
            }

            HorizontalDivider(color = cardBorder, modifier = Modifier.padding(vertical = 2.dp))

            // Bluetooth 自動再生優先権
            SettingSwitchRowWithHelp(
                label = "Bluetooth接続時の自動再生＆優先権",
                checked = autoPlayOnBt,
                onHelp = {
                    activeHelpItem = SettingHelpItem(
                        title = "Bluetooth自動再生＆優先権",
                        description = "ONにすると、直前の再生曲・位置・キューをMediaSessionに常時ウォーム保持します。車載機やBluetoothイヤホン接続時に他の音楽・動画アプリに優先権を奪われず、スムーズに自動再生を再開します。"
                    )
                },
                onToggle = { viewModel.setAutoPlayOnBtConnect(it) }
            )
        }

        // 4. Player Screen Sub-Elements (シンプル化設定)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "再生画面の表示項目",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        activeHelpItem = SettingHelpItem(
                            title = "再生画面の表示項目",
                            description = "再生画面をよりシンプルで落ち着いた外観に保つため、アーティスト名・アルバム名、再生スコープ（プレイリスト名等）、ハイレゾ周波数/ビットレートバッジの表示・非表示を個別に設定できます。"
                        )
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "ヘルプ",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            SettingSwitchRow("アーティスト名・アルバム名を表示", displayConfig.showArtistAndAlbum) {
                viewModel.updateDisplayConfig(displayConfig.copy(showArtistAndAlbum = it))
            }
            SettingSwitchRow("再生スコープ名を上部に控えめ表示", displayConfig.showScopeAndPlaylistBar) {
                viewModel.updateDisplayConfig(displayConfig.copy(showScopeAndPlaylistBar = it))
            }
            SettingSwitchRow("サンプリング周波数・ビットレート表示", displayConfig.showAudioFormatBadge) {
                viewModel.updateDisplayConfig(displayConfig.copy(showAudioFormatBadge = it))
            }
        }
    }

    // 共通ヘルプダイアログ
    activeHelpItem?.let { item ->
        AlertDialog(
            onDismissRequest = { activeHelpItem = null },
            title = {
                Text(
                    text = item.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = item.description,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                TextButton(onClick = { activeHelpItem = null }) {
                    Text("閉じる", color = gold, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF14192F),
            shape = RoundedCornerShape(14.dp)
        )
    }
}

private data class SettingHelpItem(
    val title: String,
    val description: String
)

@Composable
private fun StageChoiceButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF1A1533) else Color(0xFF070913))
            .border(1.4.dp, if (selected) accent else Color(0xFF1F2A4A), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, color = if (selected) accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color(0xFF94A3B8), fontSize = 10.sp)
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFFCBD5E1), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFC8A24A))
        )
    }
}

@Composable
private fun SettingSwitchRowWithHelp(
    label: String,
    checked: Boolean,
    onHelp: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(label, color = Color(0xFFCBD5E1), fontSize = 12.sp)
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onHelp, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "ヘルプ",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFC8A24A))
        )
    }
}
