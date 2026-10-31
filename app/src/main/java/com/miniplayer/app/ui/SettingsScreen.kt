@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miniplayer.app.BuildConfig
import com.miniplayer.app.core.model.SafRoot
import com.miniplayer.app.ui.theme.tnum

/**
 * Strict 8-Row Minimalist Settings Screen (`DESIGN.md` §4.4) organized into 4 clean groups,
 * including Row 8 `关于与自动更新` (GitHub Release checker, changelog, SHA-256 verified APK
 * downloader, 1-tap installer, and PC LAN Web Studio entry).
 */
@Composable
fun SettingsScreen(
    viewModel: MiniPlayerViewModel,
    onAddFolderClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val safRoots by viewModel.safRoots.collectAsState()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val context = LocalContext.current

    var showFoldersManagerSheet by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // === Group 1: 本地媒体库与无线连接 (Rows 1–3) ===
            SettingsSectionCard(title = "本地媒体库与无线连接") {
                // Row 1: 管理已添加的文件夹
                SettingsClickableRow(
                    title = "管理已添加的文件夹 (${safRoots.size} 个目录)",
                    subtitle = "仅扫描授权目录 · 支持 💻 PC 局域网 Web 管理台无线传歌",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { viewModel.setLanStudioVisible(true) },
                                modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            ) {
                                Text("💻 Web管理台")
                            }
                            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                        }
                    },
                    onClick = { showFoldersManagerSheet = true },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))

                // Row 2: 启动时后台增量刷新已添加目录
                SettingsSwitchRow(
                    title = "启动时后台增量刷新已添加目录",
                    subtitle = "游标级快速跳过未变更文件，未挂载 OTG 自动保留书签",
                    checked = settings.autoRescanOnLaunch,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(autoRescanOnLaunch = enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))

                // Row 3: 遵循子目录 .nomedia 跳过扫描
                SettingsSwitchRow(
                    title = "遵循子目录 .nomedia 跳过扫描",
                    subtitle = "显式添加的根目录始终正常扫描；开启后跳过含 .nomedia 的下级子目录",
                    checked = settings.respectSubfolderNomedia,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(respectSubfolderNomedia = enabled) }
                    },
                )
            }

            // === Group 2: 标签与联网补全 (Rows 4–5) ===
            SettingsSectionCard(title = "标签与联网补全") {
                // Row 4: 保存标签时尝试同时写入原文件
                SettingsSwitchRow(
                    title = "保存标签时尝试同时写入原文件",
                    subtitle = "在本地覆盖层之外，将 ID3v2 / Vorbis / MP4 标签原子级写回原音频",
                    checked = settings.writeTagsToFileDefault,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(writeTagsToFileDefault = enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))

                // Row 5: 清理本地封面与波形缓存
                SettingsClickableRow(
                    title = "清理本地封面与波形缓存",
                    subtitle = "当前占用 ${MiniPlayerViewModel.formatByteSize(cacheBytes)}（不删除音乐文件与练习书签）",
                    trailing = {
                        FilledTonalButton(
                            onClick = { viewModel.clearLocalCaches() },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        ) {
                            Text("清理")
                        }
                    },
                    onClick = { viewModel.clearLocalCaches() },
                )
            }

            // === Group 3: 练习模式默认偏好 (Rows 6–7) ===
            SettingsSectionCard(title = "练习模式默认偏好") {
                // Row 6: 循环预备倒计时提示音 (木鱼轻击声)
                SettingsSwitchRow(
                    title = "循环预备倒计时提示音 (木鱼轻击声)",
                    subtitle = "静音环境关闭后仅保留视觉拍点倒计时环",
                    checked = settings.countInClickSound,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(countInClickSound = enabled) }
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))

                // Row 7: 练习模式下保持屏幕常亮
                SettingsSwitchRow(
                    title = "练习模式下保持屏幕常亮",
                    subtitle = "双手持乐器跟练或看谱时不自动暗屏锁屏",
                    checked = settings.keepScreenOnInPractice,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(keepScreenOnInPractice = enabled) }
                    },
                )
            }

            // === Group 4: 关于与自动更新 (Row 8) ===
            SettingsSectionCard(title = "关于与自动更新") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MiniPlayer v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleMedium.tnum(),
                            )
                            Text(
                                text = "纯本地受控扫描 · Material You · GitHub 安全签名校验更新",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.checkForAppUpdates() },
                            enabled = updateState !is UiUpdateState.Checking && updateState !is UiUpdateState.Downloading,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(if (updateState is UiUpdateState.Checking) "检查中…" else "检查 GitHub 更新")
                        }
                    }

                    // Inline Auto-Update Status & Changelog Card
                    when (val state = updateState) {
                        is UiUpdateState.Idle -> Unit
                        is UiUpdateState.Checking -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is UiUpdateState.Checked -> {
                            val info = state.info
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (info.updateAvailable) {
                                        Text(
                                            text = "发现新版本 ${info.latestTag} (${MiniPlayerViewModel.formatByteSize(info.apkSizeBytes)})",
                                            style = MaterialTheme.typography.titleMedium.tnum(),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (info.releaseNotes.isNotBlank()) {
                                            Text(
                                                text = info.releaseNotes,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                        Button(
                                            onClick = { viewModel.downloadAndVerifyUpdate(info) },
                                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                                        ) {
                                            Text("下载并校验更新 (SHA-256 + 签名校验)")
                                        }
                                    } else {
                                        Text(
                                            text = "当前已是最新版本 (${info.latestTag})",
                                            style = MaterialTheme.typography.bodyMedium.tnum(),
                                        )
                                    }
                                }
                            }
                        }
                        is UiUpdateState.Downloading -> {
                            val frac = if (state.totalBytes > 0L) {
                                (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "正在下载 ${state.info.apkName}：${MiniPlayerViewModel.formatByteSize(state.downloadedBytes)} / ${MiniPlayerViewModel.formatByteSize(state.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium.tnum(),
                                )
                            }
                        }
                        is UiUpdateState.ReadyToInstall -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "✓ ${state.info.latestTag} 已通过 SHA-256 与签名证书校验",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Button(
                                        onClick = {
                                            val intent = viewModel.buildInstallOrPermissionIntent(state.apkFile)
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                                    ) {
                                        Text("立即安装更新")
                                    }
                                }
                            }
                        }
                        is UiUpdateState.Error -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { showLicensesDialog = true }) {
                            Text("开源许可声明")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showFoldersManagerSheet) {
        SafRootsManagerSheet(
            roots = safRoots,
            onAddFolder = {
                showFoldersManagerSheet = false
                onAddFolderClick()
            },
            onRescanRoot = { viewModel.rescanRoot(it.rootId) },
            onRemoveRoot = { viewModel.removeSafRoot(it.rootId) },
            onDismiss = { showFoldersManagerSheet = false },
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "MiniPlayer 基于 Apache License 2.0 协议构建，使用 Android Jetpack Compose Material 3、AndroidX Media3 (ExoPlayer) 及开放免密钥数据库 MusicBrainz / iTunes Search / LRCLIB。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsClickableRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

@Composable
private fun SafRootsManagerSheet(
    roots: List<SafRoot>,
    onAddFolder: () -> Unit,
    onRescanRoot: (SafRoot) -> Unit,
    onRemoveRoot: (SafRoot) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已授权的本地目录 (${roots.size})", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = onAddFolder,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("+ 添加文件夹")
                }
            }
            if (roots.isEmpty()) {
                Text(
                    text = "暂未添加任何本地目录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                roots.forEach { root ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(root.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${root.itemCount} 首媒体 · ${root.mountState.name}",
                                    style = MaterialTheme.typography.labelMedium.tnum(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRescanRoot(root) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
                            }
                            IconButton(onClick = { onRemoveRoot(root) }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "移除目录",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
