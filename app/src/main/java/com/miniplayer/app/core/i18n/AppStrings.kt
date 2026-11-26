package com.miniplayer.app.core.i18n

import com.miniplayer.app.core.metadata.ArtistSplitter
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.BeatGridSource
import com.miniplayer.app.core.model.CountInMode
import com.miniplayer.app.core.model.LibraryView
import com.miniplayer.app.core.model.PlaylistFilter
import com.miniplayer.app.core.model.StemType

/**
 * Complete, strictly single-language UI string table for MiniPlayer Android (`zh`, `en`, `ja`).
 * Pure JVM (zero Android imports) so core modules, ViewModels, LAN server, and unit tests can
 * resolve strings deterministically and assert 100% key parity across all supported languages.
 */
interface AppStrings {
    // Common
    val appName: String
    val tabLibrary: String
    val tabPlaylists: String
    val back: String
    val close: String
    val cancel: String
    val confirm: String
    val save: String
    val create: String
    val delete: String
    val reset: String
    val ignore: String
    val open: String
    val clear: String
    val rename: String
    val moreOptions: String
    val moreActions: String
    val settings: String
    val play: String
    val pause: String
    val previousTrack: String
    val nextTrack: String
    val video: String
    val audio: String
    val singleTrack: String
    val unknownAlbum: String
    val unknownArtist: String
    val variousArtists: String
    val internalStorage: String
    val localFolder: String
    val importedPlaylistFallback: String
    val defaultPracticePlaylistName: String
    val defaultPlaylistName: String
    val defaultSectionLabel: String
    val sectionWithTimeTpl: String
    val unknownAlbumPendingTracksTpl: String

    // Language settings
    val settingsLanguageSection: String
    val settingsLanguageTitle: String
    val settingsLanguageSubtitle: String
    val languageSystem: String
    val languageZhNative: String
    val languageEnNative: String
    val languageJaNative: String

    // Sub-tabs
    val libraryFolders: String
    val libraryAlbums: String
    val libraryArtists: String
    val libraryVideos: String
    val playlistAll: String
    val playlistPractice: String
    val playlistBookmarks: String
    val emptyPracticePlaylists: String
    val emptyPlaylists: String

    // MiniPlayer bar
    val abLoopActive: String
    val practiceMode: String

    // Library screen
    val addFolder: String
    val addLocalFolderCta: String
    val addVideoFolderCta: String
    val emptyFoldersTitle: String
    val emptyFoldersSubtitle: String
    val addedFoldersHeaderTpl: String
    val switchToFlatViewDesc: String
    val switchToTreeViewDesc: String
    val badgePractice: String
    val badgeExcludeGrouping: String
    val badgeOffline: String
    val badgePermissionRevoked: String
    val mediaCountTpl: String
    val readOnlyTag: String
    val folderMenuDesc: String
    val subfolderMenuDesc: String
    val unsetPracticeFolder: String
    val setPracticeFolder: String
    val unsetExcludeGrouping: String
    val setExcludeGrouping: String
    val addAllToPlaylist: String
    val batchAutoMatchTagsAndCover: String
    val batchEditTags: String
    val incrementalRescan: String
    val removeRootFolder: String
    val removeRootConfirmTitleTpl: String
    val removeRootConfirmBody: String
    val removeFromIndex: String
    val navigateUpDirDesc: String
    val practiceFolderBanner: String
    val excludedGroupingBanner: String
    val subfolderItemCountTpl: String
    val emptyDirMessage: String
    val emptyAlbumsTitle: String
    val emptyAlbumsSubtitle: String
    val badgePendingTag: String
    val unknownAlbumCardTitleTpl: String
    val unknownAlbumCardSubtitle: String
    val albumCardSubtitleTpl: String
    val emptyArtistsTitle: String
    val emptyArtistsSubtitle: String
    val artistStatsTpl: String
    val emptyVideosTitle: String
    val emptyVideosSubtitle: String
    val bookmarkCountBadgeTpl: String
    val bookmarkCountShortTpl: String
    val practiceHistoryBadge: String
    val watchedProgressTpl: String
    val videoMenuDesc: String
    val openInPracticeMode: String
    val addToPlaylistOrPractice: String
    val editTrackTags: String
    val albumMenuDesc: String
    val batchAutoComplete: String
    val batchEditAlbumTags: String
    val unknownAlbumBannerTitle: String
    val unknownAlbumBannerSubtitle: String
    val trackCountShortTpl: String
    val playAll: String
    val practicePlayAll: String
    val artistAlbumsHeaderTpl: String
    val artistAllTracksHeaderTpl: String

    // Playlists screen
    val newPlaylist: String
    val emptyPlaylistsSubtitle: String
    val playlistItemsCountTpl: String
    val playlistBookmarksSuffixTpl: String
    val normalPlaylistSuffix: String
    val playlistMenuDesc: String
    val switchToNormalPlaylist: String
    val setAsPracticePlaylist: String
    val exportM3u8: String
    val deletePlaylist: String
    val emptyBookmarksMessage: String
    val artistBookmarksCountTpl: String
    val playlistMoreActionsDesc: String
    val practicePlaylistModeTitle: String
    val practicePlaylistModeSubtitle: String
    val practicePlayAllPlaylist: String
    val addTracksOrVideos: String
    val dragReorderDesc: String
    val semitonesShortTpl: String
    val trackMenuDesc: String
    val moveUp: String
    val moveDown: String
    val removeFromPlaylist: String
    val renamePlaylistTitle: String
    val playlistNameLabel: String
    val playlistNamePlaceholder: String
    val setAsPracticePlaylistSubtitle: String
    val addAudioOrVideoTitle: String
    val addSelectedCountTpl: String
    val filterTracksPlaceholder: String
    val addToPlaylistHeaderTpl: String
    val newPlaylistOrPracticeBtn: String

    // Metadata Editor & Batch Completion
    val batchEditTagsTitleTpl: String
    val searchingOpenLibrary: String
    val autoMatchOnlineBtn: String
    val pickFromGallery: String
    val restoreDefault: String
    val preserveBackingSuffixLabel: String
    val candidatesMatchedHintTpl: String
    val fieldTitle: String
    val fieldTitleBatchLocked: String
    val fieldTitleBatchPlaceholder: String
    val fieldArtist: String
    val fieldAlbum: String
    val fieldAlbumArtist: String
    val fieldTrackNumber: String
    val fieldTrackNumberBatchLocked: String
    val fieldKeepOriginalPlaceholder: String
    val fieldMixedPlaceholder: String
    val fieldYear: String
    val fieldKeepValuePlaceholder: String
    val writeBackAudioTags: String
    val writeDbOnlyHint: String
    val lyricsTagCheck: String
    val candidateStagedPill: String
    val batchAutoCompleteTitle: String
    val batchSearchingProgressTpl: String
    val cleanedTitleFallbackTpl: String
    val switchCandidatesTpl: String
    val preserveBackingChip: String
    val writeBackIfWritable: String
    val writingProgress: String
    val confirmApplyCountTpl: String

    // Now Playing
    val lyricsButtonShort: String
    val addToPlaylistDesc: String
    val shuffleOnDesc: String
    val shuffleOffDesc: String
    val repeatAllDesc: String
    val repeatOneDesc: String
    val playOnceDesc: String
    val completedLoopsBadgeTpl: String
    val prevOrRestartADesc: String
    val rewind5sDesc: String
    val forward5sDesc: String
    val rewind10sDesc: String
    val forward10sDesc: String
    val fileDetailsTitle: String
    val fileDetailNameTpl: String
    val fileDetailPathTpl: String
    val fileDetailFormatTpl: String
    val fileDetailSizeTpl: String
    val fileDetailResolutionTpl: String
    val collapsePlayerDesc: String
    val nowPlayingTitle: String
    val enterLandscapeVideoDesc: String
    val sleepTimer: String
    val currentQueueDesc: String
    val fastSeekActiveSuffix: String
    val videoSeekAutoTpl: String
    val videoSeekExact: String
    val fileDetails: String
    val noLyricsFound: String
    val fetchLyricsLrclibBtn: String
    val queueHeaderTpl: String
    val sleepTimerRemainingSuffixTpl: String
    val sleepTimerTrackEndSuffix: String
    val sleepTimerMinutesTpl: String
    val sleepTimerTrackEndOption: String
    val sleepTimerOffBtn: String

    // Practice Deck & Video Practice Surface
    val barsCountTpl: String
    val waitingPointB: String
    val waveformRulerDescTpl: String
    val updateBookmarkRange: String
    val renameBookmark: String
    val deleteBookmark: String
    val renameBookmarkTitle: String
    val saveBookmarkSheetTitle: String
    val customBookmarkPlaceholder: String
    val presetSectionIntro: String
    val presetSectionVerse: String
    val presetSectionPreChorus: String
    val presetSectionChorus: String
    val presetSectionBridge: String
    val presetSectionSolo1: String
    val presetSectionSolo2: String
    val presetSectionOutro: String
    val setPointADescTpl: String
    val setPointBDescTpl: String
    val suspendLoopDesc: String
    val resumeLoopDesc: String
    val clearLoopDesc: String
    val saveBookmarkDesc: String
    val speedResetDescTpl: String
    val pitchControlDesc: String
    val stemMixerDesc: String
    val countInOnDesc: String
    val countInOffDesc: String
    val countInBadge4: String
    val countInOffShort: String
    val keepScreenOnEnabledDesc: String
    val keepScreenOnDisabledDesc: String
    val videoMirrorOnDesc: String
    val videoMirrorOffDesc: String
    val bpmChipDescTpl: String
    val effectiveBpmTpl: String
    val detectedBpmSuffixTpl: String
    val bpmMinusDesc: String
    val bpmPlusDesc: String
    val tapTempoLabel: String
    val detectingLabel: String
    val autoDetectLabel: String
    val barSnapLabel: String
    val alignDownbeatDesc: String
    val alignDownbeatLabel: String
    val firstDownbeatTpl: String
    val beatGridSourceManual: String
    val beatGridSourceStems: String
    val beatGridSourceTag: String
    val beatOneLabel: String
    val shiftBeatMinusLabel: String
    val shiftBeatMinusDesc: String
    val shiftBeatPlusLabel: String
    val shiftBeatPlusDesc: String
    val shiftHalfBarLabel: String
    val shiftHalfBarAmbiguousDesc: String
    val shiftHalfBarDesc: String
    val nudgeMsMinusDesc: String
    val nudgeMsPlusDesc: String
    val pendingStemsGridTpl: String
    val pitchMinusSt: String
    val pitchPlusSt: String
    val pitchOriginal: String
    val pitchResetTpl: String
    val pitchHintMinus1: String
    val pitchHintPlus1: String
    val pitchHintDefault: String
    val centsSliderDescTpl: String
    val stemModelTitle: String
    val stemModelSubtitle: String
    val stemDownloadingTpl: String
    val stemDownloadBtn: String
    val stemSeparatingSegTpl: String
    val stemDecodingTpl: String
    val stemSeparateTrackBtn: String
    val stemReadyBadge: String
    val stemVocals: String
    val stemGuitar: String
    val stemBass: String
    val stemDrums: String
    val stemOther: String
    val stemVolumeDescTpl: String
    val stemUnmuteDescTpl: String
    val stemMuteDescTpl: String
    val stemUnsoloDescTpl: String
    val stemSoloDescTpl: String
    val videoZoomResetTpl: String
    val videoZoomResetCompactTpl: String
    val videoMirroredBadge: String
    val exitFullscreenDesc: String
    val disableVideoMirrorDesc: String
    val enableVideoMirrorDesc: String
    val dockHudBottomDesc: String
    val dockHudTopDesc: String
    val exitPracticeModeDesc: String
    val portraitModeDesc: String
    val loopCounterCapsuleTpl: String
    val countInHeadlineTpl: String
    val countInSkipHint: String

    // Settings & Agent Feedback
    val settingsLibrarySection: String
    val settingsLanStudioTitle: String
    val settingsLanStudioSubtitle: String
    val settingsAutoRescanTitle: String
    val settingsAutoRescanSubtitle: String
    val settingsCacheSection: String
    val settingsClearCacheTitle: String
    val settingsClearCacheSubtitleTpl: String
    val settingsAboutSection: String
    val settingsAboutTagline: String
    val settingsCheckingUpdate: String
    val settingsCheckUpdateBtn: String
    val settingsNewVersionTpl: String
    val settingsDownloadInstallBtn: String
    val settingsAlreadyLatestTpl: String
    val settingsDownloadingApkTpl: String
    val settingsVerifiedApkTpl: String
    val settingsInstallNowBtn: String
    val feedbackEntryTitle: String
    val feedbackEmailUnset: String
    val feedbackEmailSetTpl: String
    val feedbackSendButton: String
    val openSourceLicensesBtn: String
    val openSourceLicensesTitle: String
    val openSourceLicensesBody: String
    val feedbackSheetSubtitleTpl: String
    val feedbackEmailLabel: String
    val feedbackEmailInvalidHintTpl: String
    val feedbackEmailCleared: String
    val feedbackEmailSaved: String
    val feedbackTitleLabel: String
    val feedbackTitlePlaceholder: String
    val feedbackEmailSubjectPreviewTpl: String
    val feedbackDetailsLabel: String
    val feedbackDetailsPlaceholder: String
    val feedbackStagingImage: String
    val feedbackChangeImage: String
    val feedbackAddImage: String
    val feedbackRemoveImageDesc: String
    val feedbackReadImageFailedTpl: String
    val feedbackRetryFallback: String
    val feedbackIncludeDiagnosticsTitle: String
    val feedbackIncludeDiagnosticsSubtitle: String
    val feedbackHintMissingEmail: String
    val feedbackHintMissingContent: String
    val feedbackEmailRequiredErrorTpl: String
    val feedbackNoMailClient: String
    val feedbackCannotReadImage: String
    val feedbackImageTooLarge: String
    val feedbackImageEmpty: String
    val feedbackInvalidEmailError: String
    val feedbackDefaultSummary: String
    val feedbackAttachmentOnlySummary: String
    val feedbackDiagHeader: String
    val feedbackDiagAppVersion: String
    val feedbackDiagDevice: String
    val feedbackDiagMedia: String
    val feedbackDiagPlaying: String
    val feedbackDiagPaused: String
    val feedbackDiagLoopActive: String
    val feedbackDiagLoopSuspended: String
    val feedbackDiagNoLoop: String
    val feedbackDiagPracticeState: String
    val feedbackDiagSpeed: String
    val feedbackDiagPitch: String
    val feedbackDiagBeatGrid: String
    val feedbackDiagBeatOne: String
    val feedbackDiagPhraseOffset: String
    val feedbackDiagSource: String
    val feedbackDiagPlaybackMode: String
    val feedbackDiagCasualMode: String
    val feedbackDiagAttachment: String

    // ViewModel Snackbars & Errors
    val msgFolderAddedTpl: String
    val msgFolderAlreadyCoveredTpl: String
    val msgFolderMergedTpl: String
    val msgIncrementalRescanDone: String
    val msgRootRescanDone: String
    val msgRootRemoved: String
    val msgSetPracticeFolder: String
    val msgUnsetPracticeFolder: String
    val msgSetExcludeGrouping: String
    val msgUnsetExcludeGrouping: String
    val msgFolderNoMediaToAdd: String
    val msgCreatedPracticePlaylistTpl: String
    val msgCreatedPlaylistTpl: String
    val msgPlaylistPracticeEnabled: String
    val msgPlaylistPracticeDisabled: String
    val msgPlaylistDeleted: String
    val msgAddedTracksCountTpl: String
    val msgImportedPlaylistTpl: String
    val msgImportedPlaylistUnmatchedSuffixTpl: String
    val msgExportedM3u8: String
    val msgExportM3u8Failed: String
    val msgBookmarkSavedTpl: String
    val msgBookmarkDeleted: String
    val msgLyricsNotFound: String
    val msgOnlineCandidatesEmpty: String
    val msgOnlineSearchFailedTpl: String
    val msgCheckNetworkFallback: String
    val msgMetaSavedDbOnly: String
    val msgMetaSavedDbAndFile: String
    val msgMetaSavedDeferred: String
    val msgMetaSavedReadonlyFallback: String
    val msgAppliedCandidateTpl: String
    val msgResetMetadataDone: String
    val msgNoAudioForBatch: String
    val msgBatchSearchFailedTpl: String
    val msgNetworkErrorFallback: String
    val msgBatchCommitDoneTpl: String
    val msgCacheCleared: String
    val msgCacheClearedFreedSuffixTpl: String
    val msgUpdateCheckFailed: String
    val msgUpdateDownloadFailed: String
    val msgInstallLaunchFailedTpl: String
    val msgGrantInstallPermission: String
    val msgSafPermissionFailedTpl: String
    val msgPermissionDenied: String

    // StemSeparationEngine errors
    val stemErrVerifyFailedTpl: String
    val stemErrDownloadNetwork: String
    val stemErrTooManyRedirectsTpl: String
    val stemErrHttpStatusTpl: String
    val stemErrDownloadFailed: String
    val stemErrSeparationFailed: String
    val stemErrModelMissing: String
    val stemErrDecodeFailed: String
    val stemErrMapCacheFailed: String

    // LAN Studio Sheet
    val lanIfaceWifi: String
    val lanIfaceHotspot: String
    val lanIfaceUsb: String
    val lanIfaceEthernet: String
    val lanIfaceLan: String
    val lanStartFailedTpl: String
    val lanUnknownError: String
    val lanActivityReady: String
    val lanActivityUploadedTpl: String
    val lanActivityStopped: String
    val lanActivityPinEnabled: String
    val lanActivityPinDisabled: String
    val lanRunningSubtitleTpl: String
    val lanStoppedSubtitle: String
    val lanRefreshIpDesc: String
    val lanNoNetworkHint: String
    val lanQrDescTpl: String
    val lanPinLabelTpl: String
    val lanCopiedAddress: String
    val lanCopyAddress: String
    val lanStatClients: String
    val lanStatRequests: String
    val lanStatUploads: String
    val lanUploadedValueTpl: String
    val lanFeaturesHeader: String
    val lanFeaturesBullets: String
    val lanPinToggleTitle: String
    val lanPinToggleSubtitle: String
    val lanStopServerBtn: String
    val lanStartServerBtnTpl: String

    val presetSectionLabels: List<String>
        get() = listOf(
            presetSectionIntro,
            presetSectionVerse,
            presetSectionPreChorus,
            presetSectionChorus,
            presetSectionBridge,
            presetSectionSolo1,
            presetSectionSolo2,
            presetSectionOutro,
        )

    fun languageOptionLabel(option: AppLanguage): String = when (option) {
        AppLanguage.SYSTEM -> languageSystem
        AppLanguage.ZH -> languageZhNative
        AppLanguage.EN -> languageEnNative
        AppLanguage.JA -> languageJaNative
    }

    fun libraryViewLabel(view: LibraryView): String = when (view) {
        LibraryView.FOLDERS -> libraryFolders
        LibraryView.ALBUMS -> libraryAlbums
        LibraryView.ARTISTS -> libraryArtists
        LibraryView.VIDEOS -> libraryVideos
    }

    fun playlistFilterLabel(filter: PlaylistFilter): String = when (filter) {
        PlaylistFilter.ALL -> playlistAll
        PlaylistFilter.PRACTICE_ONLY -> playlistPractice
        PlaylistFilter.WITH_BOOKMARKS -> playlistBookmarks
    }

    fun stemLabel(stem: StemType): String = when (stem) {
        StemType.VOCALS -> stemVocals
        StemType.GUITAR -> stemGuitar
        StemType.BASS -> stemBass
        StemType.DRUMS -> stemDrums
        StemType.OTHER -> stemOther
    }

    fun countInShortLabel(mode: CountInMode): String = when (mode) {
        CountInMode.OFF -> countInOffShort
        else -> countInBadge4
    }

    fun beatGridSourceSuffix(source: BeatGridSource): String = when (source) {
        BeatGridSource.MANUAL -> beatGridSourceManual
        BeatGridSource.AUTO_STEMS -> beatGridSourceStems
        BeatGridSource.EMBEDDED_TAG -> beatGridSourceTag
        BeatGridSource.AUTO_MIX, BeatGridSource.AUTO_LEGACY -> ""
    }

    fun displayAlbum(rawAlbum: String?): String {
        val trimmed = rawAlbum?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == AlbumSummary.UNKNOWN_ALBUM_TITLE) {
            unknownAlbum
        } else {
            trimmed
        }
    }

    fun displayAlbumTitle(album: AlbumSummary): String =
        if (album.isUnknownAlbum || album.title == AlbumSummary.UNKNOWN_ALBUM_TITLE) {
            unknownAlbum
        } else {
            album.title
        }

    fun displayArtist(rawArtist: String?): String {
        val trimmed = rawArtist?.trim().orEmpty()
        return when {
            trimmed.isEmpty() || trimmed == ArtistSplitter.UNKNOWN_ARTIST_DISPLAY -> unknownArtist
            trimmed.equals(AlbumSummary.VARIOUS_ARTISTS_KEY, ignoreCase = true) -> variousArtists
            else -> trimmed
        }
    }

    fun fmt(template: String, vararg args: Any?): String {
        var out = template
        args.forEachIndexed { index, arg ->
            out = out.replace("{$index}", arg?.toString().orEmpty())
        }
        return out
    }

    companion object {
        fun forLanguage(language: ResolvedLanguage): AppStrings = when (language) {
            ResolvedLanguage.ZH -> ZH
            ResolvedLanguage.EN -> EN
            ResolvedLanguage.JA -> JA
        }

        fun current(): AppStrings = forLanguage(AppLocaleHolder.activeLanguage)

        val ZH: AppStrings = object : AppStrings {
            override val appName = "Play"
            override val tabLibrary = "媒体库"
            override val tabPlaylists = "播放列表"
            override val back = "返回"
            override val close = "关闭"
            override val cancel = "取消"
            override val confirm = "确定"
            override val save = "保存"
            override val create = "创建"
            override val delete = "删除"
            override val reset = "重置"
            override val ignore = "忽略"
            override val open = "打开"
            override val clear = "清理"
            override val rename = "重命名"
            override val moreOptions = "更多选项"
            override val moreActions = "更多操作"
            override val settings = "设置"
            override val play = "播放"
            override val pause = "暂停"
            override val previousTrack = "上一首"
            override val nextTrack = "下一首"
            override val video = "视频"
            override val audio = "音频"
            override val singleTrack = "单曲"
            override val unknownAlbum = "未知专辑"
            override val unknownArtist = "未知歌手"
            override val variousArtists = "群星"
            override val internalStorage = "内置存储"
            override val localFolder = "本地文件夹"
            override val importedPlaylistFallback = "导入列表"
            override val defaultPracticePlaylistName = "新建练习列表"
            override val defaultPlaylistName = "新建播放列表"
            override val defaultSectionLabel = "段落"
            override val sectionWithTimeTpl = "段落 {0}"
            override val unknownAlbumPendingTracksTpl = "{0} 首待整理"

            override val settingsLanguageSection = "语言"
            override val settingsLanguageTitle = "应用语言"
            override val settingsLanguageSubtitle = "切换后立即生效，无需重启，局域网管理台同步更新"
            override val languageSystem = "跟随系统"
            override val languageZhNative = "中文"
            override val languageEnNative = "English"
            override val languageJaNative = "日本語"

            override val libraryFolders = "文件夹"
            override val libraryAlbums = "专辑"
            override val libraryArtists = "歌手"
            override val libraryVideos = "视频"
            override val playlistAll = "全部"
            override val playlistPractice = "练习列表"
            override val playlistBookmarks = "含段落书签"
            override val emptyPracticePlaylists = "暂无练习列表"
            override val emptyPlaylists = "暂无自建播放列表"

            override val abLoopActive = "A-B 循环中"
            override val practiceMode = "练习模式"

            override val addFolder = "添加文件夹"
            override val addLocalFolderCta = "+ 添加本地文件夹"
            override val addVideoFolderCta = "+ 添加视频文件夹"
            override val emptyFoldersTitle = "只扫描你指定的本地文件夹"
            override val emptyFoldersSubtitle = "绝不申请全局存储权限，绝不把系统铃声、语音碎片混入曲库。"
            override val addedFoldersHeaderTpl = "已添加的文件夹 ({0})"
            override val switchToFlatViewDesc = "当前层级目录树，点击切换平铺视图"
            override val switchToTreeViewDesc = "当前平铺视图，点击切换层级目录树"
            override val badgePractice = "练习"
            override val badgeExcludeGrouping = "不自动归类"
            override val badgeOffline = "未挂载"
            override val badgePermissionRevoked = "需重新授权"
            override val mediaCountTpl = "{0} 个媒体"
            override val readOnlyTag = "只读"
            override val folderMenuDesc = "文件夹菜单"
            override val subfolderMenuDesc = "子文件夹菜单"
            override val unsetPracticeFolder = "取消练习文件夹"
            override val setPracticeFolder = "设为练习文件夹"
            override val unsetExcludeGrouping = "取消不自动归类"
            override val setExcludeGrouping = "设为不自动归类"
            override val addAllToPlaylist = "全部添加到播放列表"
            override val batchAutoMatchTagsAndCover = "批量自动补全标签与封面"
            override val batchEditTags = "批量编辑标签"
            override val incrementalRescan = "增量重新扫描"
            override val removeRootFolder = "移除此根文件夹"
            override val removeRootConfirmTitleTpl = "移除「{0}」？"
            override val removeRootConfirmBody = "仅从 MiniPlayer 媒体库索引移除，绝不会删除手机或存储盘上的任何物理文件。"
            override val removeFromIndex = "移除索引"
            override val navigateUpDirDesc = "返回上级目录"
            override val practiceFolderBanner = "当前为练习文件夹：点播任意曲目/视频将自动展开练习工作台"
            override val excludedGroupingBanner = "当前文件夹已开启「不自动归类」：此目录下的文件不会显示在专辑、歌手、视频中"
            override val subfolderItemCountTpl = "{0} 首媒体"
            override val emptyDirMessage = "此目录暂无匹配的本地音频或视频文件"
            override val emptyAlbumsTitle = "暂无专辑归类"
            override val emptyAlbumsSubtitle = "添加本地音乐文件夹后，将按专辑与专辑歌手自动聚合并分离「未知专辑」。"
            override val badgePendingTag = "待补全"
            override val unknownAlbumCardTitleTpl = "未知专辑 ({0} 首)"
            override val unknownAlbumCardSubtitle = "无内嵌专辑标签 · 点击批量识别"
            override val albumCardSubtitleTpl = "{0} · {1} 首"
            override val emptyArtistsTitle = "暂无歌手索引"
            override val emptyArtistsSubtitle = "添加音乐文件夹后，将自动智能拆分合唱与多歌手标签。"
            override val artistStatsTpl = "{0} 张专辑 · {1} 首单曲"
            override val emptyVideosTitle = "暂无本地教学或音乐视频"
            override val emptyVideosSubtitle = "支持 .mp4 / .mkv / .webm / .mov，提供双指 4x 放大看指板、镜像翻转与 A-B 段落循环。"
            override val bookmarkCountBadgeTpl = "{0} 段书签"
            override val bookmarkCountShortTpl = "{0} 段"
            override val practiceHistoryBadge = "练习记录"
            override val watchedProgressTpl = "已看 {0}"
            override val videoMenuDesc = "视频菜单"
            override val openInPracticeMode = "以练习模式打开"
            override val addToPlaylistOrPractice = "加入播放列表 / 练习列表"
            override val editTrackTags = "编辑标签信息"
            override val albumMenuDesc = "专辑菜单"
            override val batchAutoComplete = "批量自动联网补全"
            override val batchEditAlbumTags = "批量编辑专辑标签"
            override val unknownAlbumBannerTitle = "自动联网识别全部曲目"
            override val unknownAlbumBannerSubtitle = "免 Key 检索 MusicBrainz / iTunes / LRCLIB，一键补齐标题、专辑与高清封面"
            override val trackCountShortTpl = "{0} 首"
            override val playAll = "全部播放"
            override val practicePlayAll = "练习播放"
            override val artistAlbumsHeaderTpl = "参与专辑 ({0})"
            override val artistAllTracksHeaderTpl = "全部单曲 ({0})"

            override val newPlaylist = "新建播放列表"
            override val emptyPlaylistsSubtitle = "可将音频伴奏与教学视频混排在同一练习列表中，点播自动展开练习工作台。"
            override val playlistItemsCountTpl = "{0} 首音视频"
            override val playlistBookmarksSuffixTpl = " · 含 {0} 个段落书签"
            override val normalPlaylistSuffix = " · 普通播放列表"
            override val playlistMenuDesc = "播放列表菜单"
            override val switchToNormalPlaylist = "切换为普通播放列表"
            override val setAsPracticePlaylist = "设为练习列表"
            override val exportM3u8 = "导出 .m3u8"
            override val deletePlaylist = "删除列表"
            override val emptyBookmarksMessage = "暂无已保存的 A-B 段落书签。在播放页展开「练习模式」设好 A/B 点后保存书签即可在此处一键直达。"
            override val artistBookmarksCountTpl = "{0} · {1} 个段落"
            override val playlistMoreActionsDesc = "列表更多操作"
            override val practicePlaylistModeTitle = "练习列表模式"
            override val practicePlaylistModeSubtitle = "播放列表内任意音频或教学视频时自动展开练习工作台"
            override val practicePlayAllPlaylist = "全部练习播放"
            override val addTracksOrVideos = "添加曲目/视频"
            override val dragReorderDesc = "长按上下拖拽排序"
            override val semitonesShortTpl = "{0} 半音"
            override val trackMenuDesc = "曲目菜单"
            override val moveUp = "上移一位"
            override val moveDown = "下移一位"
            override val removeFromPlaylist = "从列表移除"
            override val renamePlaylistTitle = "重命名播放列表"
            override val playlistNameLabel = "列表名称"
            override val playlistNamePlaceholder = "例如：本周乐队排练曲目"
            override val setAsPracticePlaylistSubtitle = "播放时自动开启练习模式"
            override val addAudioOrVideoTitle = "添加音频或教学视频"
            override val addSelectedCountTpl = "添加 ({0})"
            override val filterTracksPlaceholder = "筛选曲目或视频…"
            override val addToPlaylistHeaderTpl = "加入播放列表 ({0} 首)"
            override val newPlaylistOrPracticeBtn = "新建播放列表 / 练习列表"

            override val batchEditTagsTitleTpl = "批量编辑标签 ({0} 首)"
            override val searchingOpenLibrary = "正在检索开放曲库…"
            override val autoMatchOnlineBtn = "自动联网匹配标签/封面"
            override val pickFromGallery = "从相册选图"
            override val restoreDefault = "恢复默认"
            override val preserveBackingSuffixLabel = "标题保留「(伴奏)」后缀，避免与原唱混淆"
            override val candidatesMatchedHintTpl = "已匹配 {0} 个结果（点击填入表单，确认后点右上角保存）："
            override val fieldTitle = "标题"
            override val fieldTitleBatchLocked = "标题（批量编辑时锁定）"
            override val fieldTitleBatchPlaceholder = "保持各曲原值（批量模式不覆盖）"
            override val fieldArtist = "歌手"
            override val fieldAlbum = "专辑"
            override val fieldAlbumArtist = "专辑歌手"
            override val fieldTrackNumber = "音轨号"
            override val fieldTrackNumberBatchLocked = "音轨号（批量锁定）"
            override val fieldKeepOriginalPlaceholder = "保持各曲原值"
            override val fieldMixedPlaceholder = "多项不同（未改动则保持原值）"
            override val fieldYear = "年份"
            override val fieldKeepValuePlaceholder = "保持原值"
            override val writeBackAudioTags = "同时安全写入原音频文件标签 (ID3v2 / Vorbis / MP4 ilst)"
            override val writeDbOnlyHint = "仅保存至本地数据库覆盖层（视频格式或只读目录免改原文件）"
            override val lyricsTagCheck = "歌词✓"
            override val candidateStagedPill = "已填入 · 请点上方保存"
            override val batchAutoCompleteTitle = "批量自动联网补全预览"
            override val batchSearchingProgressTpl = "正在并发检索 MusicBrainz / iTunes / LRCLIB：{0} / {1} 首…"
            override val cleanedTitleFallbackTpl = "清洗标题回退：{0}"
            override val switchCandidatesTpl = "切换候选 ({0})"
            override val preserveBackingChip = "保留伴奏后缀"
            override val writeBackIfWritable = "具备写权限时同步写入原音频文件"
            override val writingProgress = "正在写入…"
            override val confirmApplyCountTpl = "确认应用 ({0} 首)"

            override val lyricsButtonShort = "词"
            override val addToPlaylistDesc = "加入播放列表"
            override val shuffleOnDesc = "随机播放：已开启"
            override val shuffleOffDesc = "随机播放：已关闭"
            override val repeatAllDesc = "列表循环"
            override val repeatOneDesc = "单曲循环"
            override val playOnceDesc = "顺序播完即停"
            override val completedLoopsBadgeTpl = "已练 {0} 遍"
            override val prevOrRestartADesc = "单击回到 A 点重练，双击跳回曲首"
            override val rewind5sDesc = "快退 5 秒"
            override val forward5sDesc = "快进 5 秒"
            override val rewind10sDesc = "快退 10 秒"
            override val forward10sDesc = "快进 10 秒"
            override val fileDetailsTitle = "媒体文件详情"
            override val fileDetailNameTpl = "文件名：{0}"
            override val fileDetailPathTpl = "相对路径：{0}"
            override val fileDetailFormatTpl = "格式：{0}"
            override val fileDetailSizeTpl = "大小：{0}"
            override val fileDetailResolutionTpl = "视频分辨率：{0} × {1}"
            override val collapsePlayerDesc = "收起播放面板"
            override val nowPlayingTitle = "正在播放"
            override val enterLandscapeVideoDesc = "横屏全屏播放"
            override val sleepTimer = "睡眠定时"
            override val currentQueueDesc = "当前播放队列"
            override val fastSeekActiveSuffix = "（已启用关键帧极速防卡顿）"
            override val videoSeekAutoTpl = "视频循环寻帧：自动极速{0}"
            override val videoSeekExact = "视频循环寻帧：强制精准帧"
            override val fileDetails = "文件详情"
            override val noLyricsFound = "未检测到内嵌或同名 .lrc 歌词"
            override val fetchLyricsLrclibBtn = "从 LRCLIB 免 Key 获取同步歌词"
            override val queueHeaderTpl = "当前播放队列 ({0})"
            override val sleepTimerRemainingSuffixTpl = " · 剩余 {0}"
            override val sleepTimerTrackEndSuffix = " · 播完当前曲目后暂停"
            override val sleepTimerMinutesTpl = "{0} 分钟后暂停"
            override val sleepTimerTrackEndOption = "播完当前曲目后暂停"
            override val sleepTimerOffBtn = "关闭睡眠定时"

            override val barsCountTpl = "{0}小节"
            override val waitingPointB = " · 待设 B 点"
            override val waveformRulerDescTpl = "音频波形与 A-B 循环标尺，当前播放位置 {0}"
            override val updateBookmarkRange = "用当前 A-B 更新范围"
            override val renameBookmark = "重命名书签"
            override val deleteBookmark = "删除书签"
            override val renameBookmarkTitle = "重命名段落书签"
            override val saveBookmarkSheetTitle = "保存段落书签（单手点选预设即刻保存）"
            override val customBookmarkPlaceholder = "或输入自定义备注（如：扫拨难点）"
            override val presetSectionIntro = "前奏"
            override val presetSectionVerse = "主歌"
            override val presetSectionPreChorus = "导歌"
            override val presetSectionChorus = "副歌"
            override val presetSectionBridge = "桥段"
            override val presetSectionSolo1 = "独奏 1"
            override val presetSectionSolo2 = "独奏 2"
            override val presetSectionOutro = "尾奏"
            override val setPointADescTpl = "设当前位置为 A 起点，当前 {0}"
            override val setPointBDescTpl = "设当前位置为 B 终点，当前 {0}"
            override val suspendLoopDesc = "挂起循环"
            override val resumeLoopDesc = "恢复循环"
            override val clearLoopDesc = "清除循环"
            override val saveBookmarkDesc = "保存段落书签"
            override val speedResetDescTpl = "当前练习倍速 {0} 倍，点击恢复 1.0 倍原速"
            override val pitchControlDesc = "调整音调"
            override val stemMixerDesc = "分轨乐器音量 (人声/吉他/贝斯/鼓/其他)"
            override val countInOnDesc = "预备拍：4拍（已开启）"
            override val countInOffDesc = "预备拍：已关闭"
            override val countInBadge4 = "4拍"
            override val countInOffShort = "关"
            override val keepScreenOnEnabledDesc = "练习常亮：已开启"
            override val keepScreenOnDisabledDesc = "练习常亮：已关闭"
            override val videoMirrorOnDesc = "视频水平镜像：已开启"
            override val videoMirrorOffDesc = "视频水平镜像：已关闭"
            override val bpmChipDescTpl = "BPM 速度 {0}，点击展开或收起 BPM 与自动测速栏"
            override val effectiveBpmTpl = "慢放 ≈ {0}"
            override val detectedBpmSuffixTpl = " · 测得 {0}"
            override val bpmMinusDesc = "BPM 减 1"
            override val bpmPlusDesc = "BPM 加 1"
            override val tapTempoLabel = "打拍"
            override val detectingLabel = "检测中…"
            override val autoDetectLabel = "自动"
            override val barSnapLabel = "小节对齐"
            override val alignDownbeatDesc = "将当前播放位置校准为小节第1拍起点"
            override val alignDownbeatLabel = "对齐第1拍"
            override val firstDownbeatTpl = "首拍 {0}"
            override val beatGridSourceManual = " · 手动"
            override val beatGridSourceStems = " · 分轨"
            override val beatGridSourceTag = " · 标签"
            override val beatOneLabel = "第1拍"
            override val shiftBeatMinusLabel = "-1拍"
            override val shiftBeatMinusDesc = "第一拍提前1拍"
            override val shiftBeatPlusLabel = "+1拍"
            override val shiftBeatPlusDesc = "第一拍推后1拍"
            override val shiftHalfBarLabel = "±2拍"
            override val shiftHalfBarAmbiguousDesc = "第1拍与第3拍难以区分，点按换到第3拍"
            override val shiftHalfBarDesc = "第一拍换到第3拍（半小节翻转）"
            override val nudgeMsMinusDesc = "第一拍提前10毫秒"
            override val nudgeMsPlusDesc = "第一拍推后10毫秒"
            override val pendingStemsGridTpl = "检测到更准的节拍网格 ({0}) — 应用?"
            override val pitchMinusSt = "-1 半音"
            override val pitchPlusSt = "+1 半音"
            override val pitchOriginal = "原调 (0 st)"
            override val pitchResetTpl = "{0} st · 恢复原调"
            override val pitchHintMinus1 = "提示：将标准调原曲降半音至 Eb（无需改调弦）"
            override val pitchHintPlus1 = "提示：+1 半音 = 用标准调弦吉他跟弹 Eb 降半音原曲"
            override val pitchHintDefault = "范围 ±6 半音；下方可做 ±50 ct 音分微调校准"
            override val centsSliderDescTpl = "音分微调 {0} ct"
            override val stemModelTitle = "首次使用需下载 HTDemucs 6s 专业分离模型 (~100 MB)"
            override val stemModelSubtitle = "Meta HTDemucs 混合 Transformer 端侧离线分离 5 轨音频（下载一次后永久本地可用）"
            override val stemDownloadingTpl = "正在下载 HTDemucs 模型 {0}%…"
            override val stemDownloadBtn = "下载 HTDemucs 模型"
            override val stemSeparatingSegTpl = "正在分离音轨 {0}/{1} ({2}%)…"
            override val stemDecodingTpl = "正在解码音频准备分离 ({0}%)…"
            override val stemSeparateTrackBtn = "使用 HTDemucs 分离当前曲目 (5轨)"
            override val stemReadyBadge = "HTDemucs 5轨已就绪"
            override val stemVocals = "人声"
            override val stemGuitar = "吉他"
            override val stemBass = "贝斯"
            override val stemDrums = "鼓"
            override val stemOther = "其他"
            override val stemVolumeDescTpl = "{0} 音量 {1}%"
            override val stemUnmuteDescTpl = "{0} 取消静音"
            override val stemMuteDescTpl = "{0} 静音"
            override val stemUnsoloDescTpl = "{0} 取消独奏"
            override val stemSoloDescTpl = "{0} 独奏"
            override val videoZoomResetTpl = "{0}x · 重置"
            override val videoZoomResetCompactTpl = "{0}x 重置"
            override val videoMirroredBadge = "镜像中"
            override val exitFullscreenDesc = "退出全屏"
            override val disableVideoMirrorDesc = "关闭视频镜像"
            override val enableVideoMirrorDesc = "开启视频镜像"
            override val dockHudBottomDesc = "置底避让"
            override val dockHudTopDesc = "移至顶部"
            override val exitPracticeModeDesc = "退出练习模式"
            override val portraitModeDesc = "竖屏模式"
            override val loopCounterCapsuleTpl = " (第 {0} 遍)"
            override val countInHeadlineTpl = "预备拍 {0} / 4"
            override val countInSkipHint = "轻触跳过立即起播"

            override val settingsLibrarySection = "本地媒体库与无线连接"
            override val settingsLanStudioTitle = "PC 局域网 Web 管理台"
            override val settingsLanStudioSubtitle = "在同一 Wi-Fi 下用电脑浏览器无线传歌、批量改标签与管理播放列表"
            override val settingsAutoRescanTitle = "启动时后台增量刷新已添加目录"
            override val settingsAutoRescanSubtitle = "游标级快速跳过未变更文件，未挂载 OTG 自动保留书签"
            override val settingsCacheSection = "本地缓存"
            override val settingsClearCacheTitle = "清理本地封面与波形缓存"
            override val settingsClearCacheSubtitleTpl = "当前占用 {0}（不删除音乐文件与练习书签）"
            override val settingsAboutSection = "关于与自动更新"
            override val settingsAboutTagline = "纯本地受控扫描 · Material You · GitHub 安全签名校验更新"
            override val settingsCheckingUpdate = "检查中…"
            override val settingsCheckUpdateBtn = "检查 GitHub 更新"
            override val settingsNewVersionTpl = "发现新版本 {0} ({1})"
            override val settingsDownloadInstallBtn = "下载并安装更新 (SHA-256 + 签名校验)"
            override val settingsAlreadyLatestTpl = "当前已是最新版本 ({0})"
            override val settingsDownloadingApkTpl = "正在下载 {0}：{1} / {2}"
            override val settingsVerifiedApkTpl = "✓ {0} 已通过 SHA-256 与签名证书校验"
            override val settingsInstallNowBtn = "立即安装更新"
            override val feedbackEntryTitle = "发送反馈给 Agent"
            override val feedbackEmailUnset = "Agent 反馈邮箱：未设置（仅保存在本机）"
            override val feedbackEmailSetTpl = "Agent 反馈邮箱：{0}"
            override val feedbackSendButton = "发送反馈"
            override val openSourceLicensesBtn = "开源许可声明"
            override val openSourceLicensesTitle = "开源许可"
            override val openSourceLicensesBody = "MiniPlayer 基于 Apache License 2.0 协议构建，使用 Android Jetpack Compose Material 3、AndroidX Media3 (ExoPlayer) 及开放免密钥数据库 MusicBrainz / iTunes Search / LRCLIB。"
            override val feedbackSheetSubtitleTpl = "自动生成 {0}: [{1}] 路由前缀，拉起本机邮箱一键发送。"
            override val feedbackEmailLabel = "Agent 反馈邮箱（仅保存在本机）"
            override val feedbackEmailInvalidHintTpl = "请输入有效的邮箱地址，例如 {0}"
            override val feedbackEmailCleared = "已清空 Agent 反馈邮箱"
            override val feedbackEmailSaved = "已保存 Agent 反馈邮箱"
            override val feedbackTitleLabel = "标题"
            override val feedbackTitlePlaceholder = "例如：练习模式希望支持导出循环片段"
            override val feedbackEmailSubjectPreviewTpl = "邮件主题：{0}"
            override val feedbackDetailsLabel = "问题描述"
            override val feedbackDetailsPlaceholder = "补充使用场景、复现步骤或改进建议"
            override val feedbackStagingImage = "处理中…"
            override val feedbackChangeImage = "更换图片"
            override val feedbackAddImage = "增加图片"
            override val feedbackRemoveImageDesc = "移除图片"
            override val feedbackReadImageFailedTpl = "读取图片失败：{0}"
            override val feedbackRetryFallback = "请重试"
            override val feedbackIncludeDiagnosticsTitle = "附带版本与播放/练习状态信息"
            override val feedbackIncludeDiagnosticsSubtitle = "仅包含版本号、速度、变调与循环时间点，不包含本地目录路径"
            override val feedbackHintMissingEmail = "请先填写 Agent 反馈邮箱"
            override val feedbackHintMissingContent = "请至少填写标题、问题描述或增加图片中的一项"
            override val feedbackEmailRequiredErrorTpl = "请先填写有效的 Agent 反馈邮箱，例如 {0}"
            override val feedbackNoMailClient = "未找到可用的邮件客户端，请先安装邮箱应用"
            override val feedbackCannotReadImage = "无法读取所选图片"
            override val feedbackImageTooLarge = "截图文件超过 20 MB 上限"
            override val feedbackImageEmpty = "所选图片内容为空"
            override val feedbackInvalidEmailError = "Agent 反馈邮箱格式无效"
            override val feedbackDefaultSummary = "使用反馈"
            override val feedbackAttachmentOnlySummary = "图片反馈"
            override val feedbackDiagHeader = "Play 诊断信息"
            override val feedbackDiagAppVersion = "应用版本"
            override val feedbackDiagDevice = "设备环境"
            override val feedbackDiagMedia = "当前媒体"
            override val feedbackDiagPlaying = "播放中"
            override val feedbackDiagPaused = "已暂停"
            override val feedbackDiagLoopActive = "激活"
            override val feedbackDiagLoopSuspended = "挂起"
            override val feedbackDiagNoLoop = "未设循环"
            override val feedbackDiagPracticeState = "练习状态"
            override val feedbackDiagSpeed = "速度"
            override val feedbackDiagPitch = "变调"
            override val feedbackDiagBeatGrid = "节拍网格"
            override val feedbackDiagBeatOne = "第一拍"
            override val feedbackDiagPhraseOffset = "乐句偏移"
            override val feedbackDiagSource = "来源"
            override val feedbackDiagPlaybackMode = "播放模式"
            override val feedbackDiagCasualMode = "听歌模式"
            override val feedbackDiagAttachment = "附加截图"

            override val msgFolderAddedTpl = "已添加文件夹「{0}」"
            override val msgFolderAlreadyCoveredTpl = "该目录已包含在「{0}」中，已为你直接定位"
            override val msgFolderMergedTpl = "已添加父目录「{0}」并无损归并 {1} 个旧子目录"
            override val msgIncrementalRescanDone = "已完成增量刷新"
            override val msgRootRescanDone = "目录增量扫描完成"
            override val msgRootRemoved = "已从媒体库移除该文件夹（未删除本地文件）"
            override val msgSetPracticeFolder = "已设为练习文件夹（点击曲目自动开启练习模式）"
            override val msgUnsetPracticeFolder = "已恢复为普通文件夹"
            override val msgSetExcludeGrouping = "已设为「不自动归类」（该目录下的文件不显示在专辑、歌手、视频中）"
            override val msgUnsetExcludeGrouping = "已取消「不自动归类」"
            override val msgFolderNoMediaToAdd = "该文件夹暂无可添加的媒体文件"
            override val msgCreatedPracticePlaylistTpl = "已创建练习列表「{0}」"
            override val msgCreatedPlaylistTpl = "已创建播放列表「{0}」"
            override val msgPlaylistPracticeEnabled = "已开启练习列表模式"
            override val msgPlaylistPracticeDisabled = "已切换为普通播放列表"
            override val msgPlaylistDeleted = "已删除播放列表"
            override val msgAddedTracksCountTpl = "已加入 {0} 首曲目"
            override val msgImportedPlaylistTpl = "已导入「{0}」：成功匹配 {1} 首"
            override val msgImportedPlaylistUnmatchedSuffixTpl = "（{0} 首未在已添加文件夹中找到）"
            override val msgExportedM3u8 = "已导出 .m3u8 播放列表"
            override val msgExportM3u8Failed = "导出播放列表失败"
            override val msgBookmarkSavedTpl = "已保存段落书签「{0}」"
            override val msgBookmarkDeleted = "已删除段落书签"
            override val msgLyricsNotFound = "未找到匹配的同步歌词"
            override val msgOnlineCandidatesEmpty = "未找到匹配的在线标签候选，可尝试修改关键词重试"
            override val msgOnlineSearchFailedTpl = "在线匹配请求失败：{0}"
            override val msgCheckNetworkFallback = "请检查网络连接"
            override val msgMetaSavedDbOnly = "已更新本地标签覆盖层"
            override val msgMetaSavedDbAndFile = "已更新标签并安全写入原音频文件"
            override val msgMetaSavedDeferred = "已立即生效，原文件标签将在切歌后静默写回"
            override val msgMetaSavedReadonlyFallback = "目录为只读，已保存在本地覆盖层"
            override val msgAppliedCandidateTpl = "已应用「{0} - {1}」标签与封面"
            override val msgResetMetadataDone = "已恢复原始文件标签"
            override val msgNoAudioForBatch = "没有可联网补全的音频曲目"
            override val msgBatchSearchFailedTpl = "批量联网识别失败：{0}"
            override val msgNetworkErrorFallback = "网络异常"
            override val msgBatchCommitDoneTpl = "批量补全完成，已更新 {0} 首曲目"
            override val msgCacheCleared = "已清理本地封面与波形缓存"
            override val msgCacheClearedFreedSuffixTpl = "（释放 {0}）"
            override val msgUpdateCheckFailed = "检查更新失败，请检查网络连接"
            override val msgUpdateDownloadFailed = "下载或安全校验 APK 失败"
            override val msgInstallLaunchFailedTpl = "无法启动安装程序：{0}"
            override val msgGrantInstallPermission = "请允许 Play 安装未知应用，返回后将自动安装更新"
            override val msgSafPermissionFailedTpl = "无法获取目录持久化访问授权：{0}"
            override val msgPermissionDenied = "权限被拒绝"

            override val stemErrVerifyFailedTpl = "校验失败：下载的模型文件不完整或格式无效 ({0} KB)"
            override val stemErrDownloadNetwork = "无法下载 HTDemucs 模型，请检查网络连接后重试"
            override val stemErrTooManyRedirectsTpl = "下载重定向过多或无效 ({0})"
            override val stemErrHttpStatusTpl = "服务器返回 HTTP {0}"
            override val stemErrDownloadFailed = "HTDemucs 模型下载失败"
            override val stemErrSeparationFailed = "HTDemucs 分轨失败"
            override val stemErrModelMissing = "尚未下载 HTDemucs 模型"
            override val stemErrDecodeFailed = "无法解码当前音频轨道"
            override val stemErrMapCacheFailed = "无法映射分离后的音轨缓存"

            override val lanIfaceWifi = "Wi-Fi"
            override val lanIfaceHotspot = "热点"
            override val lanIfaceUsb = "USB 网络共享"
            override val lanIfaceEthernet = "以太网"
            override val lanIfaceLan = "局域网"
            override val lanStartFailedTpl = "启动失败（端口 {0} 可能被占用）：{1}"
            override val lanUnknownError = "未知错误"
            override val lanActivityReady = "服务已就绪，请在电脑浏览器访问上述地址"
            override val lanActivityUploadedTpl = "已接收上传：{0}"
            override val lanActivityStopped = "局域网服务已停止"
            override val lanActivityPinEnabled = "已启用访问 PIN 码保护"
            override val lanActivityPinDisabled = "已关闭访问 PIN 码保护"
            override val lanRunningSubtitleTpl = "运行中 · {0}"
            override val lanStoppedSubtitle = "未启动 · 电脑浏览器免安装管理曲库与打点"
            override val lanRefreshIpDesc = "刷新局域网 IP 地址"
            override val lanNoNetworkHint = "当前未检测到 Wi-Fi 或热点连接，请将手机与电脑接入同一局域网后点击右上角刷新"
            override val lanQrDescTpl = "局域网管理台二维码 {0}"
            override val lanPinLabelTpl = "访问 PIN 码：{0}"
            override val lanCopiedAddress = "已复制地址 ✓"
            override val lanCopyAddress = "复制地址"
            override val lanStatClients = "活跃连接"
            override val lanStatRequests = "累计请求"
            override val lanStatUploads = "无线接收"
            override val lanUploadedValueTpl = "{0} 个 ({1})"
            override val lanFeaturesHeader = "电脑浏览器打开即可使用四大工作台："
            override val lanFeaturesBullets = "• 无线拖拽传歌/教学视频与练习文件夹标记\n" +
                "• 曲库大表格行内改标签与免 Key 批量联网补全\n" +
                "• 播放列表与练习列表拖拽编排及 .m3u8 导入导出\n" +
                "• PC 在线预听与键盘 [ / ] 键极速 A-B 段落书签打点"
            override val lanPinToggleTitle = "访问 PIN 码验证（公共 Wi-Fi 推荐开启）"
            override val lanPinToggleSubtitle = "防火墙默认仅允许局域网私有 IP 访问，绝不暴露公网"
            override val lanStopServerBtn = "停止服务"
            override val lanStartServerBtnTpl = "启动服务 (端口 {0})"
        }

        val EN: AppStrings = object : AppStrings {
            override val appName = "Play"
            override val tabLibrary = "Library"
            override val tabPlaylists = "Playlists"
            override val back = "Back"
            override val close = "Close"
            override val cancel = "Cancel"
            override val confirm = "OK"
            override val save = "Save"
            override val create = "Create"
            override val delete = "Delete"
            override val reset = "Reset"
            override val ignore = "Ignore"
            override val open = "Open"
            override val clear = "Clear"
            override val rename = "Rename"
            override val moreOptions = "More options"
            override val moreActions = "More actions"
            override val settings = "Settings"
            override val play = "Play"
            override val pause = "Pause"
            override val previousTrack = "Previous"
            override val nextTrack = "Next"
            override val video = "Video"
            override val audio = "Audio"
            override val singleTrack = "Single"
            override val unknownAlbum = "Unknown Album"
            override val unknownArtist = "Unknown Artist"
            override val variousArtists = "Various Artists"
            override val internalStorage = "Internal Storage"
            override val localFolder = "Local Folder"
            override val importedPlaylistFallback = "Imported Playlist"
            override val defaultPracticePlaylistName = "New Practice Playlist"
            override val defaultPlaylistName = "New Playlist"
            override val defaultSectionLabel = "Section"
            override val sectionWithTimeTpl = "Section {0}"
            override val unknownAlbumPendingTracksTpl = "{0} tracks to organize"

            override val settingsLanguageSection = "Language"
            override val settingsLanguageTitle = "App Language"
            override val settingsLanguageSubtitle = "Applies immediately without restart, including LAN Web Studio"
            override val languageSystem = "System Default"
            override val languageZhNative = "中文"
            override val languageEnNative = "English"
            override val languageJaNative = "日本語"

            override val libraryFolders = "Folders"
            override val libraryAlbums = "Albums"
            override val libraryArtists = "Artists"
            override val libraryVideos = "Videos"
            override val playlistAll = "All"
            override val playlistPractice = "Practice"
            override val playlistBookmarks = "Bookmarks"
            override val emptyPracticePlaylists = "No practice playlists yet"
            override val emptyPlaylists = "No playlists yet"

            override val abLoopActive = "A-B Loop"
            override val practiceMode = "Practice Mode"

            override val addFolder = "Add Folder"
            override val addLocalFolderCta = "+ Add Local Folder"
            override val addVideoFolderCta = "+ Add Video Folder"
            override val emptyFoldersTitle = "Scan only folders you choose"
            override val emptyFoldersSubtitle = "No broad storage permissions required. System ringtones and voice clips stay out of your library."
            override val addedFoldersHeaderTpl = "Added Folders ({0})"
            override val switchToFlatViewDesc = "Tree view active; tap to switch to flat view"
            override val switchToTreeViewDesc = "Flat view active; tap to switch to tree view"
            override val badgePractice = "Practice"
            override val badgeExcludeGrouping = "Unindexed"
            override val badgeOffline = "Offline"
            override val badgePermissionRevoked = "Re-authorize"
            override val mediaCountTpl = "{0} items"
            override val readOnlyTag = "Read-only"
            override val folderMenuDesc = "Folder menu"
            override val subfolderMenuDesc = "Subfolder menu"
            override val unsetPracticeFolder = "Remove Practice Folder"
            override val setPracticeFolder = "Set as Practice Folder"
            override val unsetExcludeGrouping = "Include in Auto-Grouping"
            override val setExcludeGrouping = "Exclude from Auto-Grouping"
            override val addAllToPlaylist = "Add All to Playlist"
            override val batchAutoMatchTagsAndCover = "Batch Auto-Match Tags & Cover"
            override val batchEditTags = "Batch Edit Tags"
            override val incrementalRescan = "Incremental Rescan"
            override val removeRootFolder = "Remove Root Folder"
            override val removeRootConfirmTitleTpl = "Remove \"{0}\"?"
            override val removeRootConfirmBody = "Removes this folder from MiniPlayer's index only. No files on your device or storage will be deleted."
            override val removeFromIndex = "Remove"
            override val navigateUpDirDesc = "Up one folder"
            override val practiceFolderBanner = "Practice Folder: tapping any track or video opens Practice Mode automatically"
            override val excludedGroupingBanner = "Excluded from Auto-Grouping: files here will not appear in Albums, Artists, or Videos"
            override val subfolderItemCountTpl = "{0} items"
            override val emptyDirMessage = "No matching local audio or video files in this folder"
            override val emptyAlbumsTitle = "No Albums Yet"
            override val emptyAlbumsSubtitle = "Add a local music folder to automatically group tracks by Album and Album Artist."
            override val badgePendingTag = "Untagged"
            override val unknownAlbumCardTitleTpl = "Unknown Album ({0})"
            override val unknownAlbumCardSubtitle = "No album tag · Tap to auto-match"
            override val albumCardSubtitleTpl = "{0} · {1} tracks"
            override val emptyArtistsTitle = "No Artists Yet"
            override val emptyArtistsSubtitle = "Add a music folder to automatically index solo and featured artists."
            override val artistStatsTpl = "{0} albums · {1} tracks"
            override val emptyVideosTitle = "No Local Videos Yet"
            override val emptyVideosSubtitle = "Supports .mp4, .mkv, .webm, and .mov with 4x pinch zoom, horizontal mirror flip, and A-B looping."
            override val bookmarkCountBadgeTpl = "{0} bookmarks"
            override val bookmarkCountShortTpl = "{0} seg"
            override val practiceHistoryBadge = "Practiced"
            override val watchedProgressTpl = "Watched {0}"
            override val videoMenuDesc = "Video menu"
            override val openInPracticeMode = "Open in Practice Mode"
            override val addToPlaylistOrPractice = "Add to Playlist / Practice List"
            override val editTrackTags = "Edit Tags"
            override val albumMenuDesc = "Album menu"
            override val batchAutoComplete = "Batch Online Auto-Match"
            override val batchEditAlbumTags = "Batch Edit Album Tags"
            override val unknownAlbumBannerTitle = "Auto-Match All Tracks Online"
            override val unknownAlbumBannerSubtitle = "Search MusicBrainz, iTunes & LRCLIB with no API key to fill titles, albums, and covers"
            override val trackCountShortTpl = "{0} tracks"
            override val playAll = "Play All"
            override val practicePlayAll = "Practice All"
            override val artistAlbumsHeaderTpl = "Albums ({0})"
            override val artistAllTracksHeaderTpl = "All Tracks ({0})"

            override val newPlaylist = "New Playlist"
            override val emptyPlaylistsSubtitle = "Mix backing tracks and lesson videos in one Practice Playlist to launch Practice Mode automatically."
            override val playlistItemsCountTpl = "{0} items"
            override val playlistBookmarksSuffixTpl = " · {0} bookmarks"
            override val normalPlaylistSuffix = " · Standard playlist"
            override val playlistMenuDesc = "Playlist menu"
            override val switchToNormalPlaylist = "Switch to Standard Playlist"
            override val setAsPracticePlaylist = "Set as Practice Playlist"
            override val exportM3u8 = "Export .m3u8"
            override val deletePlaylist = "Delete Playlist"
            override val emptyBookmarksMessage = "No saved A-B section bookmarks yet. Open Practice Mode while playing, set A/B loop points, and save a bookmark for 1-tap access."
            override val artistBookmarksCountTpl = "{0} · {1} sections"
            override val playlistMoreActionsDesc = "More playlist actions"
            override val practicePlaylistModeTitle = "Practice Playlist Mode"
            override val practicePlaylistModeSubtitle = "Automatically open Practice Mode when playing any audio or video in this playlist"
            override val practicePlayAllPlaylist = "Practice All"
            override val addTracksOrVideos = "Add Tracks / Videos"
            override val dragReorderDesc = "Long-press and drag to reorder"
            override val semitonesShortTpl = "{0} st"
            override val trackMenuDesc = "Track menu"
            override val moveUp = "Move Up"
            override val moveDown = "Move Down"
            override val removeFromPlaylist = "Remove from Playlist"
            override val renamePlaylistTitle = "Rename Playlist"
            override val playlistNameLabel = "Playlist Name"
            override val playlistNamePlaceholder = "e.g. Band Rehearsal Setlist"
            override val setAsPracticePlaylistSubtitle = "Automatically open Practice Mode on play"
            override val addAudioOrVideoTitle = "Add Audio or Videos"
            override val addSelectedCountTpl = "Add ({0})"
            override val filterTracksPlaceholder = "Filter tracks or videos…"
            override val addToPlaylistHeaderTpl = "Add to Playlist ({0})"
            override val newPlaylistOrPracticeBtn = "New Playlist / Practice List"

            override val batchEditTagsTitleTpl = "Batch Edit Tags ({0})"
            override val searchingOpenLibrary = "Searching online databases…"
            override val autoMatchOnlineBtn = "Auto-Match Tags & Cover"
            override val pickFromGallery = "Pick Image"
            override val restoreDefault = "Reset"
            override val preserveBackingSuffixLabel = "Keep backing-track suffix in title to distinguish from original"
            override val candidatesMatchedHintTpl = "{0} matches found (tap to fill form, then tap Save):"
            override val fieldTitle = "Title"
            override val fieldTitleBatchLocked = "Title (locked in batch mode)"
            override val fieldTitleBatchPlaceholder = "Keep each track's original title"
            override val fieldArtist = "Artist"
            override val fieldAlbum = "Album"
            override val fieldAlbumArtist = "Album Artist"
            override val fieldTrackNumber = "Track #"
            override val fieldTrackNumberBatchLocked = "Track # (locked)"
            override val fieldKeepOriginalPlaceholder = "Keep original"
            override val fieldMixedPlaceholder = "Multiple values (unchanged if blank)"
            override val fieldYear = "Year"
            override val fieldKeepValuePlaceholder = "Keep original"
            override val writeBackAudioTags = "Also write tags safely to audio files (ID3v2 / Vorbis / MP4 ilst)"
            override val writeDbOnlyHint = "Save to local override layer only (videos or read-only folders stay untouched)"
            override val lyricsTagCheck = "Lyrics ✓"
            override val candidateStagedPill = "Filled · Tap Save above"
            override val batchAutoCompleteTitle = "Batch Auto-Match Preview"
            override val batchSearchingProgressTpl = "Searching MusicBrainz / iTunes / LRCLIB: {0} / {1}…"
            override val cleanedTitleFallbackTpl = "Cleaned filename fallback: {0}"
            override val switchCandidatesTpl = "Switch Match ({0})"
            override val preserveBackingChip = "Keep Backing Suffix"
            override val writeBackIfWritable = "Write back to audio files when writable"
            override val writingProgress = "Saving…"
            override val confirmApplyCountTpl = "Apply ({0} tracks)"

            override val lyricsButtonShort = "LRC"
            override val addToPlaylistDesc = "Add to Playlist"
            override val shuffleOnDesc = "Shuffle: On"
            override val shuffleOffDesc = "Shuffle: Off"
            override val repeatAllDesc = "Repeat All"
            override val repeatOneDesc = "Repeat One"
            override val playOnceDesc = "Play Once"
            override val completedLoopsBadgeTpl = "{0} loops"
            override val prevOrRestartADesc = "Tap to restart from A, double-tap for track start"
            override val rewind5sDesc = "Rewind 5s"
            override val forward5sDesc = "Forward 5s"
            override val rewind10sDesc = "Rewind 10s"
            override val forward10sDesc = "Forward 10s"
            override val fileDetailsTitle = "Media File Details"
            override val fileDetailNameTpl = "File name: {0}"
            override val fileDetailPathTpl = "Relative path: {0}"
            override val fileDetailFormatTpl = "Format: {0}"
            override val fileDetailSizeTpl = "Size: {0}"
            override val fileDetailResolutionTpl = "Resolution: {0} × {1}"
            override val collapsePlayerDesc = "Collapse player"
            override val nowPlayingTitle = "Now Playing"
            override val enterLandscapeVideoDesc = "Fullscreen landscape"
            override val sleepTimer = "Sleep Timer"
            override val currentQueueDesc = "Play Queue"
            override val fastSeekActiveSuffix = " (fast keyframe active)"
            override val videoSeekAutoTpl = "Video loop seek: Auto fast{0}"
            override val videoSeekExact = "Video loop seek: Exact frame"
            override val fileDetails = "File Details"
            override val noLyricsFound = "No embedded or local .lrc lyrics found"
            override val fetchLyricsLrclibBtn = "Fetch Synced Lyrics from LRCLIB"
            override val queueHeaderTpl = "Play Queue ({0})"
            override val sleepTimerRemainingSuffixTpl = " · {0} left"
            override val sleepTimerTrackEndSuffix = " · Pause after current track"
            override val sleepTimerMinutesTpl = "Pause in {0} min"
            override val sleepTimerTrackEndOption = "Pause after current track"
            override val sleepTimerOffBtn = "Turn Off Sleep Timer"

            override val barsCountTpl = "{0} bars"
            override val waitingPointB = " · Set B"
            override val waveformRulerDescTpl = "Waveform and A-B loop ruler, position {0}"
            override val updateBookmarkRange = "Update with Current A-B"
            override val renameBookmark = "Rename Bookmark"
            override val deleteBookmark = "Delete Bookmark"
            override val renameBookmarkTitle = "Rename Section Bookmark"
            override val saveBookmarkSheetTitle = "Save Section Bookmark (tap a preset)"
            override val customBookmarkPlaceholder = "Or enter custom label (e.g. Sweep riff)"
            override val presetSectionIntro = "Intro"
            override val presetSectionVerse = "Verse"
            override val presetSectionPreChorus = "Pre-Chorus"
            override val presetSectionChorus = "Chorus"
            override val presetSectionBridge = "Bridge"
            override val presetSectionSolo1 = "Solo 1"
            override val presetSectionSolo2 = "Solo 2"
            override val presetSectionOutro = "Outro"
            override val setPointADescTpl = "Set A start point, current {0}"
            override val setPointBDescTpl = "Set B end point, current {0}"
            override val suspendLoopDesc = "Pause loop"
            override val resumeLoopDesc = "Resume loop"
            override val clearLoopDesc = "Clear loop"
            override val saveBookmarkDesc = "Save section bookmark"
            override val speedResetDescTpl = "Practice speed {0}x, tap to reset to 1.0x"
            override val pitchControlDesc = "Adjust pitch"
            override val stemMixerDesc = "Stem mixer (Vocals/Guitar/Bass/Drums/Other)"
            override val countInOnDesc = "Count-in: 4 beats (On)"
            override val countInOffDesc = "Count-in: Off"
            override val countInBadge4 = "4b"
            override val countInOffShort = "Off"
            override val keepScreenOnEnabledDesc = "Keep screen on: On"
            override val keepScreenOnDisabledDesc = "Keep screen on: Off"
            override val videoMirrorOnDesc = "Video mirror: On"
            override val videoMirrorOffDesc = "Video mirror: Off"
            override val bpmChipDescTpl = "Tempo {0} BPM, tap to toggle BPM & auto-detect panel"
            override val effectiveBpmTpl = "Effective ≈ {0}"
            override val detectedBpmSuffixTpl = " · Detected {0}"
            override val bpmMinusDesc = "Decrease BPM by 1"
            override val bpmPlusDesc = "Increase BPM by 1"
            override val tapTempoLabel = "Tap"
            override val detectingLabel = "Detecting…"
            override val autoDetectLabel = "Auto"
            override val barSnapLabel = "Bar Snap"
            override val alignDownbeatDesc = "Align current position as Beat 1 downbeat"
            override val alignDownbeatLabel = "Set Beat 1"
            override val firstDownbeatTpl = "Beat 1 {0}"
            override val beatGridSourceManual = " · Manual"
            override val beatGridSourceStems = " · Stems"
            override val beatGridSourceTag = " · Tag"
            override val beatOneLabel = "Beat 1"
            override val shiftBeatMinusLabel = "-1b"
            override val shiftBeatMinusDesc = "Shift Beat 1 earlier by 1 beat"
            override val shiftBeatPlusLabel = "+1b"
            override val shiftBeatPlusDesc = "Shift Beat 1 later by 1 beat"
            override val shiftHalfBarLabel = "±2b"
            override val shiftHalfBarAmbiguousDesc = "Beat 1 and Beat 3 are close; tap to flip to Beat 3"
            override val shiftHalfBarDesc = "Flip Beat 1 to Beat 3 (half-bar shift)"
            override val nudgeMsMinusDesc = "Nudge Beat 1 earlier by 10 ms"
            override val nudgeMsPlusDesc = "Nudge Beat 1 later by 10 ms"
            override val pendingStemsGridTpl = "More accurate beat grid ({0}) — Apply?"
            override val pitchMinusSt = "-1 st"
            override val pitchPlusSt = "+1 st"
            override val pitchOriginal = "Original (0 st)"
            override val pitchResetTpl = "{0} st · Reset"
            override val pitchHintMinus1 = "Tip: Tune a standard track down to Eb without retuning"
            override val pitchHintPlus1 = "Tip: +1 st lets you play Eb-tuned songs in standard tuning"
            override val pitchHintDefault = "Range ±6 st; fine-tune ±50 ct below"
            override val centsSliderDescTpl = "Fine tune {0} ct"
            override val stemModelTitle = "First-time setup: download HTDemucs 6s model (~100 MB)"
            override val stemModelSubtitle = "On-device Meta HTDemucs 5-stem separation (download once, works offline forever)"
            override val stemDownloadingTpl = "Downloading HTDemucs model {0}%…"
            override val stemDownloadBtn = "Download HTDemucs Model"
            override val stemSeparatingSegTpl = "Separating stems {0}/{1} ({2}%)…"
            override val stemDecodingTpl = "Decoding audio for separation ({0}%)…"
            override val stemSeparateTrackBtn = "Separate Stems with HTDemucs (5 stems)"
            override val stemReadyBadge = "HTDemucs 5 Stems Ready"
            override val stemVocals = "Vocals"
            override val stemGuitar = "Guitar"
            override val stemBass = "Bass"
            override val stemDrums = "Drums"
            override val stemOther = "Other"
            override val stemVolumeDescTpl = "{0} volume {1}%"
            override val stemUnmuteDescTpl = "Unmute {0}"
            override val stemMuteDescTpl = "Mute {0}"
            override val stemUnsoloDescTpl = "Unsolo {0}"
            override val stemSoloDescTpl = "Solo {0}"
            override val videoZoomResetTpl = "{0}x · Reset"
            override val videoZoomResetCompactTpl = "{0}x Reset"
            override val videoMirroredBadge = "Mirrored"
            override val exitFullscreenDesc = "Exit fullscreen"
            override val disableVideoMirrorDesc = "Disable video mirror"
            override val enableVideoMirrorDesc = "Enable video mirror"
            override val dockHudBottomDesc = "Dock HUD at bottom"
            override val dockHudTopDesc = "Dock HUD at top"
            override val exitPracticeModeDesc = "Exit Practice Mode"
            override val portraitModeDesc = "Portrait mode"
            override val loopCounterCapsuleTpl = " (Loop {0})"
            override val countInHeadlineTpl = "Count-in {0} / 4"
            override val countInSkipHint = "Tap to skip and play now"

            override val settingsLibrarySection = "Local Library & Wireless"
            override val settingsLanStudioTitle = "LAN Web Studio"
            override val settingsLanStudioSubtitle = "Transfer files, batch-edit tags, and manage playlists from a desktop browser on the same Wi-Fi"
            override val settingsAutoRescanTitle = "Auto-rescan folders on launch"
            override val settingsAutoRescanSubtitle = "Fast incremental scan skips unchanged files and preserves bookmarks for offline storage"
            override val settingsCacheSection = "Local Cache"
            override val settingsClearCacheTitle = "Clear Cover & Waveform Cache"
            override val settingsClearCacheSubtitleTpl = "Currently using {0} (keeps music files & bookmarks)"
            override val settingsAboutSection = "About & Updates"
            override val settingsAboutTagline = "Local SAF scanning · Material You · Verified GitHub updates"
            override val settingsCheckingUpdate = "Checking…"
            override val settingsCheckUpdateBtn = "Check for Updates"
            override val settingsNewVersionTpl = "New version {0} ({1})"
            override val settingsDownloadInstallBtn = "Download & Install (SHA-256 + Signature Verified)"
            override val settingsAlreadyLatestTpl = "You are up to date ({0})"
            override val settingsDownloadingApkTpl = "Downloading {0}: {1} / {2}"
            override val settingsVerifiedApkTpl = "✓ {0} verified (SHA-256 & signing certificate)"
            override val settingsInstallNowBtn = "Install Update Now"
            override val feedbackEntryTitle = "Send Feedback to Agent"
            override val feedbackEmailUnset = "Agent feedback email: Not set (stored on device only)"
            override val feedbackEmailSetTpl = "Agent feedback email: {0}"
            override val feedbackSendButton = "Send Feedback"
            override val openSourceLicensesBtn = "Open-Source Licenses"
            override val openSourceLicensesTitle = "Open-Source Licenses"
            override val openSourceLicensesBody = "MiniPlayer is built under the Apache License 2.0 using Android Jetpack Compose Material 3, AndroidX Media3 (ExoPlayer), and open no-key databases MusicBrainz, iTunes Search, and LRCLIB."
            override val feedbackSheetSubtitleTpl = "Automatically adds the {0}: [{1}] routing prefix and opens your mail app."
            override val feedbackEmailLabel = "Agent Feedback Email (stored on device only)"
            override val feedbackEmailInvalidHintTpl = "Enter a valid email address, e.g. {0}"
            override val feedbackEmailCleared = "Cleared Agent feedback email"
            override val feedbackEmailSaved = "Saved Agent feedback email"
            override val feedbackTitleLabel = "Title"
            override val feedbackTitlePlaceholder = "e.g. Export A-B loop clip in Practice Mode"
            override val feedbackEmailSubjectPreviewTpl = "Subject: {0}"
            override val feedbackDetailsLabel = "Description"
            override val feedbackDetailsPlaceholder = "Add usage context, reproduction steps, or suggestions"
            override val feedbackStagingImage = "Processing…"
            override val feedbackChangeImage = "Change Image"
            override val feedbackAddImage = "Add Image"
            override val feedbackRemoveImageDesc = "Remove image"
            override val feedbackReadImageFailedTpl = "Failed to read image: {0}"
            override val feedbackRetryFallback = "Please try again"
            override val feedbackIncludeDiagnosticsTitle = "Include app version & playback/practice diagnostics"
            override val feedbackIncludeDiagnosticsSubtitle = "Includes version, speed, pitch, and loop points only — no local file paths"
            override val feedbackHintMissingEmail = "Enter an Agent feedback email first"
            override val feedbackHintMissingContent = "Fill in at least one of Title, Description, or Image"
            override val feedbackEmailRequiredErrorTpl = "Please enter a valid Agent feedback email, e.g. {0}"
            override val feedbackNoMailClient = "No email app found. Please install a mail client."
            override val feedbackCannotReadImage = "Unable to read selected image"
            override val feedbackImageTooLarge = "Image exceeds the 20 MB limit"
            override val feedbackImageEmpty = "Selected image is empty"
            override val feedbackInvalidEmailError = "Invalid Agent feedback email address"
            override val feedbackDefaultSummary = "App Feedback"
            override val feedbackAttachmentOnlySummary = "Image Feedback"
            override val feedbackDiagHeader = "Play Diagnostics"
            override val feedbackDiagAppVersion = "App Version"
            override val feedbackDiagDevice = "Device"
            override val feedbackDiagMedia = "Current Media"
            override val feedbackDiagPlaying = "Playing"
            override val feedbackDiagPaused = "Paused"
            override val feedbackDiagLoopActive = "Active"
            override val feedbackDiagLoopSuspended = "Suspended"
            override val feedbackDiagNoLoop = "No loop"
            override val feedbackDiagPracticeState = "Practice State"
            override val feedbackDiagSpeed = "Speed"
            override val feedbackDiagPitch = "Pitch"
            override val feedbackDiagBeatGrid = "Beat Grid"
            override val feedbackDiagBeatOne = "Beat 1"
            override val feedbackDiagPhraseOffset = "Phrase Offset"
            override val feedbackDiagSource = "Source"
            override val feedbackDiagPlaybackMode = "Playback Mode"
            override val feedbackDiagCasualMode = "Casual Mode"
            override val feedbackDiagAttachment = "Attachment"

            override val msgFolderAddedTpl = "Added folder \"{0}\""
            override val msgFolderAlreadyCoveredTpl = "Already covered by \"{0}\" — navigated there"
            override val msgFolderMergedTpl = "Added parent folder \"{0}\" and merged {1} subfolder(s)"
            override val msgIncrementalRescanDone = "Incremental rescan complete"
            override val msgRootRescanDone = "Folder rescan complete"
            override val msgRootRemoved = "Removed folder from library (local files kept)"
            override val msgSetPracticeFolder = "Set as Practice Folder (auto-opens Practice Mode)"
            override val msgUnsetPracticeFolder = "Restored to standard folder"
            override val msgSetExcludeGrouping = "Excluded from auto-grouping (hidden from Albums, Artists & Videos)"
            override val msgUnsetExcludeGrouping = "Included back in auto-grouping"
            override val msgFolderNoMediaToAdd = "No media files in this folder to add"
            override val msgCreatedPracticePlaylistTpl = "Created practice playlist \"{0}\""
            override val msgCreatedPlaylistTpl = "Created playlist \"{0}\""
            override val msgPlaylistPracticeEnabled = "Practice Playlist mode enabled"
            override val msgPlaylistPracticeDisabled = "Switched to standard playlist"
            override val msgPlaylistDeleted = "Playlist deleted"
            override val msgAddedTracksCountTpl = "Added {0} track(s)"
            override val msgImportedPlaylistTpl = "Imported \"{0}\": matched {1} track(s)"
            override val msgImportedPlaylistUnmatchedSuffixTpl = " ({0} not found in added folders)"
            override val msgExportedM3u8 = "Exported .m3u8 playlist"
            override val msgExportM3u8Failed = "Failed to export playlist"
            override val msgBookmarkSavedTpl = "Saved bookmark \"{0}\""
            override val msgBookmarkDeleted = "Bookmark deleted"
            override val msgLyricsNotFound = "No matching synced lyrics found"
            override val msgOnlineCandidatesEmpty = "No online tag matches found. Try adjusting the keywords."
            override val msgOnlineSearchFailedTpl = "Online match failed: {0}"
            override val msgCheckNetworkFallback = "Please check your network connection"
            override val msgMetaSavedDbOnly = "Saved to local tag override"
            override val msgMetaSavedDbAndFile = "Tags saved and written to audio file"
            override val msgMetaSavedDeferred = "Applied now; audio file will update after switching tracks"
            override val msgMetaSavedReadonlyFallback = "Folder is read-only; saved to local override"
            override val msgAppliedCandidateTpl = "Applied tags & cover for \"{0} - {1}\""
            override val msgResetMetadataDone = "Restored original file tags"
            override val msgNoAudioForBatch = "No audio tracks available for online matching"
            override val msgBatchSearchFailedTpl = "Batch online matching failed: {0}"
            override val msgNetworkErrorFallback = "Network error"
            override val msgBatchCommitDoneTpl = "Batch completion finished: updated {0} track(s)"
            override val msgCacheCleared = "Cleared local cover & waveform cache"
            override val msgCacheClearedFreedSuffixTpl = " (freed {0})"
            override val msgUpdateCheckFailed = "Failed to check for updates. Check your network."
            override val msgUpdateDownloadFailed = "Failed to download or verify update APK"
            override val msgInstallLaunchFailedTpl = "Unable to launch installer: {0}"
            override val msgGrantInstallPermission = "Allow Play to install unknown apps; the update will resume automatically when you return"
            override val msgSafPermissionFailedTpl = "Failed to persist folder access permission: {0}"
            override val msgPermissionDenied = "Permission denied"

            override val stemErrVerifyFailedTpl = "Verification failed: downloaded model is incomplete or invalid ({0} KB)"
            override val stemErrDownloadNetwork = "Failed to download HTDemucs model. Check your network and retry."
            override val stemErrTooManyRedirectsTpl = "Too many or invalid download redirects ({0})"
            override val stemErrHttpStatusTpl = "Server returned HTTP {0}"
            override val stemErrDownloadFailed = "HTDemucs model download failed"
            override val stemErrSeparationFailed = "HTDemucs stem separation failed"
            override val stemErrModelMissing = "HTDemucs model not downloaded yet"
            override val stemErrDecodeFailed = "Unable to decode current audio track"
            override val stemErrMapCacheFailed = "Unable to map separated stem cache"

            override val lanIfaceWifi = "Wi-Fi"
            override val lanIfaceHotspot = "Hotspot"
            override val lanIfaceUsb = "USB Tethering"
            override val lanIfaceEthernet = "Ethernet"
            override val lanIfaceLan = "LAN"
            override val lanStartFailedTpl = "Failed to start (port {0} may be in use): {1}"
            override val lanUnknownError = "Unknown error"
            override val lanActivityReady = "Server ready — open the URL above in your desktop browser"
            override val lanActivityUploadedTpl = "Uploaded: {0}"
            override val lanActivityStopped = "LAN server stopped"
            override val lanActivityPinEnabled = "Access PIN protection enabled"
            override val lanActivityPinDisabled = "Access PIN protection disabled"
            override val lanRunningSubtitleTpl = "Running · {0}"
            override val lanStoppedSubtitle = "Stopped · Manage library & A-B bookmarks from desktop browser"
            override val lanRefreshIpDesc = "Refresh LAN IP addresses"
            override val lanNoNetworkHint = "No Wi-Fi or hotspot detected. Connect phone and PC to the same LAN, then tap Refresh."
            override val lanQrDescTpl = "LAN Web Studio QR code {0}"
            override val lanPinLabelTpl = "Access PIN: {0}"
            override val lanCopiedAddress = "Copied ✓"
            override val lanCopyAddress = "Copy URL"
            override val lanStatClients = "Clients"
            override val lanStatRequests = "Requests"
            override val lanStatUploads = "Uploads"
            override val lanUploadedValueTpl = "{0} ({1})"
            override val lanFeaturesHeader = "Open in a desktop browser to use all 4 workspaces:"
            override val lanFeaturesBullets = "• Drag-and-drop audio/video uploads & Practice Folder rules\n" +
                "• Spreadsheet inline tag editing & no-key batch online matching\n" +
                "• Drag-to-reorder playlists, Practice Lists & .m3u8 import/export\n" +
                "• Desktop preview & fast A-B bookmarking with [ / ] keys"
            override val lanPinToggleTitle = "Require Access PIN (recommended on public Wi-Fi)"
            override val lanPinToggleSubtitle = "Firewall allows private LAN IPs only — never exposed publicly"
            override val lanStopServerBtn = "Stop Server"
            override val lanStartServerBtnTpl = "Start Server (Port {0})"
        }

        val JA: AppStrings = object : AppStrings {
            override val appName = "Play"
            override val tabLibrary = "ライブラリ"
            override val tabPlaylists = "プレイリスト"
            override val back = "戻る"
            override val close = "閉じる"
            override val cancel = "キャンセル"
            override val confirm = "確定"
            override val save = "保存"
            override val create = "作成"
            override val delete = "削除"
            override val reset = "リセット"
            override val ignore = "無視"
            override val open = "開く"
            override val clear = "消去"
            override val rename = "名前を変更"
            override val moreOptions = "その他のオプション"
            override val moreActions = "その他の操作"
            override val settings = "設定"
            override val play = "再生"
            override val pause = "一時停止"
            override val previousTrack = "前の曲"
            override val nextTrack = "次の曲"
            override val video = "動画"
            override val audio = "オーディオ"
            override val singleTrack = "シングル"
            override val unknownAlbum = "不明なアルバム"
            override val unknownArtist = "不明なアーティスト"
            override val variousArtists = "ヴァリアス・アーティスト"
            override val internalStorage = "内部ストレージ"
            override val localFolder = "ローカルフォルダ"
            override val importedPlaylistFallback = "インポート済みプレイリスト"
            override val defaultPracticePlaylistName = "新規練習プレイリスト"
            override val defaultPlaylistName = "新規プレイリスト"
            override val defaultSectionLabel = "セクション"
            override val sectionWithTimeTpl = "セクション {0}"
            override val unknownAlbumPendingTracksTpl = "{0} 曲未整理"

            override val settingsLanguageSection = "言語"
            override val settingsLanguageTitle = "アプリの言語"
            override val settingsLanguageSubtitle = "再起動不要で即座に反映され、LAN Web スタジオにも連動します"
            override val languageSystem = "システムに従う"
            override val languageZhNative = "中文"
            override val languageEnNative = "English"
            override val languageJaNative = "日本語"

            override val libraryFolders = "フォルダ"
            override val libraryAlbums = "アルバム"
            override val libraryArtists = "アーティスト"
            override val libraryVideos = "動画"
            override val playlistAll = "すべて"
            override val playlistPractice = "練習リスト"
            override val playlistBookmarks = "ブックマーク"
            override val emptyPracticePlaylists = "練習プレイリストはまだありません"
            override val emptyPlaylists = "プレイリストはまだありません"

            override val abLoopActive = "A-B ループ中"
            override val practiceMode = "練習モード"

            override val addFolder = "フォルダを追加"
            override val addLocalFolderCta = "+ ローカルフォルダを追加"
            override val addVideoFolderCta = "+ 動画フォルダを追加"
            override val emptyFoldersTitle = "指定したローカルフォルダのみをスキャン"
            override val emptyFoldersSubtitle = "全ストレージ権限は不要です。着信音やボイスメモがライブラリに混ざることはありません。"
            override val addedFoldersHeaderTpl = "追加済みフォルダ ({0})"
            override val switchToFlatViewDesc = "階層ツリー表示中、タップでフラット表示に切替"
            override val switchToTreeViewDesc = "フラット表示中、タップで階層ツリーに切替"
            override val badgePractice = "練習"
            override val badgeExcludeGrouping = "自動分類から除外"
            override val badgeOffline = "未マウント"
            override val badgePermissionRevoked = "再許可が必要"
            override val mediaCountTpl = "{0} 件のメディア"
            override val readOnlyTag = "読み取り専用"
            override val folderMenuDesc = "フォルダメニュー"
            override val subfolderMenuDesc = "サブフォルダメニュー"
            override val unsetPracticeFolder = "練習フォルダを解除"
            override val setPracticeFolder = "練習フォルダに設定"
            override val unsetExcludeGrouping = "自動分類の除外を解除"
            override val setExcludeGrouping = "自動分類から除外"
            override val addAllToPlaylist = "すべてプレイリストに追加"
            override val batchAutoMatchTagsAndCover = "タグとジャケットを一括自動取得"
            override val batchEditTags = "タグを一括編集"
            override val incrementalRescan = "差分再スキャン"
            override val removeRootFolder = "このフォルダを削除"
            override val removeRootConfirmTitleTpl = "「{0}」を削除しますか？"
            override val removeRootConfirmBody = "MiniPlayer のライブラリ索引からのみ削除されます。端末内のファイルは一切削除されません。"
            override val removeFromIndex = "索引から削除"
            override val navigateUpDirDesc = "上のフォルダへ"
            override val practiceFolderBanner = "練習フォルダ：曲や動画をタップすると自動で練習モードを開きます"
            override val excludedGroupingBanner = "「自動分類から除外」が有効：このフォルダのファイルはアルバム・アーティスト・動画に表示されません"
            override val subfolderItemCountTpl = "{0} 件"
            override val emptyDirMessage = "このフォルダに一致するオーディオ・動画ファイルはありません"
            override val emptyAlbumsTitle = "アルバムはまだありません"
            override val emptyAlbumsSubtitle = "ローカル音楽フォルダを追加すると、アルバムとアルバムアーティスト別に自動分類されます。"
            override val badgePendingTag = "未整理"
            override val unknownAlbumCardTitleTpl = "不明なアルバム ({0} 曲)"
            override val unknownAlbumCardSubtitle = "アルバムタグなし · タップで一括識別"
            override val albumCardSubtitleTpl = "{0} · {1} 曲"
            override val emptyArtistsTitle = "アーティストはまだありません"
            override val emptyArtistsSubtitle = "音楽フォルダを追加すると、コラボや複数アーティストのタグを自動で分類します。"
            override val artistStatsTpl = "{0} アルバム · {1} 曲"
            override val emptyVideosTitle = "ローカル動画はまだありません"
            override val emptyVideosSubtitle = ".mp4 / .mkv / .webm / .mov に対応。4倍ピンチズーム、左右反転、A-B ループが使えます。"
            override val bookmarkCountBadgeTpl = "ブックマーク {0} 件"
            override val bookmarkCountShortTpl = "{0} 件"
            override val practiceHistoryBadge = "練習履歴"
            override val watchedProgressTpl = "視聴済み {0}"
            override val videoMenuDesc = "動画メニュー"
            override val openInPracticeMode = "練習モードで開く"
            override val addToPlaylistOrPractice = "プレイリスト / 練習リストに追加"
            override val editTrackTags = "タグを編集"
            override val albumMenuDesc = "アルバムメニュー"
            override val batchAutoComplete = "一括オンライン自動補完"
            override val batchEditAlbumTags = "アルバムタグを一括編集"
            override val unknownAlbumBannerTitle = "全曲をオンラインで自動識別"
            override val unknownAlbumBannerSubtitle = "API キー不要で MusicBrainz / iTunes / LRCLIB からタイトル・アルバム・高画質ジャケットを一括取得"
            override val trackCountShortTpl = "{0} 曲"
            override val playAll = "すべて再生"
            override val practicePlayAll = "練習再生"
            override val artistAlbumsHeaderTpl = "アルバム ({0})"
            override val artistAllTracksHeaderTpl = "すべての曲 ({0})"

            override val newPlaylist = "新規プレイリスト"
            override val emptyPlaylistsSubtitle = "伴奏オーディオとレッスン動画を1つの練習リストに混在でき、タップで自動的に練習モードが開きます。"
            override val playlistItemsCountTpl = "{0} 件"
            override val playlistBookmarksSuffixTpl = " · ブックマーク {0} 件"
            override val normalPlaylistSuffix = " · 通常プレイリスト"
            override val playlistMenuDesc = "プレイリストメニュー"
            override val switchToNormalPlaylist = "通常プレイリストに変更"
            override val setAsPracticePlaylist = "練習リストに設定"
            override val exportM3u8 = ".m3u8 をエクスポート"
            override val deletePlaylist = "プレイリストを削除"
            override val emptyBookmarksMessage = "保存済みの A-B ブックマークはまだありません。再生画面で練習モードを開き、A/B 点を設定して保存するとここからワンタップで移動できます。"
            override val artistBookmarksCountTpl = "{0} · {1} セクション"
            override val playlistMoreActionsDesc = "プレイリストのその他操作"
            override val practicePlaylistModeTitle = "練習リストモード"
            override val practicePlaylistModeSubtitle = "このリスト内の曲や動画を再生すると自動で練習モードを開きます"
            override val practicePlayAllPlaylist = "すべて練習再生"
            override val addTracksOrVideos = "曲・動画を追加"
            override val dragReorderDesc = "長押しドラッグで並び替え"
            override val semitonesShortTpl = "{0} 半音"
            override val trackMenuDesc = "曲メニュー"
            override val moveUp = "1つ上へ"
            override val moveDown = "1つ下へ"
            override val removeFromPlaylist = "リストから削除"
            override val renamePlaylistTitle = "プレイリスト名を変更"
            override val playlistNameLabel = "プレイリスト名"
            override val playlistNamePlaceholder = "例：今週のバンド練習曲"
            override val setAsPracticePlaylistSubtitle = "再生時に自動で練習モードを開始"
            override val addAudioOrVideoTitle = "オーディオ・動画を追加"
            override val addSelectedCountTpl = "追加 ({0})"
            override val filterTracksPlaceholder = "曲や動画を絞り込み…"
            override val addToPlaylistHeaderTpl = "プレイリストに追加 ({0} 件)"
            override val newPlaylistOrPracticeBtn = "新規プレイリスト / 練習リスト"

            override val batchEditTagsTitleTpl = "タグを一括編集 ({0} 曲)"
            override val searchingOpenLibrary = "オンラインデータベースを検索中…"
            override val autoMatchOnlineBtn = "タグ・ジャケットを自動取得"
            override val pickFromGallery = "画像を選択"
            override val restoreDefault = "デフォルトに戻す"
            override val preserveBackingSuffixLabel = "タイトルに伴奏表記を残す（原曲との混同を防止）"
            override val candidatesMatchedHintTpl = "{0} 件一致（タップして入力後、右上の保存をタップ）："
            override val fieldTitle = "タイトル"
            override val fieldTitleBatchLocked = "タイトル（一括編集時はロック）"
            override val fieldTitleBatchPlaceholder = "各曲の元の値を維持"
            override val fieldArtist = "アーティスト"
            override val fieldAlbum = "アルバム"
            override val fieldAlbumArtist = "アルバムアーティスト"
            override val fieldTrackNumber = "トラック番号"
            override val fieldTrackNumberBatchLocked = "トラック番号（ロック）"
            override val fieldKeepOriginalPlaceholder = "元の値を維持"
            override val fieldMixedPlaceholder = "複数の値（未変更なら維持）"
            override val fieldYear = "年"
            override val fieldKeepValuePlaceholder = "元の値を維持"
            override val writeBackAudioTags = "元の音声ファイルにも安全にタグを書き込む (ID3v2 / Vorbis / MP4 ilst)"
            override val writeDbOnlyHint = "ローカルデータベースのみに保存（動画または読み取り専用フォルダは変更しません）"
            override val lyricsTagCheck = "歌詞✓"
            override val candidateStagedPill = "入力済み · 上の保存をタップ"
            override val batchAutoCompleteTitle = "一括自動補完プレビュー"
            override val batchSearchingProgressTpl = "MusicBrainz / iTunes / LRCLIB を検索中：{0} / {1} 曲…"
            override val cleanedTitleFallbackTpl = "ファイル名から整形：{0}"
            override val switchCandidatesTpl = "候補を切替 ({0})"
            override val preserveBackingChip = "伴奏表記を維持"
            override val writeBackIfWritable = "書き込み可能な場合は音声ファイルにも反映"
            override val writingProgress = "書き込み中…"
            override val confirmApplyCountTpl = "適用 ({0} 曲)"

            override val lyricsButtonShort = "詞"
            override val addToPlaylistDesc = "プレイリストに追加"
            override val shuffleOnDesc = "シャッフル：オン"
            override val shuffleOffDesc = "シャッフル：オフ"
            override val repeatAllDesc = "全曲リピート"
            override val repeatOneDesc = "1曲リピート"
            override val playOnceDesc = "1回再生"
            override val completedLoopsBadgeTpl = "{0} 回完了"
            override val prevOrRestartADesc = "タップで A 点から再練習、ダブルタップで曲頭へ"
            override val rewind5sDesc = "5秒戻る"
            override val forward5sDesc = "5秒進む"
            override val rewind10sDesc = "10秒戻る"
            override val forward10sDesc = "10秒進む"
            override val fileDetailsTitle = "ファイル詳細"
            override val fileDetailNameTpl = "ファイル名：{0}"
            override val fileDetailPathTpl = "相対パス：{0}"
            override val fileDetailFormatTpl = "形式：{0}"
            override val fileDetailSizeTpl = "サイズ：{0}"
            override val fileDetailResolutionTpl = "解像度：{0} × {1}"
            override val collapsePlayerDesc = "プレイヤーを閉じる"
            override val nowPlayingTitle = "再生中"
            override val enterLandscapeVideoDesc = "横画面フルスクリーン"
            override val sleepTimer = "スリープタイマー"
            override val currentQueueDesc = "再生キュー"
            override val fastSeekActiveSuffix = "（キーフレーム高速シーク有効）"
            override val videoSeekAutoTpl = "動画ループシーク：自動高速{0}"
            override val videoSeekExact = "動画ループシーク：精密フレーム"
            override val fileDetails = "ファイル詳細"
            override val noLyricsFound = "埋め込み歌詞または同名の .lrc ファイルがありません"
            override val fetchLyricsLrclibBtn = "LRCLIB から同期歌詞を取得"
            override val queueHeaderTpl = "再生キュー ({0})"
            override val sleepTimerRemainingSuffixTpl = " · 残り {0}"
            override val sleepTimerTrackEndSuffix = " · 現在の曲の終了後に一時停止"
            override val sleepTimerMinutesTpl = "{0} 分後に一時停止"
            override val sleepTimerTrackEndOption = "現在の曲の終了後に一時停止"
            override val sleepTimerOffBtn = "スリープタイマーをオフ"

            override val barsCountTpl = "{0}小節"
            override val waitingPointB = " · B点待機"
            override val waveformRulerDescTpl = "波形と A-B ループルーラー、現在位置 {0}"
            override val updateBookmarkRange = "現在の A-B 範囲で更新"
            override val renameBookmark = "ブックマーク名を変更"
            override val deleteBookmark = "ブックマークを削除"
            override val renameBookmarkTitle = "ブックマーク名を変更"
            override val saveBookmarkSheetTitle = "ブックマークを保存（プリセットをタップ）"
            override val customBookmarkPlaceholder = "カスタム名を入力（例：速弾きパート）"
            override val presetSectionIntro = "イントロ"
            override val presetSectionVerse = "Aメロ"
            override val presetSectionPreChorus = "Bメロ"
            override val presetSectionChorus = "サビ"
            override val presetSectionBridge = "ブリッジ"
            override val presetSectionSolo1 = "ソロ 1"
            override val presetSectionSolo2 = "ソロ 2"
            override val presetSectionOutro = "アウトロ"
            override val setPointADescTpl = "現在位置を A 点に設定、現在 {0}"
            override val setPointBDescTpl = "現在位置を B 点に設定、現在 {0}"
            override val suspendLoopDesc = "ループを一時停止"
            override val resumeLoopDesc = "ループを再開"
            override val clearLoopDesc = "ループをクリア"
            override val saveBookmarkDesc = "ブックマークを保存"
            override val speedResetDescTpl = "練習速度 {0}倍、タップで1.0倍に戻す"
            override val pitchControlDesc = "ピッチ調整"
            override val stemMixerDesc = "ステムミキサー (ボーカル/ギター/ベース/ドラム/その他)"
            override val countInOnDesc = "カウントイン：4拍（オン）"
            override val countInOffDesc = "カウントイン：オフ"
            override val countInBadge4 = "4拍"
            override val countInOffShort = "オフ"
            override val keepScreenOnEnabledDesc = "画面常時点灯：オン"
            override val keepScreenOnDisabledDesc = "画面常時点灯：オフ"
            override val videoMirrorOnDesc = "動画左右反転：オン"
            override val videoMirrorOffDesc = "動画左右反転：オフ"
            override val bpmChipDescTpl = "テンポ {0} BPM、タップで BPM パネルを開閉"
            override val effectiveBpmTpl = "実効 ≈ {0}"
            override val detectedBpmSuffixTpl = " · 検出 {0}"
            override val bpmMinusDesc = "BPM を 1 下げる"
            override val bpmPlusDesc = "BPM を 1 上げる"
            override val tapTempoLabel = "タップ"
            override val detectingLabel = "検出中…"
            override val autoDetectLabel = "自動"
            override val barSnapLabel = "小節スナップ"
            override val alignDownbeatDesc = "現在位置を小節の1拍目に合わせる"
            override val alignDownbeatLabel = "1拍目に合わせる"
            override val firstDownbeatTpl = "1拍目 {0}"
            override val beatGridSourceManual = " · 手動"
            override val beatGridSourceStems = " · ステム"
            override val beatGridSourceTag = " · タグ"
            override val beatOneLabel = "1拍目"
            override val shiftBeatMinusLabel = "-1拍"
            override val shiftBeatMinusDesc = "1拍目を1拍早める"
            override val shiftBeatPlusLabel = "+1拍"
            override val shiftBeatPlusDesc = "1拍目を1拍遅らせる"
            override val shiftHalfBarLabel = "±2拍"
            override val shiftHalfBarAmbiguousDesc = "1拍目と3拍目が近いため、タップで3拍目に切替"
            override val shiftHalfBarDesc = "1拍目を3拍目に反転（半小節シフト）"
            override val nudgeMsMinusDesc = "1拍目を10ミリ秒早める"
            override val nudgeMsPlusDesc = "1拍目を10ミリ秒遅らせる"
            override val pendingStemsGridTpl = "より高精度なビートグリッド ({0}) — 適用？"
            override val pitchMinusSt = "-1 半音"
            override val pitchPlusSt = "+1 半音"
            override val pitchOriginal = "原曲キー (0 st)"
            override val pitchResetTpl = "{0} st · リセット"
            override val pitchHintMinus1 = "ヒント：標準チューニングの曲を半音下げ（Eb）に調整"
            override val pitchHintPlus1 = "ヒント：+1 半音で半音下げ曲を標準チューニングで練習"
            override val pitchHintDefault = "範囲 ±6 半音、下で ±50 ct の微調整が可能"
            override val centsSliderDescTpl = "セント微調整 {0} ct"
            override val stemModelTitle = "初回のみ HTDemucs 6s 分離モデルのダウンロードが必要 (~100 MB)"
            override val stemModelSubtitle = "Meta HTDemucs による端末内オフライン5ステム分離（一度ダウンロードすれば常時利用可能）"
            override val stemDownloadingTpl = "HTDemucs モデルをダウンロード中 {0}%…"
            override val stemDownloadBtn = "HTDemucs モデルをダウンロード"
            override val stemSeparatingSegTpl = "ステム分離中 {0}/{1} ({2}%)…"
            override val stemDecodingTpl = "分離用にオーディオをデコード中 ({0}%)…"
            override val stemSeparateTrackBtn = "HTDemucs で現在の曲を5ステム分離"
            override val stemReadyBadge = "HTDemucs 5ステム準備完了"
            override val stemVocals = "ボーカル"
            override val stemGuitar = "ギター"
            override val stemBass = "ベース"
            override val stemDrums = "ドラム"
            override val stemOther = "その他"
            override val stemVolumeDescTpl = "{0} 音量 {1}%"
            override val stemUnmuteDescTpl = "{0} のミュートを解除"
            override val stemMuteDescTpl = "{0} をミュート"
            override val stemUnsoloDescTpl = "{0} のソロを解除"
            override val stemSoloDescTpl = "{0} をソロ"
            override val videoZoomResetTpl = "{0}x · リセット"
            override val videoZoomResetCompactTpl = "{0}x リセット"
            override val videoMirroredBadge = "反転中"
            override val exitFullscreenDesc = "フルスクリーン終了"
            override val disableVideoMirrorDesc = "動画反転をオフ"
            override val enableVideoMirrorDesc = "動画反転をオン"
            override val dockHudBottomDesc = "HUDを下部に移動"
            override val dockHudTopDesc = "HUDを上部に移動"
            override val exitPracticeModeDesc = "練習モードを終了"
            override val portraitModeDesc = "縦画面モード"
            override val loopCounterCapsuleTpl = " ({0}回目)"
            override val countInHeadlineTpl = "カウントイン {0} / 4"
            override val countInSkipHint = "タップでスキップして即再生"

            override val settingsLibrarySection = "ローカルライブラリとワイヤレス接続"
            override val settingsLanStudioTitle = "PC LAN Web スタジオ"
            override val settingsLanStudioSubtitle = "同じ Wi-Fi 上の PC ブラウザから無線転送・タグ一括編集・プレイリスト管理"
            override val settingsAutoRescanTitle = "起動時にフォルダを自動差分スキャン"
            override val settingsAutoRescanSubtitle = "未変更ファイルを高速スキップし、未接続 OTG のブックマークも自動保持"
            override val settingsCacheSection = "ローカルキャッシュ"
            override val settingsClearCacheTitle = "ジャケット・波形キャッシュを消去"
            override val settingsClearCacheSubtitleTpl = "現在 {0} 使用中（音楽ファイルやブックマークは保持）"
            override val settingsAboutSection = "アプリ情報とアップデート"
            override val settingsAboutTagline = "ローカル限定スキャン · Material You · 署名検証付き GitHub 更新"
            override val settingsCheckingUpdate = "確認中…"
            override val settingsCheckUpdateBtn = "アップデートを確認"
            override val settingsNewVersionTpl = "新しいバージョン {0} ({1})"
            override val settingsDownloadInstallBtn = "ダウンロードしてインストール (SHA-256・署名検証済)"
            override val settingsAlreadyLatestTpl = "最新バージョンです ({0})"
            override val settingsDownloadingApkTpl = "{0} をダウンロード中：{1} / {2}"
            override val settingsVerifiedApkTpl = "✓ {0} の SHA-256 と署名証明書を検証済み"
            override val settingsInstallNowBtn = "今すぐインストール"
            override val feedbackEntryTitle = "Agent にフィードバックを送信"
            override val feedbackEmailUnset = "Agent フィードバック先メール：未設定（端末内のみ保存）"
            override val feedbackEmailSetTpl = "Agent フィードバック先メール：{0}"
            override val feedbackSendButton = "フィードバックを送信"
            override val openSourceLicensesBtn = "オープンソースライセンス"
            override val openSourceLicensesTitle = "オープンソースライセンス"
            override val openSourceLicensesBody = "MiniPlayer は Apache License 2.0 に基づき、Android Jetpack Compose Material 3、AndroidX Media3 (ExoPlayer)、および API キー不要の公開データベース MusicBrainz / iTunes Search / LRCLIB を使用しています。"
            override val feedbackSheetSubtitleTpl = "{0}: [{1}] プレフィックスを自動付与し、メールアプリで送信します。"
            override val feedbackEmailLabel = "Agent フィードバック先メール（端末内のみ保存）"
            override val feedbackEmailInvalidHintTpl = "有効なメールアドレスを入力してください（例：{0}）"
            override val feedbackEmailCleared = "Agent フィードバック先メールを消去しました"
            override val feedbackEmailSaved = "Agent フィードバック先メールを保存しました"
            override val feedbackTitleLabel = "件名"
            override val feedbackTitlePlaceholder = "例：練習モードでループ区間の書き出しに対応してほしい"
            override val feedbackEmailSubjectPreviewTpl = "メール件名：{0}"
            override val feedbackDetailsLabel = "詳細内容"
            override val feedbackDetailsPlaceholder = "使用状況、再現手順、改善案などを記入してください"
            override val feedbackStagingImage = "処理中…"
            override val feedbackChangeImage = "画像を変更"
            override val feedbackAddImage = "画像を追加"
            override val feedbackRemoveImageDesc = "画像を削除"
            override val feedbackReadImageFailedTpl = "画像の読み込みに失敗しました：{0}"
            override val feedbackRetryFallback = "もう一度お試しください"
            override val feedbackIncludeDiagnosticsTitle = "バージョンと再生・練習状態の診断情報を添付"
            override val feedbackIncludeDiagnosticsSubtitle = "バージョン・速度・ピッチ・ループ位置のみを含み、ローカルパスは含みません"
            override val feedbackHintMissingEmail = "先に Agent フィードバック先メールを入力してください"
            override val feedbackHintMissingContent = "件名・詳細内容・画像のいずれか1つ以上を入力してください"
            override val feedbackEmailRequiredErrorTpl = "有効な Agent フィードバック先メールを入力してください（例：{0}）"
            override val feedbackNoMailClient = "利用可能なメールアプリが見つかりません。メールアプリをインストールしてください。"
            override val feedbackCannotReadImage = "選択した画像を読み込めません"
            override val feedbackImageTooLarge = "画像ファイルが 20 MB の上限を超えています"
            override val feedbackImageEmpty = "選択した画像が空です"
            override val feedbackInvalidEmailError = "無効なメールアドレスです"
            override val feedbackDefaultSummary = "フィードバック"
            override val feedbackAttachmentOnlySummary = "画像フィードバック"
            override val feedbackDiagHeader = "Play 診断情報"
            override val feedbackDiagAppVersion = "アプリバージョン"
            override val feedbackDiagDevice = "端末環境"
            override val feedbackDiagMedia = "再生中のメディア"
            override val feedbackDiagPlaying = "再生中"
            override val feedbackDiagPaused = "一時停止中"
            override val feedbackDiagLoopActive = "有効"
            override val feedbackDiagLoopSuspended = "一時停止"
            override val feedbackDiagNoLoop = "ループ未設定"
            override val feedbackDiagPracticeState = "練習状態"
            override val feedbackDiagSpeed = "速度"
            override val feedbackDiagPitch = "ピッチ"
            override val feedbackDiagBeatGrid = "ビートグリッド"
            override val feedbackDiagBeatOne = "1拍目"
            override val feedbackDiagPhraseOffset = "フレーズオフセット"
            override val feedbackDiagSource = "ソース"
            override val feedbackDiagPlaybackMode = "再生モード"
            override val feedbackDiagCasualMode = "通常モード"
            override val feedbackDiagAttachment = "添付画像"

            override val msgFolderAddedTpl = "フォルダ「{0}」を追加しました"
            override val msgFolderAlreadyCoveredTpl = "このフォルダは「{0}」に含まれているため直接移動しました"
            override val msgFolderMergedTpl = "親フォルダ「{0}」を追加し、{1} 件の子フォルダを統合しました"
            override val msgIncrementalRescanDone = "差分スキャンが完了しました"
            override val msgRootRescanDone = "フォルダの差分スキャンが完了しました"
            override val msgRootRemoved = "ライブラリからフォルダを削除しました（端末内ファイルは保持）"
            override val msgSetPracticeFolder = "練習フォルダに設定しました（曲タップで自動で練習モードを起動）"
            override val msgUnsetPracticeFolder = "通常フォルダに戻しました"
            override val msgSetExcludeGrouping = "「自動分類から除外」に設定しました（アルバム・アーティスト・動画に表示されません）"
            override val msgUnsetExcludeGrouping = "「自動分類から除外」を解除しました"
            override val msgFolderNoMediaToAdd = "このフォルダに追加可能なメディアファイルがありません"
            override val msgCreatedPracticePlaylistTpl = "練習リスト「{0}」を作成しました"
            override val msgCreatedPlaylistTpl = "プレイリスト「{0}」を作成しました"
            override val msgPlaylistPracticeEnabled = "練習リストモードをオンにしました"
            override val msgPlaylistPracticeDisabled = "通常プレイリストに切り替えました"
            override val msgPlaylistDeleted = "プレイリストを削除しました"
            override val msgAddedTracksCountTpl = "{0} 曲を追加しました"
            override val msgImportedPlaylistTpl = "「{0}」をインポート：{1} 曲一致"
            override val msgImportedPlaylistUnmatchedSuffixTpl = "（{0} 曲は追加済みフォルダに見つかりませんでした）"
            override val msgExportedM3u8 = ".m3u8 プレイリストをエクスポートしました"
            override val msgExportM3u8Failed = "プレイリストのエクスポートに失敗しました"
            override val msgBookmarkSavedTpl = "ブックマーク「{0}」を保存しました"
            override val msgBookmarkDeleted = "ブックマークを削除しました"
            override val msgLyricsNotFound = "一致する同期歌詞が見つかりませんでした"
            override val msgOnlineCandidatesEmpty = "オンライン候補が見つかりませんでした。キーワードを変更して再試行してください。"
            override val msgOnlineSearchFailedTpl = "オンライン検索に失敗しました：{0}"
            override val msgCheckNetworkFallback = "ネットワーク接続を確認してください"
            override val msgMetaSavedDbOnly = "ローカルタグを更新しました"
            override val msgMetaSavedDbAndFile = "タグを更新し、音声ファイルにも書き込みました"
            override val msgMetaSavedDeferred = "即時反映しました。元の音声ファイルは曲切替後に更新されます"
            override val msgMetaSavedReadonlyFallback = "読み取り専用フォルダのためローカルのみに保存しました"
            override val msgAppliedCandidateTpl = "「{0} - {1}」のタグとジャケットを適用しました"
            override val msgResetMetadataDone = "元のファイルタグを復元しました"
            override val msgNoAudioForBatch = "オンライン補完対象のオーディオ曲がありません"
            override val msgBatchSearchFailedTpl = "一括オンライン検索に失敗しました：{0}"
            override val msgNetworkErrorFallback = "ネットワークエラー"
            override val msgBatchCommitDoneTpl = "一括補完が完了しました（{0} 曲更新）"
            override val msgCacheCleared = "ジャケットと波形のキャッシュを消去しました"
            override val msgCacheClearedFreedSuffixTpl = "（{0} 解放）"
            override val msgUpdateCheckFailed = "アップデートの確認に失敗しました。ネットワークを確認してください。"
            override val msgUpdateDownloadFailed = "APK のダウンロードまたは検証に失敗しました"
            override val msgInstallLaunchFailedTpl = "インストーラーを起動できません：{0}"
            override val msgGrantInstallPermission = "Play による不明なアプリのインストールを許可してください。戻ると自動で更新を開始します"
            override val msgSafPermissionFailedTpl = "フォルダの永続アクセス権限を取得できませんでした：{0}"
            override val msgPermissionDenied = "権限が拒否されました"

            override val stemErrVerifyFailedTpl = "検証失敗：モデルファイルが不完全または無効です ({0} KB)"
            override val stemErrDownloadNetwork = "HTDemucs モデルをダウンロードできません。ネットワークを確認して再試行してください。"
            override val stemErrTooManyRedirectsTpl = "ダウンロードのリダイレクトが多すぎるか無効です ({0})"
            override val stemErrHttpStatusTpl = "サーバーエラー HTTP {0}"
            override val stemErrDownloadFailed = "HTDemucs モデルのダウンロードに失敗しました"
            override val stemErrSeparationFailed = "HTDemucs ステム分離に失敗しました"
            override val stemErrModelMissing = "HTDemucs モデルが未ダウンロードです"
            override val stemErrDecodeFailed = "現在のオーディオトラックをデコードできません"
            override val stemErrMapCacheFailed = "分離済みステムキャッシュの読み込みに失敗しました"

            override val lanIfaceWifi = "Wi-Fi"
            override val lanIfaceHotspot = "テザリング"
            override val lanIfaceUsb = "USB テザリング"
            override val lanIfaceEthernet = "イーサネット"
            override val lanIfaceLan = "LAN"
            override val lanStartFailedTpl = "起動に失敗しました（ポート {0} が使用中の可能性があります）：{1}"
            override val lanUnknownError = "不明なエラー"
            override val lanActivityReady = "サーバー準備完了。PC のブラウザで上記 URL を開いてください"
            override val lanActivityUploadedTpl = "アップロード受信：{0}"
            override val lanActivityStopped = "LAN サーバーを停止しました"
            override val lanActivityPinEnabled = "アクセス PIN 保護を有効にしました"
            override val lanActivityPinDisabled = "アクセス PIN 保護を無効にしました"
            override val lanRunningSubtitleTpl = "起動中 · {0}"
            override val lanStoppedSubtitle = "停止中 · PC ブラウザから曲管理と A-B 打点が可能"
            override val lanRefreshIpDesc = "LAN IP アドレスを更新"
            override val lanNoNetworkHint = "Wi-Fi またはテザリングが検出されません。端末と PC を同じ LAN に接続し、右上の更新をタップしてください。"
            override val lanQrDescTpl = "LAN Web スタジオ QR コード {0}"
            override val lanPinLabelTpl = "アクセス PIN：{0}"
            override val lanCopiedAddress = "コピー済み ✓"
            override val lanCopyAddress = "URL をコピー"
            override val lanStatClients = "接続数"
            override val lanStatRequests = "リクエスト"
            override val lanStatUploads = "受信ファイル"
            override val lanUploadedValueTpl = "{0} 件 ({1})"
            override val lanFeaturesHeader = "PC ブラウザで開くと 4 つのワークスペースを利用できます："
            override val lanFeaturesBullets = "• オーディオ・動画のドラッグ＆ドロップ転送と練習フォルダ設定\n" +
                "• 表形式でのインラインタグ編集と API キー不要の一括オンライン補完\n" +
                "• プレイリスト・練習リストのドラッグ並び替えと .m3u8 入出力\n" +
                "• PC プレビューと [ / ] キーによる高速 A-B ブックマーク打点"
            override val lanPinToggleTitle = "アクセス PIN 認証（公共 Wi-Fi では有効推奨）"
            override val lanPinToggleSubtitle = "ローカル LAN のプライベート IP のみ許可され、外部公開されません"
            override val lanStopServerBtn = "サーバーを停止"
            override val lanStartServerBtnTpl = "サーバーを起動 (ポート {0})"
        }
    }
}
