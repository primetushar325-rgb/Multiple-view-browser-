package com.example.multiview

import com.example.multiview.utils.AdblockMode
import com.example.multiview.utils.BlockingPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ad-blocker must stay selective: the blocklist is only allowed to stop
 * what it is safe to stop. These rules are the difference between "ads gone"
 * and "Gmail broken".
 */
class BlockingPolicyTest {

    @Test fun normalModeNeverBlocksThePageItself() {
        assertFalse(
            BlockingPolicy.shouldBlock(
                AdblockMode.NORMAL, matched = true, isMainFrame = true, protected = false,
            ),
        )
    }

    @Test fun normalModeBlocksMatchedSubresources() {
        assertTrue(
            BlockingPolicy.shouldBlock(
                AdblockMode.NORMAL, matched = true, isMainFrame = false, protected = false,
            ),
        )
    }

    @Test fun aggressiveModeBlocksMainFramesToo() {
        assertTrue(
            BlockingPolicy.shouldBlock(
                AdblockMode.AGGRESSIVE, matched = true, isMainFrame = true, protected = false,
            ),
        )
        assertTrue(
            BlockingPolicy.shouldBlock(
                AdblockMode.AGGRESSIVE, matched = true, isMainFrame = false, protected = false,
            ),
        )
    }

    @Test fun unmatchedRequestsPassInEveryMode() {
        AdblockMode.values().forEach { mode ->
            assertFalse(
                BlockingPolicy.shouldBlock(
                    mode, matched = false, isMainFrame = false, protected = false,
                ),
            )
        }
    }

    @Test fun websocketsAreNeverIntercepted() {
        assertTrue(BlockingPolicy.isProtected("wss", ""))
        assertTrue(BlockingPolicy.isProtected("ws", ""))
        // ...so even aggressive mode leaves them alone.
        assertFalse(
            BlockingPolicy.shouldBlock(
                AdblockMode.AGGRESSIVE, matched = true, isMainFrame = false,
                protected = BlockingPolicy.isProtected("wss", ""),
            ),
        )
    }

    @Test fun serverSentEventsAreNeverIntercepted() {
        assertTrue(BlockingPolicy.isProtected("https", "text/event-stream"))
        assertTrue(BlockingPolicy.isProtected("https", "Accept: text/event-stream; charset=utf-8"))
        assertFalse(BlockingPolicy.isProtected("https", "text/html"))
    }

    @Test fun unknownModeNameFallsBackToNormal() {
        assertEqualsNormal(BlockingPolicy.fromName(null))
        assertEqualsNormal(BlockingPolicy.fromName(""))
        assertEqualsNormal(BlockingPolicy.fromName("turbo"))
        assertTrue(BlockingPolicy.fromName("aggressive") == AdblockMode.AGGRESSIVE)
        assertTrue(BlockingPolicy.fromName("AGGRESSIVE") == AdblockMode.AGGRESSIVE)
        assertEqualsNormal(BlockingPolicy.fromName("normal"))
    }

    private fun assertEqualsNormal(mode: AdblockMode) {
        assertTrue("expected NORMAL, got $mode", mode == AdblockMode.NORMAL)
    }
}
