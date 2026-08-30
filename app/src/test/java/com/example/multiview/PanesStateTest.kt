package com.example.multiview

import com.example.multiview.data.PaneIdentity
import com.example.multiview.data.PaneState
import com.example.multiview.data.PaneStateJson
import com.example.multiview.data.PanesSnapshot
import com.example.multiview.data.ProfileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanesStateTest {

    /** Deterministic fixture: explicit ids so round-trip equality is meaningful. */
    private fun pane(
        url: String,
        title: String = "",
        mode: ProfileMode = ProfileMode.SHARED,
        id: String = "pane-${url.hashCode()}",
    ) = PaneState(
        paneId = id,
        profileId = "profile_$id",
        url = url,
        title = title,
        profileMode = mode,
        desktopMode = false,
        accountEmail = "",
        createdAt = 0L,
    )

    @Test fun roundTripsThePaneList() {
        val original = PanesSnapshot(
            panes = listOf(
                pane("https://mail.google.com", "Gmail", ProfileMode.SHARED),
                pane("https://www.youtube.com", "YouTube", ProfileMode.ISOLATED),
                pane("https://web.whatsapp.com", "", ProfileMode.ISOLATED),
            ),
            focusedIndex = 1,
        )
        val restored = PaneStateJson.decode(PaneStateJson.encode(original))
        assertEquals(original, restored)
    }

    @Test fun preservesPaneOrder() {
        val urls = (1..8).map { "https://site$it.example.com" }
        val snapshot = PanesSnapshot(urls.map { pane(it) }, 0)
        val restored = PaneStateJson.decode(PaneStateJson.encode(snapshot))
        assertEquals(urls, restored.panes.map { it.url })
    }

    /** The ids are the point of the refactor, so they must survive a restart. */
    @Test fun paneAndProfileIdsSurviveTheRoundTrip() {
        val original = PanesSnapshot(
            panes = listOf(
                pane("https://a.com", id = "pane-a"),
                pane("https://b.com", id = "pane-b"),
            ),
            focusedIndex = 0,
        )
        val restored = PaneStateJson.decode(PaneStateJson.encode(original))
        assertEquals(listOf("pane-a", "pane-b"), restored.panes.map { it.paneId })
        assertEquals(
            listOf("profile_pane-a", "profile_pane-b"),
            restored.panes.map { it.profileId },
        )
        assertEquals(PaneIdentity("pane-a", "profile_pane-a"), restored.panes[0].identity)
    }

    @Test fun persistsTheFocusedIndex() {
        val snapshot = PanesSnapshot(listOf(pane("https://a.com"), pane("https://b.com")), 1)
        assertEquals(1, PaneStateJson.decode(PaneStateJson.encode(snapshot)).focusedIndex)
    }

    @Test fun clampsAFocusedIndexThatNoLongerExists() {
        val json = PaneStateJson.encode(PanesSnapshot(listOf(pane("https://a.com")), 5))
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

    /**
     * Payloads written by the previous release carry no stable ids. They must
     * migrate onto the original `mv-pane-<index>` profile name so an upgraded
     * install keeps the Google sessions it already had, and the migration must
     * be deterministic so repeated decodes agree.
     */
    @Test fun legacyPayloadsMigrateFromPositionDeterministically() {
        val legacy = """{"panes":[{"url":"https://a.com"},{"url":"https://b.com"}],"focused":0}"""
        val first = PaneStateJson.decode(legacy)
        val second = PaneStateJson.decode(legacy)
        assertEquals(listOf("mv-pane-0", "mv-pane-1"), first.panes.map { it.profileId })
        assertEquals(first.panes.map { it.paneId }, second.panes.map { it.paneId })
    }

    /** Two panes on one profile would share a login, so a clash is rejected. */
    @Test fun duplicatePaneIdsAreResolvedInsteadOfShared() {
        val json = """{"panes":[
            {"id":"same","pid":"profile_same","url":"https://a.com"},
            {"id":"same","pid":"profile_same","url":"https://b.com"}
        ],"focused":0}"""
        val restored = PaneStateJson.decode(json)
        assertEquals(2, restored.panes.size)
        assertEquals(
            "both panes must end up on distinct profiles",
            2,
            restored.panes.map { it.profileId }.toSet().size,
        )
    }

    @Test fun desktopEmailAndCreatedAtSurvive() {
        val original = PanesSnapshot(
            panes = listOf(
                PaneState(
                    paneId = "pane-a",
                    profileId = "profile_a",
                    url = "https://mail.google.com",
                    title = "Gmail",
                    profileMode = ProfileMode.ISOLATED,
                    desktopMode = true,
                    accountEmail = "user@example.com",
                    createdAt = 1_700_000_000_000L,
                ),
            ),
            focusedIndex = 0,
        )
        assertEquals(original, PaneStateJson.decode(PaneStateJson.encode(original)))
    }

    @Test fun unicodeTitlesSurvive() {
        val snapshot = PanesSnapshot(listOf(pane("https://a.com", "ইনবক্স – Gmail")), 0)
        assertEquals("ইনবক্স – Gmail", PaneStateJson.decode(PaneStateJson.encode(snapshot)).panes[0].title)
    }
}
