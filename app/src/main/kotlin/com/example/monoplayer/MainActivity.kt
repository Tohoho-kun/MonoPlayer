package com.example.monoplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.monoplayer.data.TrackItem
import com.example.monoplayer.dsp.DspStateHolder
import com.example.monoplayer.dsp.MonoAlgorithm
import com.example.monoplayer.dsp.OutputStageMode
import com.example.monoplayer.ui.*
import com.example.monoplayer.ui.theme.MonoPlayerTheme

enum class MainNavTab(val label: String, val icon: ImageVector) {
    PLAYER("再生", Icons.Default.Album),
    QUEUE("キュー", Icons.AutoMirrored.Filled.QueueMusic),
    LIBRARY("ライブラリ", Icons.Default.LibraryMusic),
    DSP_EQ("DSP・EQ", Icons.Default.GraphicEq),
    SETTINGS("設定", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonoPlayerTheme(darkTheme = true, dynamicColor = false) {
                MonoPlayerAppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonoPlayerAppRoot(
    viewModel: MonoPlayerViewModel = viewModel()
) {
    var currentTab by remember { mutableStateOf(MainNavTab.PLAYER) }
    var trackForPlaylistDialog by remember { mutableStateOf<TrackItem?>(null) }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val playlists by viewModel.playlists.collectAsState()
    val displayConfig by viewModel.displayConfig.collectAsState()
    val statusBanner by viewModel.statusBannerMessage.collectAsState()
    val activeAlgo by DspStateHolder.algoFlow.collectAsState()
    val spatialConfig by DspStateHolder.spatialConfigFlow.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    // SAF Directory Picker Launcher for Folder Exploration
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.addSafFolderUri(uri)
        }
    }

    // Audio Storage & Bluetooth Connect Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (audioGranted) {
            viewModel.rescanLibrary()
        }
    }

    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        containerColor = Color(0xFF040408),
        topBar = {
            // Hide app name and header completely on the Player screen
            if (currentTab != MainNavTab.PLAYER) {
                Surface(
                    color = Color(0xFF080A14),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentTab.label,
                            color = Color(0xFFF4F1EA),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val stageSuffix = when {
                            activeAlgo == MonoAlgorithm.STEREO_REF -> ""
                            spatialConfig.stageMode == OutputStageMode.NARROW_STEREO -> " • NARROW ${spatialConfig.narrowWidthPercent.toInt()}%"
                            spatialConfig.depthEnabled -> " • Z-DEPTH"
                            else -> ""
                        }
                        Text(
                            text = "${activeAlgo.badge}$stageSuffix",
                            color = Color(0xFFC8A24A),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF06060D),
                contentColor = Color.White
            ) {
                MainNavTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFC8A24A),
                            selectedTextColor = Color(0xFFC8A24A),
                            indicatorColor = Color(0xFF1A1634),
                            unselectedIconColor = Color(0xFF5D6782),
                            unselectedTextColor = Color(0xFF5D6782)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainNavTab.PLAYER -> NowPlayingScreen(
                    viewModel = viewModel,
                    onOpenPlaylistAssignDialog = { trackForPlaylistDialog = it },
                    onOpenQueue = { currentTab = MainNavTab.QUEUE }
                )
                MainNavTab.QUEUE -> QueueScreen(
                    viewModel = viewModel,
                    onOpenPlaylistAssignDialog = { trackForPlaylistDialog = it },
                    onOpenPlayerTab = { currentTab = MainNavTab.PLAYER }
                )
                MainNavTab.DSP_EQ -> DspAndEqScreen(
                    viewModel = viewModel
                )
                MainNavTab.LIBRARY -> LibraryScreen(
                    viewModel = viewModel,
                    onRequestAddFolder = { folderPickerLauncher.launch(null) },
                    onOpenPlaylistAssignDialog = { trackForPlaylistDialog = it },
                    onOpenPlayerTab = { currentTab = MainNavTab.PLAYER }
                )
                MainNavTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
            }

            // Floating Status Toast Banner
            AnimatedVisibility(
                visible = statusBanner != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            ) {
                Surface(
                    color = Color(0xFF0284C7),
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = statusBanner ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    // Multi-Playlist Assign Dialog (1 track -> multiple playlists)
    trackForPlaylistDialog?.let { targetTrack ->
        MultiPlaylistAssignDialog(
            track = targetTrack,
            playlists = playlists,
            onCreateNewPlaylist = { newName ->
                viewModel.createPlaylist(newName, initialTrackIds = listOf(targetTrack.id))
            },
            onSaveMembership = { selectedIds ->
                viewModel.setTrackPlaylistsMembership(targetTrack.id, selectedIds)
                trackForPlaylistDialog = null
            },
            onDismiss = { trackForPlaylistDialog = null }
        )
    }

    // Display Item Customization Dialog
    if (showCustomizeDialog) {
        DisplayCustomizeDialog(
            config = displayConfig,
            onUpdateConfig = { viewModel.updateDisplayConfig(it) },
            onDismiss = { showCustomizeDialog = false }
        )
    }
}
