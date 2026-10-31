@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miniplayer.app.lan.LanStudioManager
import com.miniplayer.app.lan.LanStudioSheet
import com.miniplayer.app.ui.*
import com.miniplayer.app.ui.theme.MiniPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MiniPlayerTheme {
                val vm: MiniPlayerViewModel = viewModel()
                val lanController = remember(vm) {
                    LanStudioManager.get(
                        context = this@MainActivity,
                        coreProvider = { vm.core },
                    ).also { controller ->
                        controller.currentlyPlayingItemIdProvider = {
                            vm.playbackSnapshot.value.currentItem?.itemId
                        }
                    }
                }
                KeepScreenOnEffect(activity = this, viewModel = vm)
                BackgroundVideoAudioContinuationEffect(viewModel = vm)
                MiniPlayerAppShell(
                    viewModel = vm,
                    lanStudioSheetSlot = { onDismiss ->
                        LanStudioSheet(
                            onDismiss = onDismiss,
                            controller = lanController,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Holds `FLAG_KEEP_SCREEN_ON` whenever playing video OR while Practice Mode is active with
 * `keepScreenOnInPractice == true` (`DESIGN.md` §5.3 #3).
 */
@Composable
private fun KeepScreenOnEffect(
    activity: ComponentActivity,
    viewModel: MiniPlayerViewModel,
) {
    val snapshot by viewModel.playbackSnapshot.collectAsState()
    val practiceActive by viewModel.isPracticeModeActive.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val shouldKeepOn = (snapshot.isPlaying && snapshot.currentItem?.isVideo == true) ||
        (practiceActive && snapshot.currentItem != null && settings.keepScreenOnInPractice)

    DisposableEffect(shouldKeepOn) {
        if (shouldKeepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Unbinds video `TextureView` on `ON_STOP` without pausing ExoPlayer so video lessons/concerts
 * continue seamless audio-only playback when screen turns off, and re-binds on `ON_START`.
 */
@Composable
private fun BackgroundVideoAudioContinuationEffect(viewModel: MiniPlayerViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.playerController.onUiStarted()
                Lifecycle.Event.ON_STOP -> viewModel.playerController.onUiStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun MiniPlayerAppShell(
    viewModel: MiniPlayerViewModel,
    lanStudioSheetSlot: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val snapshot by viewModel.playbackSnapshot.collectAsState()
    val practiceModeActive by viewModel.isPracticeModeActive.collectAsState()
    val practiceState by viewModel.practiceState.collectAsState()
    val countInProgress by viewModel.countInProgress.collectAsState()
    val showNowPlaying by viewModel.showNowPlaying.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val showLanStudio by viewModel.showLanStudio.collectAsState()
    val playlistPickerItems by viewModel.playlistPickerTargetItems.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val metadataTargets by viewModel.metadataEditTargets.collectAsState()
    val onlineCandidates by viewModel.onlineCandidates.collectAsState()
    val isSearchingCandidates by viewModel.isSearchingCandidates.collectAsState()
    val batchPreview by viewModel.batchMatchPreview.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val isCommittingBatch by viewModel.isCommittingBatch.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var pendingExportPlaylistId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.hapticTicks.collect {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // SAF Directory Picker (`ACTION_OPEN_DOCUMENT_TREE`)
    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            viewModel.handlePickedSafTreeUri(treeUri)
        }
    }

    // M3U / M3U8 Import Picker
    val importM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importM3uPlaylist(uri)
        }
    }

    // M3U8 Export Document Creator
    val exportM3uLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri: Uri? ->
        val plId = pendingExportPlaylistId
        pendingExportPlaylistId = null
        if (uri != null && plId != null) {
            viewModel.exportM3uPlaylist(plId, uri)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showSettings) {
            SettingsScreen(
                viewModel = viewModel,
                onAddFolderClick = { openTreeLauncher.launch(null) },
                onBack = { viewModel.setSettingsVisible(false) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Docked Persistent MiniPlayer Bar right above the 2-Tab NavigationBar (§3.2 & §4.3)
                        if (snapshot.currentItem != null) {
                            MiniPlayerBar(
                                snapshot = snapshot,
                                practiceModeActive = practiceModeActive,
                                practiceState = practiceState,
                                isCountingIn = countInProgress != null,
                                onExpand = { viewModel.setNowPlayingVisible(true) },
                                onTogglePlayPause = { viewModel.playerController.togglePlayPause() },
                                onNext = { viewModel.playerController.skipToNext() },
                            )
                        }

                        // Strict 2 Top-Level Bottom Tabs: `媒体库` & `播放列表` (§3.2)
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == MainTab.LIBRARY,
                                onClick = { viewModel.selectTab(MainTab.LIBRARY) },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == MainTab.LIBRARY) {
                                            Icons.Filled.LibraryMusic
                                        } else {
                                            Icons.Outlined.LibraryMusic
                                        },
                                        contentDescription = "媒体库",
                                    )
                                },
                                label = { Text("媒体库") },
                            )
                            NavigationBarItem(
                                selected = selectedTab == MainTab.PLAYLISTS,
                                onClick = { viewModel.selectTab(MainTab.PLAYLISTS) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                                        contentDescription = "播放列表",
                                    )
                                },
                                label = { Text("播放列表") },
                            )
                        }
                    }
                },
            ) { rootPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = rootPadding.calculateBottomPadding()),
                ) {
                    when (selectedTab) {
                        MainTab.LIBRARY -> {
                            LibraryScreen(
                                viewModel = viewModel,
                                onAddFolderClick = { openTreeLauncher.launch(null) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        MainTab.PLAYLISTS -> {
                            PlaylistsScreen(
                                viewModel = viewModel,
                                onImportM3uClick = {
                                    importM3uLauncher.launch(
                                        arrayOf(
                                            "audio/x-mpegurl",
                                            "application/vnd.apple.mpegurl",
                                            "audio/mpegurl",
                                            "text/plain",
                                            "*/*",
                                        ),
                                    )
                                },
                                onExportM3uRequest = { playlistId, playlistName ->
                                    pendingExportPlaylistId = playlistId
                                    exportM3uLauncher.launch("${playlistName.ifBlank { "playlist" }}.m3u8")
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        // Full-Screen Unified Audio/Video Now Playing Overlay
        if (showNowPlaying && snapshot.currentItem != null) {
            NowPlayingSheet(
                viewModel = viewModel,
                onDismiss = { viewModel.setNowPlayingVisible(false) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Add-to-Playlist (`[+ 列表]`) Bottom Sheet
        if (playlistPickerItems.isNotEmpty()) {
            AddToPlaylistBottomSheet(
                targetItems = playlistPickerItems,
                playlists = playlists,
                onSelectPlaylist = { plId ->
                    viewModel.addItemsToPlaylist(plId, playlistPickerItems.map { it.itemId })
                },
                onCreateAndAdd = { name, isPractice ->
                    viewModel.createPlaylist(name, isPractice, playlistPickerItems.map { it.itemId })
                    viewModel.closeAddToPlaylistPicker()
                },
                onDismiss = { viewModel.closeAddToPlaylistPicker() },
            )
        }

        // Simple 6-Field Metadata Editor & Online Match Candidate Sheet
        if (metadataTargets.isNotEmpty()) {
            MetadataEditorSheet(
                targets = metadataTargets,
                defaultWriteBackToFile = settings.writeTagsToFileDefault,
                candidates = onlineCandidates,
                isSearchingOnline = isSearchingCandidates,
                onSearchOnline = { item, titleQ, artistQ ->
                    viewModel.searchSingleTrackOnlineCandidates(item, titleQ, artistQ)
                },
                onApplyCandidateDirectly = { item, cand, preserveBacking, writeBack ->
                    viewModel.applySingleCandidateDirectly(item, cand, preserveBacking, writeBack)
                },
                onSaveDraft = { draft -> viewModel.saveMetadataDraft(draft) },
                onResetOverrides = { items -> viewModel.resetMetadataOverrides(items) },
                onDismiss = { viewModel.closeMetadataEditor() },
            )
        }

        // Batch Folder / `未知专辑` Online Auto-Completion Sheet
        if (batchPreview != null || batchProgress != null) {
            BatchMetadataCompletionSheet(
                previewItems = batchPreview,
                progress = batchProgress,
                isCommitting = isCommittingBatch,
                defaultWriteBack = settings.writeTagsToFileDefault,
                onSelectCandidate = { idx, candIdx -> viewModel.updateBatchMatchSelection(idx, candIdx) },
                onTogglePreserveBacking = { idx -> viewModel.toggleBatchPreserveBacking(idx) },
                onCommit = { writeBack -> viewModel.commitBatchAutoCompletion(writeBack) },
                onDismiss = { viewModel.dismissBatchAutoCompletion() },
            )
        }

        // PC LAN Web Studio Sheet (`💻` button in LibraryScreen TopAppBar & SettingsScreen)
        if (showLanStudio) {
            if (lanStudioSheetSlot != null) {
                lanStudioSheetSlot { viewModel.setLanStudioVisible(false) }
            } else {
                LanStudioSheet(
                    onDismiss = { viewModel.setLanStudioVisible(false) },
                )
            }
        }
    }
}
