package com.example.monoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monoplayer.data.PlayerDisplayConfig
import com.example.monoplayer.data.Playlist
import com.example.monoplayer.data.TrackItem

/**
 * Dialog that allows assigning a single track to ANY number of playlists simultaneously
 * ("一つの曲を複数のプレイリストに追加することも可能").
 */
@Composable
fun MultiPlaylistAssignDialog(
    track: TrackItem,
    playlists: List<Playlist>,
    onCreateNewPlaylist: (String) -> Unit,
    onSaveMembership: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIds = remember(track, playlists) {
        val initial = playlists.filter { track.id in it.trackIds }.map { it.id }
        mutableStateListOf<String>().apply { addAll(initial) }
    }
    var newPlName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0D18),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Column {
                Text("プレイリストへ追加・複数登録", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(track.title, fontSize = 12.sp, color = Color(0xFF38BDF8))
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 380.dp)
            ) {
                Text(
                    text = "登録したいプレイリストにチェックを入れてください（複数選択可）:",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    playlists.forEach { pl ->
                        val checked = pl.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedIds.remove(pl.id)
                                    else selectedIds.add(pl.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    if (isChecked) selectedIds.add(pl.id)
                                    else selectedIds.remove(pl.id)
                                }
                            )
                            Column {
                                Text(pl.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("${pl.trackIds.size} 曲", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Quick inline new playlist creation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newPlName,
                        onValueChange = { newPlName = it },
                        placeholder = { Text("新しいプレイリスト名", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(
                        onClick = {
                            if (newPlName.isNotBlank()) {
                                onCreateNewPlaylist(newPlName)
                                newPlName = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新規作成")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSaveMembership(selectedIds.toSet()) }) {
                Text("保存 (${selectedIds.size}件)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

/**
 * Dialog for customizing visible items on the Now Playing screen.
 * Enforces strict mutual exclusivity (二者択一) between Club Visualizer and Jacket Art.
 */
@Composable
fun DisplayCustomizeDialog(
    config: PlayerDisplayConfig,
    onUpdateConfig: (PlayerDisplayConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isVisualizer = config.showVisualizer

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0D18),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFE2E8F0),
        title = {
            Column {
                Text("CLUB STAGE 表示項目カスタマイズ", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    text = "メインステージ演出（二者択一）と情報パネルの表示設定",
                    fontSize = 11.sp,
                    color = Color(0xFFD4AF37)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "メインステージ表示（二者択一）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Option 1: Club Visualizer
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isVisualizer) Color(0xFF1E1B4B) else Color(0xFF0F172A)
                            )
                            .border(
                                width = if (isVisualizer) 1.5.dp else 1.dp,
                                color = if (isVisualizer) Color(0xFFD4AF37) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onUpdateConfig(config.copy(showVisualizer = true, showJacketArt = false))
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (isVisualizer) Color(0xFFD4AF37) else Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ヴィジュアライザー",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVisualizer) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Option 2: Jacket Art Stage
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (!isVisualizer) Color(0xFF1E1B4B) else Color(0xFF0F172A)
                            )
                            .border(
                                width = if (!isVisualizer) 1.5.dp else 1.dp,
                                color = if (!isVisualizer) Color(0xFFD4AF37) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onUpdateConfig(config.copy(showVisualizer = false, showJacketArt = true))
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = if (!isVisualizer) Color(0xFFD4AF37) else Color(0xFF64748B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ジャケット表示",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isVisualizer) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                CustomizeToggleRow(
                    label = "アーティスト名・アルバム名 (名寄せ表示)",
                    checked = config.showArtistAndAlbum,
                    onCheckedChange = { onUpdateConfig(config.copy(showArtistAndAlbum = it)) }
                )
                CustomizeToggleRow(
                    label = "オーディオ仕様バッジ (Hz / kbps / Mono Ch)",
                    checked = config.showAudioFormatBadge,
                    onCheckedChange = { onUpdateConfig(config.copy(showAudioFormatBadge = it)) }
                )
                CustomizeToggleRow(
                    label = "DSP位相差メトリクス & アルゴリズム切替バー",
                    checked = config.showDspFormulaAndMetrics,
                    onCheckedChange = { onUpdateConfig(config.copy(showDspFormulaAndMetrics = it)) }
                )
                CustomizeToggleRow(
                    label = "GAIN & 10-BAND EQ クイックステータス",
                    checked = config.showInlineQuickEqBar,
                    onCheckedChange = { onUpdateConfig(config.copy(showInlineQuickEqBar = it)) }
                )
                CustomizeToggleRow(
                    label = "上部再生スコープ (Album / Artist / List) バー",
                    checked = config.showScopeAndPlaylistBar,
                    onCheckedChange = { onUpdateConfig(config.copy(showScopeAndPlaylistBar = it)) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37), contentColor = Color(0xFF05070E))
            ) {
                Text("完了", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun CustomizeToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFD4AF37),
                checkedTrackColor = Color(0xFF38BDF8).copy(alpha = 0.35f)
            )
        )
    }
}
