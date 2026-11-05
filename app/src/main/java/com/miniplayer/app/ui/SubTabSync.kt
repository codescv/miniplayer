package com.miniplayer.app.ui

import com.miniplayer.app.core.model.LibraryView
import com.miniplayer.app.core.model.PlaylistFilter
import com.miniplayer.app.core.model.PlaylistSummary

/*
 * Pure-Kotlin (Compose-free) logic behind the swipeable sub-tab pagers of Tab 1 `媒体库` and
 * Tab 2 `播放列表`: tab ordering, page clamping, the bidirectional FilterChip <-> HorizontalPager
 * sync state machine and per-page playlist filtering. Kept free of Android / Compose types so it
 * can be exercised directly by JVM unit tests (see `SubTabSyncTest`).
 */

/** Ordered upper FilterChip tabs (= pager pages) of both bottom-tab list screens. */
internal object SubTabOrder {
    val LIBRARY: List<LibraryView> = listOf(
        LibraryView.FOLDERS,
        LibraryView.ALBUMS,
        LibraryView.ARTISTS,
        LibraryView.VIDEOS,
    )

    val PLAYLISTS: List<PlaylistFilter> = listOf(
        PlaylistFilter.ALL,
        PlaylistFilter.PRACTICE_ONLY,
        PlaylistFilter.WITH_BOOKMARKS,
    )

    fun libraryLabel(view: LibraryView): String = when (view) {
        LibraryView.FOLDERS -> "文件夹"
        LibraryView.ALBUMS -> "专辑"
        LibraryView.ARTISTS -> "歌手"
        LibraryView.VIDEOS -> "视频"
    }

    fun playlistLabel(filter: PlaylistFilter): String = when (filter) {
        PlaylistFilter.ALL -> "全部"
        PlaylistFilter.PRACTICE_ONLY -> "练习列表"
        PlaylistFilter.WITH_BOOKMARKS -> "含段落书签"
    }
}

/** Clamps [page] into `[0, pageCount - 1]` (returns 0 for an empty pager). */
internal fun clampPage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/** Page index of [tab]; unknown tabs fall back to the first page. */
internal fun <T> List<T>.pageIndexOf(tab: T): Int = indexOf(tab).coerceAtLeast(0)

/** Tab shown on [page], clamped into range. */
internal fun <T> List<T>.tabAtPage(page: Int): T = this[clampPage(page, size)]

/**
 * Sync bookkeeping held by the screen.
 *
 * @property pendingTarget page an in-flight *programmatic* scroll (chip tap / external ViewModel
 *   change) is heading to, or `null` when the pager is idle or being driven by the user's finger.
 *   While set, intermediate `currentPage` / `settledPage` values emitted by the pager animation
 *   are ignored, so jumping `文件夹 -> 视频` never flashes `专辑` / `歌手` nor fires their side effects.
 * @property generation monotonically increasing id of the latest programmatic scroll, so a
 *   cancelled older scroll can never clear the [pendingTarget] of a newer one.
 */
internal data class SubTabSyncState(
    val pendingTarget: Int? = null,
    val generation: Long = 0L,
)

/** What a FilterChip tap has to do. */
internal data class ChipTapDecision(
    /** Page to push into the ViewModel immediately, or `null` when it is already selected there. */
    val commitPage: Int?,
    /** Page the pager must programmatically scroll to. */
    val scrollTarget: Int,
)

/** Result of starting a programmatic scroll: new state + the generation that owns it. */
internal data class ProgrammaticScrollStart(
    val state: SubTabSyncState,
    val generation: Long,
)

/**
 * Bidirectional FilterChip <-> pager sync decisions (pure state transitions).
 *
 * Contract used by the Compose layer (`rememberSubTabPager`):
 * - **Chip tap**: [onChipTap] -> commit to the ViewModel right away, then [beginProgrammaticScroll]
 *   and `animateScrollToPage`; when that scroll finishes *or is cancelled* (newer tap, user drag)
 *   call [onProgrammaticScrollEnded] with its generation.
 * - **User swipe / fling**: whenever the pager reports a new `(settledPage, isScrollInProgress,
 *   pendingTarget)` triple, [pageToCommitOnIdle] says which page (if any) to push to the ViewModel.
 *   Only a fully idle pager with no programmatic scroll in flight ever commits, so a cancelled
 *   half-swipe commits nothing and a multi-page fling commits only its final page.
 * - **External ViewModel change**: [externalSyncTarget] says whether the pager must scroll to the
 *   newly selected page.
 * - **Chip highlight / FAB**: [displayedPage] is what the UI shows as active.
 */
internal object SubTabPagerSync {

    fun onChipTap(
        state: SubTabSyncState,
        tappedPage: Int,
        selectedPage: Int,
        settledPage: Int,
        isScrollInProgress: Boolean,
        pageCount: Int,
    ): ChipTapDecision? {
        if (pageCount <= 0) return null
        val target = clampPage(tappedPage, pageCount)
        val selected = clampPage(selectedPage, pageCount)
        val pending = state.pendingTarget
        val alreadyThere = target == selected && (
            pending == target ||
                (pending == null && !isScrollInProgress && clampPage(settledPage, pageCount) == target)
            )
        // Mandatory single selection: re-tapping the active chip is a no-op (never deselects).
        if (alreadyThere) return null
        return ChipTapDecision(
            commitPage = target.takeIf { it != selected },
            scrollTarget = target,
        )
    }

    fun beginProgrammaticScroll(state: SubTabSyncState, target: Int): ProgrammaticScrollStart {
        val generation = state.generation + 1
        return ProgrammaticScrollStart(
            state = SubTabSyncState(pendingTarget = target, generation = generation),
            generation = generation,
        )
    }

    /** Clears [SubTabSyncState.pendingTarget] only if [generation] still owns it. */
    fun onProgrammaticScrollEnded(state: SubTabSyncState, generation: Long): SubTabSyncState =
        if (state.generation == generation && state.pendingTarget != null) {
            state.copy(pendingTarget = null)
        } else {
            state
        }

    /** Page to push to the ViewModel after a user gesture settles, or `null` for nothing. */
    fun pageToCommitOnIdle(
        state: SubTabSyncState,
        settledPage: Int,
        isScrollInProgress: Boolean,
        selectedPage: Int,
        pageCount: Int,
    ): Int? {
        if (pageCount <= 0 || isScrollInProgress || state.pendingTarget != null) return null
        val settled = clampPage(settledPage, pageCount)
        return settled.takeIf { it != clampPage(selectedPage, pageCount) }
    }

    /** Page the pager must scroll to after the ViewModel selection changed, or `null`. */
    fun externalSyncTarget(
        state: SubTabSyncState,
        selectedPage: Int,
        currentPage: Int,
        isScrollInProgress: Boolean,
        pageCount: Int,
    ): Int? {
        if (pageCount <= 0) return null
        val selected = clampPage(selectedPage, pageCount)
        val pending = state.pendingTarget
        if (pending != null) {
            // Already heading there (normal chip tap) -> nothing; otherwise redirect the animation.
            return selected.takeIf { it != pending }
        }
        // The user's finger owns the pager: let the gesture settle, the idle commit reconciles.
        if (isScrollInProgress) return null
        return selected.takeIf { it != clampPage(currentPage, pageCount) }
    }

    /**
     * Page highlighted in the chip row (and driving FAB visibility): the programmatic target while
     * one is in flight, the pager's gesture target while the user drags/flings (crisp feedback that
     * snaps back on a cancelled half-swipe), else the settled page.
     */
    fun displayedPage(
        state: SubTabSyncState,
        settledPage: Int,
        targetPage: Int,
        isScrollInProgress: Boolean,
        pageCount: Int,
    ): Int = clampPage(
        state.pendingTarget ?: if (isScrollInProgress) targetPage else settledPage,
        pageCount,
    )
}

/**
 * Horizontal scroll offset that reveals a chip spanning `[chipStart, chipEnd)` (content px) in a
 * scroll viewport currently showing `[scrollValue, scrollValue + viewportWidth)`, keeping
 * [edgePadding] px of breathing room. Returns `null` when the chip is already fully visible.
 */
internal fun chipRevealScrollTarget(
    chipStart: Int,
    chipEnd: Int,
    scrollValue: Int,
    viewportWidth: Int,
    maxScroll: Int,
    edgePadding: Int = 0,
): Int? {
    if (viewportWidth <= 0 || chipEnd <= chipStart) return null
    val target = when {
        chipStart - edgePadding < scrollValue -> chipStart - edgePadding
        chipEnd + edgePadding > scrollValue + viewportWidth -> {
            val endAligned = chipEnd + edgePadding - viewportWidth
            // A chip wider than the viewport is start-aligned instead.
            if (chipEnd - chipStart + 2 * edgePadding > viewportWidth) chipStart - edgePadding else endAligned
        }
        else -> return null
    }.coerceIn(0, maxScroll.coerceAtLeast(0))
    return target.takeIf { it != scrollValue }
}

/**
 * Playlists rendered on the pager page of [pageFilter] (each page filters independently of the
 * globally selected chip so mid-swipe neighbours render their own content).
 * [PlaylistFilter.WITH_BOOKMARKS] shows bookmarked tracks instead of playlists, hence empty.
 */
internal fun playlistsForPage(
    playlists: List<PlaylistSummary>,
    pageFilter: PlaylistFilter,
    searchQuery: String,
): List<PlaylistSummary> {
    val matchesFilter: (PlaylistSummary) -> Boolean = when (pageFilter) {
        PlaylistFilter.ALL -> { _ -> true }
        PlaylistFilter.PRACTICE_ONLY -> { pl -> pl.isPracticePlaylist }
        PlaylistFilter.WITH_BOOKMARKS -> return emptyList()
    }
    return playlists.filter { pl ->
        matchesFilter(pl) && (searchQuery.isBlank() || pl.name.contains(searchQuery, ignoreCase = true))
    }
}

/** Empty-state title of a playlist pager page. */
internal fun playlistPageEmptyTitle(pageFilter: PlaylistFilter): String = when (pageFilter) {
    PlaylistFilter.PRACTICE_ONLY -> "暂无练习列表"
    else -> "暂无自建播放列表"
}

/** Whether the `新建播放列表` FAB is shown for the active playlist tab. */
internal fun playlistFabVisible(activeFilter: PlaylistFilter): Boolean =
    activeFilter != PlaylistFilter.WITH_BOOKMARKS

/** Whether the `添加文件夹` FAB is shown for the active library tab. */
internal fun libraryAddFolderFabVisible(
    activeView: LibraryView,
    isBrowsingFolder: Boolean,
    hasRoots: Boolean,
): Boolean = activeView == LibraryView.FOLDERS && !isBrowsingFolder && hasRoots
