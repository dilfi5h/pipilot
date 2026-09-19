package dev.pipilot.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollTest {
    @Test
    fun emptyListCountsAsAtBottom() {
        assertTrue(ChatScroll.isAtBottom(itemCount = 0, viewportEndOffset = 800, visible = emptyList()))
    }

    @Test
    fun lastItemFullyBelowViewportIsNotAtBottom() {
        assertFalse(
            ChatScroll.isAtBottom(
                itemCount = 3,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 0, offset = 0, size = 800)),
            ),
        )
    }

    @Test
    fun lastVisibleIndexIsNotEnoughWhenTallLastItemIsPinnedToTop() {
        // Live bubble fills the screen; user is looking at the start of it.
        assertFalse(
            ChatScroll.isAtBottom(
                itemCount = 4,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 3, offset = 0, size = 2400)),
            ),
        )
    }

    @Test
    fun scrolledInsideTallLastItemIsNotAtBottom() {
        assertFalse(
            ChatScroll.isAtBottom(
                itemCount = 4,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 3, offset = -400, size = 2400)),
            ),
        )
    }

    @Test
    fun trueBottomOfTallLastItemIsAtBottom() {
        assertTrue(
            ChatScroll.isAtBottom(
                itemCount = 4,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 3, offset = -1600, size = 2400)),
            ),
        )
    }

    @Test
    fun slackKeepsFollowWhenAFewPixelsRemain() {
        assertTrue(
            ChatScroll.isAtBottom(
                itemCount = 2,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 1, offset = 0, size = 850)),
            ),
        )
        assertFalse(
            ChatScroll.isAtBottom(
                itemCount = 2,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 1, offset = 0, size = 950)),
            ),
        )
    }

    @Test
    fun lastItemBottomOffsetPinsEndOfTallItem() {
        assertEquals(0, ChatScroll.lastItemBottomOffset(itemSize = 400, viewportSize = 800))
        assertEquals(1600, ChatScroll.lastItemBottomOffset(itemSize = 2400, viewportSize = 800))
        assertEquals(1612, ChatScroll.lastItemBottomOffset(itemSize = 2400, viewportSize = 800, afterContentPadding = 12))
    }

    @Test
    fun contentPaddingIsCountedWhenCheckingBottom() {
        assertTrue(
            ChatScroll.isAtBottom(
                itemCount = 2,
                viewportEndOffset = 800,
                visible = listOf(ChatVisibleItem(index = 1, offset = -200, size = 988)),
                afterContentPadding = 12,
            ),
        )
    }
}
