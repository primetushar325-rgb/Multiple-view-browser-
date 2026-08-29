package com.example.multiview

import com.example.multiview.panes.SitePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePresetsTest {

    @Test fun everyPresetIsAnHttpsUrl() {
        SitePresets.ALL.forEach { preset ->
            assertTrue("${preset.name} must be https", preset.url.startsWith("https://"))
            assertTrue("${preset.name} must have a host", preset.url.removePrefix("https://").length > 4)
        }
    }

    @Test fun noDuplicateUrls() {
        val urls = SitePresets.ALL.map { it.url }
        assertEquals("duplicate preset URLs", urls.size, urls.toSet().size)
    }

    @Test fun noDuplicateNames() {
        val names = SitePresets.ALL.map { it.name }
        assertEquals("duplicate preset names", names.size, names.toSet().size)
    }

    @Test fun iconsAreDistinct() {
        val icons = SitePresets.ALL.map { it.iconRes }
        assertEquals("two presets share an icon", icons.size, icons.toSet().size)
        icons.forEach { assertNotEquals(0, it) }
    }

    @Test fun theCoreSitesArePresent() {
        val urls = SitePresets.ALL.map { it.url }.toSet()
        listOf(
            "https://mail.google.com",
            "https://www.youtube.com",
            "https://www.facebook.com",
            "https://x.com",
            "https://www.instagram.com",
            "https://web.whatsapp.com",
            "https://www.messenger.com",
        ).forEach { assertTrue("missing preset $it", it in urls) }
    }

    @Test fun lookupByNameWorks() {
        assertEquals("https://mail.google.com", SitePresets.byName("Gmail")?.url)
        assertEquals(null, SitePresets.byName("Not A Site"))
    }

    @Test fun namesAreNotBlank() {
        SitePresets.ALL.forEach { assertTrue(it.name.isNotBlank()) }
        assertFalse(SitePresets.ALL.isEmpty())
    }
}
