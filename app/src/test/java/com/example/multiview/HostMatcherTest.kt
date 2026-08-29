package com.example.multiview

import com.example.multiview.utils.HostMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMatcherTest {

    private val matcher = HostMatcher(
        blocked = listOf("doubleclick.net", "googlesyndication.com", "scorecardresearch.com"),
        whitelisted = HostMatcher.LOGIN_WHITELIST,
    )

    @Test fun blocksExactHost() {
        assertTrue(matcher.isBlocked("doubleclick.net"))
    }

    @Test fun blocksSubdomain() {
        assertTrue(matcher.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(matcher.isBlocked("a.b.c.scorecardresearch.com"))
    }

    @Test fun doesNotBlockUnrelatedHost() {
        assertFalse(matcher.isBlocked("example.com"))
        assertFalse(matcher.isBlocked("notdoubleclick.net"))
    }

    @Test fun doesNotBlockSuperdomainOfEntry() {
        // "net" is a suffix of the entry, not a subdomain of it.
        assertFalse(matcher.isBlocked("net"))
        assertFalse(matcher.isBlocked("syndication.com"))
    }

    @Test fun whitelistAlwaysWins() {
        val hostile = HostMatcher(
            blocked = listOf("google.com", "youtube.com", "facebook.com", "gmail.com"),
            whitelisted = HostMatcher.LOGIN_WHITELIST,
        )
        // Even with the login hosts explicitly blocked, they must stay reachable,
        // otherwise sign-in dies with no visible error.
        HostMatcher.LOGIN_WHITELIST.forEach { assertFalse("$it must never be blocked", hostile.isBlocked(it)) }
        assertFalse(hostile.isBlocked("mail.google.com"))
        assertFalse(hostile.isBlocked("accounts.google.com"))
        assertFalse(hostile.isBlocked("s.ytimg.com"))
        assertFalse(hostile.isBlocked("static.xx.fbcdn.net"))
        assertFalse(hostile.isBlocked("www.instagram.com"))
        assertFalse(hostile.isBlocked("web.whatsapp.com"))
    }

    @Test fun whitelistCoversSubdomainsToo() {
        val hostile = HostMatcher(listOf("googleapis.com"), HostMatcher.LOGIN_WHITELIST)
        assertFalse(hostile.isBlocked("www.googleapis.com"))
        assertFalse(hostile.isBlocked("maps.googleapis.com"))
    }

    @Test fun matchingIsCaseAndWhitespaceInsensitive() {
        assertTrue(matcher.isBlocked("  DOUBLECLICK.NET "))
        assertTrue(matcher.isBlocked("DoubleClick.net"))
    }

    @Test fun trimsSchemeAndWwwAndPath() {
        assertTrue(matcher.isBlocked("http://www.doubleclick.net/ad.js"))
        assertTrue(matcher.isBlocked("https://doubleclick.net:443/x"))
    }

    @Test fun emptyHostIsNeverBlocked() {
        assertFalse(matcher.isBlocked(""))
        assertFalse(matcher.isBlocked("   "))
    }

    @Test fun blocksFromFullUrls() {
        assertTrue(matcher.isBlockedUrl("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertFalse(matcher.isBlockedUrl("https://mail.google.com/mail/u/0/"))
    }

    @Test fun mainFrameAndSubFrameTreatedTheSame() {
        // The blocklist answers at the resource level, so both a navigation and
        // an ad iframe on the same host resolve identically.
        val host = "ads.doubleclick.net"
        assertTrue(matcher.isBlockedUrl("https://$host/"))
        assertTrue(matcher.isBlockedUrl("https://$host/iframe.html"))
    }

    @Test fun shippedBlocklistHasEnoughEntries() {
        assertTrue("expected 300+ hosts", matcher.blockedCount >= 3)
    }
}
