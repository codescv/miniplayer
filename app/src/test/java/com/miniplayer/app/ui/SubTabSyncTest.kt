package com.miniplayer.app.ui

import com.miniplayer.app.core.model.LibraryView
import com.miniplayer.app.core.model.PlaylistFilter
import com.miniplayer.app.core.model.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the swipeable sub-tab logic in `SubTabSync.kt`: tab order, clamping,
 * bidirectional FilterChip <-> pager sync and per-page playlist filtering.
 */
class SubTabSyncTest {

    // ---------------------------------------------------------------- tab order & clamping

    @Test
    fun libraryTabs_areOrderedFoldersAlbumsArtistsVideos_withChineseLabels() {
        assertEquals(
            listOf(LibraryView.FOLDERS, LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.VIDEOS),
            SubTabOrder.LIBRARY,
        )
        assertEquals(listOf("文件夹", "专辑", "歌手", "视频"), SubTabOrder.LIBRARY.map(SubTabOrder::libraryLabel))
        assertEquals(LibraryView.entries.toSet(), SubTabOrder.LIBRARY.toSet())
    }

    @Test
    fun playlistTabs_areOrderedAllPracticeBookmarks_withChineseLabels() {
        assertEquals(
            listOf(PlaylistFilter.ALL, PlaylistFilter.PRACTICE_ONLY, PlaylistFilter.WITH_BOOKMARKS),
            SubTabOrder.PLAYLISTS,
        )
        assertEquals(listOf("全部", "练习列表", "含段落书签"), SubTabOrder.PLAYLISTS.map(SubTabOrder::playlistLabel))
        assertEquals(PlaylistFilter.entries.toSet(), SubTabOrder.PLAYLISTS.toSet())
    }

    @Test
    fun pageIndexAndTabAtPage_roundTripAndClamp() {
        SubTabOrder.LIBRARY.forEachIndexed { i, view ->
            assertEquals(i, SubTabOrder.LIBRARY.pageIndexOf(view))
            assertEquals(view, SubTabOrder.LIBRARY.tabAtPage(i))
        }
        SubTabOrder.PLAYLISTS.forEachIndexed { i, f ->
            assertEquals(i, SubTabOrder.PLAYLISTS.pageIndexOf(f))
            assertEquals(f, SubTabOrder.PLAYLISTS.tabAtPage(i))
        }
        assertEquals(LibraryView.FOLDERS, SubTabOrder.LIBRARY.tabAtPage(-5))
        assertEquals(LibraryView.VIDEOS, SubTabOrder.LIBRARY.tabAtPage(99))
        assertEquals(PlaylistFilter.WITH_BOOKMARKS, SubTabOrder.PLAYLISTS.tabAtPage(3))
        assertEquals(0, listOf("a", "b").pageIndexOf("zzz"))
        assertEquals(0, clampPage(-1, 4))
        assertEquals(3, clampPage(7, 4))
        assertEquals(2, clampPage(2, 4))
        assertEquals(0, clampPage(5, 0))
    }

    // ---------------------------------------------------------------- harness

    /**
     * Mirrors `SubTabPagerController` / `rememberSubTabPager` wiring with a fake pager + ViewModel,
     * recording every ViewModel write and every chip highlight so flicker / ping-pong is assertable.
     */
    private class Harness(val pageCount: Int, initial: Int = 0) {
        var vmSelected = initial
        val vmCommits = mutableListOf<Int>()
        var state = SubTabSyncState()
        var currentPage = initial
        var settledPage = initial
        var targetPage = initial
        var scrolling = false
        val displayedHistory = mutableListOf<Int>()
        private var activeGeneration = 0L

        init { recordDisplayed() }

        val displayed: Int
            get() = SubTabPagerSync.displayedPage(state, settledPage, targetPage, scrolling, pageCount)

        private fun recordDisplayed() {
            if (displayedHistory.lastOrNull() != displayed) displayedHistory += displayed
        }

        private fun commit(page: Int) {
            vmSelected = page
            vmCommits += page
        }

        /** Re-evaluates the snapshotFlow idle collector. */
        private fun idleCheck() {
            SubTabPagerSync.pageToCommitOnIdle(state, settledPage, scrolling, vmSelected, pageCount)?.let(::commit)
            recordDisplayed()
        }

        private fun startProgrammatic(target: Int): Long {
            val start = SubTabPagerSync.beginProgrammaticScroll(state, target)
            state = start.state
            activeGeneration = start.generation
            recordDisplayed()
            return start.generation
        }

        fun tapChip(page: Int): Long? {
            val decision = SubTabPagerSync.onChipTap(state, page, vmSelected, settledPage, scrolling, pageCount)
                ?: return null
            decision.commitPage?.let(::commit)
            val gen = startProgrammatic(decision.scrollTarget)
            externalEffect() // LaunchedEffect(selectedPage) re-runs after the VM round trip
            return gen
        }

        /** Programmatic animation passes through [page] (currentPage changes, target stays). */
        fun animateThrough(page: Int) {
            scrolling = true
            currentPage = page
            targetPage = state.pendingTarget ?: targetPage
            idleCheck()
        }

        /** Programmatic animation of [gen] reaches its target and ends. */
        fun finishProgrammatic(gen: Long = activeGeneration) {
            val target = state.pendingTarget ?: currentPage
            currentPage = target
            targetPage = target
            settledPage = target
            scrolling = false
            idleCheck()
            state = SubTabPagerSync.onProgrammaticScrollEnded(state, gen)
            idleCheck()
        }

        /** Programmatic scroll [gen] got cancelled (newer tap or user finger). */
        fun cancelProgrammatic(gen: Long) {
            state = SubTabPagerSync.onProgrammaticScrollEnded(state, gen)
            idleCheck()
        }

        fun userDrag(current: Int, target: Int) {
            scrolling = true
            currentPage = current
            targetPage = target
            idleCheck()
        }

        fun userSettle(page: Int) {
            currentPage = page
            targetPage = page
            settledPage = page
            scrolling = false
            idleCheck()
            externalEffect()
        }

        fun externalSelect(page: Int) {
            vmSelected = page
            externalEffect()
        }

        private fun externalEffect() {
            SubTabPagerSync.externalSyncTarget(state, vmSelected, currentPage, scrolling, pageCount)
                ?.let { startProgrammatic(it) }
        }
    }

    // ---------------------------------------------------------------- chip tap

    @Test
    fun chipTap_multiTabJump_commitsOnce_andNeverFlickersIntermediateChips() {
        val h = Harness(pageCount = 4)
        val gen = h.tapChip(3)!!
        assertEquals(listOf(3), h.vmCommits)
        assertEquals(3, h.displayed)
        // Animation passes through 专辑 / 歌手 -> neither highlighted nor committed.
        h.animateThrough(1)
        h.animateThrough(2)
        assertEquals(3, h.displayed)
        h.finishProgrammatic(gen)
        assertEquals(listOf(3), h.vmCommits)
        assertEquals(listOf(0, 3), h.displayedHistory)
        assertNull(h.state.pendingTarget)
        assertEquals(3, h.vmSelected)
    }

    @Test
    fun chipTap_onAlreadyActiveChip_isNoOp_mandatorySingleSelection() {
        val h = Harness(pageCount = 4, initial = 2)
        assertNull(h.tapChip(2))
        assertTrue(h.vmCommits.isEmpty())
        assertEquals(2, h.displayed)
    }

    @Test
    fun chipTap_onChipAlreadyBeingAnimatedTo_isNoOp() {
        val h = Harness(pageCount = 4)
        h.tapChip(3)
        h.animateThrough(1)
        assertNull(h.tapChip(3))
        assertEquals(listOf(3), h.vmCommits)
    }

    @Test
    fun rapidConsecutiveChipTaps_newerTapCancelsOlderScroll_withoutStaleClear() {
        val h = Harness(pageCount = 4)
        val g1 = h.tapChip(3)!!
        h.animateThrough(1)
        val g2 = h.tapChip(1)!!
        // Older scroll's `finally` runs late: must not clear the newer pending target.
        h.cancelProgrammatic(g1)
        assertEquals(1, h.state.pendingTarget)
        assertEquals(1, h.displayed)
        h.finishProgrammatic(g2)
        assertEquals(listOf(3, 1), h.vmCommits)
        assertEquals(listOf(0, 3, 1), h.displayedHistory)
        assertEquals(1, h.vmSelected)
        assertEquals(1, h.settledPage)
    }

    @Test
    fun rapidChipTaps_settledPageTransientlyUpdatedBetweenScrolls_isIgnored() {
        val h = Harness(pageCount = 4)
        val g1 = h.tapChip(3)!!
        h.animateThrough(2)
        val g2 = h.tapChip(0)!!
        // Pager briefly reports idle on page 2 between the two animations.
        h.settledPage = 2
        h.scrolling = false
        h.cancelProgrammatic(g1)
        assertEquals(listOf(3, 0), h.vmCommits)
        assertEquals(0, h.displayed)
        h.finishProgrammatic(g2)
        assertEquals(listOf(3, 0), h.vmCommits)
        assertEquals(0, h.vmSelected)
    }

    // ---------------------------------------------------------------- user swipe

    @Test
    fun userSwipe_toNeighbour_commitsOnlyAfterSettle() {
        val h = Harness(pageCount = 3)
        h.userDrag(current = 0, target = 1)
        assertTrue(h.vmCommits.isEmpty())
        assertEquals(1, h.displayed) // crisp chip feedback while dragging
        h.userSettle(1)
        assertEquals(listOf(1), h.vmCommits)
        assertNull(h.state.pendingTarget) // VM round trip does not trigger a programmatic scroll
        assertEquals(1, h.displayed)
    }

    @Test
    fun cancelledHalfSwipe_commitsNothing_andHighlightSnapsBack() {
        val h = Harness(pageCount = 4, initial = 1)
        h.userDrag(current = 1, target = 2) // past the threshold...
        h.userDrag(current = 1, target = 1) // ...then dragged back
        h.userSettle(1)
        assertTrue(h.vmCommits.isEmpty())
        assertEquals(1, h.displayed)
        assertEquals(1, h.vmSelected)
    }

    @Test
    fun rapidMultiPageFling_commitsOnlyFinalPage() {
        val h = Harness(pageCount = 4)
        h.userDrag(current = 0, target = 1)
        h.userDrag(current = 1, target = 2)
        h.userDrag(current = 2, target = 3)
        assertTrue(h.vmCommits.isEmpty())
        h.userSettle(3)
        assertEquals(listOf(3), h.vmCommits)
        assertEquals(3, h.vmSelected)
    }

    @Test
    fun userSwipeInterruptsChipAnimation_userWins_andVmFollowsFinalPage() {
        val h = Harness(pageCount = 4)
        val gen = h.tapChip(3)!!
        h.animateThrough(1)
        // Finger grabs the pager: animateScrollToPage is cancelled.
        h.cancelProgrammatic(gen)
        h.userDrag(current = 1, target = 2)
        assertEquals(2, h.displayed)
        h.userSettle(2)
        assertEquals(listOf(3, 2), h.vmCommits)
        assertEquals(2, h.vmSelected)
        assertNull(h.state.pendingTarget)
    }

    @Test
    fun chipScrollBlockedByFinger_settlingOnSamePage_stillReconcilesVm() {
        val h = Harness(pageCount = 4)
        h.userDrag(current = 0, target = 0)
        val gen = h.tapChip(2)!! // VM -> 2, but the scroll is refused while the finger owns the pager
        h.cancelProgrammatic(gen)
        h.userSettle(0) // settledPage never changed
        assertEquals(listOf(2, 0), h.vmCommits)
        assertEquals(0, h.vmSelected)
        assertEquals(0, h.displayed)
    }

    @Test
    fun swipeThenChipTapThenSwipe_neverDesyncs() {
        val h = Harness(pageCount = 3)
        h.userDrag(0, 1); h.userSettle(1)
        val gen = h.tapChip(0)!!
        h.finishProgrammatic(gen)
        h.userDrag(0, 1); h.userDrag(1, 2); h.userSettle(2)
        assertEquals(listOf(1, 0, 2), h.vmCommits)
        assertEquals(h.settledPage, h.vmSelected)
        assertEquals(h.vmSelected, h.displayed)
    }

    // ---------------------------------------------------------------- external VM change

    @Test
    fun externalVmChange_whileIdle_scrollsPagerWithoutCommittingBack() {
        val h = Harness(pageCount = 4)
        h.externalSelect(2)
        assertEquals(2, h.state.pendingTarget)
        assertEquals(2, h.displayed)
        h.animateThrough(1)
        h.finishProgrammatic()
        assertTrue(h.vmCommits.isEmpty()) // no ping-pong write back into the VM
        assertEquals(2, h.settledPage)
    }

    @Test
    fun externalVmChange_matchingPager_isNoOp() {
        val h = Harness(pageCount = 4, initial = 1)
        h.externalSelect(1)
        assertNull(h.state.pendingTarget)
    }

    @Test
    fun externalVmChange_duringChipAnimation_redirectsToNewPage() {
        val h = Harness(pageCount = 4)
        val g1 = h.tapChip(3)!!
        h.animateThrough(1)
        h.externalSelect(2)
        assertEquals(2, h.state.pendingTarget)
        h.cancelProgrammatic(g1)
        h.finishProgrammatic()
        assertEquals(2, h.settledPage)
        assertEquals(2, h.vmSelected)
        assertEquals(listOf(3), h.vmCommits)
    }

    @Test
    fun externalVmChange_duringUserDrag_isDeferredToSettle_userWins() {
        val h = Harness(pageCount = 4)
        h.userDrag(0, 1)
        h.externalSelect(3)
        assertNull(h.state.pendingTarget)
        h.userSettle(1)
        assertEquals(1, h.vmSelected)
        assertEquals(listOf(1), h.vmCommits)
    }

    // ---------------------------------------------------------------- pure decision edges

    @Test
    fun onProgrammaticScrollEnded_onlyClearsOwningGeneration() {
        val s1 = SubTabPagerSync.beginProgrammaticScroll(SubTabSyncState(), 2)
        val s2 = SubTabPagerSync.beginProgrammaticScroll(s1.state, 3)
        assertEquals(s2.state, SubTabPagerSync.onProgrammaticScrollEnded(s2.state, s1.generation))
        assertNull(SubTabPagerSync.onProgrammaticScrollEnded(s2.state, s2.generation).pendingTarget)
        assertTrue(s2.generation > s1.generation)
    }

    @Test
    fun pageToCommitOnIdle_requiresIdleAndNoPending() {
        val idle = SubTabSyncState()
        assertEquals(2, SubTabPagerSync.pageToCommitOnIdle(idle, 2, false, 0, 4))
        assertNull(SubTabPagerSync.pageToCommitOnIdle(idle, 2, true, 0, 4))
        assertNull(SubTabPagerSync.pageToCommitOnIdle(idle, 0, false, 0, 4))
        assertNull(SubTabPagerSync.pageToCommitOnIdle(SubTabSyncState(pendingTarget = 3), 2, false, 0, 4))
        assertEquals(3, SubTabPagerSync.pageToCommitOnIdle(idle, 9, false, 0, 4))
        assertNull(SubTabPagerSync.pageToCommitOnIdle(idle, 1, false, 0, 0))
    }

    @Test
    fun onChipTap_decisions() {
        val idle = SubTabSyncState()
        assertEquals(ChipTapDecision(commitPage = 2, scrollTarget = 2), SubTabPagerSync.onChipTap(idle, 2, 0, 0, false, 4))
        // VM already says 2 but pager is elsewhere (e.g. mid user-drag): scroll, no duplicate commit.
        assertEquals(ChipTapDecision(commitPage = null, scrollTarget = 2), SubTabPagerSync.onChipTap(idle, 2, 2, 1, true, 4))
        assertEquals(ChipTapDecision(commitPage = 3, scrollTarget = 3), SubTabPagerSync.onChipTap(idle, 42, 0, 0, false, 4))
        assertNull(SubTabPagerSync.onChipTap(idle, 0, 0, 0, false, 0))
    }

    @Test
    fun displayedPage_priorities() {
        assertEquals(3, SubTabPagerSync.displayedPage(SubTabSyncState(pendingTarget = 3), 0, 1, true, 4))
        assertEquals(1, SubTabPagerSync.displayedPage(SubTabSyncState(), 0, 1, true, 4))
        assertEquals(2, SubTabPagerSync.displayedPage(SubTabSyncState(), 2, 1, false, 4))
    }

    // ---------------------------------------------------------------- chip row auto-reveal

    @Test
    fun chipReveal_scrollsClippedChipsIntoViewWithPadding() {
        // Viewport 300px showing [0,300), content max scroll 200.
        assertNull(chipRevealScrollTarget(20, 120, 0, 300, 200, 16))
        assertEquals(156, chipRevealScrollTarget(340, 440, 0, 300, 200, 16)) // 440+16-300
        assertEquals(200, chipRevealScrollTarget(400, 490, 0, 300, 200, 16)) // clamped to max
        assertEquals(4, chipRevealScrollTarget(20, 120, 150, 300, 200, 16)) // 20-16
        assertEquals(0, chipRevealScrollTarget(10, 120, 150, 300, 200, 16)) // clamped to 0
        // Wider than viewport -> start aligned.
        assertEquals(84, chipRevealScrollTarget(100, 500, 0, 300, 600, 16))
        assertNull(chipRevealScrollTarget(0, 100, 0, 0, 200, 16))
    }

    // ---------------------------------------------------------------- per-page playlist filtering

    private val playlists = listOf(
        PlaylistSummary(1, "Daily Mix", isPracticePlaylist = false, itemCount = 10),
        PlaylistSummary(2, "Solo Drills", isPracticePlaylist = true, itemCount = 4, totalBookmarks = 6),
        PlaylistSummary(3, "Blues Licks", isPracticePlaylist = true, itemCount = 7),
        PlaylistSummary(4, "Road Trip", isPracticePlaylist = false, itemCount = 20, totalBookmarks = 1),
    )

    @Test
    fun playlistsForPage_eachPageFiltersIndependently() {
        assertEquals(listOf(1L, 2L, 3L, 4L), playlistsForPage(playlists, PlaylistFilter.ALL, "").map { it.playlistId })
        assertEquals(listOf(2L, 3L), playlistsForPage(playlists, PlaylistFilter.PRACTICE_ONLY, "").map { it.playlistId })
        assertTrue(playlistsForPage(playlists, PlaylistFilter.WITH_BOOKMARKS, "").isEmpty())
    }

    @Test
    fun playlistsForPage_appliesSearchQueryCaseInsensitivelyPerPage() {
        assertEquals(listOf(2L), playlistsForPage(playlists, PlaylistFilter.ALL, "solo").map { it.playlistId })
        assertEquals(listOf(3L), playlistsForPage(playlists, PlaylistFilter.PRACTICE_ONLY, "BLUES").map { it.playlistId })
        assertTrue(playlistsForPage(playlists, PlaylistFilter.PRACTICE_ONLY, "road").isEmpty())
        assertEquals(4, playlistsForPage(playlists, PlaylistFilter.ALL, "   ").size)
    }

    @Test
    fun playlistsForPage_neighbourPagesRenderOwnContentMidSwipe() {
        // Whatever chip is globally selected, page N renders filter N.
        val pages = SubTabOrder.PLAYLISTS.map { playlistsForPage(playlists, it, "").map(PlaylistSummary::playlistId) }
        assertEquals(listOf(listOf(1L, 2L, 3L, 4L), listOf(2L, 3L), emptyList()), pages)
    }

    @Test
    fun playlistEmptyTitles_andFabRules() {
        assertEquals("暂无自建播放列表", playlistPageEmptyTitle(PlaylistFilter.ALL))
        assertEquals("暂无练习列表", playlistPageEmptyTitle(PlaylistFilter.PRACTICE_ONLY))
        assertTrue(playlistFabVisible(PlaylistFilter.ALL))
        assertTrue(playlistFabVisible(PlaylistFilter.PRACTICE_ONLY))
        assertFalse(playlistFabVisible(PlaylistFilter.WITH_BOOKMARKS))

        assertTrue(libraryAddFolderFabVisible(LibraryView.FOLDERS, isBrowsingFolder = false, hasRoots = true))
        assertFalse(libraryAddFolderFabVisible(LibraryView.FOLDERS, isBrowsingFolder = true, hasRoots = true))
        assertFalse(libraryAddFolderFabVisible(LibraryView.FOLDERS, isBrowsingFolder = false, hasRoots = false))
        listOf(LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.VIDEOS).forEach {
            assertFalse(libraryAddFolderFabVisible(it, isBrowsingFolder = false, hasRoots = true))
        }
    }
}
