package com.miniplayer.app.lan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miniplayer.app.core.CoreRegistry
import com.miniplayer.app.core.MiniPlayerCoreProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import kotlin.math.floor

data class LanEndpoint(
    val interfaceName: String,
    val ipv4: String,
    val categoryLabel: String,
    val priority: Int,
)

class LanAddressDetector(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun currentEndpoints(): List<LanEndpoint> {
        val rawIfaces = runCatching {
            val en = NetworkInterface.getNetworkInterfaces()
            if (en != null) Collections.list(en) else emptyList()
        }.getOrDefault(emptyList())

        val result = ArrayList<LanEndpoint>()
        for (nif in rawIfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(false)
            val virtual = runCatching { nif.isVirtual }.getOrDefault(false)
            val ptp = runCatching { nif.isPointToPoint }.getOrDefault(false)
            if (!up || loopback || virtual || ptp) continue

            val name = nif.name ?: continue
            if (!isAllowedLocalInterface(name)) continue

            val addrs = runCatching { Collections.list(nif.inetAddresses) }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                if (!LanStudioServer.isAllowedLanAddress(addr)) continue
                val ip = addr.hostAddress ?: continue
                val (prio, label) = classifyInterface(name.lowercase(Locale.ROOT), addr.isSiteLocalAddress)
                result.add(
                    LanEndpoint(
                        interfaceName = name,
                        ipv4 = ip,
                        categoryLabel = label,
                        priority = prio,
                    ),
                )
            }
        }
        return result
            .distinctBy { it.ipv4 }
            .sortedWith(compareBy<LanEndpoint> { it.priority }.thenBy { it.interfaceName }.thenBy { it.ipv4 })
    }

    fun observeEndpoints(): Flow<List<LanEndpoint>> = callbackFlow {
        trySend(currentEndpoints())
        val cm = connectivityManager
        if (cm == null) {
            awaitClose {}
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentEndpoints())
            }

            override fun onLost(network: Network) {
                trySend(currentEndpoints())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentEndpoints())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(currentEndpoints())
            }
        }
        val registered = runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            true
        }.getOrDefault(false)

        awaitClose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun classifyInterface(lowerName: String, isSiteLocal: Boolean): Pair<Int, String> = when {
        lowerName.startsWith("wlan") && isSiteLocal -> 0 to "Wi-Fi"
        lowerName.startsWith("wlan") -> 1 to "Wi-Fi"
        lowerName.startsWith("ap") || lowerName.startsWith("swlan") || lowerName.startsWith("softap") -> 2 to "热点"
        lowerName.startsWith("rndis") || lowerName.startsWith("usb") || lowerName.startsWith("ncm") -> 3 to "USB 网络共享"
        lowerName.startsWith("eth") -> 4 to "以太网"
        else -> 5 to "局域网"
    }

    companion object {
        private val EXCLUDED_PREFIXES = listOf(
            "rmnet", "r_rmnet", "ccmni", "pdp", "wwan", "tun", "tap",
            "ppp", "wg", "ipsec", "clat", "v4-", "utun", "epdg", "sit", "ip6tnl", "dummy",
        )
        private val ALLOWED_PREFIXES = listOf(
            "wlan", "ap", "swlan", "softap", "rndis", "usb", "ncm", "eth", "bridge",
        )

        internal fun isAllowedLocalInterface(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase(Locale.ROOT)
            if (lower.isEmpty() || EXCLUDED_PREFIXES.any { lower.startsWith(it) }) return false
            return ALLOWED_PREFIXES.any { lower.startsWith(it) }
        }
    }
}

data class LanStudioUiState(
    val isRunning: Boolean = false,
    val boundPort: Int = LanStudioServer.DEFAULT_PORT,
    val endpoints: List<LanEndpoint> = emptyList(),
    val sessionPin: String? = null,
    val activeClients: Int = 0,
    val totalRequests: Long = 0L,
    val uploadedFilesCount: Int = 0,
    val uploadedBytes: Long = 0L,
    val lastActivity: String? = null,
    val errorMessage: String? = null,
) {
    val urls: List<String>
        get() {
            val port = if (boundPort > 0) boundPort else LanStudioServer.DEFAULT_PORT
            return if (endpoints.isNotEmpty()) {
                endpoints.map { "http://${it.ipv4}:$port" }
            } else {
                listOf("http://127.0.0.1:$port")
            }
        }

    val primaryUrl: String
        get() = urls.first()
}

/**
 * Lifecycle-safe controller managing [LanStudioServer] and reactive [LanStudioUiState].
 */
class LanStudioController(
    context: Context,
    private val coreProvider: () -> MiniPlayerCoreProvider = { CoreRegistry.get(context.applicationContext) },
) {
    private val appContext = context.applicationContext
    private val detector = LanAddressDetector(appContext)
    private val storageGateway = AndroidLanStorageGateway(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()

    private val _uiState = MutableStateFlow(
        LanStudioUiState(endpoints = detector.currentEndpoints()),
    )
    val uiState: StateFlow<LanStudioUiState> = _uiState.asStateFlow()

    private var server: LanStudioServer? = null
    private var collectorJob: Job? = null

    init {
        scope.launch {
            detector.observeEndpoints().collect { eps ->
                _uiState.update { it.copy(endpoints = eps) }
            }
        }
    }

    @Synchronized
    fun startServer(port: Int = LanStudioServer.DEFAULT_PORT) {
        if (server?.isRunning == true) return
        refreshAddresses()
        val pin = _uiState.value.sessionPin
        val cfg = LanServerConfig(
            port = port,
            sessionPin = pin,
        )
        val instance = LanStudioServer(
            config = cfg,
            core = coreProvider(),
            storage = storageGateway,
        )
        try {
            instance.start()
        } catch (t: Throwable) {
            _uiState.update {
                it.copy(
                    isRunning = false,
                    errorMessage = "启动失败（端口 $port 可能被占用）：${t.message ?: "未知错误"}",
                )
            }
            return
        }

        server = instance
        _uiState.update {
            it.copy(
                isRunning = true,
                boundPort = instance.boundPort,
                errorMessage = null,
                lastActivity = "服务已就绪，请在电脑浏览器访问上述地址",
            )
        }

        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch {
                instance.activeClients.collect { clients ->
                    _uiState.update { it.copy(activeClients = clients) }
                }
            }
            launch {
                instance.events.collect { ev ->
                    when (ev) {
                        is LanServerEvent.Request -> {
                            _uiState.update {
                                it.copy(
                                    totalRequests = instance.totalRequests,
                                    lastActivity = "${ev.method} ${ev.path} (${ev.status})",
                                )
                            }
                        }

                        is LanServerEvent.FileUploaded -> {
                            _uiState.update {
                                it.copy(
                                    uploadedFilesCount = instance.totalUploadedFiles,
                                    uploadedBytes = instance.totalUploadedBytes,
                                    lastActivity = "已接收上传: ${ev.relPath}",
                                )
                            }
                        }

                        is LanServerEvent.Error -> {
                            _uiState.update { it.copy(errorMessage = ev.message) }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    @Synchronized
    fun stopServer() {
        collectorJob?.cancel()
        collectorJob = null
        server?.stop()
        server = null
        _uiState.update {
            it.copy(
                isRunning = false,
                activeClients = 0,
                lastActivity = "局域网服务已停止",
            )
        }
    }

    fun toggleServer(port: Int = LanStudioServer.DEFAULT_PORT) {
        if (_uiState.value.isRunning) {
            stopServer()
        } else {
            startServer(port)
        }
    }

    @Synchronized
    fun setPinProtectionEnabled(enabled: Boolean) {
        val newPin = if (enabled) {
            String.format(Locale.US, "%04d", secureRandom.nextInt(10_000))
        } else {
            null
        }
        val wasRunning = server?.isRunning == true
        val activePort = server?.boundPort?.takeIf { it > 0 } ?: LanStudioServer.DEFAULT_PORT
        if (wasRunning) {
            stopServer()
        }
        _uiState.update { it.copy(sessionPin = newPin) }
        if (wasRunning) {
            startServer(activePort)
        }
    }

    fun refreshAddresses() {
        _uiState.update { it.copy(endpoints = detector.currentEndpoints()) }
    }
}

/**
 * Process-wide singleton accessor so `LibraryScreen` (`💻` icon) and `SettingsScreen` share
 * the same running [LanStudioController] seamlessly.
 */
object LanStudioManager {
    @Volatile
    private var instance: LanStudioController? = null

    fun get(
        context: Context,
        coreProvider: (() -> MiniPlayerCoreProvider)? = null,
    ): LanStudioController {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appCtx = context.applicationContext
            val created = if (coreProvider != null) {
                LanStudioController(appCtx, coreProvider)
            } else {
                LanStudioController(appCtx)
            }
            instance = created
            return created
        }
    }
}

@Composable
fun LanStudioSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    controller: LanStudioController = LanStudioManager.get(LocalContext.current),
) {
    LanStudioModalSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        controller = controller,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanStudioModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    controller: LanStudioController = LanStudioManager.get(LocalContext.current),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by controller.uiState.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        LanStudioSheetContent(
            state = state,
            onToggleServer = { controller.toggleServer() },
            onTogglePin = { controller.setPinProtectionEnabled(it) },
            onRefreshAddresses = { controller.refreshAddresses() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanStudioSheetContent(
    state: LanStudioUiState,
    onToggleServer: () -> Unit,
    onTogglePin: (Boolean) -> Unit,
    onRefreshAddresses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val urls = state.urls
    var selectedUrlIdx by remember(urls) { mutableIntStateOf(0) }
    val activeBaseUrl = urls.getOrElse(selectedUrlIdx) { urls.first() }
    val fullAccessUrl = remember(activeBaseUrl, state.sessionPin) {
        if (!state.sessionPin.isNullOrBlank()) {
            "$activeBaseUrl/?token=${state.sessionPin}"
        } else {
            activeBaseUrl
        }
    }
    var copiedFeedback by remember(activeBaseUrl, state.sessionPin) { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column {
                    Text(
                        text = "PC 局域网 Web 管理台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                ),
                        )
                        Text(
                            text = if (state.isRunning) {
                                "运行中 · $activeBaseUrl"
                            } else {
                                "未启动 · 电脑浏览器免安装管理曲库与打点"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconButton(
                onClick = onRefreshAddresses,
                modifier = Modifier.semantics { contentDescription = "刷新局域网 IP 地址" },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新局域网 IP 地址",
                )
            }
        }

        if (state.isRunning) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    if (state.endpoints.size > 1) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            state.endpoints.forEachIndexed { idx, ep ->
                                FilterChip(
                                    selected = selectedUrlIdx == idx,
                                    onClick = { selectedUrlIdx = idx },
                                    label = { Text("${ep.categoryLabel}: ${ep.ipv4}") },
                                )
                            }
                        }
                    } else if (state.endpoints.isEmpty()) {
                        Text(
                            text = "⚠️ 当前未检测到 Wi-Fi 或热点连接，请将手机与电脑接入同一局域网后点击右上角刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // Scannable QR Code Canvas
                    val qrMatrix = remember(fullAccessUrl) {
                        runCatching { QrCodeGenerator.encode(fullAccessUrl) }.getOrNull()
                    }
                    if (qrMatrix != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .widthIn(max = 196.dp)
                                .fillMaxWidth(0.56f)
                                .aspectRatio(1f)
                                .semantics {
                                    contentDescription = "局域网管理台二维码 $activeBaseUrl"
                                },
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                            ) {
                                val quietZone = 4
                                val totalModules = qrMatrix.size + quietZone * 2
                                val cellPx = floor(minOf(size.width, size.height) / totalModules).coerceAtLeast(1f)
                                val gridPx = cellPx * totalModules
                                val originX = floor((size.width - gridPx) / 2f)
                                val originY = floor((size.height - gridPx) / 2f)

                                drawRect(color = Color.White, size = size)
                                for (y in 0 until qrMatrix.size) {
                                    for (x in 0 until qrMatrix.size) {
                                        if (qrMatrix[x, y]) {
                                            drawRect(
                                                color = Color.Black,
                                                topLeft = Offset(
                                                    x = originX + (x + quietZone) * cellPx,
                                                    y = originY + (y + quietZone) * cellPx,
                                                ),
                                                size = Size(cellPx, cellPx),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = activeBaseUrl,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )

                    if (!state.sessionPin.isNullOrBlank()) {
                        Text(
                            text = "访问 PIN 码: ${state.sessionPin}",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("MiniPlayer LAN URL", fullAccessUrl))
                            copiedFeedback = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (copiedFeedback) "已复制地址 ✓" else "复制地址")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    // Live Transfer / Request Counter
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatMetricColumn(label = "活跃连接", value = state.activeClients.toString())
                        StatMetricColumn(label = "累计请求", value = state.totalRequests.toString())
                        StatMetricColumn(
                            label = "无线接收",
                            value = "${state.uploadedFilesCount} 个 (${formatLanBytes(state.uploadedBytes)})",
                        )
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(14.dp),
                ) {
                    Text(
                        text = "电脑浏览器打开即可使用四大工作台：",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "• 📁 无线拖拽传歌/教学视频与 🎯 练习文件夹标记\n" +
                            "• 🏷️ 曲库大表格行内改标签与 ✨ 免 Key 批量联网补全\n" +
                            "• 🎯 播放列表与练习列表拖拽编排及 .m3u8 导入导出\n" +
                            "• 🎸 PC 在线预听与键盘 [ / ] 键极速 A-B 段落书签打点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Optional PIN security toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        text = "访问 PIN 码验证（公共 Wi-Fi 推荐开启）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "防火墙默认仅允许局域网私有 IP 访问，绝不暴露公网",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = !state.sessionPin.isNullOrBlank(),
                onCheckedChange = onTogglePin,
            )
        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onToggleServer,
            colors = if (state.isRunning) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(
                imageVector = if (state.isRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.isRunning) "停止服务" else "启动服务 (端口 ${LanStudioServer.DEFAULT_PORT})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatMetricColumn(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun formatLanBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
