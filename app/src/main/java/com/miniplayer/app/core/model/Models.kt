package com.miniplayer.app.core.model

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class MountState {
    MOUNTED,
    OFFLINE_UNMOUNTED,
    PERMISSION_REVOKED,
}

data class SafRoot(
    val rootId: Long,
    val treeUri: String,
    val treeDocId: String,
    val displayName: String,
    val isWritable: Boolean,
    val mountState: MountState = MountState.MOUNTED,
    val itemCount: Int = 0,
    val isPracticeFolder: Boolean = false,
    val respectNomedia: Boolean? = null,
    val lastScanAt: Long = 0L,
)

data class FolderRule(
    val folderUri: String,
    val rootId: Long,
    val relPath: String,
    val isPracticeFolder: Boolean,
    val respectNomedia: Boolean? = null,
)

data class SubfolderEntry(
    val folderUri: String,
    val rootId: Long,
    val relPath: String,
    val name: String,
    val itemCount: Int,
    val isPracticeFolder: Boolean,
    val hasNomedia: Boolean = false,
    val respectNomedia: Boolean? = null,
)

data class FolderBrowseState(
    val root: SafRoot,
    val currentRelPath: String,
    val breadcrumbs: List<Pair<String, String>>,
    val subfolders: List<SubfolderEntry>,
    val mediaItems: List<MediaItem>,
    val isCurrentPracticeFolder: Boolean,
)

sealed class AddRootResult {
    data class Added(val root: SafRoot) : AddRootResult()
    data class AlreadyCoveredByParent(val parentRoot: SafRoot, val childRelPath: String) : AddRootResult()
    data class MergedChildRoots(val root: SafRoot, val mergedCount: Int) : AddRootResult()
    data class Error(val message: String) : AddRootResult()
}

data class ScanProgress(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val currentFolder: String = "",
    val addedOrUpdatedCount: Int = 0,
)

data class MediaItem(
    val itemId: Long,
    val documentUri: String,
    val rootId: Long,
    val parentRelPath: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val coverPathOrUri: String? = null,
    val hasMetadataOverride: Boolean = false,
    val isBackingTrack: Boolean = false,
    val isRootWritable: Boolean = false,
    val isInPracticeFolder: Boolean = false,
    val lastPositionMs: Long = 0L,
    val bookmarkCount: Int = 0,
    val hasPracticeRecord: Boolean = false,
    val savedPracticeSpeed: Float = 1.0f,
    val savedSemitones: Int = 0,
) {
    val displayAlbum: String
        get() = album?.takeIf { it.isNotBlank() } ?: AlbumSummary.UNKNOWN_ALBUM_TITLE

    val resolutionBadge: String?
        get() {
            if (!isVideo || height <= 0) return null
            val shortSide = minOf(width.takeIf { it > 0 } ?: height, height)
            return when {
                shortSide >= 2160 -> "4K"
                shortSide >= 1440 -> "2K"
                shortSide >= 1080 -> "1080p"
                shortSide >= 720 -> "720p"
                else -> "${shortSide}p"
            }
        }
}

data class AlbumSummary(
    val albumKey: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val coverPathOrUri: String? = null,
    val trackCount: Int,
    val isUnknownAlbum: Boolean = false,
) {
    companion object {
        const val UNKNOWN_ALBUM_KEY = "__unknown_album__"
        const val UNKNOWN_ALBUM_TITLE = "未知专辑"
    }
}

data class ArtistSummary(
    val artistNorm: String,
    val displayName: String,
    val albumCount: Int,
    val trackCount: Int,
    val coverPathOrUri: String? = null,
)

data class ArtistDetail(
    val artist: ArtistSummary,
    val albums: List<AlbumSummary>,
    val tracks: List<MediaItem>,
)

enum class LibraryView {
    FOLDERS,
    ALBUMS,
    ARTISTS,
    VIDEOS,
}

enum class FolderDisplayMode {
    TREE,
    FLAT,
}

enum class SortOrder {
    NAME_ASC,
    DATE_MODIFIED_DESC,
    DURATION_DESC,
    ALBUM_ASC,
    ARTIST_ASC,
    YEAR_DESC,
    RECENT_PROGRESS_DESC,
    TRACK_COUNT_DESC,
}

enum class PlaylistFilter {
    ALL,
    PRACTICE_ONLY,
    WITH_BOOKMARKS,
}

data class PlaylistSummary(
    val playlistId: Long,
    val name: String,
    val isPracticePlaylist: Boolean,
    val itemCount: Int,
    val totalBookmarks: Int = 0,
    val coverPaths: List<String> = emptyList(),
    val updatedAt: Long = 0L,
)

data class PlaylistDetail(
    val summary: PlaylistSummary,
    val items: List<MediaItem>,
)

data class M3uImportResult(
    val playlistId: Long,
    val playlistName: String,
    val matchedCount: Int,
    val unmatchedCount: Int,
    val isPracticePlaylist: Boolean,
)

enum class PlayContext {
    CASUAL,
    PRACTICE_PLAYLIST,
    PRACTICE_FOLDER,
    BOOKMARK_JUMP,
}

enum class CountInMode {
    OFF,
    SEC_1,
    SEC_2,
    BEAT_4,
}

enum class VideoSeekMode {
    AUTO,
    EXACT,
}

enum class CasualRepeatMode {
    REPEAT_ALL,
    REPEAT_ONE,
    PLAY_ONCE,
}

data class PracticeState(
    val itemId: Long,
    val practiceSpeed: Float = 1.0f,
    val pitchSemitones: Int = 0,
    val pitchCents: Int = 0,
    val bpm: Int = 120,
    val countInMode: CountInMode = CountInMode.OFF,
    val preRollEnabled: Boolean = false,
    val loopEnabled: Boolean = false,
    val activeAMs: Long? = null,
    val activeBMs: Long? = null,
    val videoMirror: Boolean = false,
    val videoZoom: Float = 1.0f,
    val hudDockTop: Boolean = false,
    val videoSeekMode: VideoSeekMode = VideoSeekMode.AUTO,
) {
    val isNonDefault: Boolean
        get() = practiceSpeed != 1.0f ||
            pitchSemitones != 0 ||
            pitchCents != 0 ||
            (activeAMs != null && activeBMs != null) ||
            videoMirror
}

data class PracticeBookmark(
    val bookmarkId: Long,
    val itemId: Long,
    val label: String,
    val aMs: Long,
    val bMs: Long,
    val savedSpeed: Float? = null,
    val createdAt: Long = 0L,
)

data class BookmarkedTrackItem(
    val mediaItem: MediaItem,
    val bookmarks: List<PracticeBookmark>,
)

data class SpeedTrainerConfig(
    val enabled: Boolean = false,
    val startSpeed: Float = 0.60f,
    val targetSpeed: Float = 1.00f,
    val stepSpeed: Float = 0.05f,
    val loopsPerStep: Int = 4,
    val currentLoopInStep: Int = 0,
)

enum class MetadataField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    TRACK_NUMBER,
    YEAR,
    COVER,
}

data class MetadataDraft(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val coverBytes: ByteArray? = null,
    val coverMimeType: String? = null,
    val dirtyFields: Set<MetadataField> = MetadataField.entries.toSet(),
    val writeBackToFile: Boolean = false,
)

enum class MetadataSaveOutcome {
    SAVED_DB_ONLY,
    SAVED_DB_AND_FILE,
    SAVED_DB_DEFERRED_FILE,
    SAVED_DB_READONLY_FALLBACK,
}

enum class OnlineSource {
    MUSICBRAINZ,
    ITUNES,
}

data class MetadataCandidate(
    val source: OnlineSource,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val syncedLrc: String? = null,
    val plainLyrics: String? = null,
    val confidenceScore: Int = 0,
)

data class BatchMatchItem(
    val mediaItem: MediaItem,
    val cleanedTitle: String,
    val candidates: List<MetadataCandidate>,
    val selectedCandidateIndex: Int = if ((candidates.firstOrNull()?.confidenceScore ?: 0) >= 85) 0 else -1,
    val preserveBackingSuffix: Boolean = mediaItem.isBackingTrack,
)

data class LrcLine(
    val timeMs: Long,
    val text: String,
)

enum class LyricsSource {
    EMBEDDED,
    LOCAL_LRC,
    LRCLIB,
}

data class LyricsData(
    val lines: List<LrcLine>,
    val plainText: String? = null,
    val source: LyricsSource,
)

data class AppSettings(
    val autoRescanOnLaunch: Boolean = true,
    val respectSubfolderNomedia: Boolean = false,
    val writeTagsToFileDefault: Boolean = false,
    val countInClickSound: Boolean = true,
    val keepScreenOnInPractice: Boolean = true,
)

data class UpdateInfo(
    val currentVersion: String,
    val latestTag: String,
    val releaseNotes: String,
    val publishedAt: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val expectedSha256: String? = null,
    val updateAvailable: Boolean,
)

object PracticeMath {
    const val MIN_LOOP_SPAN_MS = 500L
    const val COARSE_NUDGE_MS = 500L
    const val FINE_NUDGE_MS = 100L
    const val VIDEO_FAST_SEEK_THRESHOLD_MS = 80L

    val PRESET_SECTION_LABELS: List<String> = listOf(
        "Intro", "Verse", "Pre-Chorus", "Chorus", "Bridge", "Solo 1", "Solo 2", "Outro",
    )

    val SPEED_PRESETS: List<Float> = listOf(
        0.50f, 0.65f, 0.75f, 0.85f, 0.90f, 1.00f, 1.25f, 1.50f,
    )

    fun clampSpeed(speed: Float): Float {
        val clamped = speed.coerceIn(0.25f, 2.00f)
        return (clamped * 100f).roundToInt() / 100f
    }

    fun computePitchFactor(semitones: Int, cents: Int): Float {
        val st = semitones.coerceIn(-6, 6)
        val ct = cents.coerceIn(-50, 50)
        return 2.0.pow((st + ct / 100.0) / 12.0).toFloat()
    }

    fun computeClickIntervalMs(bpm: Int, practiceSpeed: Float): Long {
        val safeBpm = bpm.coerceIn(30, 300)
        val safeSpeed = practiceSpeed.coerceIn(0.25f, 2.00f)
        return (60_000.0 / (safeBpm * safeSpeed)).roundToLong().coerceAtLeast(80L)
    }

    fun computeTapTempoBpm(tapTimestampsMs: List<Long>): Int? {
        if (tapTimestampsMs.size < 2) return null
        val recent = tapTimestampsMs.takeLast(5)
        val intervals = recent.zipWithNext { a, b -> b - a }.filter { it in 180L..2500L }
        if (intervals.isEmpty()) return null
        val sorted = intervals.sorted()
        val medianMs = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        return (60_000.0 / medianMs).roundToInt().coerceIn(30, 300)
    }

    fun normalizeLoopPoints(aMs: Long, bMs: Long, durationMs: Long): Pair<Long, Long>? {
        val maxDur = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        var start = minOf(aMs, bMs).coerceIn(0L, maxDur)
        var end = maxOf(aMs, bMs).coerceIn(0L, maxDur)
        if (end - start < MIN_LOOP_SPAN_MS) {
            if (start + MIN_LOOP_SPAN_MS <= maxDur) {
                end = start + MIN_LOOP_SPAN_MS
            } else if (end - MIN_LOOP_SPAN_MS >= 0L) {
                start = end - MIN_LOOP_SPAN_MS
            } else {
                return null
            }
        }
        return start to end
    }

    fun shouldAutoActivatePracticeMode(context: PlayContext): Boolean =
        context != PlayContext.CASUAL

    fun formatTimeMs(ms: Long, includeTenths: Boolean = false): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val tenths = (safe % 1000L) / 100L
        val base = if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
        return if (includeTenths) "$base.$tenths" else base
    }
}
