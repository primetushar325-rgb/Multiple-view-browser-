package com.example.multiview.panes

import com.example.multiview.R

/** One entry in the site picker. */
data class SitePreset(val name: String, val url: String, val iconRes: Int)

/**
 * The preset list. Edit this one file to change what the picker offers; the
 * sheet, the icons and the URLs are all derived from here.
 */
object SitePresets {

    val ALL: List<SitePreset> = listOf(
        SitePreset("Gmail", "https://mail.google.com", R.drawable.ic_site_gmail),
        SitePreset("YouTube", "https://www.youtube.com", R.drawable.ic_site_youtube),
        SitePreset("Facebook", "https://www.facebook.com", R.drawable.ic_site_facebook),
        SitePreset("X (Twitter)", "https://x.com", R.drawable.ic_site_x),
        SitePreset("Instagram", "https://www.instagram.com", R.drawable.ic_site_instagram),
        SitePreset("WhatsApp Web", "https://web.whatsapp.com", R.drawable.ic_site_whatsapp),
        SitePreset("Messenger", "https://www.messenger.com", R.drawable.ic_site_messenger),
        SitePreset("TikTok", "https://www.tiktok.com", R.drawable.ic_site_tiktok),
        SitePreset("Google Drive", "https://drive.google.com", R.drawable.ic_site_drive),
        SitePreset("Google Search", "https://www.google.com", R.drawable.ic_site_search),
    )

    fun byName(name: String): SitePreset? = ALL.firstOrNull { it.name == name }
}
