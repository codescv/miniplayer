@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.miniplayer.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miniplayer.app.BuildConfig
import com.miniplayer.app.core.feedback.AgentFeedbackHelper
import com.miniplayer.app.core.feedback.AgentFeedbackRequest
import com.miniplayer.app.ui.theme.tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimalist Settings Screen (`DESIGN.md` §4.4) organized into 3 clean groups:
 * 1. `本地媒体库与无线连接` (PC LAN Web Studio entry, launch incremental rescan)
 * 2. `本地缓存` (local cover & waveform cache cleanup)
 * 3. `关于与自动更新` (GitHub Release checker, changelog, SHA-256 + signature verified APK updater,
 *    and local-only Agent Feedback Email & 1-click feedback sender)
 */
@Composable
fun SettingsScreen(
    viewModel: MiniPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val agentFeedbackEmail by viewModel.agentFeedbackEmail.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showLicensesDialog by remember { mutableStateOf(false) }
    var showEmailConfigDialog by remember { mutableStateOf(false) }
    var showFeedbackSheet by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // === Group 1: 本地媒体库与无线连接 ===
            SettingsSectionCard(title = "本地媒体库与无线连接") {
                // Row 1: PC 局域网 Web 管理台
                SettingsClickableRow(
                    title = "PC 局域网 Web 管理台",
                    subtitle = "在同一 Wi-Fi 下用电脑浏览器无线传歌、批量改标签与管理播放列表",
                    trailing = {
                        FilledTonalButton(
                            onClick = { viewModel.setLanStudioVisible(true) },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        ) {
                            Text("打开")
                        }
                    },
                    onClick = { viewModel.setLanStudioVisible(true) },
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
            }

            // === Group 2: 本地缓存 ===
            SettingsSectionCard(title = "本地缓存") {
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

            // === Group 3: 关于与自动更新 ===
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

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // Agent Feedback Email & 1-Click Send Feedback to Agent
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        ) {
                            Text(
                                text = "发送反馈给 Agent",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (agentFeedbackEmail.isBlank()) {
                                    "Agent 反馈邮箱：未设置（仅保存在本机）"
                                } else {
                                    "Agent 反馈邮箱：$agentFeedbackEmail"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(
                            onClick = { showFeedbackSheet = true },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        ) {
                            Text("发送反馈")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showEmailConfigDialog = true }) {
                            Text("Agent 反馈邮箱")
                        }
                        TextButton(onClick = { showLicensesDialog = true }) {
                            Text("开源许可声明")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEmailConfigDialog) {
        AgentEmailConfigDialog(
            initialEmail = agentFeedbackEmail,
            onSaveEmail = { newEmail ->
                val ok = viewModel.saveAgentFeedbackEmail(newEmail)
                if (ok) {
                    showEmailConfigDialog = false
                    viewModel.emitMessage(
                        if (newEmail.isBlank()) "已清空本机 Agent 反馈邮箱" else "已保存 Agent 反馈邮箱至本机",
                    )
                }
                ok
            },
            onDismiss = { showEmailConfigDialog = false },
        )
    }

    if (showFeedbackSheet) {
        AgentFeedbackSheet(
            savedEmail = agentFeedbackEmail,
            onSaveEmail = { email -> viewModel.saveAgentFeedbackEmail(email) },
            onBuildDiagnostics = { attachmentName -> viewModel.buildFeedbackDiagnostics(attachmentName) },
            onEmitMessage = { msg -> viewModel.emitMessage(msg) },
            onDismiss = { showFeedbackSheet = false },
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
private fun AgentEmailConfigDialog(
    initialEmail: String,
    onSaveEmail: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var emailInput by remember(initialEmail) { mutableStateOf(initialEmail) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 反馈邮箱") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "该邮箱仅保存在当前设备的本地配置中，不会上传或内置于应用代码。留空并保存可清除配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        errorText = null
                    },
                    label = { Text("接收反馈的 Agent 邮箱") },
                    placeholder = { Text(AgentFeedbackHelper.EMAIL_PLACEHOLDER) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let { err -> { Text(err) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = AgentFeedbackHelper.normalizeEmail(emailInput)
                    if (trimmed.isNotEmpty() && !AgentFeedbackHelper.isValidEmail(trimmed)) {
                        errorText = "请输入有效的邮箱地址，例如 ${AgentFeedbackHelper.EMAIL_PLACEHOLDER}"
                    } else {
                        onSaveEmail(trimmed)
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AgentFeedbackSheet(
    savedEmail: String,
    onSaveEmail: (String) -> Boolean,
    onBuildDiagnostics: (String?) -> com.miniplayer.app.core.feedback.FeedbackDiagnostics,
    onEmitMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var emailInput by remember(savedEmail) { mutableStateOf(savedEmail) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var summaryInput by remember { mutableStateOf("") }
    var detailsInput by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var stagedAttachment by remember { mutableStateOf<StagedFeedbackAttachment?>(null) }
    var isStagingImage by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            isStagingImage = true
            scope.launch {
                val staged = withContext(Dispatchers.IO) {
                    runCatching { AgentFeedbackLauncher.stageAttachmentFromUri(context, pickedUri) }
                }
                isStagingImage = false
                staged.onSuccess { att ->
                    stagedAttachment?.file?.let { old -> runCatching { old.delete() } }
                    stagedAttachment = att
                }.onFailure { err ->
                    onEmitMessage("读取截图失败：${err.message ?: "请重试"}")
                }
            }
        }
    }

    val subjectPreview = remember(summaryInput, detailsInput) {
        AgentFeedbackHelper.formatSubject(
            summary = summaryInput,
            versionName = BuildConfig.VERSION_NAME,
            detailsFallback = detailsInput,
        )
    }

    val normalizedEmail = AgentFeedbackHelper.normalizeEmail(emailInput)
    val canSaveEmailInline = normalizedEmail != savedEmail &&
        (normalizedEmail.isEmpty() || AgentFeedbackHelper.isValidEmail(normalizedEmail))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "发送反馈给 Agent",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "自动生成 ${AgentFeedbackHelper.PROJECT_ROUTING_PREFIX}: [${AgentFeedbackHelper.normalizeVersionTag(BuildConfig.VERSION_NAME)}] 路由前缀，拉起本机邮箱一键发送。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = emailInput,
                onValueChange = {
                    emailInput = it
                    emailError = null
                },
                label = { Text("Agent 反馈邮箱（仅保存在本机）") },
                placeholder = { Text(AgentFeedbackHelper.EMAIL_PLACEHOLDER) },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { err -> { Text(err) } },
                trailingIcon = if (canSaveEmailInline) {
                    {
                        TextButton(
                            onClick = {
                                if (onSaveEmail(normalizedEmail)) {
                                    onEmitMessage(
                                        if (normalizedEmail.isEmpty()) "已清空 Agent 反馈邮箱" else "已保存 Agent 反馈邮箱",
                                    )
                                }
                            },
                        ) {
                            Text("保存")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = summaryInput,
                onValueChange = { summaryInput = it },
                label = { Text("反馈简述") },
                placeholder = { Text("例如：练习模式希望支持导出循环片段") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = "邮件主题：$subjectPreview",
                        style = MaterialTheme.typography.labelSmall.tnum(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = detailsInput,
                onValueChange = { detailsInput = it },
                label = { Text("详细说明（可选）") },
                placeholder = { Text("补充使用场景、复现步骤或改进建议") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // Optional Screenshot Picker Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !isStagingImage,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            isStagingImage -> "处理中…"
                            stagedAttachment != null -> "更换截图"
                            else -> "添加截图（可选）"
                        },
                    )
                }

                stagedAttachment?.let { att ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = att.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    runCatching { att.file.delete() }
                                    stagedAttachment = null
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "移除截图",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Optional Playback / Practice Diagnostics Switch
            Surface(
                onClick = { includeDiagnostics = !includeDiagnostics },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "附带版本与播放/练习状态信息",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "仅包含版本号、速度、变调与循环时间点，不包含本地目录路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = includeDiagnostics,
                        onCheckedChange = { includeDiagnostics = it },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val targetEmail = AgentFeedbackHelper.normalizeEmail(emailInput)
                        if (!AgentFeedbackHelper.isValidEmail(targetEmail)) {
                            emailError = "请先填写有效的 Agent 反馈邮箱，例如 ${AgentFeedbackHelper.EMAIL_PLACEHOLDER}"
                            return@Button
                        }
                        onSaveEmail(targetEmail)
                        val diagnostics = if (includeDiagnostics || stagedAttachment != null) {
                            onBuildDiagnostics(stagedAttachment?.displayName)
                        } else {
                            null
                        }
                        val payload = AgentFeedbackHelper.buildPayload(
                            AgentFeedbackRequest(
                                recipientEmail = targetEmail,
                                summary = summaryInput,
                                details = detailsInput,
                                versionName = BuildConfig.VERSION_NAME,
                                includeDiagnostics = includeDiagnostics,
                                diagnostics = diagnostics,
                            ),
                        )
                        val launched = AgentFeedbackLauncher.launchFeedbackEmail(
                            context = context,
                            payload = payload,
                            attachment = stagedAttachment,
                        )
                        if (launched) {
                            onDismiss()
                        } else {
                            onEmitMessage("未找到可用的邮件客户端，请先安装邮箱应用")
                        }
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("发送反馈给 Agent")
                }
            }
        }
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
        color = Color.Transparent,
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
        color = Color.Transparent,
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
