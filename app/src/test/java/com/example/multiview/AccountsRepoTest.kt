package com.example.multiview

import com.example.multiview.data.AccountsJson
import com.example.multiview.data.AccountsRepo
import com.example.multiview.data.GoogleAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the new pure account->pane mapping and the slot/JSON codec. These are
 * the parts of the saved-account pool that are worth locking down: the ordering
 * promise (pane N gets account slot N) and that slots are never reused.
 */
class AccountsRepoTest {

    private fun account(n: Int) = GoogleAccount(AccountsRepo.slotIdFor(n), "user$n@gmail.com", n.toLong())

    @Test fun slotIdsAreSequentialAndPrefixed() {
        assertEquals("mv-account-1", AccountsRepo.slotIdFor(1))
        assertEquals("mv-account-2", AccountsRepo.slotIdFor(2))
        assertEquals("mv-account-10", AccountsRepo.slotIdFor(10))
    }

    @Test fun nextSlotNumberIsOnePastTheHighestExisting() {
        assertEquals(1, AccountsRepo.nextSlotNumber(emptyList()))
        assertEquals(4, AccountsRepo.nextSlotNumber(listOf(account(1), account(2), account(3))))
        // A removed middle slot must NOT be reused: highest is 3 -> next is 4.
        assertEquals(4, AccountsRepo.nextSlotNumber(listOf(account(1), account(3))))
    }

    @Test fun assignsOneAccountPerPaneInOrder() {
        val accounts = listOf(account(1), account(2), account(3))
        val assigned = AccountsRepo.assignToPanes(accounts, 3)
        assertEquals(3, assigned.size)
        assertEquals("mv-account-1", assigned[0]?.slotId)
        assertEquals("mv-account-2", assigned[1]?.slotId)
        assertEquals("mv-account-3", assigned[2]?.slotId)
    }

    @Test fun panesBeyondSavedAccountsMapToNull() {
        val accounts = listOf(account(1), account(2))
        val assigned = AccountsRepo.assignToPanes(accounts, 5)
        assertEquals(5, assigned.size)
        assertEquals("mv-account-1", assigned[0]?.slotId)
        assertEquals("mv-account-2", assigned[1]?.slotId)
        assertNull(assigned[2])
        assertNull(assigned[3])
        assertNull(assigned[4])
    }

    @Test fun extraAccountsBeyondPaneCountAreUnused() {
        val accounts = listOf(account(1), account(2), account(3), account(4))
        val assigned = AccountsRepo.assignToPanes(accounts, 2)
        assertEquals(2, assigned.size)
        assertEquals("mv-account-1", assigned[0]?.slotId)
        assertEquals("mv-account-2", assigned[1]?.slotId)
    }

    @Test fun zeroOrNegativePaneCountYieldsEmptyAssignment() {
        assertTrue(AccountsRepo.assignToPanes(listOf(account(1)), 0).isEmpty())
        assertTrue(AccountsRepo.assignToPanes(listOf(account(1)), -3).isEmpty())
    }

    @Test fun jsonRoundTripsTheAccountList() {
        val original = listOf(account(1), account(2), account(3))
        val restored = AccountsJson.decode(AccountsJson.encode(original))
        assertEquals(original, restored)
    }

    @Test fun jsonDecodeIsSafeOnGarbage() {
        assertTrue(AccountsJson.decode(null).isEmpty())
        assertTrue(AccountsJson.decode("").isEmpty())
        assertTrue(AccountsJson.decode("not json").isEmpty())
    }
}
