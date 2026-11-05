package com.miniplayer.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Swipeable sub-tab controller shared by `媒体库` (4 views) and `播放列表` (3 filters).
 *
 * The ViewModel selection stays the single source of truth; this object wires the
 * [SubTabPagerSync] state machine to a Compose [PagerState]:
 * - chip tap -> ViewModel updated immediately + `animateScrollToPage` (newer taps cancel older ones),
 * - user swipe -> ViewModel updated once the pager settles (never for intermediate pages),
 * - external ViewModel change -> pager scrolls to the new page.
 */
@Stable
internal class SubTabPagerController<T>(
    val tabs: List<T>,
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    var syncState by mutableStateOf(SubTabSyncState())
        private set

    private var scrollJob: Job? = null

    /** Latest ViewModel selection + setter (updated every composition). */
    internal var selectedPage: Int = pagerState.currentPage
    internal var onSelect: (T) -> Unit = {}

    /** Page highlighted in the chip row / driving FABs. */
    val displayedPage: Int by derivedStateOf {
        SubTabPagerSync.displayedPage(
            state = syncState,
            settledPage = pagerState.settledPage,
            targetPage = pagerState.targetPage,
            isScrollInProgress = pagerState.isScrollInProgress,
            pageCount = tabs.size,
        )
    }

    val displayedTab: T get() = tabs.tabAtPage(displayedPage)

    fun onChipClick(tab: T) {
        val decision = SubTabPagerSync.onChipTap(
            state = syncState,
            tappedPage = tabs.pageIndexOf(tab),
            selectedPage = selectedPage,
            settledPage = pagerState.settledPage,
            isScrollInProgress = pagerState.isScrollInProgress,
            pageCount = tabs.size,
        ) ?: return
        decision.commitPage?.let { onSelect(tabs.tabAtPage(it)) }
        animateTo(decision.scrollTarget)
    }

    internal fun animateTo(target: Int) {
        val start = SubTabPagerSync.beginProgrammaticScroll(syncState, target)
        syncState = start.state
        scrollJob?.cancel()
        scrollJob = scope.launch {
            try {
                pagerState.animateScrollToPage(target)
            } finally {
                // Runs on completion AND on cancellation (newer tap / user drag); only clears
                // the pending target if no newer programmatic scroll has taken over.
                syncState = SubTabPagerSync.onProgrammaticScrollEnded(syncState, start.generation)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> rememberSubTabPager(
    tabs: List<T>,
    selectedTab: T,
    onSelectTab: (T) -> Unit,
): SubTabPagerController<T> {
    val selectedPage = tabs.pageIndexOf(selectedTab)
    val pagerState = rememberPagerState(initialPage = selectedPage) { tabs.size }
    val scope = rememberCoroutineScope()
    val controller = remember(tabs, pagerState, scope) { SubTabPagerController(tabs, pagerState, scope) }
    val latestOnSelect by rememberUpdatedState(onSelectTab)
    controller.selectedPage = selectedPage
    controller.onSelect = { latestOnSelect(it) }

    // External ViewModel change (or a redirect while a programmatic scroll is in flight).
    LaunchedEffect(controller, selectedPage) {
        SubTabPagerSync.externalSyncTarget(
            state = controller.syncState,
            selectedPage = selectedPage,
            currentPage = pagerState.currentPage,
            isScrollInProgress = pagerState.isScrollInProgress,
            pageCount = tabs.size,
        )?.let { controller.animateTo(it) }
    }

    // User swipe / fling settled -> push the final page into the ViewModel (exactly once).
    LaunchedEffect(controller) {
        snapshotFlow {
            Triple(pagerState.settledPage, pagerState.isScrollInProgress, controller.syncState.pendingTarget)
        }.collect { (settled, scrolling, _) ->
            SubTabPagerSync.pageToCommitOnIdle(
                state = controller.syncState,
                settledPage = settled,
                isScrollInProgress = scrolling,
                selectedPage = controller.selectedPage,
                pageCount = tabs.size,
            )?.let { page ->
                // Optimistically record it so a re-emission before the ViewModel round-trips
                // cannot commit the same page twice.
                controller.selectedPage = page
                latestOnSelect(tabs.tabAtPage(page))
            }
        }
    }
    return controller
}

/**
 * Single-line, horizontally scrollable, mandatory single-select M3 FilterChip row. The active chip
 * is auto-revealed when it changes (swipe on narrow screens / large font scales).
 */
@Composable
internal fun <T> SubTabChipRow(
    tabs: List<T>,
    activeTab: T,
    label: (T) -> String,
    onChipClick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val horizontalPadding = 16.dp
    val paddingPx = with(density) { horizontalPadding.roundToPx() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val chipBounds = remember(tabs) { arrayOfNulls<IntRange>(tabs.size) }
    var boundsVersion by remember { mutableIntStateOf(0) }
    val activeIndex = tabs.pageIndexOf(activeTab)

    LaunchedEffect(activeIndex, viewportWidth, boundsVersion) {
        val bounds = chipBounds.getOrNull(activeIndex) ?: return@LaunchedEffect
        chipRevealScrollTarget(
            chipStart = bounds.first,
            chipEnd = bounds.last,
            scrollValue = scrollState.value,
            viewportWidth = viewportWidth,
            maxScroll = scrollState.maxValue,
            edgePadding = paddingPx,
        )?.let { scrollState.animateScrollTo(it) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportWidth = it.width }
            .horizontalScroll(scrollState)
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            FilterChip(
                selected = index == activeIndex,
                // Mandatory single selection: the controller ignores re-taps of the active chip.
                onClick = { onChipClick(tab) },
                label = { Text(label(tab)) },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .onPlaced { coords ->
                        val start = paddingPx + coords.positionInParent().x.toInt()
                        val range = start..(start + coords.size.width)
                        if (chipBounds[index] != range) {
                            chipBounds[index] = range
                            boundsVersion++
                        }
                    },
            )
        }
    }
}
