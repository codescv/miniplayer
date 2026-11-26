@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

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
import androidx.compose.material.icons.outlined.Check
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
import com.miniplayer.app.core.i18n.AppLanguage
import com.miniplayer.app.ui.theme.tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimalist Settings Screen (`DESIGN.md` §4.4) organized into clean groups:
 * 1. Language (System Default / 中文 / English / 日本語 — immediate switch without restart)
 * 2. Local Library & Wireless (PC LAN Web Studio entry, launch incremental rescan)
 * 3. Local Cache (local cover & waveform cache cleanup)
 * 4. About & Auto-Update (GitHub Release checker, changelog, SHA-256 + signature verified APK updater,
 *    and a single unified feedback entry that opens [AgentFeedbackSheet])
 */
@Composable
fun SettingsScreen(
    viewModel: MiniPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val settings by viewModel.settings.collectAsState()
    val cacheBytes by viewModel.cacheSizeBytes.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val agentFeedbackEmail by viewModel.agentFeedbackEmail.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showLicensesDialog by remember { mutableStateOf(false) }
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
                title = { Text(s.settings) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = s.back)
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
            // === Group 0: Language (Immediate Switch) ===
            SettingsSectionCard(title = s.settingsLanguageSection) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = s.settingsLanguageTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = s.settingsLanguageSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppLanguage.entries.forEach { option ->
                            val selected = settings.appLanguage == option
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setAppLanguage(option) },
                                label = { Text(s.languageOptionLabel(option)) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                            )
                        }
                    }
                }
            }

            // === Group 1: Local Library & Wireless ===
            SettingsSectionCard(title = s.settingsLibrarySection) {
                // Row 1: PC LAN Web Studio
                SettingsClickableRow(
                    title = s.settingsLanStudioTitle,
                    subtitle = s.settingsLanStudioSubtitle,
                    trailing = {
                        FilledTonalButton(
                            onClick = { viewModel.setLanStudioVisible(true) },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        ) {
                            Text(s.open)
                        }
                    },
                    onClick = { viewModel.setLanStudioVisible(true) },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))

                // Row 2: Auto-rescan on launch
                SettingsSwitchRow(
                    title = s.settingsAutoRescanTitle,
                    subtitle = s.settingsAutoRescanSubtitle,
                    checked = settings.autoRescanOnLaunch,
                    onCheckedChange = { enabled ->
                        viewModel.updateAppSettings { it.copy(autoRescanOnLaunch = enabled) }
                    },
                )
            }

            // === Group 2: Local Cache ===
            SettingsSectionCard(title = s.settingsCacheSection) {
                SettingsClickableRow(
                    title = s.settingsClearCacheTitle,
                    subtitle = s.fmt(s.settingsClearCacheSubtitleTpl, MiniPlayerViewModel.formatByteSize(cacheBytes)),
                    trailing = {
                        FilledTonalButton(
                            onClick = { viewModel.clearLocalCaches() },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        ) {
                            Text(s.clear)
                        }
                    },
                    onClick = { viewModel.clearLocalCaches() },
                )
            }

            // === Group 3: About & Auto-Update ===
            SettingsSectionCard(title = s.settingsAboutSection) {
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
                                text = s.settingsAboutTagline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.checkForAppUpdates() },
                            enabled = updateState !is UiUpdateState.Checking && updateState !is UiUpdateState.Downloading,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(if (updateState is UiUpdateState.Checking) s.settingsCheckingUpdate else s.settingsCheckUpdateBtn)
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
                                            text = s.fmt(
                                                s.settingsNewVersionTpl,
                                                info.latestTag,
                                                MiniPlayerViewModel.formatByteSize(info.apkSizeBytes),
                                            ),
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
                                            onClick = {
                                                viewModel.downloadAndVerifyUpdate(info) { apkFile ->
                                                    viewModel.requestInstallApk(apkFile, context)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                                        ) {
                                            Text(s.settingsDownloadInstallBtn)
                                        }
                                    } else {
                                        Text(
                                            text = s.fmt(s.settingsAlreadyLatestTpl, info.latestTag),
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
                                    text = s.fmt(
                                        s.settingsDownloadingApkTpl,
                                        state.info.apkName,
                                        MiniPlayerViewModel.formatByteSize(state.downloadedBytes),
                                        MiniPlayerViewModel.formatByteSize(state.totalBytes),
                                    ),
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
                                        text = s.fmt(s.settingsVerifiedApkTpl, state.info.latestTag),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Button(
                                        onClick = { viewModel.requestInstallApk(state.apkFile, context) },
                                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                                    ) {
                                        Text(s.settingsInstallNowBtn)
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

                    // Single unified feedback entry (v1.0.14): email config lives inside AgentFeedbackSheet
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
                                text = s.feedbackEntryTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (agentFeedbackEmail.isBlank()) {
                                    s.feedbackEmailUnset
                                } else {
                                    s.fmt(s.feedbackEmailSetTpl, agentFeedbackEmail)
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
                            Text(s.feedbackSendButton)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showLicensesDialog = true }) {
                            Text(s.openSourceLicensesBtn)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
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
            title = { Text(s.openSourceLicensesTitle) },
            text = { Text(s.openSourceLicensesBody) },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text(s.close) }
            },
        )
    }
}

@Composable
private fun AgentFeedbackSheet(
    savedEmail: String,
    onSaveEmail: (String) -> Boolean,
    onBuildDiagnostics: (String?) -> com.miniplayer.app.core.feedback.FeedbackDiagnostics,
    onEmitMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val resolvedLanguage = LocalResolvedLanguage.current
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
                    onEmitMessage(s.fmt(s.feedbackReadImageFailedTpl, err.message ?: s.feedbackRetryFallback))
                }
            }
        }
    }

    val hasAttachment = stagedAttachment != null
    val subjectPreview = remember(summaryInput, detailsInput, hasAttachment, resolvedLanguage) {
        AgentFeedbackHelper.formatSubject(
            summary = summaryInput,
            versionName = BuildConfig.VERSION_NAME,
            detailsFallback = detailsInput,
            hasAttachment = hasAttachment,
            language = resolvedLanguage,
        )
    }

    val normalizedEmail = AgentFeedbackHelper.normalizeEmail(emailInput)
    val canSaveEmailInline = normalizedEmail != savedEmail &&
        (normalizedEmail.isEmpty() || AgentFeedbackHelper.isValidEmail(normalizedEmail))
    val emailValid = AgentFeedbackHelper.isValidEmail(normalizedEmail)
    val emailHint = emailError ?: if (normalizedEmail.isNotEmpty() && !emailValid) {
        s.fmt(s.feedbackEmailInvalidHintTpl, AgentFeedbackHelper.EMAIL_PLACEHOLDER)
    } else {
        null
    }
    val hasContent = AgentFeedbackHelper.hasFeedbackContent(summaryInput, detailsInput, hasAttachment)
    val canSend = !isStagingImage &&
        AgentFeedbackHelper.canSendFeedback(normalizedEmail, summaryInput, detailsInput, hasAttachment)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = s.feedbackEntryTitle,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = s.fmt(
                    s.feedbackSheetSubtitleTpl,
                    AgentFeedbackHelper.PROJECT_ROUTING_PREFIX,
                    AgentFeedbackHelper.normalizeVersionTag(BuildConfig.VERSION_NAME),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = emailInput,
                onValueChange = {
                    emailInput = it
                    emailError = null
                },
                label = { Text(s.feedbackEmailLabel) },
                placeholder = { Text(AgentFeedbackHelper.EMAIL_PLACEHOLDER) },
                singleLine = true,
                isError = emailHint != null,
                supportingText = emailHint?.let { err -> { Text(err) } },
                trailingIcon = if (canSaveEmailInline) {
                    {
                        TextButton(
                            onClick = {
                                if (onSaveEmail(normalizedEmail)) {
                                    onEmitMessage(
                                        if (normalizedEmail.isEmpty()) s.feedbackEmailCleared else s.feedbackEmailSaved,
                                    )
                                }
                            },
                        ) {
                            Text(s.save)
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
                label = { Text(s.feedbackTitleLabel) },
                placeholder = { Text(s.feedbackTitlePlaceholder) },
                singleLine = true,
                supportingText = {
                    Text(
                        text = s.fmt(s.feedbackEmailSubjectPreviewTpl, subjectPreview),
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
                label = { Text(s.feedbackDetailsLabel) },
                placeholder = { Text(s.feedbackDetailsPlaceholder) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // Add Image (Android Photo Picker -> FileProvider EXTRA_STREAM attachment)
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
                            isStagingImage -> s.feedbackStagingImage
                            stagedAttachment != null -> s.feedbackChangeImage
                            else -> s.feedbackAddImage
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
                                    contentDescription = s.feedbackRemoveImageDesc,
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
                            text = s.feedbackIncludeDiagnosticsTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = s.feedbackIncludeDiagnosticsSubtitle,
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

            val sendBlockedHint = when {
                normalizedEmail.isEmpty() -> s.feedbackHintMissingEmail
                !hasContent -> s.feedbackHintMissingContent
                else -> null
            }
            if (sendBlockedHint != null) {
                Text(
                    text = sendBlockedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    Text(s.cancel)
                }
                Button(
                    onClick = {
                        val targetEmail = AgentFeedbackHelper.normalizeEmail(emailInput)
                        if (!AgentFeedbackHelper.isValidEmail(targetEmail)) {
                            emailError = s.fmt(s.feedbackEmailRequiredErrorTpl, AgentFeedbackHelper.EMAIL_PLACEHOLDER)
                            return@Button
                        }
                        if (!AgentFeedbackHelper.hasFeedbackContent(summaryInput, detailsInput, stagedAttachment != null)) {
                            onEmitMessage(s.feedbackHintMissingContent)
                            return@Button
                        }
                        onSaveEmail(targetEmail)
                        val diagnostics = if (includeDiagnostics || stagedAttachment != null) {
                            onBuildDiagnostics(stagedAttachment?.displayName)
                        } else {
                            null
                        }
                        val payload = AgentFeedbackHelper.buildPayload(
                            request = AgentFeedbackRequest(
                                recipientEmail = targetEmail,
                                summary = summaryInput,
                                details = detailsInput,
                                versionName = BuildConfig.VERSION_NAME,
                                includeDiagnostics = includeDiagnostics,
                                diagnostics = diagnostics,
                                hasAttachment = stagedAttachment != null,
                            ),
                            language = resolvedLanguage,
                        )
                        val launched = AgentFeedbackLauncher.launchFeedbackEmail(
                            context = context,
                            payload = payload,
                            attachment = stagedAttachment,
                        )
                        if (launched) {
                            onDismiss()
                        } else {
                            onEmitMessage(s.feedbackNoMailClient)
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(s.feedbackEntryTitle)
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
