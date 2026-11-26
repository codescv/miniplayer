package com.miniplayer.app.core.i18n

/**
 * Strictly single-language UI string table for the embedded PC LAN Web Studio SPA
 * (`LanStudioWebApp`) in `zh`, `en`, and `ja`.
 * Pure JVM (zero Android imports) for deterministic server rendering and unit test parity checks.
 */
interface LanWebStrings {
    // Page & Topbar
    val pageTitle: String
    val connectingLibrary: String
    val scanning: String
    val scanningCountTpl: String
    val statSummaryTpl: String
    val tabFolders: String
    val tabMetadata: String
    val tabPlaylists: String
    val tabStudio: String
    val rescanLibraryBtn: String
    val rescanLibraryTooltip: String
    val themeBtn: String
    val themeTooltip: String

    // Auth & Common
    val pinRequiredError: String
    val requestFailedTpl: String
    val pinModalTitle: String
    val pinModalHint: String
    val pinModalPlaceholder: String
    val pinSubmitBtn: String
    val pinUnlockedToast: String
    val pinWrongError: String
    val cancel: String
    val close: String
    val saveChanges: String
    val delete: String
    val edit: String
    val create: String
    val rename: String
    val remove: String
    val unknownAlbum: String
    val unknownArtist: String
    val variousArtists: String
    val video: String
    val audio: String
    val videoTagPrefix: String
    val mountMounted: String
    val mountOffline: String
    val mountPermissionRevoked: String

    // Workspace 1: Folders & Upload
    val mountedFoldersTitle: String
    val safTip: String
    val practiceFolderToggleLabel: String
    val newSubfolderBtn: String
    val uploadMediaBtn: String
    val dropzoneTitle: String
    val dropzoneSubtitle: String
    val uploadPreparing: String
    val colName: String
    val colTypeSpec: String
    val colArtistAlbum: String
    val colPracticeTags: String
    val colActions: String
    val noFolderAddedCrumbs: String
    val noFolderAddedEmpty: String
    val noMountedFolders: String
    val badgeReadOnly: String
    val badgePracticeShort: String
    val itemsCountTpl: String
    val rootFolderFallback: String
    val badgePracticeDir: String
    val badgeNormalDir: String
    val subfolderCountTpl: String
    val unsetPracticeDirBtn: String
    val setPracticeDirBtn: String
    val bookmarksCountBadgeTpl: String
    val previewMarkBtn: String
    val tagsBtn: String
    val folderEmptyHint: String
    val alertAddFolderFirst: String
    val promptNewSubfolderName: String
    val toastFolderCreatedTpl: String
    val toastMarkedPracticeFolder: String
    val toastUnmarkedPracticeFolder: String
    val toastSubfolderMarkedPractice: String
    val toastSubfolderUnmarkedPractice: String
    val uploadFailedStatusTpl: String
    val uploadNetworkError: String
    val alertMountWritableFirst: String
    val uploadingProgressTpl: String
    val toastUploadedCountTpl: String
    val uploadFailedGeneric: String
    val uploadUnsupportedExtTpl: String
    val toastRescanComplete: String

    // Workspace 2: Metadata Spreadsheet
    val filterAllLibrary: String
    val filterByFolder: String
    val filterByAlbum: String
    val filterUnknownAlbum: String
    val filterByArtist: String
    val filterVideo: String
    val metaSearchPlaceholder: String
    val batchAutoCompleteListBtn: String
    val batchSelectedPrefix: String
    val batchSelectedSuffix: String
    val batchEditBtn: String
    val batchAutoCompleteSelectedBtn: String
    val batchAddToPlaylistBtn: String
    val batchResetMetadataBtn: String
    val colTrackNum: String
    val colTitleInline: String
    val colArtist: String
    val colAlbum: String
    val colYear: String
    val colDurationFormat: String
    val subfilterAllFolders: String
    val subfilterAllAlbums: String
    val subfilterAllArtists: String
    val rootFallbackTpl: String
    val metaEmptyHint: String
    val badgeEdited: String
    val badgeEditedTooltip: String
    val badgeBacking: String
    val autoMatchOnlineBtn: String
    val previewBtn: String
    val toastTagUpdated: String
    val backingTitleSuffix: String
    val preserveBackingCheckboxTpl: String
    val editModalTitle: String
    val editModalFileTpl: String
    val editModalAutoMatchBtn: String
    val fieldTitle: String
    val fieldArtist: String
    val fieldAlbum: String
    val fieldAlbumArtist: String
    val fieldTrackYear: String
    val placeholderTrackNum: String
    val placeholderYear: String
    val writebackCheckbox: String
    val extraCover: String
    val extraLyrics: String
    val stagedHintPlain: String
    val stagedHintWithExtrasTpl: String
    val candidatesMatchedHeaderTpl: String
    val candidateLyricsTag: String
    val candidateCoverTag: String
    val candidateFilledBadge: String
    val candidateMetaLineTpl: String
    val searchingOnlineProviders: String
    val noOnlineCandidatesRetry: String
    val toastMetadataSaved: String
    val batchEditTitleTpl: String
    val batchEditSubtitle: String
    val batchFieldArtist: String
    val batchPlaceholderArtist: String
    val batchFieldAlbum: String
    val batchPlaceholderAlbum: String
    val batchFieldAlbumArtist: String
    val batchPlaceholderAlbumArtist: String
    val batchFieldYear: String
    val batchPlaceholderYear: String
    val batchApplyBtn: String
    val alertSelectAtLeastOneField: String
    val toastBatchUpdatedTpl: String
    val confirmResetMetadataTpl: String
    val toastMetadataReset: String
    val toastAutoMatchedTpl: String
    val autoMatchSuccessFallback: String
    val toastNoHighConfidenceCandidate: String
    val alertNoOnlineCandidates: String
    val pickCandidateTitle: String
    val candidateSourceLineTpl: String
    val toastAppliedOnlineTags: String
    val alertFilteredListEmpty: String
    val toastBatchSearchingTpl: String
    val toastBatchAutoCompleteDoneTpl: String

    // Workspace 3: Playlists & Practice Lists
    val playlistsTitle: String
    val newPlaylistBtn: String
    val importM3uBtn: String
    val filterAll: String
    val filterPracticeLists: String
    val playlistPaneEmptyInitial: String
    val playlistPaneEmptyNone: String
    val playlistsEmptyFiltered: String
    val playlistSidebarMetaTpl: String
    val markBtnShort: String
    val playlistTracksEmpty: String
    val playlistTrackCountHintTpl: String
    val playlistPracticeToggleLabel: String
    val addTracksBtn: String
    val exportM3uBtn: String
    val colOrder: String
    val colTrackVideo: String
    val colDuration: String
    val colReorderActions: String
    val createPlaylistTitle: String
    val playlistNameLabel: String
    val playlistNamePlaceholder: String
    val newPlaylistPracticeCheckbox: String
    val alertEnterPlaylistName: String
    val toastPlaylistCreatedTpl: String
    val promptRenamePlaylist: String
    val toastSetPracticePlaylist: String
    val toastSetNormalPlaylist: String
    val confirmDeletePlaylist: String
    val toastPlaylistDeleted: String
    val addTracksModalTitle: String
    val allTracksAlreadyInPlaylist: String
    val addSelectedBtn: String
    val toastAddedTracksTpl: String
    val alertCreatePlaylistFirst: String
    val batchAddPlaylistTitleTpl: String
    val practiceTagPrefix: String
    val addToListConfirmBtn: String
    val toastAddedToPlaylist: String
    val importedPlaylistFallback: String
    val toastImportedPlaylistTpl: String

    // Workspace 4: PC Preview & A-B Studio
    val selectTrackVideoTitle: String
    val studioSearchPlaceholder: String
    val studioEmptyHint: String
    val videoMirrorLabel: String
    val shortcutsLabel: String
    val shortcutPlayPause: String
    val shortcutSetA: String
    val shortcutSetB: String
    val shortcutLoopToggle: String
    val shortcutBackToA: String
    val audioPreviewBanner: String
    val curTimeLabel: String
    val abRangeUnset: String
    val abPointASetTpl: String
    val durTimeLabel: String
    val loopOff: String
    val loopOn: String
    val loopPaused: String
    val jumpToABtn: String
    val clearAbBtn: String
    val bookmarksCardTitle: String
    val bookmarkInputPlaceholder: String
    val bookmarkSaveSpeedLabel: String
    val saveBookmarkBtn: String
    val practiceStateCardTitle: String
    val speedSliderLabelPrefix: String
    val speedSliderLabelSuffix: String
    val speedResetBtn: String
    val pitchSemitonesLabel: String
    val bpmLabel: String
    val tapTempoBtn: String
    val countInLabel: String
    val countInOffOption: String
    val countInBeat4Option: String
    val savePracticeStateBtn: String
    val studioBookmarkShortBadgeTpl: String
    val toastSetAbFirst: String
    val bookmarksEmptyHint: String
    val alertSetAbPointsFirst: String
    val defaultSectionNumberTpl: String
    val toastBookmarkSavedTpl: String
    val toastBookmarkDeleted: String
    val toastPracticeStateSaved: String

    fun toJsMap(): Map<String, String> = linkedMapOf(
        "scanningCountTpl" to scanningCountTpl,
        "statSummaryTpl" to statSummaryTpl,
        "pinRequiredError" to pinRequiredError,
        "requestFailedTpl" to requestFailedTpl,
        "pinModalTitle" to pinModalTitle,
        "pinModalHint" to pinModalHint,
        "pinModalPlaceholder" to pinModalPlaceholder,
        "pinSubmitBtn" to pinSubmitBtn,
        "pinUnlockedToast" to pinUnlockedToast,
        "pinWrongError" to pinWrongError,
        "cancel" to cancel,
        "close" to close,
        "saveChanges" to saveChanges,
        "delete" to delete,
        "edit" to edit,
        "create" to create,
        "rename" to rename,
        "remove" to remove,
        "unknownAlbum" to unknownAlbum,
        "unknownArtist" to unknownArtist,
        "variousArtists" to variousArtists,
        "video" to video,
        "audio" to audio,
        "videoTagPrefix" to videoTagPrefix,
        "mountMounted" to mountMounted,
        "mountOffline" to mountOffline,
        "mountPermissionRevoked" to mountPermissionRevoked,
        "noFolderAddedCrumbs" to noFolderAddedCrumbs,
        "noFolderAddedEmpty" to noFolderAddedEmpty,
        "noMountedFolders" to noMountedFolders,
        "badgeReadOnly" to badgeReadOnly,
        "badgePracticeShort" to badgePracticeShort,
        "itemsCountTpl" to itemsCountTpl,
        "rootFolderFallback" to rootFolderFallback,
        "badgePracticeDir" to badgePracticeDir,
        "badgeNormalDir" to badgeNormalDir,
        "subfolderCountTpl" to subfolderCountTpl,
        "unsetPracticeDirBtn" to unsetPracticeDirBtn,
        "setPracticeDirBtn" to setPracticeDirBtn,
        "bookmarksCountBadgeTpl" to bookmarksCountBadgeTpl,
        "previewMarkBtn" to previewMarkBtn,
        "tagsBtn" to tagsBtn,
        "folderEmptyHint" to folderEmptyHint,
        "alertAddFolderFirst" to alertAddFolderFirst,
        "promptNewSubfolderName" to promptNewSubfolderName,
        "toastFolderCreatedTpl" to toastFolderCreatedTpl,
        "toastMarkedPracticeFolder" to toastMarkedPracticeFolder,
        "toastUnmarkedPracticeFolder" to toastUnmarkedPracticeFolder,
        "toastSubfolderMarkedPractice" to toastSubfolderMarkedPractice,
        "toastSubfolderUnmarkedPractice" to toastSubfolderUnmarkedPractice,
        "uploadFailedStatusTpl" to uploadFailedStatusTpl,
        "uploadNetworkError" to uploadNetworkError,
        "alertMountWritableFirst" to alertMountWritableFirst,
        "uploadingProgressTpl" to uploadingProgressTpl,
        "toastUploadedCountTpl" to toastUploadedCountTpl,
        "uploadFailedGeneric" to uploadFailedGeneric,
        "toastRescanComplete" to toastRescanComplete,
        "subfilterAllFolders" to subfilterAllFolders,
        "subfilterAllAlbums" to subfilterAllAlbums,
        "subfilterAllArtists" to subfilterAllArtists,
        "rootFallbackTpl" to rootFallbackTpl,
        "metaEmptyHint" to metaEmptyHint,
        "badgeEdited" to badgeEdited,
        "badgeEditedTooltip" to badgeEditedTooltip,
        "badgeBacking" to badgeBacking,
        "autoMatchOnlineBtn" to autoMatchOnlineBtn,
        "previewBtn" to previewBtn,
        "toastTagUpdated" to toastTagUpdated,
        "backingTitleSuffix" to backingTitleSuffix,
        "preserveBackingCheckboxTpl" to preserveBackingCheckboxTpl,
        "editModalTitle" to editModalTitle,
        "editModalFileTpl" to editModalFileTpl,
        "editModalAutoMatchBtn" to editModalAutoMatchBtn,
        "fieldTitle" to fieldTitle,
        "fieldArtist" to fieldArtist,
        "fieldAlbum" to fieldAlbum,
        "fieldAlbumArtist" to fieldAlbumArtist,
        "fieldTrackYear" to fieldTrackYear,
        "placeholderTrackNum" to placeholderTrackNum,
        "placeholderYear" to placeholderYear,
        "writebackCheckbox" to writebackCheckbox,
        "extraCover" to extraCover,
        "extraLyrics" to extraLyrics,
        "stagedHintPlain" to stagedHintPlain,
        "stagedHintWithExtrasTpl" to stagedHintWithExtrasTpl,
        "candidatesMatchedHeaderTpl" to candidatesMatchedHeaderTpl,
        "candidateLyricsTag" to candidateLyricsTag,
        "candidateCoverTag" to candidateCoverTag,
        "candidateFilledBadge" to candidateFilledBadge,
        "candidateMetaLineTpl" to candidateMetaLineTpl,
        "searchingOnlineProviders" to searchingOnlineProviders,
        "noOnlineCandidatesRetry" to noOnlineCandidatesRetry,
        "toastMetadataSaved" to toastMetadataSaved,
        "batchEditTitleTpl" to batchEditTitleTpl,
        "batchEditSubtitle" to batchEditSubtitle,
        "batchFieldArtist" to batchFieldArtist,
        "batchPlaceholderArtist" to batchPlaceholderArtist,
        "batchFieldAlbum" to batchFieldAlbum,
        "batchPlaceholderAlbum" to batchPlaceholderAlbum,
        "batchFieldAlbumArtist" to batchFieldAlbumArtist,
        "batchPlaceholderAlbumArtist" to batchPlaceholderAlbumArtist,
        "batchFieldYear" to batchFieldYear,
        "batchPlaceholderYear" to batchPlaceholderYear,
        "batchApplyBtn" to batchApplyBtn,
        "alertSelectAtLeastOneField" to alertSelectAtLeastOneField,
        "toastBatchUpdatedTpl" to toastBatchUpdatedTpl,
        "confirmResetMetadataTpl" to confirmResetMetadataTpl,
        "toastMetadataReset" to toastMetadataReset,
        "toastAutoMatchedTpl" to toastAutoMatchedTpl,
        "autoMatchSuccessFallback" to autoMatchSuccessFallback,
        "toastNoHighConfidenceCandidate" to toastNoHighConfidenceCandidate,
        "alertNoOnlineCandidates" to alertNoOnlineCandidates,
        "pickCandidateTitle" to pickCandidateTitle,
        "candidateSourceLineTpl" to candidateSourceLineTpl,
        "toastAppliedOnlineTags" to toastAppliedOnlineTags,
        "alertFilteredListEmpty" to alertFilteredListEmpty,
        "toastBatchSearchingTpl" to toastBatchSearchingTpl,
        "toastBatchAutoCompleteDoneTpl" to toastBatchAutoCompleteDoneTpl,
        "playlistPaneEmptyNone" to playlistPaneEmptyNone,
        "playlistsEmptyFiltered" to playlistsEmptyFiltered,
        "playlistSidebarMetaTpl" to playlistSidebarMetaTpl,
        "markBtnShort" to markBtnShort,
        "playlistTracksEmpty" to playlistTracksEmpty,
        "playlistTrackCountHintTpl" to playlistTrackCountHintTpl,
        "playlistPracticeToggleLabel" to playlistPracticeToggleLabel,
        "addTracksBtn" to addTracksBtn,
        "exportM3uBtn" to exportM3uBtn,
        "colOrder" to colOrder,
        "colTrackVideo" to colTrackVideo,
        "colPracticeTags" to colPracticeTags,
        "colDuration" to colDuration,
        "colReorderActions" to colReorderActions,
        "createPlaylistTitle" to createPlaylistTitle,
        "playlistNameLabel" to playlistNameLabel,
        "playlistNamePlaceholder" to playlistNamePlaceholder,
        "newPlaylistPracticeCheckbox" to newPlaylistPracticeCheckbox,
        "alertEnterPlaylistName" to alertEnterPlaylistName,
        "toastPlaylistCreatedTpl" to toastPlaylistCreatedTpl,
        "promptRenamePlaylist" to promptRenamePlaylist,
        "toastSetPracticePlaylist" to toastSetPracticePlaylist,
        "toastSetNormalPlaylist" to toastSetNormalPlaylist,
        "confirmDeletePlaylist" to confirmDeletePlaylist,
        "toastPlaylistDeleted" to toastPlaylistDeleted,
        "addTracksModalTitle" to addTracksModalTitle,
        "allTracksAlreadyInPlaylist" to allTracksAlreadyInPlaylist,
        "addSelectedBtn" to addSelectedBtn,
        "toastAddedTracksTpl" to toastAddedTracksTpl,
        "alertCreatePlaylistFirst" to alertCreatePlaylistFirst,
        "batchAddPlaylistTitleTpl" to batchAddPlaylistTitleTpl,
        "practiceTagPrefix" to practiceTagPrefix,
        "addToListConfirmBtn" to addToListConfirmBtn,
        "toastAddedToPlaylist" to toastAddedToPlaylist,
        "importedPlaylistFallback" to importedPlaylistFallback,
        "toastImportedPlaylistTpl" to toastImportedPlaylistTpl,
        "abRangeUnset" to abRangeUnset,
        "abPointASetTpl" to abPointASetTpl,
        "loopOn" to loopOn,
        "loopPaused" to loopPaused,
        "studioBookmarkShortBadgeTpl" to studioBookmarkShortBadgeTpl,
        "toastSetAbFirst" to toastSetAbFirst,
        "bookmarksEmptyHint" to bookmarksEmptyHint,
        "alertSetAbPointsFirst" to alertSetAbPointsFirst,
        "defaultSectionNumberTpl" to defaultSectionNumberTpl,
        "toastBookmarkSavedTpl" to toastBookmarkSavedTpl,
        "toastBookmarkDeleted" to toastBookmarkDeleted,
        "toastPracticeStateSaved" to toastPracticeStateSaved,
    )

    companion object {
        fun forLanguage(language: ResolvedLanguage): LanWebStrings = when (language) {
            ResolvedLanguage.ZH -> ZH
            ResolvedLanguage.EN -> EN
            ResolvedLanguage.JA -> JA
        }

        val ZH: LanWebStrings = object : LanWebStrings {
            override val pageTitle = "MiniPlayer · PC 局域网 Web 管理台"
            override val connectingLibrary = "正在连接手机曲库…"
            override val scanning = "扫描中…"
            override val scanningCountTpl = "扫描中 ({0})…"
            override val statSummaryTpl = "曲库 {0} 首 (视频 {1}) · 列表 {2} 个 · 书签曲目 {3}"
            override val tabFolders = "无线传文件与目录"
            override val tabMetadata = "曲库表格与联网补全"
            override val tabPlaylists = "播放列表与练习列表"
            override val tabStudio = "PC 预听与 A-B 打点器"
            override val rescanLibraryBtn = "刷新曲库"
            override val rescanLibraryTooltip = "增量扫描所有已添加文件夹"
            override val themeBtn = "主题"
            override val themeTooltip = "切换深色/浅色主题"

            override val pinRequiredError = "需要输入访问 PIN 码"
            override val requestFailedTpl = "请求失败 ({0})"
            override val pinModalTitle = "输入局域网访问 PIN 码"
            override val pinModalHint = "请在手机端「PC 局域网 Web 管理台」面板查看 4 位 PIN 码："
            override val pinModalPlaceholder = "例如 1234"
            override val pinSubmitBtn = "验证并进入"
            override val pinUnlockedToast = "已解锁局域网管理台"
            override val pinWrongError = "PIN 码错误"
            override val cancel = "取消"
            override val close = "关闭"
            override val saveChanges = "保存更改"
            override val delete = "删除"
            override val edit = "编辑"
            override val create = "创建"
            override val rename = "重命名"
            override val remove = "移除"
            override val unknownAlbum = "未知专辑"
            override val unknownArtist = "未知歌手"
            override val variousArtists = "群星"
            override val video = "视频"
            override val audio = "音频"
            override val videoTagPrefix = "[视频] "
            override val mountMounted = "已挂载"
            override val mountOffline = "离线"
            override val mountPermissionRevoked = "权限已失效"

            override val mountedFoldersTitle = "已挂载文件夹"
            override val safTip = "提示：出于 Android SAF 隐私安全规范，新增顶层文件夹请在手机端点击右下角「添加文件夹」按钮授权；授权后即可在此无线上传文件、建子目录或标记练习目录。"
            override val practiceFolderToggleLabel = "设为练习文件夹（手机点播自动开练习模式，且不混入专辑/歌手/视频）"
            override val newSubfolderBtn = "+ 新建子文件夹"
            override val uploadMediaBtn = "上传音视频 / 歌词"
            override val dropzoneTitle = "拖拽 .mp3 / .flac / .wav / .m4a / .mp4 / .mkv / .lrc 文件到此处无线直传手机当前目录"
            override val dropzoneSubtitle = "支持多文件连续流式上传，上传完成后自动触发增量扫描入库"
            override val uploadPreparing = "准备上传…"
            override val colName = "名称"
            override val colTypeSpec = "类型 / 规格"
            override val colArtistAlbum = "歌手 / 专辑"
            override val colPracticeTags = "练习标记"
            override val colActions = "操作"
            override val noFolderAddedCrumbs = "尚未在手机上添加本地文件夹"
            override val noFolderAddedEmpty = "请先在手机端 MiniPlayer 点击右下角「添加文件夹」按钮挂载音乐或练习伴奏目录"
            override val noMountedFolders = "暂无已挂载目录"
            override val badgeReadOnly = "只读"
            override val badgePracticeShort = "练习"
            override val itemsCountTpl = "{0} 项"
            override val rootFolderFallback = "根目录"
            override val badgePracticeDir = "练习目录"
            override val badgeNormalDir = "普通目录"
            override val subfolderCountTpl = "子文件夹 · {0} 项"
            override val unsetPracticeDirBtn = "取消练习标记"
            override val setPracticeDirBtn = "设为练习目录"
            override val bookmarksCountBadgeTpl = "书签 {0} 段"
            override val previewMarkBtn = "预听/打点"
            override val tagsBtn = "标签"
            override val folderEmptyHint = "此目录为空，可直接拖拽音视频文件到上方区域无线上传"
            override val alertAddFolderFirst = "请先在手机上添加文件夹"
            override val promptNewSubfolderName = "新建子文件夹名称（如 Backing 或 J-Rock）："
            override val toastFolderCreatedTpl = "已创建文件夹: {0}"
            override val toastMarkedPracticeFolder = "已标记为练习文件夹"
            override val toastUnmarkedPracticeFolder = "已切换为普通文件夹"
            override val toastSubfolderMarkedPractice = "子目录已设为练习目录"
            override val toastSubfolderUnmarkedPractice = "已取消练习目录标记"
            override val uploadFailedStatusTpl = "上传失败 ({0})"
            override val uploadNetworkError = "网络中断，上传失败"
            override val alertMountWritableFirst = "请先在手机端挂载至少一个可写文件夹"
            override val uploadingProgressTpl = "正在上传 ({0}/{1}): {2}"
            override val toastUploadedCountTpl = "已成功上传 {0} 个文件并自动入库！"
            override val uploadFailedGeneric = "上传失败"
            override val uploadUnsupportedExtTpl = "不支持上传该类型的文件：.{0}"
            override val toastRescanComplete = "已完成曲库增量扫描"

            override val filterAllLibrary = "全部曲库"
            override val filterByFolder = "按文件夹"
            override val filterByAlbum = "按专辑"
            override val filterUnknownAlbum = "未知专辑"
            override val filterByArtist = "按歌手"
            override val filterVideo = "视频"
            override val metaSearchPlaceholder = "搜索标题 / 歌手 / 专辑 / 文件名…"
            override val batchAutoCompleteListBtn = "批量联网补全当前列表"
            override val batchSelectedPrefix = "已选中 "
            override val batchSelectedSuffix = " 项（双击表格单元格可直接行内快改）"
            override val batchEditBtn = "批量修改专辑/歌手/年份"
            override val batchAutoCompleteSelectedBtn = "批量自动联网补全"
            override val batchAddToPlaylistBtn = "加入播放/练习列表"
            override val batchResetMetadataBtn = "恢复原始标签"
            override val colTrackNum = "音轨#"
            override val colTitleInline = "标题 (双击行内编辑)"
            override val colArtist = "歌手"
            override val colAlbum = "专辑"
            override val colYear = "年份"
            override val colDurationFormat = "时长/格式"
            override val subfilterAllFolders = "全部文件夹"
            override val subfilterAllAlbums = "全部专辑"
            override val subfilterAllArtists = "全部歌手"
            override val rootFallbackTpl = "目录 #{0}"
            override val metaEmptyHint = "没有符合当前筛选条件的曲目"
            override val badgeEdited = "已改"
            override val badgeEditedTooltip = "已含本地覆盖标签"
            override val badgeBacking = "伴奏"
            override val autoMatchOnlineBtn = "自动联网匹配"
            override val previewBtn = "预听"
            override val toastTagUpdated = "已更新标签"
            override val backingTitleSuffix = " (伴奏)"
            override val preserveBackingCheckboxTpl = "标题保留「{0}」后缀，避免与原唱混淆"
            override val editModalTitle = "编辑曲目元数据"
            override val editModalFileTpl = "文件: {0}"
            override val editModalAutoMatchBtn = "在线自动补全当前曲目"
            override val fieldTitle = "标题"
            override val fieldArtist = "歌手"
            override val fieldAlbum = "专辑"
            override val fieldAlbumArtist = "专辑歌手"
            override val fieldTrackYear = "音轨号 / 年份"
            override val placeholderTrackNum = "音轨号"
            override val placeholderYear = "年份"
            override val writebackCheckbox = "同时尝试写回原文件内嵌标签（默认仅写手机高速覆盖层）"
            override val extraCover = "封面"
            override val extraLyrics = "歌词"
            override val stagedHintPlain = "已填入候选 · 请点「保存更改」生效"
            override val stagedHintWithExtrasTpl = "已填入候选（含{0}） · 请点「保存更改」生效"
            override val candidatesMatchedHeaderTpl = "已匹配 {0} 个结果（点击填入表单，确认后点下方「保存更改」）："
            override val candidateLyricsTag = " · 歌词✓"
            override val candidateCoverTag = " · 封面✓"
            override val candidateFilledBadge = "已填入"
            override val candidateMetaLineTpl = "专辑: {0} · 年份: {1} · {2}"
            override val searchingOnlineProviders = "正在免 Key 检索 MusicBrainz / iTunes / LRCLIB…"
            override val noOnlineCandidatesRetry = "未找到匹配的在线候选，可修改标题/歌手关键词后重试"
            override val toastMetadataSaved = "元数据已保存"
            override val batchEditTitleTpl = "批量修改 {0} 首曲目元数据"
            override val batchEditSubtitle = "仅勾选的字段会被批量覆盖，未勾选字段保持每首曲目原值不变："
            override val batchFieldArtist = "统一修改歌手"
            override val batchPlaceholderArtist = "歌手名称"
            override val batchFieldAlbum = "统一修改专辑"
            override val batchPlaceholderAlbum = "专辑名称"
            override val batchFieldAlbumArtist = "专辑歌手"
            override val batchPlaceholderAlbumArtist = "专辑歌手"
            override val batchFieldYear = "发行年份"
            override val batchPlaceholderYear = "如 2024"
            override val batchApplyBtn = "批量应用"
            override val alertSelectAtLeastOneField = "请至少勾选一个要批量修改的字段"
            override val toastBatchUpdatedTpl = "已批量更新 {0} 首曲目"
            override val confirmResetMetadataTpl = "确认恢复所选 {0} 首曲目的原始标签？"
            override val toastMetadataReset = "已恢复原始标签"
            override val toastAutoMatchedTpl = "已自动匹配: {0}"
            override val autoMatchSuccessFallback = "成功"
            override val toastNoHighConfidenceCandidate = "未找到高置信度在线候选，请打开编辑手动搜索"
            override val alertNoOnlineCandidates = "未检索到匹配的在线候选结果"
            override val pickCandidateTitle = "选择在线匹配候选"
            override val candidateSourceLineTpl = "专辑: {0} · 年份: {1} · 来源: {2}"
            override val toastAppliedOnlineTags = "已应用所选在线标签"
            override val alertFilteredListEmpty = "当前筛选列表为空"
            override val toastBatchSearchingTpl = "正在批量联网检索 {0} 首曲目…"
            override val toastBatchAutoCompleteDoneTpl = "批量联网补全完成：成功更新 {0} 首曲目"

            override val playlistsTitle = "播放列表"
            override val newPlaylistBtn = "+ 新建"
            override val importM3uBtn = "导入 .m3u8"
            override val filterAll = "全部"
            override val filterPracticeLists = "练习列表"
            override val playlistPaneEmptyInitial = "请选择左侧播放列表，或点击「+ 新建」创建练习列表"
            override val playlistPaneEmptyNone = "暂无播放列表，点击左上角「+ 新建」创建普通列表或练习列表"
            override val playlistsEmptyFiltered = "无符合条件的播放列表"
            override val playlistSidebarMetaTpl = "{0} 首 · 书签 {1} 段"
            override val markBtnShort = "打点"
            override val playlistTracksEmpty = "列表暂无曲目，点击右上角「+ 添加曲目」从曲库选歌或教学视频混排"
            override val playlistTrackCountHintTpl = "{0} 个音视频条目（支持鼠标拖拽行排序）"
            override val playlistPracticeToggleLabel = "练习列表（手机播放自动开启练习模式）"
            override val addTracksBtn = "+ 添加曲目"
            override val exportM3uBtn = "导出 .m3u8"
            override val colOrder = "序"
            override val colTrackVideo = "曲目 / 视频"
            override val colDuration = "时长"
            override val colReorderActions = "排序 / 操作"
            override val createPlaylistTitle = "+ 新建播放列表"
            override val playlistNameLabel = "列表名称"
            override val playlistNamePlaceholder = "例如：吉他独奏每日必练"
            override val newPlaylistPracticeCheckbox = "设为练习列表（在手机端点击曲目自动开启练习模式）"
            override val alertEnterPlaylistName = "请输入播放列表名称"
            override val toastPlaylistCreatedTpl = "已创建列表: {0}"
            override val promptRenamePlaylist = "重命名播放列表："
            override val toastSetPracticePlaylist = "已设为练习列表"
            override val toastSetNormalPlaylist = "已切换为普通播放列表"
            override val confirmDeletePlaylist = "确认删除该播放列表？（不会删除手机中的原音视频文件）"
            override val toastPlaylistDeleted = "已删除播放列表"
            override val addTracksModalTitle = "添加曲目/视频到当前列表"
            override val allTracksAlreadyInPlaylist = "所有曲库曲目均已在列表中"
            override val addSelectedBtn = "添加所选"
            override val toastAddedTracksTpl = "已添加 {0} 项到列表"
            override val alertCreatePlaylistFirst = "请先在「播放列表」页新建一个播放列表"
            override val batchAddPlaylistTitleTpl = "将 {0} 首曲目加入播放/练习列表"
            override val practiceTagPrefix = "[练习] "
            override val addToListConfirmBtn = "加入列表"
            override val toastAddedToPlaylist = "已加入列表"
            override val importedPlaylistFallback = "导入列表"
            override val toastImportedPlaylistTpl = "成功导入「{0}」: 匹配 {1} 首（未找到 {2} 首）"

            override val selectTrackVideoTitle = "选择曲目 / 视频"
            override val studioSearchPlaceholder = "筛选伴奏或教学视频…"
            override val studioEmptyHint = "请从左侧选择一首伴奏音频或教学视频开始 PC 预听与 A-B 段落书签打点"
            override val videoMirrorLabel = "视频水平镜像"
            override val shortcutsLabel = "快捷键:"
            override val shortcutPlayPause = "播/停"
            override val shortcutSetA = "设A"
            override val shortcutSetB = "设B"
            override val shortcutLoopToggle = "循环开关"
            override val shortcutBackToA = "回A"
            override val audioPreviewBanner = "音频预听"
            override val curTimeLabel = "当前:"
            override val abRangeUnset = "A-B 区间: 未设置"
            override val abPointASetTpl = "已设 A 点: {0}（请按 ] 设 B 点）"
            override val durTimeLabel = "总长:"
            override val loopOff = "循环: 关"
            override val loopOn = "循环: 开启"
            override val loopPaused = "循环: 挂起"
            override val jumpToABtn = "回 A"
            override val clearAbBtn = "清除 A-B"
            override val bookmarksCardTitle = "段落书签（直存手机数据库）"
            override val bookmarkInputPlaceholder = "书签名称，如 独奏 1"
            override val bookmarkSaveSpeedLabel = "关联当前倍速"
            override val saveBookmarkBtn = "+ 保存段落书签"
            override val practiceStateCardTitle = "手机默认练习状态配置"
            override val speedSliderLabelPrefix = "变速不变调倍速 ("
            override val speedSliderLabelSuffix = " — 浏览器实时同步预听)"
            override val speedResetBtn = "1.00x 复位"
            override val pitchSemitonesLabel = "手机升降调半音 (-6 ~ +6 st)"
            override val bpmLabel = "曲速 BPM (30 ~ 300)"
            override val tapTempoBtn = "点按测速"
            override val countInLabel = "循环前预备拍"
            override val countInOffOption = "关闭（无缝连续循环）"
            override val countInBeat4Option = "4 拍木鱼节拍倒计时（随 BPM×倍速自动缩放）"
            override val savePracticeStateBtn = "保存练习参数到手机"
            override val studioBookmarkShortBadgeTpl = "书签 {0}"
            override val toastSetAbFirst = "请先用 [ 和 ] 设置 A 点与 B 点"
            override val bookmarksEmptyHint = "暂无段落书签。播放时按 [ 设起点、按 ] 设终点，选择标签点击「+ 保存段落书签」即可同步到手机！"
            override val alertSetAbPointsFirst = "请先使用 [ 和 ] 按钮（或键盘快捷键）设置循环 A 点与 B 点！"
            override val defaultSectionNumberTpl = "段落 {0}"
            override val toastBookmarkSavedTpl = "已保存段落书签: {0}"
            override val toastBookmarkDeleted = "已删除书签"
            override val toastPracticeStateSaved = "练习参数已同步到手机数据库"
        }

        val EN: LanWebStrings = object : LanWebStrings {
            override val pageTitle = "MiniPlayer · PC LAN Web Studio"
            override val connectingLibrary = "Connecting to phone library…"
            override val scanning = "Scanning…"
            override val scanningCountTpl = "Scanning ({0})…"
            override val statSummaryTpl = "Library: {0} tracks ({1} videos) · {2} playlists · {3} bookmarked"
            override val tabFolders = "Wireless Transfer & Folders"
            override val tabMetadata = "Library Table & Auto-Match"
            override val tabPlaylists = "Playlists & Practice Lists"
            override val tabStudio = "PC Preview & A-B Marker"
            override val rescanLibraryBtn = "Rescan Library"
            override val rescanLibraryTooltip = "Incrementally rescan all mounted folders"
            override val themeBtn = "Theme"
            override val themeTooltip = "Toggle dark/light theme"

            override val pinRequiredError = "Access PIN required"
            override val requestFailedTpl = "Request failed ({0})"
            override val pinModalTitle = "Enter LAN Access PIN"
            override val pinModalHint = "Check the 4-digit PIN in the phone's PC LAN Web Studio panel:"
            override val pinModalPlaceholder = "e.g. 1234"
            override val pinSubmitBtn = "Verify & Enter"
            override val pinUnlockedToast = "LAN Web Studio unlocked"
            override val pinWrongError = "Incorrect PIN"
            override val cancel = "Cancel"
            override val close = "Close"
            override val saveChanges = "Save Changes"
            override val delete = "Delete"
            override val edit = "Edit"
            override val create = "Create"
            override val rename = "Rename"
            override val remove = "Remove"
            override val unknownAlbum = "Unknown Album"
            override val unknownArtist = "Unknown Artist"
            override val variousArtists = "Various Artists"
            override val video = "Video"
            override val audio = "Audio"
            override val videoTagPrefix = "[Video] "
            override val mountMounted = "Mounted"
            override val mountOffline = "Offline"
            override val mountPermissionRevoked = "Permission Revoked"

            override val mountedFoldersTitle = "Mounted Folders"
            override val safTip = "Tip: Due to Android SAF privacy rules, grant new top-level folders by tapping 'Add Folder' on your phone first. Once granted, you can upload files, create subfolders, or mark practice folders here."
            override val practiceFolderToggleLabel = "Set as practice folder (auto-enables Practice Mode on phone and excludes from Albums/Artists/Videos)"
            override val newSubfolderBtn = "+ New Subfolder"
            override val uploadMediaBtn = "Upload Media / Lyrics"
            override val dropzoneTitle = "Drag & drop .mp3 / .flac / .wav / .m4a / .mp4 / .mkv / .lrc files here to upload wirelessly to this folder"
            override val dropzoneSubtitle = "Supports multi-file streaming uploads with automatic incremental library rescan upon completion"
            override val uploadPreparing = "Preparing upload…"
            override val colName = "Name"
            override val colTypeSpec = "Type / Specs"
            override val colArtistAlbum = "Artist / Album"
            override val colPracticeTags = "Practice Tags"
            override val colActions = "Actions"
            override val noFolderAddedCrumbs = "No local folders added on phone yet"
            override val noFolderAddedEmpty = "Tap 'Add Folder' on your phone in MiniPlayer to mount a music or practice backing folder first"
            override val noMountedFolders = "No mounted folders"
            override val badgeReadOnly = "Read-Only"
            override val badgePracticeShort = "Practice"
            override val itemsCountTpl = "{0} items"
            override val rootFolderFallback = "Root"
            override val badgePracticeDir = "Practice Folder"
            override val badgeNormalDir = "Regular Folder"
            override val subfolderCountTpl = "Subfolder · {0} items"
            override val unsetPracticeDirBtn = "Unmark Practice"
            override val setPracticeDirBtn = "Mark as Practice"
            override val bookmarksCountBadgeTpl = "{0} bookmarks"
            override val previewMarkBtn = "Preview / Mark"
            override val tagsBtn = "Tags"
            override val folderEmptyHint = "This folder is empty. Drag & drop audio or video files above to upload wirelessly."
            override val alertAddFolderFirst = "Please add a folder on your phone first"
            override val promptNewSubfolderName = "New subfolder name (e.g. Backing or J-Rock):"
            override val toastFolderCreatedTpl = "Created folder: {0}"
            override val toastMarkedPracticeFolder = "Marked as practice folder"
            override val toastUnmarkedPracticeFolder = "Switched to regular folder"
            override val toastSubfolderMarkedPractice = "Subfolder marked as practice folder"
            override val toastSubfolderUnmarkedPractice = "Removed practice folder mark"
            override val uploadFailedStatusTpl = "Upload failed ({0})"
            override val uploadNetworkError = "Network interrupted; upload failed"
            override val alertMountWritableFirst = "Please mount at least one writable folder on your phone first"
            override val uploadingProgressTpl = "Uploading ({0}/{1}): {2}"
            override val toastUploadedCountTpl = "Uploaded {0} file(s) and added to library!"
            override val uploadFailedGeneric = "Upload failed"
            override val uploadUnsupportedExtTpl = "Unsupported file type for upload: .{0}"
            override val toastRescanComplete = "Incremental library rescan complete"

            override val filterAllLibrary = "All Tracks"
            override val filterByFolder = "By Folder"
            override val filterByAlbum = "By Album"
            override val filterUnknownAlbum = "Unknown Album"
            override val filterByArtist = "By Artist"
            override val filterVideo = "Videos"
            override val metaSearchPlaceholder = "Search title / artist / album / filename…"
            override val batchAutoCompleteListBtn = "Batch Auto-Complete List"
            override val batchSelectedPrefix = "Selected "
            override val batchSelectedSuffix = " item(s) (double-click any cell to edit inline)"
            override val batchEditBtn = "Batch Edit Album/Artist/Year"
            override val batchAutoCompleteSelectedBtn = "Batch Auto-Complete Selected"
            override val batchAddToPlaylistBtn = "Add to Playlist"
            override val batchResetMetadataBtn = "Reset to Original Tags"
            override val colTrackNum = "Track #"
            override val colTitleInline = "Title (double-click to edit)"
            override val colArtist = "Artist"
            override val colAlbum = "Album"
            override val colYear = "Year"
            override val colDurationFormat = "Duration / Type"
            override val subfilterAllFolders = "All Folders"
            override val subfilterAllAlbums = "All Albums"
            override val subfilterAllArtists = "All Artists"
            override val rootFallbackTpl = "Root #{0}"
            override val metaEmptyHint = "No tracks match the current filter"
            override val badgeEdited = "Edited"
            override val badgeEditedTooltip = "Has local metadata override"
            override val badgeBacking = "Backing"
            override val autoMatchOnlineBtn = "Auto-Match"
            override val previewBtn = "Preview"
            override val toastTagUpdated = "Tags updated"
            override val backingTitleSuffix = " (Backing)"
            override val preserveBackingCheckboxTpl = "Keep \"{0}\" suffix in title to avoid confusion with original"
            override val editModalTitle = "Edit Track Metadata"
            override val editModalFileTpl = "File: {0}"
            override val editModalAutoMatchBtn = "Auto-Complete This Track Online"
            override val fieldTitle = "Title"
            override val fieldArtist = "Artist"
            override val fieldAlbum = "Album"
            override val fieldAlbumArtist = "Album Artist"
            override val fieldTrackYear = "Track # / Year"
            override val placeholderTrackNum = "Track #"
            override val placeholderYear = "Year"
            override val writebackCheckbox = "Also try writing back to embedded file tags (default writes only to phone's fast override layer)"
            override val extraCover = "Cover"
            override val extraLyrics = "Lyrics"
            override val stagedHintPlain = "Candidate filled · click 'Save Changes' to apply"
            override val stagedHintWithExtrasTpl = "Candidate filled (with {0}) · click 'Save Changes' to apply"
            override val candidatesMatchedHeaderTpl = "Matched {0} result(s) (click to fill form, then click 'Save Changes' below):"
            override val candidateLyricsTag = " · Lyrics✓"
            override val candidateCoverTag = " · Cover✓"
            override val candidateFilledBadge = "Filled"
            override val candidateMetaLineTpl = "Album: {0} · Year: {1} · {2}"
            override val searchingOnlineProviders = "Searching MusicBrainz / iTunes / LRCLIB (no API key needed)…"
            override val noOnlineCandidatesRetry = "No matching online candidates found; try refining title/artist keywords"
            override val toastMetadataSaved = "Metadata saved"
            override val batchEditTitleTpl = "Batch Edit Metadata for {0} Track(s)"
            override val batchEditSubtitle = "Only checked fields will be overwritten; unchecked fields stay untouched per track:"
            override val batchFieldArtist = "Set Artist"
            override val batchPlaceholderArtist = "Artist name"
            override val batchFieldAlbum = "Set Album"
            override val batchPlaceholderAlbum = "Album name"
            override val batchFieldAlbumArtist = "Album Artist"
            override val batchPlaceholderAlbumArtist = "Album artist"
            override val batchFieldYear = "Release Year"
            override val batchPlaceholderYear = "e.g. 2024"
            override val batchApplyBtn = "Apply to All"
            override val alertSelectAtLeastOneField = "Please check at least one field to update"
            override val toastBatchUpdatedTpl = "Updated {0} track(s)"
            override val confirmResetMetadataTpl = "Reset {0} selected track(s) to their original file tags?"
            override val toastMetadataReset = "Restored original tags"
            override val toastAutoMatchedTpl = "Auto-matched: {0}"
            override val autoMatchSuccessFallback = "Success"
            override val toastNoHighConfidenceCandidate = "No high-confidence online match found; open Edit to search manually"
            override val alertNoOnlineCandidates = "No matching online candidates found"
            override val pickCandidateTitle = "Choose Online Match"
            override val candidateSourceLineTpl = "Album: {0} · Year: {1} · Source: {2}"
            override val toastAppliedOnlineTags = "Applied selected online tags"
            override val alertFilteredListEmpty = "Current filtered list is empty"
            override val toastBatchSearchingTpl = "Searching online for {0} track(s)…"
            override val toastBatchAutoCompleteDoneTpl = "Batch auto-complete finished: updated {0} track(s)"

            override val playlistsTitle = "Playlists"
            override val newPlaylistBtn = "+ New"
            override val importM3uBtn = "Import .m3u8"
            override val filterAll = "All"
            override val filterPracticeLists = "Practice Lists"
            override val playlistPaneEmptyInitial = "Select a playlist on the left, or click '+ New' to create a practice list"
            override val playlistPaneEmptyNone = "No playlists yet. Click '+ New' at top left to create a regular or practice playlist"
            override val playlistsEmptyFiltered = "No matching playlists"
            override val playlistSidebarMetaTpl = "{0} tracks · {1} bookmarks"
            override val markBtnShort = "Mark"
            override val playlistTracksEmpty = "This playlist is empty. Click '+ Add Tracks' at top right to add audio or video tracks."
            override val playlistTrackCountHintTpl = "{0} media item(s) (drag rows to reorder)"
            override val playlistPracticeToggleLabel = "Practice list (auto-enables Practice Mode on phone)"
            override val addTracksBtn = "+ Add Tracks"
            override val exportM3uBtn = "Export .m3u8"
            override val colOrder = "#"
            override val colTrackVideo = "Track / Video"
            override val colDuration = "Duration"
            override val colReorderActions = "Order / Actions"
            override val createPlaylistTitle = "+ New Playlist"
            override val playlistNameLabel = "Playlist Name"
            override val playlistNamePlaceholder = "e.g. Daily Guitar Solo Drills"
            override val newPlaylistPracticeCheckbox = "Set as practice list (auto-enables Practice Mode when played on phone)"
            override val alertEnterPlaylistName = "Please enter a playlist name"
            override val toastPlaylistCreatedTpl = "Created playlist: {0}"
            override val promptRenamePlaylist = "Rename playlist:"
            override val toastSetPracticePlaylist = "Set as practice list"
            override val toastSetNormalPlaylist = "Switched to regular playlist"
            override val confirmDeletePlaylist = "Delete this playlist? (Original audio/video files on your phone will not be deleted)"
            override val toastPlaylistDeleted = "Playlist deleted"
            override val addTracksModalTitle = "Add Tracks / Videos to Playlist"
            override val allTracksAlreadyInPlaylist = "All library tracks are already in this playlist"
            override val addSelectedBtn = "Add Selected"
            override val toastAddedTracksTpl = "Added {0} item(s) to playlist"
            override val alertCreatePlaylistFirst = "Please create a playlist in the Playlists tab first"
            override val batchAddPlaylistTitleTpl = "Add {0} Track(s) to Playlist"
            override val practiceTagPrefix = "[Practice] "
            override val addToListConfirmBtn = "Add to Playlist"
            override val toastAddedToPlaylist = "Added to playlist"
            override val importedPlaylistFallback = "Imported Playlist"
            override val toastImportedPlaylistTpl = "Imported \"{0}\": matched {1} track(s) ({2} unmatched)"

            override val selectTrackVideoTitle = "Select Track / Video"
            override val studioSearchPlaceholder = "Filter backing tracks or tutorial videos…"
            override val studioEmptyHint = "Select an audio track or tutorial video on the left to start PC preview and A-B section marking"
            override val videoMirrorLabel = "Mirror Video Horizontally"
            override val shortcutsLabel = "Shortcuts:"
            override val shortcutPlayPause = "Play/Pause"
            override val shortcutSetA = "Set A"
            override val shortcutSetB = "Set B"
            override val shortcutLoopToggle = "Loop"
            override val shortcutBackToA = "Jump A"
            override val audioPreviewBanner = "AUDIO PREVIEW"
            override val curTimeLabel = "Current:"
            override val abRangeUnset = "A-B Range: Not Set"
            override val abPointASetTpl = "Point A set: {0} (press ] to set B)"
            override val durTimeLabel = "Duration:"
            override val loopOff = "Loop: Off"
            override val loopOn = "Loop: On"
            override val loopPaused = "Loop: Paused"
            override val jumpToABtn = "Jump to A"
            override val clearAbBtn = "Clear A-B"
            override val bookmarksCardTitle = "Section Bookmarks (Saved Directly to Phone)"
            override val bookmarkInputPlaceholder = "Bookmark name, e.g. Solo 1"
            override val bookmarkSaveSpeedLabel = "Link current speed"
            override val saveBookmarkBtn = "+ Save Bookmark"
            override val practiceStateCardTitle = "Phone Default Practice Settings"
            override val speedSliderLabelPrefix = "Time-stretch speed ("
            override val speedSliderLabelSuffix = " — live browser preview)"
            override val speedResetBtn = "1.00x Reset"
            override val pitchSemitonesLabel = "Phone pitch shift (-6 to +6 st)"
            override val bpmLabel = "Tempo BPM (30–300)"
            override val tapTempoBtn = "Tap Tempo"
            override val countInLabel = "Loop Count-In"
            override val countInOffOption = "Off (seamless loop)"
            override val countInBeat4Option = "4-beat woodblock countdown (scales with BPM × speed)"
            override val savePracticeStateBtn = "Save Practice Settings to Phone"
            override val studioBookmarkShortBadgeTpl = "Marks {0}"
            override val toastSetAbFirst = "Please set Point A and Point B with [ and ] first"
            override val bookmarksEmptyHint = "No bookmarks yet. Press [ for start and ] for end during playback, pick a label, and click '+ Save Bookmark' to sync to your phone."
            override val alertSetAbPointsFirst = "Please set loop points A and B using the [ and ] buttons (or keyboard shortcuts) first!"
            override val defaultSectionNumberTpl = "Section {0}"
            override val toastBookmarkSavedTpl = "Saved bookmark: {0}"
            override val toastBookmarkDeleted = "Bookmark deleted"
            override val toastPracticeStateSaved = "Practice settings synced to phone database"
        }

        val JA: LanWebStrings = object : LanWebStrings {
            override val pageTitle = "MiniPlayer · PC LAN Web スタジオ"
            override val connectingLibrary = "スマホのライブラリに接続中…"
            override val scanning = "スキャン中…"
            override val scanningCountTpl = "スキャン中 ({0})…"
            override val statSummaryTpl = "ライブラリ {0} 曲（動画 {1}）· リスト {2} 件 · ブックマーク {3} 曲"
            override val tabFolders = "ワイヤレス転送・フォルダ"
            override val tabMetadata = "ライブラリ一覧・自動補完"
            override val tabPlaylists = "プレイリスト・練習リスト"
            override val tabStudio = "PC 試聴・A-B マーカー"
            override val rescanLibraryBtn = "ライブラリ更新"
            override val rescanLibraryTooltip = "追加済みフォルダを差分スキャン"
            override val themeBtn = "テーマ"
            override val themeTooltip = "ダーク／ライトテーマを切り替え"

            override val pinRequiredError = "アクセス PIN が必要です"
            override val requestFailedTpl = "リクエスト失敗 ({0})"
            override val pinModalTitle = "LAN アクセス PIN を入力"
            override val pinModalHint = "スマホの「PC LAN Web スタジオ」パネルで 4 桁の PIN を確認してください："
            override val pinModalPlaceholder = "例: 1234"
            override val pinSubmitBtn = "確認して入る"
            override val pinUnlockedToast = "LAN Web スタジオのロックを解除しました"
            override val pinWrongError = "PIN が正しくありません"
            override val cancel = "キャンセル"
            override val close = "閉じる"
            override val saveChanges = "変更を保存"
            override val delete = "削除"
            override val edit = "編集"
            override val create = "作成"
            override val rename = "名前を変更"
            override val remove = "削除"
            override val unknownAlbum = "不明なアルバム"
            override val unknownArtist = "不明なアーティスト"
            override val variousArtists = "ヴァリアス・アーティスト"
            override val video = "動画"
            override val audio = "オーディオ"
            override val videoTagPrefix = "[動画] "
            override val mountMounted = "マウント済み"
            override val mountOffline = "オフライン"
            override val mountPermissionRevoked = "権限が無効"

            override val mountedFoldersTitle = "マウント済みフォルダ"
            override val safTip = "ヒント：Android SAF のセキュリティ仕様上、新しいトップレベルフォルダはスマホ右下の「フォルダを追加」から許可してください。許可後はここでワイヤレス転送やサブフォルダ作成、練習フォルダ指定ができます。"
            override val practiceFolderToggleLabel = "練習フォルダに設定（スマホ再生時に練習モードを自動起動し、アルバム／アーティスト／動画に混在させない）"
            override val newSubfolderBtn = "+ サブフォルダ作成"
            override val uploadMediaBtn = "メディア／歌詞をアップロード"
            override val dropzoneTitle = ".mp3 / .flac / .wav / .m4a / .mp4 / .mkv / .lrc ファイルをここにドラッグ＆ドロップしてスマホの現在フォルダへ直接転送"
            override val dropzoneSubtitle = "複数ファイルの連続ストリーミング転送に対応し、完了後に自動で差分スキャンを実行します"
            override val uploadPreparing = "アップロード準備中…"
            override val colName = "名前"
            override val colTypeSpec = "種類／仕様"
            override val colArtistAlbum = "アーティスト／アルバム"
            override val colPracticeTags = "練習タグ"
            override val colActions = "操作"
            override val noFolderAddedCrumbs = "スマホにローカルフォルダがまだ追加されていません"
            override val noFolderAddedEmpty = "先にスマホ版 MiniPlayer の右下「フォルダを追加」から音楽または練習用フォルダをマウントしてください"
            override val noMountedFolders = "マウント済みフォルダはありません"
            override val badgeReadOnly = "読み取り専用"
            override val badgePracticeShort = "練習"
            override val itemsCountTpl = "{0} 件"
            override val rootFolderFallback = "ルート"
            override val badgePracticeDir = "練習フォルダ"
            override val badgeNormalDir = "通常フォルダ"
            override val subfolderCountTpl = "サブフォルダ · {0} 件"
            override val unsetPracticeDirBtn = "練習指定を解除"
            override val setPracticeDirBtn = "練習フォルダにする"
            override val bookmarksCountBadgeTpl = "ブックマーク {0} 件"
            override val previewMarkBtn = "試聴／打点"
            override val tagsBtn = "タグ"
            override val folderEmptyHint = "このフォルダは空です。上のエリアへ音声・動画ファイルをドラッグして転送できます。"
            override val alertAddFolderFirst = "先にスマホでフォルダを追加してください"
            override val promptNewSubfolderName = "新しいサブフォルダ名（例: Backing や J-Rock）："
            override val toastFolderCreatedTpl = "フォルダを作成しました: {0}"
            override val toastMarkedPracticeFolder = "練習フォルダに設定しました"
            override val toastUnmarkedPracticeFolder = "通常フォルダに切り替えました"
            override val toastSubfolderMarkedPractice = "サブフォルダを練習フォルダに設定しました"
            override val toastSubfolderUnmarkedPractice = "練習フォルダ設定を解除しました"
            override val uploadFailedStatusTpl = "アップロード失敗 ({0})"
            override val uploadNetworkError = "ネットワークが切断され、アップロードに失敗しました"
            override val alertMountWritableFirst = "先にスマホで書き込み可能なフォルダを 1 つ以上マウントしてください"
            override val uploadingProgressTpl = "アップロード中 ({0}/{1}): {2}"
            override val toastUploadedCountTpl = "{0} 件のファイルをアップロードしてライブラリに追加しました！"
            override val uploadFailedGeneric = "アップロードに失敗しました"
            override val uploadUnsupportedExtTpl = "この種類のファイルはアップロードできません：.{0}"
            override val toastRescanComplete = "ライブラリの差分スキャンが完了しました"

            override val filterAllLibrary = "全ライブラリ"
            override val filterByFolder = "フォルダ別"
            override val filterByAlbum = "アルバム別"
            override val filterUnknownAlbum = "不明なアルバム"
            override val filterByArtist = "アーティスト別"
            override val filterVideo = "動画"
            override val metaSearchPlaceholder = "タイトル／アーティスト／アルバム／ファイル名で検索…"
            override val batchAutoCompleteListBtn = "現在のリストを一括補完"
            override val batchSelectedPrefix = "選択中 "
            override val batchSelectedSuffix = " 件（セルをダブルクリックで直接編集可能）"
            override val batchEditBtn = "アルバム／アーティスト／年を一括編集"
            override val batchAutoCompleteSelectedBtn = "選択項目を一括自動補完"
            override val batchAddToPlaylistBtn = "プレイリストに追加"
            override val batchResetMetadataBtn = "元のタグに戻す"
            override val colTrackNum = "トラック#"
            override val colTitleInline = "タイトル（ダブルクリックで編集）"
            override val colArtist = "アーティスト"
            override val colAlbum = "アルバム"
            override val colYear = "年"
            override val colDurationFormat = "時間／形式"
            override val subfilterAllFolders = "すべてのフォルダ"
            override val subfilterAllAlbums = "すべてのアルバム"
            override val subfilterAllArtists = "すべてのアーティスト"
            override val rootFallbackTpl = "ルート #{0}"
            override val metaEmptyHint = "現在の条件に一致する曲がありません"
            override val badgeEdited = "編集済"
            override val badgeEditedTooltip = "ローカル上書きタグあり"
            override val badgeBacking = "伴奏"
            override val autoMatchOnlineBtn = "オンライン一致"
            override val previewBtn = "試聴"
            override val toastTagUpdated = "タグを更新しました"
            override val backingTitleSuffix = " (伴奏)"
            override val preserveBackingCheckboxTpl = "原曲との混同を避けるためタイトルに「{0}」を保持"
            override val editModalTitle = "曲メタデータを編集"
            override val editModalFileTpl = "ファイル: {0}"
            override val editModalAutoMatchBtn = "この曲をオンライン自動補完"
            override val fieldTitle = "タイトル"
            override val fieldArtist = "アーティスト"
            override val fieldAlbum = "アルバム"
            override val fieldAlbumArtist = "アルバムアーティスト"
            override val fieldTrackYear = "トラック番号／年"
            override val placeholderTrackNum = "トラック#"
            override val placeholderYear = "年"
            override val writebackCheckbox = "元ファイルの埋め込みタグにも書き戻しを試行（既定はスマホ高速オーバーレイのみ）"
            override val extraCover = "カバー"
            override val extraLyrics = "歌詞"
            override val stagedHintPlain = "候補を入力済み · 「変更を保存」で反映"
            override val stagedHintWithExtrasTpl = "候補を入力済み（{0}あり）· 「変更を保存」で反映"
            override val candidatesMatchedHeaderTpl = "{0} 件の一致結果（クリックでフォームに入力し、下の「変更を保存」で反映）："
            override val candidateLyricsTag = " · 歌詞✓"
            override val candidateCoverTag = " · カバー✓"
            override val candidateFilledBadge = "入力済"
            override val candidateMetaLineTpl = "アルバム: {0} · 年: {1} · {2}"
            override val searchingOnlineProviders = "MusicBrainz / iTunes / LRCLIB を検索中（APIキー不要）…"
            override val noOnlineCandidatesRetry = "一致するオンライン候補が見つかりません。タイトルやアーティストを調整して再試行してください"
            override val toastMetadataSaved = "メタデータを保存しました"
            override val batchEditTitleTpl = "{0} 曲のメタデータを一括編集"
            override val batchEditSubtitle = "チェックを入れた項目のみ一括上書きされ、未チェック項目は各曲の元の値を維持します："
            override val batchFieldArtist = "アーティストを一括変更"
            override val batchPlaceholderArtist = "アーティスト名"
            override val batchFieldAlbum = "アルバムを一括変更"
            override val batchPlaceholderAlbum = "アルバム名"
            override val batchFieldAlbumArtist = "アルバムアーティスト"
            override val batchPlaceholderAlbumArtist = "アルバムアーティスト"
            override val batchFieldYear = "リリース年"
            override val batchPlaceholderYear = "例: 2024"
            override val batchApplyBtn = "一括適用"
            override val alertSelectAtLeastOneField = "一括変更する項目を 1 つ以上選択してください"
            override val toastBatchUpdatedTpl = "{0} 曲を一括更新しました"
            override val confirmResetMetadataTpl = "選択した {0} 曲を元のファイルタグに戻しますか？"
            override val toastMetadataReset = "元のタグに戻しました"
            override val toastAutoMatchedTpl = "自動一致しました: {0}"
            override val autoMatchSuccessFallback = "成功"
            override val toastNoHighConfidenceCandidate = "高信頼度のオンライン候補が見つかりません。編集画面から手動検索してください"
            override val alertNoOnlineCandidates = "一致するオンライン候補が見つかりませんでした"
            override val pickCandidateTitle = "オンライン一致候補を選択"
            override val candidateSourceLineTpl = "アルバム: {0} · 年: {1} · ソース: {2}"
            override val toastAppliedOnlineTags = "選択したオンラインタグを適用しました"
            override val alertFilteredListEmpty = "現在のフィルター結果は空です"
            override val toastBatchSearchingTpl = "{0} 曲をオンラインで一括検索中…"
            override val toastBatchAutoCompleteDoneTpl = "一括補完完了：{0} 曲を更新しました"

            override val playlistsTitle = "プレイリスト"
            override val newPlaylistBtn = "+ 新規"
            override val importM3uBtn = ".m3u8 をインポート"
            override val filterAll = "すべて"
            override val filterPracticeLists = "練習リスト"
            override val playlistPaneEmptyInitial = "左側のプレイリストを選択するか、「+ 新規」で練習リストを作成してください"
            override val playlistPaneEmptyNone = "プレイリストがありません。左上の「+ 新規」から通常または練習リストを作成してください"
            override val playlistsEmptyFiltered = "条件に一致するプレイリストはありません"
            override val playlistSidebarMetaTpl = "{0} 曲 · ブックマーク {1} 件"
            override val markBtnShort = "打点"
            override val playlistTracksEmpty = "曲がありません。右上の「+ 曲を追加」から音楽や練習動画を追加してください。"
            override val playlistTrackCountHintTpl = "{0} 件のメディア（行をドラッグして並べ替え可能）"
            override val playlistPracticeToggleLabel = "練習リスト（スマホ再生時に練習モードを自動起動）"
            override val addTracksBtn = "+ 曲を追加"
            override val exportM3uBtn = ".m3u8 をエクスポート"
            override val colOrder = "順"
            override val colTrackVideo = "曲／動画"
            override val colDuration = "時間"
            override val colReorderActions = "並替／操作"
            override val createPlaylistTitle = "+ 新規プレイリスト"
            override val playlistNameLabel = "リスト名"
            override val playlistNamePlaceholder = "例：ギターソロ毎日練習"
            override val newPlaylistPracticeCheckbox = "練習リストに設定（スマホで再生すると練習モードを自動起動）"
            override val alertEnterPlaylistName = "プレイリスト名を入力してください"
            override val toastPlaylistCreatedTpl = "プレイリストを作成しました: {0}"
            override val promptRenamePlaylist = "プレイリスト名を変更："
            override val toastSetPracticePlaylist = "練習リストに設定しました"
            override val toastSetNormalPlaylist = "通常プレイリストに切り替えました"
            override val confirmDeletePlaylist = "このプレイリストを削除しますか？（スマホ内の元ファイルは削除されません）"
            override val toastPlaylistDeleted = "プレイリストを削除しました"
            override val addTracksModalTitle = "現在のリストに曲／動画を追加"
            override val allTracksAlreadyInPlaylist = "ライブラリのすべての曲が既にリストに入っています"
            override val addSelectedBtn = "選択を追加"
            override val toastAddedTracksTpl = "リストに {0} 件追加しました"
            override val alertCreatePlaylistFirst = "先に「プレイリスト」タブでリストを作成してください"
            override val batchAddPlaylistTitleTpl = "{0} 曲をプレイリストに追加"
            override val practiceTagPrefix = "[練習] "
            override val addToListConfirmBtn = "リストに追加"
            override val toastAddedToPlaylist = "リストに追加しました"
            override val importedPlaylistFallback = "インポートリスト"
            override val toastImportedPlaylistTpl = "「{0}」をインポートしました：一致 {1} 曲（未一致 {2} 曲）"

            override val selectTrackVideoTitle = "曲／動画を選択"
            override val studioSearchPlaceholder = "伴奏やレッスン動画を絞り込み…"
            override val studioEmptyHint = "左側から伴奏音声またはレッスン動画を選択して、PC 試聴と A-B 区間ブックマークを開始します"
            override val videoMirrorLabel = "動画を左右反転"
            override val shortcutsLabel = "ショートカット:"
            override val shortcutPlayPause = "再生/停止"
            override val shortcutSetA = "A設定"
            override val shortcutSetB = "B設定"
            override val shortcutLoopToggle = "ループ切替"
            override val shortcutBackToA = "Aへ戻る"
            override val audioPreviewBanner = "オーディオ試聴"
            override val curTimeLabel = "現在:"
            override val abRangeUnset = "A-B 区間: 未設定"
            override val abPointASetTpl = "A 点を設定: {0}（] キーで B 点を設定）"
            override val durTimeLabel = "長さ:"
            override val loopOff = "ループ: オフ"
            override val loopOn = "ループ: オン"
            override val loopPaused = "ループ: 待機"
            override val jumpToABtn = "A へ戻る"
            override val clearAbBtn = "A-B クリア"
            override val bookmarksCardTitle = "区間ブックマーク（スマホ DB へ直接保存）"
            override val bookmarkInputPlaceholder = "ブックマーク名（例: ソロ 1）"
            override val bookmarkSaveSpeedLabel = "現在の速度を保存"
            override val saveBookmarkBtn = "+ ブックマーク保存"
            override val practiceStateCardTitle = "スマホ既定の練習設定"
            override val speedSliderLabelPrefix = "音程維持スピード ("
            override val speedSliderLabelSuffix = " — ブラウザでリアルタイム試聴)"
            override val speedResetBtn = "1.00x リセット"
            override val pitchSemitonesLabel = "スマホの音程シフト (-6〜+6 st)"
            override val bpmLabel = "テンポ BPM (30〜300)"
            override val tapTempoBtn = "タップ測定"
            override val countInLabel = "ループ前カウントイン"
            override val countInOffOption = "オフ（シームレスループ）"
            override val countInBeat4Option = "4 拍ウッドブロックカウント（BPM×速度に自動連動）"
            override val savePracticeStateBtn = "練習設定をスマホへ保存"
            override val studioBookmarkShortBadgeTpl = "ブックマーク {0}"
            override val toastSetAbFirst = "先に [ と ] で A 点と B 点を設定してください"
            override val bookmarksEmptyHint = "ブックマークはまだありません。再生中に [ で開始点、] で終了点を設定し、ラベルを選んで「+ ブックマーク保存」を押すとスマホに同期されます。"
            override val alertSetAbPointsFirst = "先に [ と ] ボタン（またはショートカット）でループの A 点と B 点を設定してください！"
            override val defaultSectionNumberTpl = "区間 {0}"
            override val toastBookmarkSavedTpl = "ブックマークを保存しました: {0}"
            override val toastBookmarkDeleted = "ブックマークを削除しました"
            override val toastPracticeStateSaved = "練習設定をスマホのデータベースへ同期しました"
        }
    }
}
