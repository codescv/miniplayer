package com.miniplayer.app.core

import android.content.Context
import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.db.MiniPlayerDatabase
import com.miniplayer.app.core.metadata.DefaultMetadataEngine
import com.miniplayer.app.core.metadata.LrclibClient
import com.miniplayer.app.core.metadata.OnlineMetadataCompleter
import com.miniplayer.app.core.metadata.UrlConnectionOnlineHttpClient
import com.miniplayer.app.core.scanner.AndroidSafTreeDataSource
import com.miniplayer.app.core.scanner.SafFolderScanner
import com.miniplayer.app.core.update.AndroidAppUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Production implementation of [MiniPlayerCoreProvider] dynamically bound via [CoreRegistry.get].
 */
class DefaultMiniPlayerCore(
    context: Context,
) : MiniPlayerCoreProvider {

    private val appContext: Context = context.applicationContext ?: context
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database = MiniPlayerDatabase(appContext)
    private val mediaRepository = MediaRepository(
        backend = database.openBackend(),
        cacheRootDir = appContext.cacheDir,
        filesRootDir = appContext.filesDir,
    )

    private val safScanner = SafFolderScanner(
        repository = mediaRepository,
        dataSource = AndroidSafTreeDataSource(appContext),
    )

    private val httpClient = UrlConnectionOnlineHttpClient()
    private val onlineCompleter = OnlineMetadataCompleter(http = httpClient)
    private val metadataEngine = DefaultMetadataEngine(
        context = appContext,
        repository = mediaRepository,
        onlineCompleter = onlineCompleter,
        lrclibClient = LrclibClient(httpClient),
    )

    private val updateService = AndroidAppUpdateService(appContext)

    override val library: MediaLibraryContract get() = mediaRepository
    override val scanner: FolderScannerContract get() = safScanner
    override val metadata: MetadataEngineContract get() = metadataEngine
    override val updater: AppUpdateContract get() = updateService

    init {
        if (mediaRepository.settings.value.autoRescanOnLaunch && mediaRepository.safRoots.value.isNotEmpty()) {
            startupScope.launch {
                runCatching { safScanner.rescanAll(incremental = true) }
            }
        }
    }
}
