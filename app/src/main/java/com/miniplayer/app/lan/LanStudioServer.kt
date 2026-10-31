package com.miniplayer.app.lan

import com.miniplayer.app.core.MiniPlayerCoreProvider
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.ArtistSummary
import com.miniplayer.app.core.model.BatchMatchItem
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.FolderBrowseState
import com.miniplayer.app.core.model.M3uImportResult
import com.miniplayer.app.core.model.MediaItem
import com.miniplayer.app.core.model.MetadataCandidate
import com.miniplayer.app.core.model.MetadataDraft
import com.miniplayer.app.core.model.MetadataField
import com.miniplayer.app.core.model.OnlineSource
import com.miniplayer.app.core.model.PlaylistDetail
import com.miniplayer.app.core.model.PlaylistSummary
import com.miniplayer.app.core.model.PracticeBookmark
import com.miniplayer.app.core.model.PracticeMath
import com.miniplayer.app.core.model.PracticeState
import com.miniplayer.app.core.model.SafRoot
import com.miniplayer.app.core.model.SubfolderEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class LanServerConfig(
    val port: Int = LanStudioServer.DEFAULT_PORT,
    val bindAddress: String = "0.0.0.0",
    val sessionPin: String? = null,
    val readOnly: Boolean = false,
    val socketTimeoutMs: Int = 60_000,
    val maxClients: Int = 32,
    val maxJsonBodyBytes: Int = 4 * 1024 * 1024,
    val maxUploadBytes: Long = 4L * 1024L * 1024L * 1024L,
    val allowedUploadExtensions: Set<String> = LanPathUtils.DEFAULT_ALLOWED_UPLOAD_EXTENSIONS,
    val clientAddressValidator: (InetAddress) -> Boolean = LanStudioServer::isAllowedLanAddress,
    val currentlyPlayingItemIdProvider: () -> Long? = { null },
)

sealed interface LanServerEvent {
    data class Started(val port: Int) : LanServerEvent
    data object Stopped : LanServerEvent
    data class Request(
        val clientIp: String,
        val method: String,
        val path: String,
        val status: Int,
    ) : LanServerEvent

    data class FileUploaded(
        val clientIp: String,
        val rootId: Long,
        val relPath: String,
        val bytes: Long,
    ) : LanServerEvent

    data class ClientRejected(val clientIp: String) : LanServerEvent
    data class Error(val message: String) : LanServerEvent
}

/**
 * Pure-Kotlin HTTP/1.1 PC LAN Web Studio server (`http://<phone-lan-ip>:8088`).
 *
 * Security invariants:
 * - Strict LAN / loopback socket firewall ([isAllowedLanAddress]): permits ONLY `127.0.0.0/8`,
 *   `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, link-local (`169.254.0.0/16`, `fe80::/10`),
 *   loopback `::1`, and IPv6 ULA/site-local (`fc00::/7`, `fec0::/10`).
 * - Strict `Host` & `Origin` header validation using syntactic IP parsing (zero DNS lookup) to
 *   prevent DNS rebinding and cross-origin CSRF attacks from untrusted web pages.
 * - Optional constant-time session PIN / token check (`X-Lan-Token`, `Authorization: Bearer`,
 *   `?token=`, or `HttpOnly; SameSite=Strict` cookie).
 */
class LanStudioServer(
    val config: LanServerConfig = LanServerConfig(),
    private val core: MiniPlayerCoreProvider,
    private val storage: LanStorageGateway = LocalLanStorageGateway(),
) {
    private val _events = MutableSharedFlow<LanServerEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LanServerEvent> = _events.asSharedFlow()

    private val _activeClients = MutableStateFlow(0)
    val activeClients: StateFlow<Int> = _activeClients.asStateFlow()

    private val _requestCounter = AtomicLong(0L)
    val totalRequests: Long
        get() = _requestCounter.get()

    private val _uploadCounter = AtomicInteger(0)
    val totalUploadedFiles: Int
        get() = _uploadCounter.get()

    private val _uploadedBytesCounter = AtomicLong(0L)
    val totalUploadedBytes: Long
        get() = _uploadedBytesCounter.get()

    private val _rejectedCounter = AtomicInteger(0)
    val rejectedConnections: Int
        get() = _rejectedCounter.get()

    private val running = AtomicBoolean(false)
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    var sessionPin: String? = config.sessionPin

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bindSockAddr = if (
            config.bindAddress == "0.0.0.0" ||
            config.bindAddress == "::" ||
            config.bindAddress.isBlank()
        ) {
            InetSocketAddress(config.port)
        } else {
            InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port)
        }
        try {
            ss.bind(bindSockAddr)
        } catch (t: Throwable) {
            runCatching { ss.close() }
            throw t
        }
        boundPort = ss.localPort
        serverSocket = ss
        running.set(true)

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "miniplayer-lan-studio").apply { isDaemon = true }
        }
        executor = pool
        _events.tryEmit(LanServerEvent.Started(boundPort))

        pool.execute { acceptLoop(ss, pool) }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null

        val snapshot = synchronized(openSockets) { openSockets.toList() }
        for (sock in snapshot) {
            runCatching { sock.close() }
        }
        openSockets.clear()
        _activeClients.value = 0

        executor?.shutdownNow()
        executor = null
        boundPort = -1
        _events.tryEmit(LanServerEvent.Stopped)
    }

    private fun acceptLoop(ss: ServerSocket, pool: ExecutorService) {
        while (running.get() && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                if (running.get()) {
                    _events.tryEmit(LanServerEvent.Error("LAN Studio accept loop terminated"))
                }
                break
            }

            val remoteAddr = client.inetAddress
            val localAddr = client.localAddress
            val allowed = remoteAddr != null && localAddr != null &&
                config.clientAddressValidator(remoteAddr) &&
                config.clientAddressValidator(localAddr)

            if (!allowed) {
                val peerIp = normalizeHostIp(remoteAddr)
                _rejectedCounter.incrementAndGet()
                _events.tryEmit(LanServerEvent.ClientRejected(peerIp))
                pool.execute { rejectNonLanSocket(client) }
                continue
            }

            if (openSockets.size >= config.maxClients) {
                runCatching {
                    val out = BufferedOutputStream(client.getOutputStream())
                    writeJsonError(out, 503, "Service Unavailable", "Too many active LAN connections", headOnly = false, close = true)
                    client.close()
                }
                continue
            }

            openSockets.add(client)
            _activeClients.value = openSockets.size
            pool.execute {
                try {
                    handleConnection(client)
                } finally {
                    openSockets.remove(client)
                    _activeClients.value = openSockets.size
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun rejectNonLanSocket(client: Socket) {
        runCatching {
            client.soTimeout = 200
            val inp = client.getInputStream()
            // Drain up to 4 KiB of initial header bytes so standard HTTP clients receive 403 cleanly without TCP RST
            val scratch = ByteArray(1024)
            var drained = 0
            while (drained < 4096) {
                val n = try {
                    inp.read(scratch, 0, minOf(scratch.size, 4096 - drained))
                } catch (_: IOException) {
                    break
                }
                if (n <= 0) break
                drained += n
                val str = String(scratch, 0, n, Charsets.ISO_8859_1)
                if (str.contains("\r\n\r\n") || str.contains("\n\n")) break
            }
            val out = BufferedOutputStream(client.getOutputStream())
            writeJsonError(out, 403, "Forbidden", "Non-LAN IP address rejected by firewall", headOnly = false, close = true)
            runCatching { client.shutdownOutput() }
        }
        runCatching { client.close() }
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = config.socketTimeoutMs
        socket.tcpNoDelay = true
        val clientIp = normalizeHostIp(socket.inetAddress)
        val input = BufferedInputStream(socket.getInputStream(), 32 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)

        while (running.get() && !socket.isClosed) {
            val requestLine = try {
                readHeaderLine(input, maxBytes = 8192)
            } catch (_: LanHttpException) {
                writeJsonError(output, 414, "URI Too Long", "Request line too long", headOnly = false, close = true)
                break
            } catch (_: IOException) {
                break
            } ?: break

            if (requestLine.isEmpty()) continue

            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeJsonError(output, 400, "Bad Request", "Malformed HTTP request line", headOnly = false, close = true)
                break
            }
            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]
            val protocol = parts.getOrNull(2)?.uppercase(Locale.ROOT) ?: "HTTP/1.1"

            val headers = LinkedHashMap<String, String>()
            var headerError = false
            var headerCount = 0
            while (true) {
                val line = try {
                    readHeaderLine(input, maxBytes = 8192)
                } catch (_: IOException) {
                    headerError = true
                    null
                }
                if (line == null) {
                    headerError = true
                    break
                }
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > 96) {
                    writeJsonError(output, 431, "Request Header Fields Too Large", "Too many headers", headOnly = false, close = true)
                    return
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }
            if (headerError) break

            val connHeader = headers["connection"]?.lowercase(Locale.ROOT).orEmpty()
            val shouldClose = (protocol == "HTTP/1.0" && !connHeader.contains("keep-alive")) ||
                connHeader.contains("close")

            val keepOpen = runBlocking {
                dispatchRequest(
                    clientIp = clientIp,
                    method = method,
                    rawTarget = rawTarget,
                    headers = headers,
                    input = input,
                    output = output,
                    requestWantsClose = shouldClose,
                )
            }
            if (!keepOpen) break
        }
    }

    private suspend fun dispatchRequest(
        clientIp: String,
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        requestWantsClose: Boolean,
    ): Boolean {
        val headOnly = method == "HEAD"
        val qIdx = rawTarget.indexOf('?')
        val rawPath = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val rawQuery = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

        try {
            validateUriRoutePath(rawPath)
            validateHostAndOrigin(method, headers)
            val query = parseQueryString(rawQuery)

            // Serve SPA shell and auth endpoint prior to PIN gate so browser can enter PIN
            if (rawPath == "/" && (method == "GET" || method == "HEAD")) {
                return handleWebRoot(clientIp, query, output, headOnly, requestWantsClose)
            }
            if (rawPath == "/favicon.ico" && (method == "GET" || method == "HEAD")) {
                writeSimpleResponse(output, 204, "No Content", "image/x-icon", ByteArray(0), headOnly = true, closeConnection = requestWantsClose)
                return !requestWantsClose
            }
            if (rawPath == "/api/auth" && method == "POST") {
                return handleAuth(clientIp, headers, input, output, requestWantsClose)
            }

            verifySessionPinIfConfigured(headers, query)

            if (config.readOnly && method in setOf("POST", "PUT", "DELETE", "PATCH")) {
                throw LanHttpException(403, "Forbidden", "LAN Web Studio is in read-only mode")
            }

            return when {
                rawPath == "/api/status" && (method == "GET" || method == "HEAD") ->
                    handleStatus(clientIp, output, headOnly, requestWantsClose)

                rawPath == "/api/roots" && (method == "GET" || method == "HEAD") ->
                    handleGetRoots(clientIp, output, headOnly, requestWantsClose)

                rawPath == "/api/folders" && (method == "GET" || method == "HEAD") ->
                    handleBrowseFolder(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/folders/mkdir" && method == "POST" ->
                    handleMkdir(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/folders/rule" && method == "POST" ->
                    handleFolderRule(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/folders/rescan" && method == "POST" ->
                    handleRescan(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/upload" && method == "POST" ->
                    handleUpload(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/items" && (method == "GET" || method == "HEAD") ->
                    handleGetItems(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/metadata/update" && method == "POST" ->
                    handleMetadataUpdate(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/metadata/batch-update" && method == "POST" ->
                    handleMetadataBatchUpdate(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/metadata/reset" && method == "POST" ->
                    handleMetadataReset(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/metadata/auto-match" && method == "POST" ->
                    handleMetadataAutoMatch(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/metadata/batch-auto-complete" && method == "POST" ->
                    handleMetadataBatchAutoComplete(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists" && (method == "GET" || method == "HEAD") ->
                    handleGetPlaylists(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/playlists" && method == "POST" ->
                    handleCreatePlaylist(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists" && method == "DELETE" ->
                    handleDeletePlaylist(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/delete" && method == "POST" ->
                    handleDeletePlaylist(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/rename" && method == "POST" ->
                    handleRenamePlaylist(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/practice-mode" && method == "POST" ->
                    handlePlaylistPracticeMode(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/items/add" && method == "POST" ->
                    handlePlaylistAddItems(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/items/remove" && method == "POST" ->
                    handlePlaylistRemoveItem(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/items/reorder" && method == "POST" ->
                    handlePlaylistReorderItems(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/playlists/export" && (method == "GET" || method == "HEAD") ->
                    handleExportPlaylistM3u8(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/playlists/import" && method == "POST" ->
                    handleImportPlaylistM3u8(clientIp, query, headers, input, output, requestWantsClose)

                extractNumericSuffix(rawPath, "/api/playlists/") != null && (method == "GET" || method == "HEAD") -> {
                    val pathId = extractNumericSuffix(rawPath, "/api/playlists/")!!.toString()
                    handleGetPlaylists(clientIp, query + ("id" to pathId), output, headOnly, requestWantsClose)
                }

                extractNumericSuffix(rawPath, "/api/playlists/") != null && method == "PUT" -> {
                    val pathId = extractNumericSuffix(rawPath, "/api/playlists/")!!
                    handleUpdatePlaylist(clientIp, pathId, headers, input, output, requestWantsClose)
                }

                extractNumericSuffix(rawPath, "/api/playlists/") != null && method == "DELETE" -> {
                    val pathId = extractNumericSuffix(rawPath, "/api/playlists/")!!.toString()
                    handleDeletePlaylist(clientIp, query + ("id" to pathId), headers, input, output, requestWantsClose)
                }

                rawPath == "/api/stream" && (method == "GET" || method == "HEAD") ->
                    handleStreamMedia(clientIp, query, headers, output, headOnly, requestWantsClose)

                rawPath == "/api/cover" && (method == "GET" || method == "HEAD") ->
                    handleStreamCover(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/practice" && (method == "GET" || method == "HEAD") ->
                    handleGetPracticeBundle(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/bookmarks" && (method == "GET" || method == "HEAD") ->
                    handleGetAllBookmarks(clientIp, query, output, headOnly, requestWantsClose)

                rawPath == "/api/practice/state" && method == "POST" ->
                    handleSavePracticeState(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/practice/bookmark" && method == "POST" ->
                    handleSaveBookmark(clientIp, headers, input, output, requestWantsClose)

                rawPath == "/api/practice/bookmark" && method == "DELETE" ->
                    handleDeleteBookmark(clientIp, query, headers, input, output, requestWantsClose)

                rawPath == "/api/practice/bookmark/delete" && method == "POST" ->
                    handleDeleteBookmark(clientIp, query, headers, input, output, requestWantsClose)

                extractNumericSuffix(rawPath, "/api/practice/bookmark/") != null && method == "DELETE" -> {
                    val pathId = extractNumericSuffix(rawPath, "/api/practice/bookmark/")!!.toString()
                    handleDeleteBookmark(clientIp, query + ("bookmarkId" to pathId), headers, input, output, requestWantsClose)
                }

                else -> {
                    recordRequest(clientIp, method, rawPath, 404)
                    writeJsonError(output, 404, "Not Found", "Unknown endpoint: $method $rawPath", headOnly, close = true)
                    false
                }
            }
        } catch (e: LanHttpException) {
            recordRequest(clientIp, method, rawPath, e.status)
            val extra = if (e.status == 401) listOf("authRequired" to JsonValue.Bool(true)) else emptyList()
            writeJsonError(output, e.status, e.reason, e.message, headOnly, close = true, extraFields = extra)
            return false
        } catch (e: IllegalArgumentException) {
            recordRequest(clientIp, method, rawPath, 400)
            writeJsonError(output, 400, "Bad Request", e.message ?: "Invalid request", headOnly, close = true)
            return false
        } catch (e: IOException) {
            if (isClientDisconnect(e)) {
                return false
            }
            _events.tryEmit(LanServerEvent.Error("$method $rawPath: ${e.message ?: "I/O error"}"))
            recordRequest(clientIp, method, rawPath, 500)
            runCatching {
                writeJsonError(output, 500, "Internal Server Error", e.message ?: "Internal server error", headOnly, close = true)
            }
            return false
        } catch (e: Exception) {
            _events.tryEmit(LanServerEvent.Error("$method $rawPath: ${e.message ?: "internal error"}"))
            recordRequest(clientIp, method, rawPath, 500)
            runCatching {
                writeJsonError(output, 500, "Internal Server Error", e.message ?: "Internal server error", headOnly, close = true)
            }
            return false
        }
    }

    private fun isClientDisconnect(e: IOException): Boolean {
        if (e is SocketException || e is SocketTimeoutException || e is EOFException) return true
        val msg = e.message?.lowercase(Locale.ROOT) ?: return false
        return msg.contains("broken pipe") ||
            msg.contains("connection reset") ||
            msg.contains("socket closed") ||
            msg.contains("connection abort")
    }

    private fun handleWebRoot(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val htmlBytes = LanStudioWebApp.renderHtml().toByteArray(Charsets.UTF_8)
        val extraHeaders = ArrayList<Pair<String, String>>(2)
        val expectedPin = sessionPin?.takeIf { it.isNotBlank() }
        val providedToken = query["token"]
        if (expectedPin != null && !providedToken.isNullOrEmpty() && constantTimeEquals(expectedPin, providedToken)) {
            extraHeaders.add("Set-Cookie" to "miniplayer_lan_token=${encodeCookieValue(expectedPin)}; Path=/; HttpOnly; SameSite=Strict")
        }
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/", 200)
        writeSimpleResponse(
            out = output,
            status = 200,
            reason = "OK",
            contentType = "text/html; charset=utf-8",
            body = htmlBytes,
            headOnly = headOnly,
            closeConnection = closeConnection,
            extraHeaders = extraHeaders,
        )
        return !closeConnection
    }

    private fun handleAuth(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val body = LanJson.parseObject(bodyText)
        val expectedPin = sessionPin?.takeIf { it.isNotBlank() }
        if (expectedPin == null) {
            recordRequest(clientIp, "POST", "/api/auth", 200)
            return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true), "authRequired" to JsonValue.Bool(false)), headOnly = false, close = closeConnection || mustClose)
        }
        val candidatePin = body.str("pin").orEmpty()
        if (!constantTimeEquals(expectedPin, candidatePin)) {
            throw LanHttpException(401, "Unauthorized", "Invalid PIN code")
        }
        val cookie = "miniplayer_lan_token=${encodeCookieValue(expectedPin)}; Path=/; HttpOnly; SameSite=Strict"
        recordRequest(clientIp, "POST", "/api/auth", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true)),
            headOnly = false,
            close = closeConnection || mustClose,
            extraHeaders = listOf("Set-Cookie" to cookie),
        )
    }

    private fun handleStatus(
        clientIp: String,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val lib = core.library
        val scan = core.scanner.scanProgress.value
        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "port" to JsonValue.Num(boundPort),
            "readOnly" to JsonValue.Bool(config.readOnly),
            "authRequired" to JsonValue.Bool(!sessionPin.isNullOrBlank()),
            "rootCount" to JsonValue.Num(lib.safRoots.value.size),
            "itemCount" to JsonValue.Num(lib.allMediaItems.value.size),
            "videoCount" to JsonValue.Num(lib.videoItems.value.size),
            "playlistCount" to JsonValue.Num(lib.playlists.value.size),
            "bookmarkedTrackCount" to JsonValue.Num(lib.bookmarkedTracks.value.size),
            "activeClients" to JsonValue.Num(_activeClients.value),
            "totalRequests" to JsonValue.Num(_requestCounter.get()),
            "totalUploadedFiles" to JsonValue.Num(_uploadCounter.get()),
            "totalUploadedBytes" to JsonValue.Num(_uploadedBytesCounter.get()),
            "scan" to LanJson.obj(
                "isScanning" to JsonValue.Bool(scan.isScanning),
                "scannedCount" to JsonValue.Num(scan.scannedCount),
                "currentFolder" to JsonValue.Str(scan.currentFolder),
                "addedOrUpdatedCount" to JsonValue.Num(scan.addedOrUpdatedCount),
            ),
        )
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/status", 200)
        return writeJsonOk(output, payload, headOnly = headOnly, close = closeConnection)
    }

    private fun handleGetRoots(
        clientIp: String,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val roots = core.library.safRoots.value.map(::serializeSafRoot)
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/roots", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "roots" to JsonValue.Arr(roots)),
            headOnly = headOnly,
            close = closeConnection,
        )
    }

    private suspend fun handleBrowseFolder(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val roots = core.library.safRoots.value
        val rootId = query["rootId"]?.toLongOrNull() ?: roots.firstOrNull()?.rootId
            ?: throw LanHttpException(404, "Not Found", "No folders mounted in library")
        val cleanRelPath = LanPathUtils.normalizeRelPath(query["relPath"].orEmpty())
        val flat = query["flat"] == "1" || query["flat"].equals("true", ignoreCase = true)

        val state = core.library.browseFolder(rootId, cleanRelPath, flat)
            ?: synthesizeFolderBrowseFallback(rootId, cleanRelPath, flat)
            ?: throw LanHttpException(404, "Not Found", "Folder not found for rootId=$rootId relPath=$cleanRelPath")

        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "root" to serializeSafRoot(state.root),
            "currentRelPath" to JsonValue.Str(state.currentRelPath),
            "isCurrentPracticeFolder" to JsonValue.Bool(state.isCurrentPracticeFolder),
            "breadcrumbs" to JsonValue.Arr(
                state.breadcrumbs.map { (label, rel) ->
                    LanJson.obj("label" to JsonValue.Str(label), "relPath" to JsonValue.Str(rel))
                },
            ),
            "subfolders" to JsonValue.Arr(state.subfolders.map(::serializeSubfolder)),
            "mediaItems" to JsonValue.Arr(state.mediaItems.map(::serializeMediaItem)),
        )
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/folders", 200)
        return writeJsonOk(output, payload, headOnly = headOnly, close = closeConnection)
    }

    private suspend fun synthesizeFolderBrowseFallback(
        rootId: Long,
        cleanRelPath: String,
        flat: Boolean,
    ): FolderBrowseState? {
        val root = core.library.safRoots.value.find { it.rootId == rootId } ?: return null
        val allInRoot = core.library.allMediaItems.value.filter { it.rootId == rootId }
        val isPractice = core.library.isPracticeFolder(rootId, cleanRelPath)

        val directItems = if (flat) {
            allInRoot.filter {
                cleanRelPath.isEmpty() || it.parentRelPath == cleanRelPath || it.parentRelPath.startsWith("$cleanRelPath/")
            }
        } else {
            allInRoot.filter { it.parentRelPath == cleanRelPath }
        }

        val childFolderMap = LinkedHashMap<String, Int>()
        if (!flat) {
            val prefix = if (cleanRelPath.isEmpty()) "" else "$cleanRelPath/"
            for (item in allInRoot) {
                val p = item.parentRelPath
                if (prefix.isEmpty() && p.isNotEmpty()) {
                    val childName = p.substringBefore('/')
                    childFolderMap[childName] = (childFolderMap[childName] ?: 0) + 1
                } else if (prefix.isNotEmpty() && p.startsWith(prefix) && p.length > prefix.length) {
                    val rest = p.substring(prefix.length)
                    val childName = rest.substringBefore('/')
                    if (childName.isNotEmpty()) {
                        childFolderMap[childName] = (childFolderMap[childName] ?: 0) + 1
                    }
                }
            }
        }

        val subfolders = childFolderMap.entries.map { (name, count) ->
            val subRel = if (cleanRelPath.isEmpty()) name else "$cleanRelPath/$name"
            SubfolderEntry(
                folderUri = "${root.treeUri.trimEnd('/')}/$subRel",
                rootId = rootId,
                relPath = subRel,
                name = name,
                itemCount = count,
                isPracticeFolder = core.library.isPracticeFolder(rootId, subRel),
            )
        }

        val crumbs = ArrayList<Pair<String, String>>()
        crumbs.add(root.displayName to "")
        if (cleanRelPath.isNotEmpty()) {
            var acc = ""
            for (seg in cleanRelPath.split('/')) {
                if (seg.isEmpty()) continue
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                crumbs.add(seg to acc)
            }
        }
        return FolderBrowseState(
            root = root,
            currentRelPath = cleanRelPath,
            breadcrumbs = crumbs,
            subfolders = subfolders,
            mediaItems = directItems,
            isCurrentPracticeFolder = isPractice,
        )
    }

    private suspend fun handleMkdir(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)

        val rootId = json.longOrNull("rootId") ?: query["rootId"]?.toLongOrNull()
            ?: core.library.safRoots.value.firstOrNull()?.rootId
            ?: throw LanHttpException(400, "Bad Request", "Missing 'rootId'")
        val relPath = json.str("relPath") ?: query["relPath"].orEmpty()
        val folderName = json.str("name") ?: query["name"]
            ?: throw LanHttpException(400, "Bad Request", "Missing 'name'")

        val root = findRootOrThrow(rootId)
        val created = storage.createSubfolder(root, relPath, folderName)
        core.scanner.rescanRoot(rootId, incremental = true)

        recordRequest(clientIp, "POST", "/api/folders/mkdir", 201)
        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "rootId" to JsonValue.Num(rootId),
            "relPath" to JsonValue.Str(created.relPath),
            "folderUri" to JsonValue.Str(created.folderUri),
            "name" to JsonValue.Str(created.name),
        )
        return writeJsonResponse(output, 201, "Created", payload, headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handleFolderRule(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)

        val rootId = json.longOrNull("rootId") ?: query["rootId"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'rootId'")
        val cleanRelPath = LanPathUtils.normalizeRelPath(json.str("relPath") ?: query["relPath"].orEmpty())
        val isPractice = json.boolOrNull("isPracticeFolder")
            ?: query["isPracticeFolder"]?.let { it == "1" || it.equals("true", ignoreCase = true) }
            ?: throw LanHttpException(400, "Bad Request", "Missing 'isPracticeFolder'")

        val root = findRootOrThrow(rootId)
        val resolvedFolderUri = json.str("folderUri")?.takeIf { it.isNotBlank() }
            ?: query["folderUri"]?.takeIf { it.isNotBlank() }
            ?: resolveFolderUri(root, cleanRelPath)

        core.scanner.setFolderPracticeRule(
            rootId = rootId,
            relPath = cleanRelPath,
            folderUri = resolvedFolderUri,
            isPracticeFolder = isPractice,
        )

        recordRequest(clientIp, "POST", "/api/folders/rule", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "rootId" to JsonValue.Num(rootId),
                "relPath" to JsonValue.Str(cleanRelPath),
                "folderUri" to JsonValue.Str(resolvedFolderUri),
                "isPracticeFolder" to JsonValue.Bool(isPractice),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun resolveFolderUri(root: SafRoot, cleanRelPath: String): String {
        if (cleanRelPath.isEmpty()) return root.treeUri
        val parentRel = cleanRelPath.substringBeforeLast('/', "")
        val leaf = cleanRelPath.substringAfterLast('/')
        val parentBrowse = core.library.browseFolder(root.rootId, parentRel, flat = false)
        parentBrowse?.subfolders?.firstOrNull { it.name == leaf || it.relPath == cleanRelPath }?.let {
            return it.folderUri
        }
        return "${root.treeUri.trimEnd('/')}/$cleanRelPath"
    }

    private suspend fun handleRescan(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val rootId = json.longOrNull("rootId") ?: query["rootId"]?.toLongOrNull()
        val incremental = json.boolOrNull("incremental") ?: true
        if (rootId != null) {
            core.scanner.rescanRoot(rootId, incremental = incremental)
        } else {
            core.scanner.rescanAll(incremental = incremental)
        }
        recordRequest(clientIp, "POST", "/api/folders/rescan", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true)),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleUpload(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val roots = core.library.safRoots.value
        val rootId = query["rootId"]?.toLongOrNull()
            ?: roots.singleOrNull { it.isWritable }?.rootId
            ?: roots.firstOrNull { it.isWritable }?.rootId
            ?: throw LanHttpException(400, "Bad Request", "Missing 'rootId' query parameter")
        val root = findRootOrThrow(rootId)
        val cleanRelPath = LanPathUtils.normalizeRelPath(query["relPath"].orEmpty())

        val contentType = headers["content-type"].orEmpty()
        val isMultipart = contentType.startsWith("multipart/form-data", ignoreCase = true)
        val bodyInfo = prepareRequestBody(headers, input, sendContinueIfNeeded = true, rawOut = output)

        val uploadedList = ArrayList<UploadedFileInfo>()
        val queryFileName = query["name"]?.takeIf { it.isNotBlank() }
            ?: query["fileName"]?.takeIf { it.isNotBlank() }
        if (!isMultipart) {
            val rawName = queryFileName
                ?: headers["x-file-name"]?.let { decodeQueryComponent(it) }
                ?: throw LanHttpException(400, "Bad Request", "Missing 'name' or 'fileName' query parameter for raw upload")
            val validName = validateUploadFileName(rawName)
            val info = storage.writeUploadedFile(
                root = root,
                parentRelPath = cleanRelPath,
                fileName = validName,
                mimeType = contentType.ifBlank { LanPathUtils.guessMimeType(validName) },
                source = bodyInfo.stream,
                maxBytes = config.maxUploadBytes,
            )
            uploadedList.add(info)
        } else {
            val boundary = extractMultipartBoundary(contentType)
                ?: throw LanHttpException(400, "Bad Request", "Missing multipart boundary")
            receiveMultipartUpload(
                bodyStream = bodyInfo.stream,
                boundary = boundary,
                root = root,
                cleanRelPath = cleanRelPath,
                fallbackName = queryFileName,
                outUploaded = uploadedList,
            )
            if (uploadedList.isEmpty()) {
                throw LanHttpException(400, "Bad Request", "No files found in multipart upload")
            }
        }

        var totalBytes = 0L
        for (u in uploadedList) {
            totalBytes += u.bytesWritten
            _uploadCounter.incrementAndGet()
            _uploadedBytesCounter.addAndGet(u.bytesWritten)
            _events.tryEmit(LanServerEvent.FileUploaded(clientIp, rootId, u.relPath, u.bytesWritten))
        }

        // Immediately rescan the target root so uploaded audio/video/lrc appears in library
        core.scanner.rescanRoot(rootId, incremental = true)

        recordRequest(clientIp, "POST", "/api/upload", 201)
        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "rootId" to JsonValue.Num(rootId),
            "count" to JsonValue.Num(uploadedList.size),
            "bytes" to JsonValue.Num(totalBytes),
            "files" to JsonValue.Arr(
                uploadedList.map { u ->
                    LanJson.obj(
                        "fileName" to JsonValue.Str(u.fileName),
                        "relPath" to JsonValue.Str(u.relPath),
                        "documentUri" to JsonValue.Str(u.documentUri),
                        "bytes" to JsonValue.Num(u.bytesWritten),
                    )
                },
            ),
        )
        return writeJsonResponse(
            output,
            201,
            "Created",
            payload,
            headOnly = false,
            close = closeConnection || bodyInfo.requiresClose,
        )
    }

    private fun validateUploadFileName(rawName: String): String {
        val clean = LanPathUtils.validateLeafName(rawName)
        val ext = LanPathUtils.extensionOf(clean)
        if (ext.isEmpty() || ext !in config.allowedUploadExtensions) {
            throw LanHttpException(400, "Bad Request", "Unsupported upload file extension '.$ext'")
        }
        return clean
    }

    private fun receiveMultipartUpload(
        bodyStream: InputStream,
        boundary: String,
        root: SafRoot,
        cleanRelPath: String,
        fallbackName: String?,
        outUploaded: MutableList<UploadedFileInfo>,
    ) {
        val reader = MultipartStreamReader(bodyStream)
        val firstMarker = "--$boundary"
        val endMarker = "--$boundary--"
        val nextDelimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)

        while (true) {
            val line = reader.readCrlfLine(maxBytes = 8192)
                ?: throw LanHttpException(400, "Bad Request", "Incomplete multipart preamble")
            if (line == endMarker) return
            if (line == firstMarker) break
        }

        while (true) {
            val partHeaders = LinkedHashMap<String, String>()
            while (true) {
                val hLine = reader.readCrlfLine(maxBytes = 8192)
                    ?: throw LanHttpException(400, "Bad Request", "Unexpected EOF in multipart headers")
                if (hLine.isEmpty()) break
                val colon = hLine.indexOf(':')
                if (colon > 0) {
                    partHeaders[hLine.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                        hLine.substring(colon + 1).trim()
                }
            }

            val disp = partHeaders["content-disposition"].orEmpty()
            val partFileName = extractDispositionFileName(disp) ?: fallbackName
            val partMime = partHeaders["content-type"].orEmpty()

            val isFinalBoundary: Boolean
            if (!partFileName.isNullOrBlank()) {
                val validName = validateUploadFileName(partFileName)
                val partStream = MultipartPartInputStream(reader, nextDelimiter)
                val uploaded = storage.writeUploadedFile(
                    root = root,
                    parentRelPath = cleanRelPath,
                    fileName = validName,
                    mimeType = partMime.ifBlank { LanPathUtils.guessMimeType(validName) },
                    source = partStream,
                    maxBytes = config.maxUploadBytes,
                )
                partStream.drainRemainderIfAny()
                isFinalBoundary = partStream.reachedFinalBoundary
                outUploaded.add(uploaded)
            } else {
                val (_, finalFlag) = reader.streamUntilBoundary(nextDelimiter, NullOutputStream)
                isFinalBoundary = finalFlag
            }

            if (isFinalBoundary) break
        }

        val drain = ByteArray(1024)
        while (bodyStream.read(drain) >= 0) {
            // Drain epilogue
        }
    }

    private suspend fun handleGetItems(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val filter = (query["filter"] ?: query["view"])?.lowercase(Locale.ROOT).orEmpty().ifBlank { "all" }
        val search = query["q"]?.trim()?.lowercase(Locale.ROOT).orEmpty()

        val baseItems: List<MediaItem> = when (filter) {
            "video", "videos" -> core.library.videoItems.value.ifEmpty {
                core.library.allMediaItems.value.filter { it.isVideo }
            }

            "unknown_album" -> {
                val viaContract = core.library.getAlbumTracks(AlbumSummary.UNKNOWN_ALBUM_KEY)
                viaContract.ifEmpty {
                    core.library.allMediaItems.value.filter {
                        it.album.isNullOrBlank() || it.album == AlbumSummary.UNKNOWN_ALBUM_TITLE
                    }
                }
            }

            "album", "albums" -> {
                val albumKey = query["albumKey"].orEmpty()
                if (albumKey.isNotEmpty()) {
                    core.library.getAlbumTracks(albumKey)
                } else {
                    core.library.allMediaItems.value
                }
            }

            "artist", "artists" -> {
                val artistNorm = query["artistNorm"].orEmpty()
                if (artistNorm.isNotEmpty()) {
                    core.library.getArtistDetail(artistNorm)?.tracks.orEmpty()
                } else {
                    core.library.allMediaItems.value
                }
            }

            "folder" -> {
                val rootId = query["rootId"]?.toLongOrNull()
                val relPath = query["relPath"]?.let { LanPathUtils.normalizeRelPath(it) }
                core.library.allMediaItems.value.filter { item ->
                    (rootId == null || item.rootId == rootId) &&
                        (relPath == null || item.parentRelPath == relPath)
                }
            }

            else -> core.library.allMediaItems.value
        }

        val filtered = if (search.isEmpty()) {
            baseItems
        } else {
            baseItems.filter { it ->
                it.title.lowercase(Locale.ROOT).contains(search) ||
                    it.artist.lowercase(Locale.ROOT).contains(search) ||
                    it.displayAlbum.lowercase(Locale.ROOT).contains(search) ||
                    it.fileName.lowercase(Locale.ROOT).contains(search)
            }
        }

        val entries = LinkedHashMap<String, JsonValue>()
        entries["ok"] = JsonValue.Bool(true)
        entries["count"] = JsonValue.Num(filtered.size)
        entries["items"] = JsonValue.Arr(filtered.map(::serializeMediaItem))
        if (query["includeFacets"] == "1" || query["includeFacets"].equals("true", ignoreCase = true)) {
            entries["albums"] = JsonValue.Arr(core.library.albums.value.map(::serializeAlbumSummary))
            entries["artists"] = JsonValue.Arr(core.library.artists.value.map(::serializeArtistSummary))
        }

        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/items", 200)
        return writeJsonOk(output, JsonValue.Obj(entries), headOnly = headOnly, close = closeConnection)
    }

    private suspend fun handleMetadataUpdate(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemId = json.longOrNull("itemId") ?: query["itemId"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        findItemOrThrow(itemId)

        val draft = parseMetadataDraftFromJson(json)
        if (draft.dirtyFields.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "No metadata fields provided to update")
        }
        val outcome = core.metadata.saveMetadata(
            itemIds = listOf(itemId),
            draft = draft,
            currentlyPlayingItemId = config.currentlyPlayingItemIdProvider(),
        )
        val updated = core.library.allMediaItems.value.find { it.itemId == itemId }

        recordRequest(clientIp, "POST", "/api/metadata/update", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "outcome" to JsonValue.Str(outcome.name),
                "item" to (updated?.let(::serializeMediaItem) ?: JsonValue.Null),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleMetadataBatchUpdate(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemIds = json.longList("itemIds").distinct()
        if (itemIds.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "Missing or empty 'itemIds'")
        }
        val draft = parseMetadataDraftFromJson(json)
        if (draft.dirtyFields.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "No metadata fields specified for batch update")
        }
        val outcome = core.metadata.saveMetadata(
            itemIds = itemIds,
            draft = draft,
            currentlyPlayingItemId = config.currentlyPlayingItemIdProvider(),
        )
        recordRequest(clientIp, "POST", "/api/metadata/batch-update", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "updatedCount" to JsonValue.Num(itemIds.size),
                "outcome" to JsonValue.Str(outcome.name),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private fun parseMetadataDraftFromJson(json: JsonValue.Obj): MetadataDraft {
        val dirty = LinkedHashSet<MetadataField>()
        if (json.has("dirtyFields")) {
            for (raw in json.stringList("dirtyFields")) {
                runCatching { MetadataField.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()?.let { dirty.add(it) }
            }
        } else {
            if (json.has("title")) dirty.add(MetadataField.TITLE)
            if (json.has("artist")) dirty.add(MetadataField.ARTIST)
            if (json.has("album")) dirty.add(MetadataField.ALBUM)
            if (json.has("albumArtist")) dirty.add(MetadataField.ALBUM_ARTIST)
            if (json.has("trackNumber")) dirty.add(MetadataField.TRACK_NUMBER)
            if (json.has("year")) dirty.add(MetadataField.YEAR)
        }
        return MetadataDraft(
            title = json.str("title"),
            artist = json.str("artist"),
            album = json.str("album"),
            albumArtist = json.str("albumArtist"),
            trackNumber = if (json.isNull("trackNumber")) null else json.intOrNull("trackNumber"),
            year = if (json.isNull("year")) null else json.intOrNull("year"),
            dirtyFields = dirty,
            writeBackToFile = json.bool("writeBackToFile", false),
        )
    }

    private suspend fun handleMetadataReset(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemIds = json.longList("itemIds").ifEmpty {
            listOfNotNull(json.longOrNull("itemId"))
        }
        if (itemIds.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "Missing 'itemIds'")
        }
        core.metadata.resetMetadataOverride(itemIds)
        recordRequest(clientIp, "POST", "/api/metadata/reset", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "resetCount" to JsonValue.Num(itemIds.size)),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleMetadataAutoMatch(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemId = query["itemId"]?.toLongOrNull() ?: json.longOrNull("itemId")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        val item = findItemOrThrow(itemId)

        val previewOnly = json.bool("previewOnly", false) || query["previewOnly"] == "1"
        val queryTitle = json.str("queryTitle") ?: query["queryTitle"]
        val queryArtist = json.str("queryArtist") ?: query["queryArtist"]
        val writeBack = json.bool("writeBackToFile", false)
        val preserveBacking = json.boolOrNull("preserveBackingSuffix") ?: item.isBackingTrack

        val explicitCandidateObj = json.obj("candidate")
        if (explicitCandidateObj != null) {
            val candidate = deserializeMetadataCandidate(explicitCandidateObj)
            val outcome = core.metadata.applyCandidate(
                itemId = itemId,
                candidate = candidate,
                preserveBackingSuffix = preserveBacking,
                writeBackToFile = writeBack,
                currentlyPlayingItemId = config.currentlyPlayingItemIdProvider(),
            )
            val updated = core.library.allMediaItems.value.find { it.itemId == itemId }
            recordRequest(clientIp, "POST", "/api/metadata/auto-match", 200)
            return writeJsonOk(
                output,
                LanJson.obj(
                    "ok" to JsonValue.Bool(true),
                    "applied" to JsonValue.Bool(true),
                    "outcome" to JsonValue.Str(outcome.name),
                    "selectedCandidate" to serializeMetadataCandidate(candidate),
                    "item" to (updated?.let(::serializeMediaItem) ?: JsonValue.Null),
                ),
                headOnly = false,
                close = closeConnection || mustClose,
            )
        }

        val candidates = core.metadata.searchOnlineCandidates(
            item = item,
            queryTitleOverride = queryTitle,
            queryArtistOverride = queryArtist,
        )

        if (previewOnly || candidates.isEmpty()) {
            recordRequest(clientIp, "POST", "/api/metadata/auto-match", 200)
            return writeJsonOk(
                output,
                LanJson.obj(
                    "ok" to JsonValue.Bool(true),
                    "applied" to JsonValue.Bool(false),
                    "candidates" to JsonValue.Arr(candidates.map(::serializeMetadataCandidate)),
                ),
                headOnly = false,
                close = closeConnection || mustClose,
            )
        }

        val pickIdx = (json.intOrNull("candidateIndex") ?: 0).coerceIn(0, candidates.lastIndex)
        val chosen = candidates[pickIdx]
        val outcome = core.metadata.applyCandidate(
            itemId = itemId,
            candidate = chosen,
            preserveBackingSuffix = preserveBacking,
            writeBackToFile = writeBack,
            currentlyPlayingItemId = config.currentlyPlayingItemIdProvider(),
        )
        val updated = core.library.allMediaItems.value.find { it.itemId == itemId }

        recordRequest(clientIp, "POST", "/api/metadata/auto-match", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "applied" to JsonValue.Bool(true),
                "outcome" to JsonValue.Str(outcome.name),
                "selectedCandidate" to serializeMetadataCandidate(chosen),
                "candidates" to JsonValue.Arr(candidates.map(::serializeMetadataCandidate)),
                "item" to (updated?.let(::serializeMediaItem) ?: JsonValue.Null),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleMetadataBatchAutoComplete(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val mode = json.str("mode")?.lowercase(Locale.ROOT) ?: "auto"
        val writeBack = json.bool("writeBackToFile", false)

        val requestedIds = json.longList("itemIds").toSet()
        val allItems = core.library.allMediaItems.value
        val targetItems = if (requestedIds.isNotEmpty()) {
            allItems.filter { it.itemId in requestedIds }
        } else {
            allItems.filter { it.album.isNullOrBlank() || it.album == AlbumSummary.UNKNOWN_ALBUM_TITLE }
                .ifEmpty { allItems }
        }

        val previewMatches = core.metadata.previewBatchCompletion(targetItems)
        if (mode == "preview") {
            recordRequest(clientIp, "POST", "/api/metadata/batch-auto-complete", 200)
            return writeJsonOk(
                output,
                LanJson.obj(
                    "ok" to JsonValue.Bool(true),
                    "mode" to JsonValue.Str("preview"),
                    "matches" to JsonValue.Arr(previewMatches.map(::serializeBatchMatchItem)),
                ),
                headOnly = false,
                close = closeConnection || mustClose,
            )
        }

        // For 1-click "auto" mode, select top candidate when available if none was preselected
        val effectiveMatches = previewMatches.map { m ->
            if (m.selectedCandidateIndex < 0 && m.candidates.isNotEmpty()) {
                m.copy(selectedCandidateIndex = 0)
            } else {
                m
            }
        }
        val committed = core.metadata.commitBatchCompletion(
            matches = effectiveMatches,
            writeBackToFile = writeBack,
            currentlyPlayingItemId = config.currentlyPlayingItemIdProvider(),
        )
        recordRequest(clientIp, "POST", "/api/metadata/batch-auto-complete", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "mode" to JsonValue.Str("auto"),
                "scannedCount" to JsonValue.Num(targetItems.size),
                "committedCount" to JsonValue.Num(committed),
                "matches" to JsonValue.Arr(effectiveMatches.map(::serializeBatchMatchItem)),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleGetPlaylists(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val specificId = query["id"]?.toLongOrNull() ?: query["playlistId"]?.toLongOrNull()
        if (specificId != null) {
            val detail = core.library.getPlaylistDetail(specificId)
                ?: throw LanHttpException(404, "Not Found", "Playlist not found: $specificId")
            recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/playlists", 200)
            return writeJsonOk(
                output,
                LanJson.obj("ok" to JsonValue.Bool(true), "playlist" to serializePlaylistDetail(detail)),
                headOnly = headOnly,
                close = closeConnection,
            )
        }

        val summaries = core.library.playlists.value.map(::serializePlaylistSummary)
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/playlists", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "playlists" to JsonValue.Arr(summaries)),
            headOnly = headOnly,
            close = closeConnection,
        )
    }

    private suspend fun handleCreatePlaylist(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val name = json.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LanHttpException(400, "Bad Request", "Playlist 'name' must not be empty")
        val isPractice = json.bool("isPracticePlaylist", false)
        val initialItems = json.longList("itemIds")

        val playlistId = core.library.createPlaylist(name, isPractice)
        if (initialItems.isNotEmpty()) {
            core.library.addItemsToPlaylist(playlistId, initialItems)
        }
        val detail = core.library.getPlaylistDetail(playlistId)
        recordRequest(clientIp, "POST", "/api/playlists", 201)
        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "playlistId" to JsonValue.Num(playlistId),
            "name" to JsonValue.Str(name),
            "isPracticePlaylist" to JsonValue.Bool(isPractice),
            "playlist" to (detail?.let(::serializePlaylistDetail) ?: JsonValue.Null),
        )
        return writeJsonResponse(output, 201, "Created", payload, headOnly = false, close = closeConnection || mustClose)
    }

    private fun extractNumericSuffix(rawPath: String, prefix: String): Long? {
        if (!rawPath.startsWith(prefix)) return null
        val tail = rawPath.substring(prefix.length).trimEnd('/')
        if (tail.isEmpty() || tail.contains('/')) return null
        return tail.toLongOrNull()
    }

    private suspend fun handleUpdatePlaylist(
        clientIp: String,
        playlistId: Long,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        core.library.getPlaylistDetail(playlistId)
            ?: throw LanHttpException(404, "Not Found", "Playlist not found: $playlistId")

        json.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { newName ->
            core.library.renamePlaylist(playlistId, newName)
        }
        json.boolOrNull("isPracticePlaylist")?.let { isPractice ->
            core.library.setPlaylistPracticeMode(playlistId, isPractice)
        }
        val addItemIds = json.longList("addItemIds")
        if (addItemIds.isNotEmpty()) {
            core.library.addItemsToPlaylist(playlistId, addItemIds)
        }
        if (json.has("orderedItemIds") || json.has("itemIds")) {
            val orderedIds = json.longList("orderedItemIds").ifEmpty { json.longList("itemIds") }
            core.library.reorderPlaylistItems(playlistId, orderedIds)
        }

        val updatedDetail = core.library.getPlaylistDetail(playlistId)
        recordRequest(clientIp, "PUT", "/api/playlists/$playlistId", 200)
        return writeJsonOk(
            output,
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "playlistId" to JsonValue.Num(playlistId),
                "playlist" to (updatedDetail?.let(::serializePlaylistDetail) ?: JsonValue.Null),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleRenamePlaylist(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'playlistId'")
        val newName = json.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw LanHttpException(400, "Bad Request", "Missing 'name'")
        core.library.renamePlaylist(playlistId, newName)
        recordRequest(clientIp, "POST", "/api/playlists/rename", 200)
        return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true)), headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handlePlaylistPracticeMode(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'playlistId'")
        val isPractice = json.boolOrNull("isPracticePlaylist")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'isPracticePlaylist'")
        core.library.setPlaylistPracticeMode(playlistId, isPractice)
        recordRequest(clientIp, "POST", "/api/playlists/practice-mode", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "isPracticePlaylist" to JsonValue.Bool(isPractice)),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleDeletePlaylist(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = query["id"]?.toLongOrNull() ?: query["playlistId"]?.toLongOrNull()
            ?: json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing playlist 'id'")
        core.library.deletePlaylist(playlistId)
        recordRequest(clientIp, "DELETE", "/api/playlists", 200)
        return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true)), headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handlePlaylistAddItems(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'playlistId'")
        val itemIds = json.longList("itemIds").ifEmpty { listOfNotNull(json.longOrNull("itemId")) }
        if (itemIds.isEmpty()) {
            throw LanHttpException(400, "Bad Request", "Missing 'itemIds'")
        }
        core.library.addItemsToPlaylist(playlistId, itemIds)
        recordRequest(clientIp, "POST", "/api/playlists/items/add", 200)
        return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true)), headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handlePlaylistRemoveItem(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'playlistId'")
        val itemId = json.longOrNull("itemId")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        core.library.removeItemFromPlaylist(playlistId, itemId)
        recordRequest(clientIp, "POST", "/api/playlists/items/remove", 200)
        return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true)), headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handlePlaylistReorderItems(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val playlistId = json.longOrNull("playlistId") ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'playlistId'")
        val orderedIds = json.longList("orderedItemIds").ifEmpty { json.longList("itemIds") }
        core.library.reorderPlaylistItems(playlistId, orderedIds)
        recordRequest(clientIp, "POST", "/api/playlists/items/reorder", 200)
        return writeJsonOk(output, LanJson.obj("ok" to JsonValue.Bool(true)), headOnly = false, close = closeConnection || mustClose)
    }

    private suspend fun handleExportPlaylistM3u8(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val playlistId = query["id"]?.toLongOrNull() ?: query["playlistId"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'id' query parameter")
        val detail = core.library.getPlaylistDetail(playlistId)
            ?: throw LanHttpException(404, "Not Found", "Playlist not found: $playlistId")

        val m3u8Text = storage.exportM3u8ViaCoreOrNull(core.metadata, playlistId)
            ?: buildM3u8Content(detail, core.library.safRoots.value)
        val bodyBytes = m3u8Text.toByteArray(Charsets.UTF_8)
        val fileName = "${detail.summary.name.ifBlank { "playlist_$playlistId" }}.m3u8"

        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/playlists/export", 200)
        writeSimpleResponse(
            out = output,
            status = 200,
            reason = "OK",
            contentType = "audio/x-mpegurl; charset=utf-8",
            body = bodyBytes,
            headOnly = headOnly,
            closeConnection = closeConnection,
            extraHeaders = listOf("Content-Disposition" to buildContentDisposition("attachment", fileName)),
        )
        return !closeConnection
    }

    private suspend fun handleImportPlaylistM3u8(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (rawBody, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val contentType = headers["content-type"].orEmpty()

        val fallbackName: String
        val m3uContent: String
        if (contentType.contains("application/json", ignoreCase = true) || rawBody.trimStart().startsWith("{")) {
            val json = LanJson.parseObject(rawBody)
            fallbackName = json.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: query["name"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "导入列表"
            m3uContent = json.str("content")
                ?: throw LanHttpException(400, "Bad Request", "Missing 'content' in JSON M3U8 import")
        } else {
            fallbackName = query["name"]?.trim()?.removeSuffix(".m3u8")?.removeSuffix(".m3u")?.takeIf { it.isNotEmpty() }
                ?: "导入列表"
            m3uContent = rawBody
        }

        if (m3uContent.isBlank()) {
            throw LanHttpException(400, "Bad Request", "Empty M3U8 playlist content")
        }

        val result = storage.importM3u8ViaCoreOrNull(core.metadata, m3uContent, fallbackName)
            ?: importM3u8BuiltIn(m3uContent, fallbackName)

        recordRequest(clientIp, "POST", "/api/playlists/import", 201)
        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "playlistId" to JsonValue.Num(result.playlistId),
            "playlistName" to JsonValue.Str(result.playlistName),
            "matchedCount" to JsonValue.Num(result.matchedCount),
            "unmatchedCount" to JsonValue.Num(result.unmatchedCount),
            "isPracticePlaylist" to JsonValue.Bool(result.isPracticePlaylist),
        )
        return writeJsonResponse(output, 201, "Created", payload, headOnly = false, close = closeConnection || mustClose)
    }

    private fun buildM3u8Content(detail: PlaylistDetail, roots: List<SafRoot>): String {
        val rootNames = roots.associate { it.rootId to it.displayName }
        return buildString(256 + detail.items.size * 128) {
            append("#EXTM3U\n")
            append("#PLAYLIST:").append(detail.summary.name.replace('\n', ' ')).append('\n')
            if (detail.summary.isPracticePlaylist) {
                append("#EXT-X-MINIPLAYER-PRACTICE:1\n")
            }
            for (item in detail.items) {
                val durSec = (item.durationMs / 1000L).coerceAtLeast(0L)
                val dispTitle = "${item.artist} - ${item.title}".replace('\n', ' ')
                append("#EXTINF:").append(durSec).append(',').append(dispTitle).append('\n')
                val rootPrefix = rootNames[item.rootId]?.takeIf { it.isNotBlank() }
                val rel = buildString {
                    if (rootPrefix != null) append(rootPrefix).append('/')
                    if (item.parentRelPath.isNotBlank()) append(item.parentRelPath.trim('/')).append('/')
                    append(item.fileName)
                }
                append(rel).append('\n')
            }
        }
    }

    private suspend fun importM3u8BuiltIn(m3uContent: String, fallbackName: String): M3uImportResult {
        var playlistName = fallbackName
        var isPractice = false
        var pendingExtInfSec: Long? = null
        val matchedIds = ArrayList<Long>()
        var unmatchedCount = 0

        val allItems = core.library.allMediaItems.value
        val rootNames = core.library.safRoots.value.associate { it.rootId to it.displayName }

        for (rawLine in m3uContent.lineSequence()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                        val parsed = line.substringAfter(':').trim()
                        if (parsed.isNotEmpty()) playlistName = parsed
                    }

                    line.startsWith("#EXT-X-MINIPLAYER-PRACTICE:", ignoreCase = true) -> {
                        val flag = line.substringAfter(':').trim()
                        isPractice = flag == "1" || flag.equals("true", ignoreCase = true)
                    }

                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        val secStr = line.substringAfter(':').substringBefore(',').trim()
                        pendingExtInfSec = secStr.toDoubleOrNull()?.toLong()
                    }
                }
                continue
            }

            val normalizedRef = line.replace('\\', '/').trimStart('/')
            val refLeaf = normalizedRef.substringAfterLast('/')
            val expectedDurationMs = pendingExtInfSec?.takeIf { it > 0L }?.let { it * 1000L }
            pendingExtInfSec = null

            // Tier 1: Exact relative path suffix match (<RootName>/<parentRel>/<fileName> or <parentRel>/<fileName>)
            val tier1 = allItems.firstOrNull { item ->
                val relNoRoot = if (item.parentRelPath.isBlank()) item.fileName else "${item.parentRelPath}/${item.fileName}"
                val rootName = rootNames[item.rootId]
                val relWithRoot = if (!rootName.isNullOrBlank()) "$rootName/$relNoRoot" else relNoRoot
                normalizedRef.equals(relWithRoot, ignoreCase = true) ||
                    normalizedRef.equals(relNoRoot, ignoreCase = true) ||
                    normalizedRef.endsWith("/$relNoRoot", ignoreCase = true)
            }
            if (tier1 != null) {
                matchedIds.add(tier1.itemId)
                continue
            }

            // Tier 2: fileName + #EXTINF duration (±1.5s)
            val sameLeaf = allItems.filter { it.fileName.equals(refLeaf, ignoreCase = true) }
            val tier2 = if (expectedDurationMs != null) {
                sameLeaf.firstOrNull { kotlin.math.abs(it.durationMs - expectedDurationMs) <= 1500L }
            } else {
                null
            }
            if (tier2 != null) {
                matchedIds.add(tier2.itemId)
                continue
            }

            // Tier 3: Unique fileName match
            if (sameLeaf.size == 1) {
                matchedIds.add(sameLeaf[0].itemId)
            } else {
                unmatchedCount++
            }
        }

        val distinctIds = matchedIds.distinct()
        val createdId = core.library.createPlaylist(playlistName, isPractice)
        if (distinctIds.isNotEmpty()) {
            core.library.addItemsToPlaylist(createdId, distinctIds)
        }
        return M3uImportResult(
            playlistId = createdId,
            playlistName = playlistName,
            matchedCount = distinctIds.size,
            unmatchedCount = unmatchedCount,
            isPracticePlaylist = isPractice,
        )
    }

    private fun handleStreamMedia(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val itemId = query["itemId"]?.toLongOrNull() ?: query["id"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId' query parameter")
        val item = findItemOrThrow(itemId)
        val resource = storage.openMediaStream(item)
            ?: throw LanHttpException(404, "Not Found", "Media file stream unavailable for '${item.fileName}'")
        return streamResourceWithRanges(
            clientIp = clientIp,
            routePath = "/api/stream",
            resource = resource,
            headers = headers,
            output = output,
            headOnly = headOnly,
            closeConnection = closeConnection,
        )
    }

    private fun handleStreamCover(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val itemId = query["itemId"]?.toLongOrNull() ?: query["id"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId' query parameter")
        val item = findItemOrThrow(itemId)
        val resource = storage.openCoverStream(item)
            ?: throw LanHttpException(404, "Not Found", "No cover art available")

        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/cover", 200)
        writeHeaders(
            out = output,
            status = 200,
            reason = "OK",
            headers = listOf(
                "Content-Type" to resource.mimeType,
                "Content-Length" to resource.sizeBytes.toString(),
                "Cache-Control" to "private, max-age=300",
                "Connection" to if (closeConnection) "close" else "keep-alive",
            ),
        )
        if (headOnly) {
            output.flush()
            return !closeConnection
        }
        val sent = resource.openAtOffset(0L).use { stream ->
            copyExact(stream, output, resource.sizeBytes)
        }
        output.flush()
        return sent == resource.sizeBytes && !closeConnection
    }

    private fun streamResourceWithRanges(
        clientIp: String,
        routePath: String,
        resource: StreamableResource,
        headers: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val totalSize = resource.sizeBytes.coerceAtLeast(0L)
        val disposition = buildContentDisposition("inline", resource.fileName)
        val rangeHeader = headers["range"]

        if (rangeHeader != null) {
            val byteRange = parseByteRange(rangeHeader, totalSize)
            if (byteRange == null) {
                recordRequest(clientIp, if (headOnly) "HEAD" else "GET", routePath, 416)
                writeHeaders(
                    out = output,
                    status = 416,
                    reason = "Range Not Satisfiable",
                    headers = listOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Content-Range" to "bytes */$totalSize",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to "0",
                        "Connection" to if (closeConnection) "close" else "keep-alive",
                    ),
                )
                output.flush()
                return !closeConnection
            }

            val (start, end) = byteRange
            val sliceLength = end - start + 1L
            recordRequest(clientIp, if (headOnly) "HEAD" else "GET", routePath, 206)
            writeHeaders(
                out = output,
                status = 206,
                reason = "Partial Content",
                headers = listOf(
                    "Content-Type" to resource.mimeType,
                    "Content-Length" to sliceLength.toString(),
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "Accept-Ranges" to "bytes",
                    "Content-Disposition" to disposition,
                    "Connection" to if (closeConnection) "close" else "keep-alive",
                ),
            )
            if (headOnly) {
                output.flush()
                return !closeConnection
            }
            val sent = resource.openAtOffset(start).use { stream ->
                copyExact(stream, output, sliceLength)
            }
            output.flush()
            return sent == sliceLength && !closeConnection
        }

        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", routePath, 200)
        writeHeaders(
            out = output,
            status = 200,
            reason = "OK",
            headers = listOf(
                "Content-Type" to resource.mimeType,
                "Content-Length" to totalSize.toString(),
                "Accept-Ranges" to "bytes",
                "Content-Disposition" to disposition,
                "Connection" to if (closeConnection) "close" else "keep-alive",
            ),
        )
        if (headOnly) {
            output.flush()
            return !closeConnection
        }
        val sent = resource.openAtOffset(0L).use { stream ->
            copyExact(stream, output, totalSize)
        }
        output.flush()
        return sent == totalSize && !closeConnection
    }

    private suspend fun handleGetPracticeBundle(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val itemId = query["itemId"]?.toLongOrNull()
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        val item = findItemOrThrow(itemId)
        val practiceState = core.library.getPracticeState(itemId)
        val bookmarks = core.library.getBookmarks(itemId)
        val lyrics = core.metadata.getLyrics(item, fetchOnlineIfMissing = false)

        val payload = LanJson.obj(
            "ok" to JsonValue.Bool(true),
            "item" to serializeMediaItem(item),
            "state" to serializePracticeState(practiceState),
            "bookmarks" to JsonValue.Arr(bookmarks.map(::serializeBookmark)),
            "presetLabels" to JsonValue.Arr(PracticeMath.PRESET_SECTION_LABELS.map(JsonValue::Str)),
            "speedPresets" to JsonValue.Arr(PracticeMath.SPEED_PRESETS.map { JsonValue.Num(it) }),
            "lyrics" to (lyrics?.let { lyr ->
                LanJson.obj(
                    "source" to JsonValue.Str(lyr.source.name),
                    "plainText" to LanJson.strOrNull(lyr.plainText),
                    "lines" to JsonValue.Arr(
                        lyr.lines.map { line ->
                            LanJson.obj(
                                "timeMs" to JsonValue.Num(line.timeMs),
                                "text" to JsonValue.Str(line.text),
                            )
                        },
                    ),
                )
            } ?: JsonValue.Null),
        )
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/practice", 200)
        return writeJsonOk(output, payload, headOnly = headOnly, close = closeConnection)
    }

    private suspend fun handleGetAllBookmarks(
        clientIp: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val itemId = query["itemId"]?.toLongOrNull()
        if (itemId != null) {
            val bms = core.library.getBookmarks(itemId)
            recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/bookmarks", 200)
            return writeJsonOk(
                output,
                LanJson.obj("ok" to JsonValue.Bool(true), "bookmarks" to JsonValue.Arr(bms.map(::serializeBookmark))),
                headOnly = headOnly,
                close = closeConnection,
            )
        }
        val tracks = core.library.bookmarkedTracks.value.map { bt ->
            LanJson.obj(
                "item" to serializeMediaItem(bt.mediaItem),
                "bookmarks" to JsonValue.Arr(bt.bookmarks.map(::serializeBookmark)),
            )
        }
        recordRequest(clientIp, if (headOnly) "HEAD" else "GET", "/api/bookmarks", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "bookmarkedTracks" to JsonValue.Arr(tracks)),
            headOnly = headOnly,
            close = closeConnection,
        )
    }

    private suspend fun handleSavePracticeState(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemId = json.longOrNull("itemId")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        val item = findItemOrThrow(itemId)
        val existing = core.library.getPracticeState(itemId)

        val newSpeed = json.floatOrNull("practiceSpeed")?.let(PracticeMath::clampSpeed) ?: existing.practiceSpeed
        val newSemitones = (json.intOrNull("pitchSemitones") ?: existing.pitchSemitones).coerceIn(-6, 6)
        val newCents = (json.intOrNull("pitchCents") ?: existing.pitchCents).coerceIn(-50, 50)
        val newBpm = (json.intOrNull("bpm") ?: existing.bpm).coerceIn(30, 300)
        val newCountIn = json.str("countInMode")?.let {
            runCatching { CountInMode.valueOf(it.uppercase(Locale.ROOT)) }.getOrNull()
        } ?: existing.countInMode
        val newPreRoll = json.boolOrNull("preRollEnabled") ?: existing.preRollEnabled
        val newLoopEnabled = json.boolOrNull("loopEnabled") ?: existing.loopEnabled
        val newVideoMirror = json.boolOrNull("videoMirror") ?: existing.videoMirror
        val newVideoZoom = (json.floatOrNull("videoZoom") ?: existing.videoZoom).coerceIn(1.0f, 4.0f)

        val rawA = if (json.has("activeAMs")) {
            if (json.isNull("activeAMs")) null else json.longOrNull("activeAMs")
        } else {
            existing.activeAMs
        }
        val rawB = if (json.has("activeBMs")) {
            if (json.isNull("activeBMs")) null else json.longOrNull("activeBMs")
        } else {
            existing.activeBMs
        }

        val normalizedPair = if (rawA != null && rawB != null) {
            PracticeMath.normalizeLoopPoints(rawA, rawB, item.durationMs)
        } else {
            null
        }

        val updated = existing.copy(
            practiceSpeed = newSpeed,
            pitchSemitones = newSemitones,
            pitchCents = newCents,
            bpm = newBpm,
            countInMode = newCountIn,
            preRollEnabled = newPreRoll,
            loopEnabled = newLoopEnabled && normalizedPair != null,
            activeAMs = normalizedPair?.first ?: rawA,
            activeBMs = normalizedPair?.second ?: rawB,
            videoMirror = newVideoMirror,
            videoZoom = newVideoZoom,
        )
        core.library.savePracticeState(updated)

        recordRequest(clientIp, "POST", "/api/practice/state", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "state" to serializePracticeState(updated)),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleSaveBookmark(
        clientIp: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val itemId = json.longOrNull("itemId")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'itemId'")
        val item = findItemOrThrow(itemId)
        val rawAMs = json.longOrNull("aMs")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'aMs'")
        val rawBMs = json.longOrNull("bMs")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'bMs'")

        val normalized = PracticeMath.normalizeLoopPoints(rawAMs, rawBMs, item.durationMs)
            ?: throw LanHttpException(400, "Bad Request", "Invalid A-B loop points ($rawAMs..$rawBMs)")
        val label = json.str("label")?.trim()?.takeIf { it.isNotEmpty() } ?: "段落"
        val savedSpeed = json.floatOrNull("savedSpeed")?.let(PracticeMath::clampSpeed)
        val bookmarkId = json.longOrNull("bookmarkId")

        val created = core.library.saveBookmark(
            itemId = itemId,
            label = label,
            aMs = normalized.first,
            bMs = normalized.second,
            savedSpeed = savedSpeed,
            bookmarkId = bookmarkId,
        )
        val allBookmarks = core.library.getBookmarks(itemId)
        val status = if (bookmarkId == null) 201 else 200
        recordRequest(clientIp, "POST", "/api/practice/bookmark", status)
        return writeJsonResponse(
            output,
            status,
            if (status == 201) "Created" else "OK",
            LanJson.obj(
                "ok" to JsonValue.Bool(true),
                "bookmark" to serializeBookmark(created),
                "bookmarks" to JsonValue.Arr(allBookmarks.map(::serializeBookmark)),
            ),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private suspend fun handleDeleteBookmark(
        clientIp: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val (bodyText, mustClose) = readBoundedUtf8Body(headers, input, config.maxJsonBodyBytes)
        val json = LanJson.parseObject(bodyText)
        val bookmarkId = query["bookmarkId"]?.toLongOrNull()
            ?: query["id"]?.toLongOrNull()
            ?: json.longOrNull("bookmarkId")
            ?: json.longOrNull("id")
            ?: throw LanHttpException(400, "Bad Request", "Missing 'bookmarkId'")

        core.library.deleteBookmark(bookmarkId)
        recordRequest(clientIp, "DELETE", "/api/practice/bookmark", 200)
        return writeJsonOk(
            output,
            LanJson.obj("ok" to JsonValue.Bool(true), "bookmarkId" to JsonValue.Num(bookmarkId)),
            headOnly = false,
            close = closeConnection || mustClose,
        )
    }

    private fun verifySessionPinIfConfigured(headers: Map<String, String>, query: Map<String, String>) {
        val expectedPin = sessionPin?.takeIf { it.isNotBlank() } ?: return
        val headerToken = headers["x-lan-token"]?.trim()
            ?: headers["authorization"]?.let { auth ->
                if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else null
            }
        val queryToken = query["token"]?.trim()
        val cookieToken = extractCookie(headers["cookie"], "miniplayer_lan_token")

        val candidate = headerToken ?: queryToken ?: cookieToken
        if (candidate.isNullOrEmpty() || !constantTimeEquals(expectedPin, candidate)) {
            throw LanHttpException(401, "Unauthorized", "Valid LAN session PIN required")
        }
    }

    private fun validateHostAndOrigin(method: String, headers: Map<String, String>) {
        val hostHeader = headers["host"]?.trim()
        if (!hostHeader.isNullOrEmpty() && !isAllowedHostAuthority(hostHeader)) {
            throw LanHttpException(403, "Forbidden", "Disallowed Host header (DNS rebinding protection)")
        }
        if (method in setOf("POST", "PUT", "DELETE", "PATCH")) {
            val originHeader = headers["origin"]?.trim()
            if (!originHeader.isNullOrEmpty()) {
                val originHost = if (originHeader.equals("null", ignoreCase = true)) {
                    null
                } else {
                    runCatching { URI(originHeader).host }.getOrNull()
                }
                if (originHost.isNullOrEmpty() || !isAllowedHostNameOrLiteral(originHost)) {
                    throw LanHttpException(403, "Forbidden", "Cross-origin state mutation blocked")
                }
            }
        }
    }

    private fun findRootOrThrow(rootId: Long): SafRoot =
        core.library.safRoots.value.find { it.rootId == rootId }
            ?: throw LanHttpException(404, "Not Found", "Root folder not found: $rootId")

    private fun findItemOrThrow(itemId: Long): MediaItem =
        core.library.allMediaItems.value.find { it.itemId == itemId }
            ?: throw LanHttpException(404, "Not Found", "Media item not found: $itemId")

    private fun recordRequest(clientIp: String, method: String, path: String, status: Int) {
        _requestCounter.incrementAndGet()
        _events.tryEmit(LanServerEvent.Request(clientIp, method, path, status))
    }

    private data class PreparedRequestBody(
        val stream: InputStream,
        val boundedLength: Long?,
        val requiresClose: Boolean,
    )

    private fun prepareRequestBody(
        headers: Map<String, String>,
        rawInput: InputStream,
        sendContinueIfNeeded: Boolean,
        rawOut: OutputStream? = null,
    ): PreparedRequestBody {
        if (sendContinueIfNeeded && rawOut != null &&
            headers["expect"]?.equals("100-continue", ignoreCase = true) == true
        ) {
            rawOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            rawOut.flush()
        }

        val te = headers["transfer-encoding"]?.lowercase(Locale.ROOT).orEmpty()
        if (te.contains("chunked")) {
            return PreparedRequestBody(
                stream = ChunkedInputStream(rawInput),
                boundedLength = null,
                requiresClose = false,
            )
        }

        val clHeader = headers["content-length"]
        if (clHeader != null) {
            val len = clHeader.trim().toLongOrNull()
            if (len == null || len < 0L) {
                throw LanHttpException(400, "Bad Request", "Invalid Content-Length header")
            }
            return PreparedRequestBody(
                stream = BoundedInputStream(rawInput, len),
                boundedLength = len,
                requiresClose = false,
            )
        }

        return PreparedRequestBody(
            stream = EmptyInputStream,
            boundedLength = 0L,
            requiresClose = false,
        )
    }

    private fun readBoundedUtf8Body(
        headers: Map<String, String>,
        rawInput: InputStream,
        maxBytes: Int,
    ): Pair<String, Boolean> {
        val bodyInfo = prepareRequestBody(headers, rawInput, sendContinueIfNeeded = false)
        if (bodyInfo.boundedLength != null && bodyInfo.boundedLength > maxBytes) {
            throw LanHttpException(413, "Payload Too Large", "Request payload exceeds $maxBytes bytes")
        }
        val out = ByteArrayOutputStream(minOf(bodyInfo.boundedLength?.toInt() ?: 256, 65536).coerceAtLeast(32))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = bodyInfo.stream.read(buf)
            if (n < 0) break
            if (n > 0) {
                total += n
                if (total > maxBytes) {
                    throw LanHttpException(413, "Payload Too Large", "Request payload exceeds $maxBytes bytes")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name()) to bodyInfo.requiresClose
    }

    companion object {
        const val DEFAULT_PORT = 8088

        /**
         * Strict LAN / loopback IP firewall:
         * Permits ONLY:
         * - IPv4 loopback (`127.0.0.0/8`)
         * - IPv4 RFC 1918 private (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
         * - IPv4 RFC 3927 link-local (`169.254.0.0/16`)
         * - IPv6 loopback (`::1`), link-local (`fe80::/10`), ULA (`fc00::/7`), site-local (`fec0::/10`)
         * Rejects public WAN IPs, carrier CGNAT (`100.64.0.0/10`), multicast, and `0.0.0.0`/`::`.
         */
        fun isAllowedLanAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isMulticastAddress) return false

            val v4 = extractIpv4Bytes(addr)
            if (v4 != null) {
                val b0 = v4[0].toInt() and 0xFF
                val b1 = v4[1].toInt() and 0xFF
                return when {
                    b0 == 127 -> true
                    b0 == 10 -> true
                    b0 == 172 && b1 in 16..31 -> true
                    b0 == 192 && b1 == 168 -> true
                    b0 == 169 && b1 == 254 -> true
                    else -> false
                }
            }

            if (addr is Inet6Address) {
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                    return true
                }
                val raw = addr.address
                if (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) {
                    return true // fc00::/7 Unique Local Address
                }
            }
            return false
        }

        internal fun extractIpv4Bytes(address: InetAddress?): ByteArray? {
            if (address == null) return null
            if (address is Inet4Address) return address.address
            if (address is Inet6Address) {
                val raw = address.address
                val isV4Mapped = raw.size == 16 &&
                    (0..9).all { raw[it] == 0.toByte() } &&
                    raw[10] == 0xFF.toByte() &&
                    raw[11] == 0xFF.toByte()
                if (isV4Mapped) return raw.copyOfRange(12, 16)
            }
            return null
        }

        internal fun normalizeHostIp(address: InetAddress?): String {
            if (address == null) return "unknown"
            val v4 = extractIpv4Bytes(address)
            if (v4 != null) {
                return "${v4[0].toInt() and 0xFF}.${v4[1].toInt() and 0xFF}.${v4[2].toInt() and 0xFF}.${v4[3].toInt() and 0xFF}"
            }
            return address.hostAddress?.substringBefore('%') ?: "unknown"
        }

        internal fun isAllowedHostAuthority(authority: String): Boolean {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return false
            val hostPart = if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                if (closeBracket <= 1) return false
                trimmed.substring(1, closeBracket)
            } else {
                val colon = trimmed.indexOf(':')
                if (colon >= 0) trimmed.substring(0, colon) else trimmed
            }
            return isAllowedHostNameOrLiteral(hostPart)
        }

        /**
         * Validates `Host` / `Origin` hostnames WITHOUT performing DNS lookups (prevents DNS rebinding).
         */
        internal fun isAllowedHostNameOrLiteral(host: String): Boolean {
            val clean = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (clean.equals("localhost", ignoreCase = true)) return true

            // Syntactic IPv4 check (a.b.c.d)
            val v4Parts = clean.split('.')
            if (v4Parts.size == 4 && v4Parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
                val bytes = ByteArray(4)
                for (i in 0..3) {
                    val v = v4Parts[i].toIntOrNull() ?: return false
                    if (v !in 0..255) return false
                    bytes[i] = v.toByte()
                }
                val addr = runCatching { InetAddress.getByAddress(bytes) }.getOrNull() ?: return false
                return isAllowedLanAddress(addr)
            }

            // Syntactic IPv6 literal check (must contain ':')
            if (clean.contains(':') && clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                val addr = runCatching { InetAddress.getByName(clean) }.getOrNull() ?: return false
                return isAllowedLanAddress(addr)
            }

            return false
        }

        internal fun parseByteRange(rangeHeader: String, totalSize: Long): Pair<Long, Long>? {
            val trimmed = rangeHeader.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
            if (totalSize <= 0L) return null
            val spec = trimmed.substring(6).trim()
            if (spec.isEmpty() || spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null

            val startPart = spec.substring(0, dash).trim()
            val endPart = spec.substring(dash + 1).trim()

            return if (startPart.isEmpty()) {
                val suffix = endPart.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val start = (totalSize - suffix).coerceAtLeast(0L)
                start to (totalSize - 1L)
            } else {
                val start = startPart.toLongOrNull() ?: return null
                if (start < 0L || start >= totalSize) return null
                val end = if (endPart.isEmpty()) {
                    totalSize - 1L
                } else {
                    val parsedEnd = endPart.toLongOrNull() ?: return null
                    if (parsedEnd < start) return null
                    minOf(parsedEnd, totalSize - 1L)
                }
                start to end
            }
        }

        internal fun parseQueryString(rawQuery: String): Map<String, String> {
            if (rawQuery.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq >= 0) {
                    val key = decodeQueryComponent(pair.substring(0, eq))
                    val value = decodeQueryComponent(pair.substring(eq + 1))
                    out[key] = value
                } else {
                    out[decodeQueryComponent(pair)] = ""
                }
            }
            return out
        }

        private fun decodeQueryComponent(raw: String): String {
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: IllegalArgumentException) {
                throw LanHttpException(400, "Bad Request", "Malformed URL query encoding")
            }
        }

        private fun validateUriRoutePath(rawUriPath: String) {
            if (rawUriPath.isEmpty() || !rawUriPath.startsWith("/")) {
                throw LanHttpException(403, "Forbidden", "Invalid URI path")
            }
            val decoded = try {
                URLDecoder.decode(rawUriPath.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                throw LanHttpException(400, "Bad Request", "Malformed URI percent-encoding")
            }
            if (decoded.contains('\u0000') || decoded.contains('\\')) {
                throw LanHttpException(403, "Forbidden", "Illegal URI characters")
            }
            for (seg in decoded.split('/')) {
                if (seg == "." || seg == "..") {
                    throw LanHttpException(403, "Forbidden", "URI traversal denied")
                }
            }
        }

        private fun extractCookie(cookieHeader: String?, targetName: String): String? {
            if (cookieHeader.isNullOrBlank()) return null
            for (part in cookieHeader.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val k = trimmed.substring(0, eq).trim()
                    if (k == targetName) {
                        val rawV = trimmed.substring(eq + 1).trim().trim('"')
                        return runCatching { URLDecoder.decode(rawV, "UTF-8") }.getOrDefault(rawV)
                    }
                }
            }
            return null
        }

        private fun encodeCookieValue(raw: String): String =
            URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        internal fun buildContentDisposition(dispositionType: String, fileName: String): String {
            val asciiSafe = buildString(fileName.length) {
                for (ch in fileName) {
                    when {
                        ch == '"' || ch == '\\' || ch.code < 0x20 -> append('_')
                        ch.code in 0x20..0x7E -> append(ch)
                        else -> append('_')
                    }
                }
            }.ifBlank { "media" }
            val rfc5987 = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            return "$dispositionType; filename=\"$asciiSafe\"; filename*=UTF-8''$rfc5987"
        }

        private fun extractMultipartBoundary(contentType: String): String? {
            for (param in contentType.split(';').drop(1)) {
                val trimmed = param.trim()
                if (trimmed.startsWith("boundary=", ignoreCase = true)) {
                    val raw = trimmed.substring("boundary=".length).trim().trim('"')
                    if (raw.isNotEmpty() && raw.length <= 200) return raw
                }
            }
            return null
        }

        private fun extractDispositionFileName(disposition: String): String? {
            if (disposition.isEmpty()) return null
            for (part in disposition.split(';')) {
                val t = part.trim()
                if (t.startsWith("filename*=", ignoreCase = true)) {
                    val rawVal = t.substring("filename*=".length).trim().trim('"')
                    val encoded = rawVal.substringAfter("''", rawVal)
                    return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
                }
            }
            for (part in disposition.split(';')) {
                val t = part.trim()
                if (t.startsWith("filename=", ignoreCase = true)) {
                    val v = t.substring("filename=".length).trim()
                    return if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
                        v.substring(1, v.length - 1)
                    } else {
                        v
                    }
                }
            }
            return null
        }

        private fun readHeaderLine(input: InputStream, maxBytes: Int): String? {
            val buf = ByteArrayOutputStream(128)
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (sawAny) buf.toString(Charsets.UTF_8.name()).trimEnd('\r') else null
                }
                sawAny = true
                if (b == '\n'.code) break
                buf.write(b)
                if (buf.size() > maxBytes) {
                    throw LanHttpException(414, "URI Too Long", "Header line exceeds maximum length")
                }
            }
            val bytes = buf.toByteArray()
            val len = if (bytes.isNotEmpty() && bytes[bytes.lastIndex] == '\r'.code.toByte()) {
                bytes.size - 1
            } else {
                bytes.size
            }
            return String(bytes, 0, len, Charsets.UTF_8)
        }

        private fun copyExact(input: InputStream, output: OutputStream, byteCount: Long): Long {
            var remaining = byteCount
            var totalSent = 0L
            val buf = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    remaining -= n
                    totalSent += n
                }
            }
            return totalSent
        }

        private fun writeHeaders(
            out: OutputStream,
            status: Int,
            reason: String,
            headers: List<Pair<String, String>>,
        ) {
            val sb = StringBuilder(256)
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((k, v) in headers) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("X-Content-Type-Options: nosniff\r\n")
            sb.append("Date: ").append(httpDateNow()).append("\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        }

        private fun writeSimpleResponse(
            out: OutputStream,
            status: Int,
            reason: String,
            contentType: String,
            body: ByteArray,
            headOnly: Boolean,
            closeConnection: Boolean,
            extraHeaders: List<Pair<String, String>> = emptyList(),
        ) {
            val hdrs = ArrayList<Pair<String, String>>(4 + extraHeaders.size)
            hdrs.add("Content-Type" to contentType)
            hdrs.add("Content-Length" to body.size.toString())
            hdrs.add("Cache-Control" to "no-store")
            hdrs.add("Connection" to if (closeConnection) "close" else "keep-alive")
            hdrs.addAll(extraHeaders)
            writeHeaders(out, status, reason, hdrs)
            if (!headOnly && body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()
        }

        private fun writeJsonOk(
            out: OutputStream,
            payload: JsonValue,
            headOnly: Boolean,
            close: Boolean,
            extraHeaders: List<Pair<String, String>> = emptyList(),
        ): Boolean = writeJsonResponse(out, 200, "OK", payload, headOnly, close, extraHeaders)

        private fun writeJsonResponse(
            out: OutputStream,
            status: Int,
            reason: String,
            payload: JsonValue,
            headOnly: Boolean,
            close: Boolean,
            extraHeaders: List<Pair<String, String>> = emptyList(),
        ): Boolean {
            val bytes = LanJson.stringify(payload).toByteArray(Charsets.UTF_8)
            writeSimpleResponse(out, status, reason, "application/json; charset=utf-8", bytes, headOnly, close, extraHeaders)
            return !close
        }

        private fun writeJsonError(
            out: OutputStream,
            status: Int,
            reason: String,
            message: String,
            headOnly: Boolean,
            close: Boolean,
            extraFields: List<Pair<String, JsonValue>> = emptyList(),
        ) {
            val map = LinkedHashMap<String, JsonValue>()
            map["ok"] = JsonValue.Bool(false)
            map["error"] = JsonValue.Str(message)
            for ((k, v) in extraFields) map[k] = v
            val bytes = LanJson.stringify(JsonValue.Obj(map)).toByteArray(Charsets.UTF_8)
            writeSimpleResponse(out, status, reason, "application/json; charset=utf-8", bytes, headOnly, close)
        }

        private fun httpDateNow(): String {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            return fmt.format(Date())
        }

        // ---------------- Domain Model JSON Serializers ----------------

        private fun serializeSafRoot(r: SafRoot): JsonValue.Obj = LanJson.obj(
            "rootId" to JsonValue.Num(r.rootId),
            "treeUri" to JsonValue.Str(r.treeUri),
            "treeDocId" to JsonValue.Str(r.treeDocId),
            "displayName" to JsonValue.Str(r.displayName),
            "isWritable" to JsonValue.Bool(r.isWritable),
            "mountState" to JsonValue.Str(r.mountState.name),
            "itemCount" to JsonValue.Num(r.itemCount),
            "isPracticeFolder" to JsonValue.Bool(r.isPracticeFolder),
            "lastScanAt" to JsonValue.Num(r.lastScanAt),
        )

        private fun serializeSubfolder(sf: SubfolderEntry): JsonValue.Obj = LanJson.obj(
            "folderUri" to JsonValue.Str(sf.folderUri),
            "rootId" to JsonValue.Num(sf.rootId),
            "relPath" to JsonValue.Str(sf.relPath),
            "name" to JsonValue.Str(sf.name),
            "itemCount" to JsonValue.Num(sf.itemCount),
            "isPracticeFolder" to JsonValue.Bool(sf.isPracticeFolder),
            "hasNomedia" to JsonValue.Bool(sf.hasNomedia),
        )

        private fun serializeMediaItem(it: MediaItem): JsonValue.Obj = LanJson.obj(
            "itemId" to JsonValue.Num(it.itemId),
            "documentUri" to JsonValue.Str(it.documentUri),
            "rootId" to JsonValue.Num(it.rootId),
            "parentRelPath" to JsonValue.Str(it.parentRelPath),
            "fileName" to JsonValue.Str(it.fileName),
            "mimeType" to JsonValue.Str(it.mimeType),
            "isVideo" to JsonValue.Bool(it.isVideo),
            "sizeBytes" to JsonValue.Num(it.sizeBytes),
            "lastModified" to JsonValue.Num(it.lastModified),
            "durationMs" to JsonValue.Num(it.durationMs),
            "width" to JsonValue.Num(it.width),
            "height" to JsonValue.Num(it.height),
            "resolutionBadge" to LanJson.strOrNull(it.resolutionBadge),
            "title" to JsonValue.Str(it.title),
            "artist" to JsonValue.Str(it.artist),
            "album" to LanJson.strOrNull(it.album),
            "displayAlbum" to JsonValue.Str(it.displayAlbum),
            "albumArtist" to LanJson.strOrNull(it.albumArtist),
            "trackNumber" to LanJson.numOrNull(it.trackNumber),
            "year" to LanJson.numOrNull(it.year),
            "hasCover" to JsonValue.Bool(!it.coverPathOrUri.isNullOrBlank()),
            "hasMetadataOverride" to JsonValue.Bool(it.hasMetadataOverride),
            "isBackingTrack" to JsonValue.Bool(it.isBackingTrack),
            "isRootWritable" to JsonValue.Bool(it.isRootWritable),
            "isInPracticeFolder" to JsonValue.Bool(it.isInPracticeFolder),
            "lastPositionMs" to JsonValue.Num(it.lastPositionMs),
            "bookmarkCount" to JsonValue.Num(it.bookmarkCount),
            "hasPracticeRecord" to JsonValue.Bool(it.hasPracticeRecord),
            "savedPracticeSpeed" to JsonValue.Num(it.savedPracticeSpeed),
            "savedSemitones" to JsonValue.Num(it.savedSemitones),
        )

        private fun serializeAlbumSummary(a: AlbumSummary): JsonValue.Obj = LanJson.obj(
            "albumKey" to JsonValue.Str(a.albumKey),
            "title" to JsonValue.Str(a.title),
            "artist" to JsonValue.Str(a.artist),
            "year" to LanJson.numOrNull(a.year),
            "trackCount" to JsonValue.Num(a.trackCount),
            "isUnknownAlbum" to JsonValue.Bool(a.isUnknownAlbum),
        )

        private fun serializeArtistSummary(a: ArtistSummary): JsonValue.Obj = LanJson.obj(
            "artistNorm" to JsonValue.Str(a.artistNorm),
            "displayName" to JsonValue.Str(a.displayName),
            "albumCount" to JsonValue.Num(a.albumCount),
            "trackCount" to JsonValue.Num(a.trackCount),
        )

        private fun serializeMetadataCandidate(c: MetadataCandidate): JsonValue.Obj = LanJson.obj(
            "source" to JsonValue.Str(c.source.name),
            "title" to JsonValue.Str(c.title),
            "artist" to JsonValue.Str(c.artist),
            "album" to LanJson.strOrNull(c.album),
            "albumArtist" to LanJson.strOrNull(c.albumArtist),
            "trackNumber" to LanJson.numOrNull(c.trackNumber),
            "year" to LanJson.numOrNull(c.year),
            "durationMs" to LanJson.numOrNull(c.durationMs),
            "coverUrl" to LanJson.strOrNull(c.coverUrl),
            "syncedLrc" to LanJson.strOrNull(c.syncedLrc),
            "plainLyrics" to LanJson.strOrNull(c.plainLyrics),
            "confidenceScore" to JsonValue.Num(c.confidenceScore),
        )

        private fun deserializeMetadataCandidate(obj: JsonValue.Obj): MetadataCandidate {
            val src = obj.str("source")?.let {
                runCatching { OnlineSource.valueOf(it.uppercase(Locale.ROOT)) }.getOrNull()
            } ?: OnlineSource.ITUNES
            return MetadataCandidate(
                source = src,
                title = obj.str("title").orEmpty(),
                artist = obj.str("artist").orEmpty(),
                album = obj.str("album"),
                albumArtist = obj.str("albumArtist"),
                trackNumber = obj.intOrNull("trackNumber"),
                year = obj.intOrNull("year"),
                durationMs = obj.longOrNull("durationMs"),
                coverUrl = obj.str("coverUrl"),
                syncedLrc = obj.str("syncedLrc"),
                plainLyrics = obj.str("plainLyrics"),
                confidenceScore = obj.int("confidenceScore", 90),
            )
        }

        private fun serializeBatchMatchItem(m: BatchMatchItem): JsonValue.Obj = LanJson.obj(
            "itemId" to JsonValue.Num(m.mediaItem.itemId),
            "fileName" to JsonValue.Str(m.mediaItem.fileName),
            "cleanedTitle" to JsonValue.Str(m.cleanedTitle),
            "selectedCandidateIndex" to JsonValue.Num(m.selectedCandidateIndex),
            "preserveBackingSuffix" to JsonValue.Bool(m.preserveBackingSuffix),
            "candidates" to JsonValue.Arr(m.candidates.map(::serializeMetadataCandidate)),
        )

        private fun serializePlaylistSummary(p: PlaylistSummary): JsonValue.Obj = LanJson.obj(
            "playlistId" to JsonValue.Num(p.playlistId),
            "name" to JsonValue.Str(p.name),
            "isPracticePlaylist" to JsonValue.Bool(p.isPracticePlaylist),
            "itemCount" to JsonValue.Num(p.itemCount),
            "totalBookmarks" to JsonValue.Num(p.totalBookmarks),
            "updatedAt" to JsonValue.Num(p.updatedAt),
        )

        private fun serializePlaylistDetail(d: PlaylistDetail): JsonValue.Obj = LanJson.obj(
            "summary" to serializePlaylistSummary(d.summary),
            "items" to JsonValue.Arr(d.items.map(::serializeMediaItem)),
        )

        private fun serializePracticeState(s: PracticeState): JsonValue.Obj = LanJson.obj(
            "itemId" to JsonValue.Num(s.itemId),
            "practiceSpeed" to JsonValue.Num(s.practiceSpeed),
            "pitchSemitones" to JsonValue.Num(s.pitchSemitones),
            "pitchCents" to JsonValue.Num(s.pitchCents),
            "bpm" to JsonValue.Num(s.bpm),
            "countInMode" to JsonValue.Str(s.countInMode.name),
            "preRollEnabled" to JsonValue.Bool(s.preRollEnabled),
            "loopEnabled" to JsonValue.Bool(s.loopEnabled),
            "activeAMs" to LanJson.numOrNull(s.activeAMs),
            "activeBMs" to LanJson.numOrNull(s.activeBMs),
            "videoMirror" to JsonValue.Bool(s.videoMirror),
            "videoZoom" to JsonValue.Num(s.videoZoom),
            "hudDockTop" to JsonValue.Bool(s.hudDockTop),
            "videoSeekMode" to JsonValue.Str(s.videoSeekMode.name),
        )

        private fun serializeBookmark(b: PracticeBookmark): JsonValue.Obj = LanJson.obj(
            "bookmarkId" to JsonValue.Num(b.bookmarkId),
            "itemId" to JsonValue.Num(b.itemId),
            "label" to JsonValue.Str(b.label),
            "aMs" to JsonValue.Num(b.aMs),
            "bMs" to JsonValue.Num(b.bMs),
            "savedSpeed" to LanJson.numOrNull(b.savedSpeed),
            "createdAt" to JsonValue.Num(b.createdAt),
        )
    }
}

private object EmptyInputStream : InputStream() {
    override fun read(): Int = -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else -1
}

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = delegate.read(b, off, toRead)
        if (n > 0) remaining -= n
        return n
    }
}

private class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
    private var chunkRemaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n < 0) -1 else (single[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        if (chunkRemaining == 0L) {
            advanceChunk()
            if (finished) return -1
        }
        val toRead = minOf(len.toLong(), chunkRemaining).toInt()
        val n = delegate.read(b, off, toRead)
        if (n < 0) throw EOFException("Unexpected EOF inside HTTP chunk")
        chunkRemaining -= n
        if (chunkRemaining == 0L) {
            expectCrlf()
        }
        return n
    }

    private fun advanceChunk() {
        val line = readAsciiLine()
        val sizeHex = line.substringBefore(';').trim()
        val size = sizeHex.toLongOrNull(16)
            ?: throw IOException("Invalid chunk size: $sizeHex")
        if (size == 0L) {
            finished = true
            while (readAsciiLine().isNotEmpty()) {
                // Consume trailer headers
            }
        } else {
            chunkRemaining = size
        }
    }

    private fun expectCrlf() {
        val b1 = delegate.read()
        if (b1 == '\r'.code) {
            delegate.read()
        }
    }

    private fun readAsciiLine(): String {
        val sb = StringBuilder(32)
        while (true) {
            val b = delegate.read()
            if (b < 0) throw EOFException("Unexpected EOF reading chunk header")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) throw IOException("Chunk header line too long")
        }
        return sb.toString()
    }
}

private class MultipartStreamReader(private val input: InputStream) {
    val buf = ByteArray(64 * 1024)
    var pos = 0
    var limit = 0
    var eof = false

    fun fill(minAvailable: Int): Boolean {
        if (limit - pos >= minAvailable) return true
        if (pos > 0) {
            val rem = limit - pos
            if (rem > 0) {
                System.arraycopy(buf, pos, buf, 0, rem)
            }
            pos = 0
            limit = rem
        }
        while (!eof && limit - pos < minAvailable && limit < buf.size) {
            val n = input.read(buf, limit, buf.size - limit)
            if (n < 0) {
                eof = true
                break
            }
            if (n > 0) limit += n
        }
        return limit - pos >= minAvailable
    }

    fun readCrlfLine(maxBytes: Int): String? {
        val lineOut = ByteArrayOutputStream(128)
        while (true) {
            if (!fill(1)) {
                return if (lineOut.size() > 0) {
                    lineOut.toString(Charsets.UTF_8.name()).trimEnd('\r')
                } else {
                    null
                }
            }
            val b = buf[pos++]
            if (b == '\n'.code.toByte()) break
            lineOut.write(b.toInt() and 0xFF)
            if (lineOut.size() > maxBytes) {
                throw LanHttpException(400, "Bad Request", "Multipart header line too long")
            }
        }
        val bytes = lineOut.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes[bytes.lastIndex] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    fun streamUntilBoundary(delimiter: ByteArray, out: OutputStream): Pair<Long, Boolean> {
        val partStream = MultipartPartInputStream(this, delimiter)
        val scratch = ByteArray(16 * 1024)
        var written = 0L
        while (true) {
            val n = partStream.read(scratch)
            if (n < 0) break
            if (n > 0) {
                out.write(scratch, 0, n)
                written += n
            }
        }
        return written to partStream.reachedFinalBoundary
    }
}

private class MultipartPartInputStream(
    private val reader: MultipartStreamReader,
    private val delimiter: ByteArray,
) : InputStream() {
    private var finished = false
    var reachedFinalBoundary = false
        private set

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n < 0) -1 else (single[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1

        while (true) {
            reader.fill(delimiter.size + 4)
            val avail = reader.limit - reader.pos
            if (avail <= 0) {
                throw LanHttpException(400, "Bad Request", "Unexpected EOF before multipart boundary")
            }
            val matchOffset = indexOfSubArray(reader.buf, reader.pos, reader.limit, delimiter)
            if (matchOffset >= 0) {
                val chunkLen = matchOffset - reader.pos
                if (chunkLen > 0) {
                    val toCopy = minOf(len, chunkLen)
                    System.arraycopy(reader.buf, reader.pos, b, off, toCopy)
                    reader.pos += toCopy
                    return toCopy
                }
                // Positioned right at delimiter
                reader.pos += delimiter.size
                reader.fill(4)
                val rem = reader.limit - reader.pos
                if (rem >= 2 && reader.buf[reader.pos] == '-'.code.toByte() && reader.buf[reader.pos + 1] == '-'.code.toByte()) {
                    reader.pos += 2
                    if (reader.limit - reader.pos >= 2 && reader.buf[reader.pos] == '\r'.code.toByte() && reader.buf[reader.pos + 1] == '\n'.code.toByte()) {
                        reader.pos += 2
                    } else if (reader.limit - reader.pos >= 1 && reader.buf[reader.pos] == '\n'.code.toByte()) {
                        reader.pos += 1
                    }
                    reachedFinalBoundary = true
                    finished = true
                    return -1
                }
                if (rem >= 2 && reader.buf[reader.pos] == '\r'.code.toByte() && reader.buf[reader.pos + 1] == '\n'.code.toByte()) {
                    reader.pos += 2
                    reachedFinalBoundary = false
                    finished = true
                    return -1
                }
                if (rem >= 1 && reader.buf[reader.pos] == '\n'.code.toByte()) {
                    reader.pos += 1
                    reachedFinalBoundary = false
                    finished = true
                    return -1
                }
                throw LanHttpException(400, "Bad Request", "Malformed multipart boundary terminator")
            }

            if (reader.eof) {
                throw LanHttpException(400, "Bad Request", "Missing closing multipart boundary")
            }

            val keepTail = delimiter.size - 1
            val safeAvail = avail - keepTail
            if (safeAvail > 0) {
                val toCopy = minOf(len, safeAvail)
                System.arraycopy(reader.buf, reader.pos, b, off, toCopy)
                reader.pos += toCopy
                return toCopy
            } else {
                val prevLimit = reader.limit
                reader.fill(avail + 1)
                if (reader.limit == prevLimit && reader.eof) {
                    throw LanHttpException(400, "Bad Request", "Truncated multipart upload stream")
                }
            }
        }
    }

    fun drainRemainderIfAny() {
        if (finished) return
        val scratch = ByteArray(4096)
        while (read(scratch) >= 0) {
            // drain
        }
    }

    private fun indexOfSubArray(haystack: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
        val maxStart = to - needle.size
        if (maxStart < from) return -1
        val first = needle[0]
        outer@ for (i in from..maxStart) {
            if (haystack[i] != first) continue
            for (j in 1 until needle.size) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
