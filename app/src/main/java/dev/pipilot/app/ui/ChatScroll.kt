package dev.pipilot.app.ui

/**
 * Viewport-based follow-bottom for a growing last chat bubble.
 *
 * [LazyListState.scrollToItem] defaults to offset 0, which pins the *start* of
 * the last item to the top of the viewport. While a live assistant block is
 * taller than the screen, that looks like "swipe down jumps back to the top of
 * the current turn." Follow must use remaining pixels under the last item, not
 * "last visible index is near the end."
 *
 * After a full history rebuild (reconnect / session switch), the list is empty
 * then long. Idle layout at the start of that list must not disable follow;
 * only a user drag away from the bottom does.
 */
internal data class ChatVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

internal data class OccupiedRefreshGesture(
    val pullPx: Float = 0f,
) {
    fun move(dy: Float, atBottom: Boolean, thresholdPx: Float = ChatScroll.REFRESH_PULL_PX): OccupiedRefreshGesture =
        copy(pullPx = ChatScroll.occupiedRefreshPull(pullPx, dy, atBottom, thresholdPx))

    fun shouldRefreshOnRelease(atBottom: Boolean, thresholdPx: Float = ChatScroll.REFRESH_PULL_PX): Boolean =
        atBottom && pullPx >= thresholdPx
}

internal object ChatScroll {
    /** How far above true bottom still counts as "stuck to the latest output." */
    const val FOLLOW_SLACK_PX = 80

    /** Upward overscroll required at the bottom before reloading a TUI-owned jsonl. */
    const val REFRESH_PULL_PX = 72f

    fun occupiedRefreshPull(
        currentPx: Float,
        availableY: Float,
        atBottom: Boolean,
        thresholdPx: Float = REFRESH_PULL_PX,
    ): Float {
        if (!atBottom || availableY >= 0f) return 0f
        return (currentPx - availableY).coerceAtMost(thresholdPx)
    }

    fun isAtBottom(
        itemCount: Int,
        viewportEndOffset: Int,
        visible: List<ChatVisibleItem>,
        lastIndex: Int = itemCount - 1,
        afterContentPadding: Int = 0,
        slackPx: Int = FOLLOW_SLACK_PX,
    ): Boolean {
        if (itemCount <= 0) return true
        val last = visible.lastOrNull { it.index == lastIndex } ?: return false
        val remaining = (last.offset + last.size + afterContentPadding) - viewportEndOffset
        return remaining <= slackPx
    }

    fun lastItemBottomOffset(
        itemSize: Int,
        viewportSize: Int,
        afterContentPadding: Int = 0,
        minOffset: Int = 0,
    ): Int = (itemSize + afterContentPadding - viewportSize).coerceAtLeast(minOffset)

    /**
     * Idle layout after a full rebuild starts at index 0, which is not the
     * bottom. Keep following until the user actually drags away.
     */
    fun followAfterIdleLayout(
        currentlyFollowing: Boolean,
        userDragging: Boolean,
        atBottom: Boolean,
    ): Boolean {
        if (userDragging) return atBottom
        if (currentlyFollowing) return true
        return atBottom
    }
}
