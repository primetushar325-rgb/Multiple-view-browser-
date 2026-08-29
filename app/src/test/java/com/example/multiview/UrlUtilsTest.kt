package com.example.multiview

import com.example.multiview.utils.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test fun recognisesUrls() {
        assertTrue(UrlUtils.isProbablyUrl("example.com"))
        assertTrue(UrlUtils.isProbablyUrl("mail.google.com/mail"))
        assertTrue(UrlUtils.isProbablyUrl("sub.domain.co.uk:8080/path?q=1"))
        assertTrue(UrlUtils.isProbablyUrl("https://example.com"))
    }

    @Test fun treatsPlainTextAsSearch() {
        assertFalse(UrlUtils.isProbablyUrl("best pizza near me"))
        assertFalse(UrlUtils.isProbablyUrl("how to tie a knot"))
        assertFalse(UrlUtils.isProbablyUrl(""))
    }

    @Test fun prependsHttpsToBareHosts() {
        assertEquals("https://mail.google.com", UrlUtils.normalize("mail.google.com"))
    }

    @Test fun leavesExistingSchemesAlone() {
        assertEquals("http://example.com", UrlUtils.normalize("http://example.com"))
        assertEquals("tel:+15551234", UrlUtils.normalize("tel:+15551234"))
    }

    @Test fun searchesWhenInputIsNotAUrl() {
        assertEquals("https://www.google.com/search?q=hello+world", UrlUtils.normalize("hello world"))
    }

    @Test fun blankInputBecomesAboutBlank() {
        assertEquals("about:blank", UrlUtils.normalize("   "))
    }

    @Test fun presetExpansion() {
        assertEquals("https://mail.google.com", UrlUtils.presetToUrl("mail.google.com"))
        assertEquals("https://web.whatsapp.com", UrlUtils.presetToUrl("https://web.whatsapp.com"))
    }

    @Test fun classifiesExternalSchemes() {
        listOf("tel:", "mailto:", "market://", "intent://", "whatsapp://", "tg:").forEach { scheme ->
            assertTrue("$scheme should be external", UrlUtils.isExternalScheme(scheme + "something"))
        }
        listOf("https://a.com", "http://a.com", "about:blank", "data:text/html,x", "javascript:void(0)")
            .forEach { url -> assertFalse("$url should be internal", UrlUtils.isExternalScheme(url)) }
    }

    @Test fun schemelessInputIsNotExternal() {
        assertFalse(UrlUtils.isExternalScheme("example.com"))
    }

    @Test fun extractsHostFromMessyUrls() {
        assertEquals("example.com", UrlUtils.hostOf("https://user:pass@Example.com:8443/a/b?c=d#e"))
        assertEquals("mail.google.com", UrlUtils.hostOf("HTTPS://MAIL.GOOGLE.COM/mail/"))
        assertEquals("a.com", UrlUtils.hostOf("a.com"))
        assertEquals("", UrlUtils.hostOf("about:blank"))
    }

    @Test fun hostHasNoTrailingDot() {
        assertEquals("example.com", UrlUtils.hostOf("https://example.com./x"))
    }

    @Test fun schemeExtraction() {
        assertEquals("https", UrlUtils.schemeOf("HTTPS://a.com"))
        assertEquals("intent", UrlUtils.schemeOf("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("", UrlUtils.schemeOf("example.com"))
    }
}
