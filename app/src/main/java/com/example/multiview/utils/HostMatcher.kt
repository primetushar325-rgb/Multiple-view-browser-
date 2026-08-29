package com.example.multiview.utils

/**
 * Host / subdomain matching for the ad-blocker.
 *
 * The whitelist is checked FIRST and always wins: the login-critical hosts
 * (Google, YouTube, Facebook, Instagram, WhatsApp) must never be blocked, even
 * if a blocklist entry would otherwise match one of their subdomains.
 */
class HostMatcher(
    blocked: Collection<String>,
    whitelisted: Collection<String> = emptyList(),
) {
    private val blockedSet: Set<String> =
        blocked.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
    private val whiteSet: Set<String> =
        whitelisted.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    val blockedCount: Int get() = blockedSet.size

    /** Exact match, or any subdomain of a listed entry. */
    fun isBlocked(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (matches(whiteSet, h)) return false
        return matches(blockedSet, h)
    }

    fun isBlockedUrl(url: String): Boolean = isBlocked(UrlUtils.hostOf(url))

    private fun matches(entries: Set<String>, host: String): Boolean {
        if (host in entries) return true
        var i = host.indexOf('.')
        while (i in 0 until host.length - 1) {
            if (host.substring(i + 1) in entries) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }

    private fun normalize(host: String): String =
        host.trim().lowercase()
            .removePrefix("http://").removePrefix("https://").removePrefix("www.")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            // "doubleclick.net:443" must still match the bare host entry.
            .substringBefore(':').trimEnd('.')

    companion object {
        /**
         * Hosts that must NEVER be blocked. Login and asset CDNs for the sites
         * MultiView targets; blocking any of these silently breaks sign-in.
         */
        val LOGIN_WHITELIST: List<String> = listOf(
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "gmail.com", "mail.google.com", "accounts.google.com",
            "youtube.com", "ytimg.com", "youtube-nocookie.com",
            "facebook.com", "fb.com", "fbcdn.net", "facebook.net",
            "instagram.com", "cdninstagram.com",
            "whatsapp.com", "whatsapp.net",
        )
    }
}
