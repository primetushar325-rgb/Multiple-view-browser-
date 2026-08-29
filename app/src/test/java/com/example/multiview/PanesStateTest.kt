package com.example.multiview

import com.example.multiview.data.PaneState
import com.example.multiview.data.PaneStateJson
import com.example.multiview.data.PanesSnapshot
import com.example.multiview.data.ProfileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanesStateTest {

    @Test fun roundTripsThePaneList() {
        val original = PanesSnapshot(
            panes = listOf(
                PaneState("https://mail.google.com", "Gmail", ProfileMode.SHARED),
                PaneState("https://www.youtube.com", "YouTube", ProfileMode.ISOLATED),
                PaneState("https://web.whatsapp.com", "", ProfileMode.ISOLATED),
            ),
            focusedIndex = 1,
        )
        val restored = PaneStateJson.decode(PaneStateJson.encode(original))
        assertEquals(original, restored)
    }

    @Test fun preservesPaneOrder() {
        val urls = (1..8).map { "https://site$it.example.com" }
        val snapshot = PanesSnapshot(urls.map { PaneState(it) }, 0)
        val restored = PaneStateJson.decode(PaneStateJson.encode(snapshot))
        assertEquals(urls, restored.panes.map { it.url })
    }

    @Test fun persistsTheFocusedIndex() {
        val snapshot = PanesSnapshot(listOf(PaneState("https://a.com"), PaneState("https://b.com")), 1)
        assertEquals(1, PaneStateJson.decode(PaneStateJson.encode(snapshot)).focusedIndex)
    }

    @Test fun clampsAFocusedIndexThatNoLongerExists() {
        val json = PaneStateJson.encode(PanesSnapshot(listOf(PaneState("https://a.com")), 5))
        val restored = PaneStateJson.decode(json)
        assertTrue(restored.focusedIndex in 0..restored.panes.size - 1)
    }

    @Test fun emptySnapshotRoundTrips() {
        val restored = PaneStateJson.decode(PaneStateJson.encode(PanesSnapshot.EMPTY))
        assertTrue(restored.panes.isEmpty())
        assertEquals(0, restored.focusedIndex)
    }

    @Test fun nullAndBlankDecodeToEmpty() {
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode(null))
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode(""))
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode("   "))
    }

    @Test fun corruptJsonDegradesToEmptyInsteadOfThrowing() {
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode("{not json"))
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode("[]"))
        assertEquals(PanesSnapshot.EMPTY, PaneStateJson.decode("{\"panes\":\"nope\"}"))
    }

    @Test fun unknownProfileModeFallsBackToShared() {
        val json = """{"panes":[{"url":"https://a.com","mode":"BOGUS"}],"focused":0}"""
        val restored = PaneStateJson.decode(json)
        assertEquals(ProfileMode.SHARED, restored.panes[0].profileMode)
    }

    @Test fun missingFieldsGetDefaults() {
        val restored = PaneStateJson.decode("""{"panes":[{}],"focused":0}""")
        assertEquals("", restored.panes[0].url)
        assertEquals(ProfileMode.SHARED, restored.panes[0].profileMode)
    }

    @Test fun unicodeTitlesSurvive() {
        val snapshot = PanesSnapshot(listOf(PaneState("https://a.com", "ইনবক্স – Gmail")), 0)
        assertEquals("ইনবক্স – Gmail", PaneStateJson.decode(PaneStateJson.encode(snapshot)).panes[0].title)
    }
}
