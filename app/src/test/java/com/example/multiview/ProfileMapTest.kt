package com.example.multiview

import com.example.multiview.data.PaneIdentity
import com.example.multiview.data.ProfileMode
import com.example.multiview.panes.ProfilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile naming is now keyed by a pane's permanent identity rather than by its
 * position in the list. That distinction is the whole point: the old index-based
 * scheme made closing pane 1 silently rebind every later pane to the previous
 * pane's cookie store, which is how "logged into the wrong account" happened.
 */
class ProfileMapTest {

    @Test fun profileIdsAreStableAcrossRestarts() {
        // Same identity must always resolve to the same cookie store, so a
        // pane's login survives a restart.
        val identity = PaneIdentity("pane-a", "profile_abcdef123456")
        repeat(3) {
            assertEquals(
                "profile_abcdef123456",
                ProfilePlan.profileNameFor(identity, ProfileMode.ISOLATED, true),
            )
        }
    }

    @Test fun profileIdsAreDistinctPerPane() {
        val ids = (1..8).map { PaneIdentity.newIdentity().profileId }
        assertEquals("8 panes must own 8 distinct profiles", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("profile id should be prefixed: $it", it.startsWith("profile_")) }
    }

    /**
     * The regression this whole refactor exists to fix.
     *
     * Under the old scheme pane 2 was "mv-pane-2"; close pane 1 and the old
     * pane 2 becomes index 1 and inherits "mv-pane-1" - someone else's session.
     * Identity is position-independent, so this can no longer happen.
     */
    @Test fun closingAnotherPaneDoesNotMoveAnIdentity() {
        val before = listOf(
            PaneIdentity("pane-1", "profile_aaa"),
            PaneIdentity("pane-2", "profile_bbb"),
            PaneIdentity("pane-3", "profile_ccc"),
        )
        // Survivors after the first pane is closed, at their NEW positions.
        val after = listOf(before[1], before[2])
        after.forEachIndexed { newIndex, identity ->
            assertEquals(
                "identity must not follow the array position",
                identity.profileId,
                ProfilePlan.profileNameFor(identity, ProfileMode.ISOLATED, true),
            )
            assertNotEquals(
                "must not be re-keyed from position $newIndex",
                "mv-pane-$newIndex",
                ProfilePlan.profileNameFor(identity, ProfileMode.ISOLATED, true),
            )
        }
    }

    @Test fun modeMapIsKeyedByPaneIdAndRoundTrips() {
        val original = mapOf(
            "pane-0" to ProfileMode.SHARED,
            "pane-1" to ProfileMode.ISOLATED,
            "pane-2" to ProfileMode.ISOLATED,
            "pane-5" to ProfileMode.SHARED,
        )
        assertEquals(original, ProfilePlan.decode(ProfilePlan.encode(original)))
    }

    @Test fun emptyAndCorruptInputYieldAnEmptyMap() {
        assertTrue(ProfilePlan.decode(null).isEmpty())
        assertTrue(ProfilePlan.decode("").isEmpty())
        assertTrue(ProfilePlan.decode("nonsense").isEmpty())
        assertTrue(ProfilePlan.decode("{}").isEmpty())
    }

    @Test fun unknownModeDegradesToShared() {
        val json = """[{"id":"pane-0","m":"NOT_A_MODE"}]"""
        assertEquals(ProfileMode.SHARED, ProfilePlan.decode(json)["pane-0"])
    }

    @Test fun entriesWithoutAnIdAreIgnored() {
        assertTrue(ProfilePlan.decode("""[{"m":"ISOLATED"}]""").isEmpty())
        assertTrue(ProfilePlan.decode("""[{"id":"","m":"ISOLATED"}]""").isEmpty())
    }

    @Test fun requestsIsolationWhenSupported() {
        assertEquals(ProfileMode.ISOLATED, ProfilePlan.effectiveMode(ProfileMode.ISOLATED, true))
    }

    @Test fun fallsBackToSharedWhenProfileStoreIsMissing() {
        assertEquals(ProfileMode.SHARED, ProfilePlan.effectiveMode(ProfileMode.ISOLATED, false))
    }

    @Test fun sharedStaysSharedEitherWay() {
        assertEquals(ProfileMode.SHARED, ProfilePlan.effectiveMode(ProfileMode.SHARED, true))
        assertEquals(ProfileMode.SHARED, ProfilePlan.effectiveMode(ProfileMode.SHARED, false))
    }

    @Test fun profileNameIsNullForSharedPanes() {
        val identity = PaneIdentity("pane-2", "mv-pane-2")
        assertNull(ProfilePlan.profileNameFor(identity, ProfileMode.SHARED, true))
        assertNull(ProfilePlan.profileNameFor(identity, ProfileMode.ISOLATED, false))
        assertEquals("mv-pane-2", ProfilePlan.profileNameFor(identity, ProfileMode.ISOLATED, true))
    }

    /**
     * State saved before stable ids existed must migrate onto the ORIGINAL
     * `mv-pane-<index>` name, because on an upgraded install those profiles
     * already hold real Google sessions. A fresh UUID here would sign every
     * existing user out on first launch after the update.
     */
    @Test fun legacyStateMigratesOntoTheOriginalProfileName() {
        assertEquals("mv-pane-0", PaneIdentity.fromLegacyPosition(0).profileId)
        assertEquals("mv-pane-3", PaneIdentity.fromLegacyPosition(3).profileId)
        assertEquals("mv-pane-7", PaneIdentity.fromLegacyPosition(7).profileId)
        // Deterministic: repeated decodes of un-migrated state must agree.
        repeat(3) {
            assertEquals(
                PaneIdentity.fromLegacyPosition(3),
                PaneIdentity.fromLegacyPosition(3),
            )
        }
    }
}
