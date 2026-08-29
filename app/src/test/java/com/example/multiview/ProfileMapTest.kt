package com.example.multiview

import com.example.multiview.data.ProfileMode
import com.example.multiview.panes.ProfilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMapTest {

    @Test fun profileIdsAreStableAcrossRestarts() {
        // The id is derived from the pane index, so pane 3 always maps to the
        // same cookie store and its login survives a restart.
        repeat(3) {
            assertEquals("mv-pane-3", ProfilePlan.profileIdFor(3))
        }
        assertEquals("mv-pane-0", ProfilePlan.profileIdFor(0))
        assertEquals("mv-pane-7", ProfilePlan.profileIdFor(7))
    }

    @Test fun profileIdsAreDistinctPerPane() {
        val ids = (0..7).map { ProfilePlan.profileIdFor(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun modeMapRoundTrips() {
        val original = mapOf(
            0 to ProfileMode.SHARED,
            1 to ProfileMode.ISOLATED,
            2 to ProfileMode.ISOLATED,
            5 to ProfileMode.SHARED,
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
        val json = """[{"i":0,"m":"NOT_A_MODE"}]"""
        assertEquals(ProfileMode.SHARED, ProfilePlan.decode(json)[0])
    }

    @Test fun negativeIndicesAreIgnored() {
        assertTrue(ProfilePlan.decode("""[{"i":-1,"m":"ISOLATED"}]""").isEmpty())
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
        assertNull(ProfilePlan.profileNameFor(2, ProfileMode.SHARED, true))
        assertNull(ProfilePlan.profileNameFor(2, ProfileMode.ISOLATED, false))
        assertEquals("mv-pane-2", ProfilePlan.profileNameFor(2, ProfileMode.ISOLATED, true))
    }
}
