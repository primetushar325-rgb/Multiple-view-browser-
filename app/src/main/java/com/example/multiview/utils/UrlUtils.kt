package com.example.multiview.utils

import java.net.URLEncoder

/**
 * Pure URL/search classification. Deliberately free of android.net.Uri so it
 * can be unit-tested on the JVM without Robolectric.
 */
object UrlUtils {

    const val SEARCH_TEMPLATE = "https://www.google.com/search?q="

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val HOST_REGEX =
        Regex("^[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?)+(:\\d+)?(/\\S*)?$")

    /** Schemes the WebView itself can render; anything else goes to an external app. */
    private val INTERNAL_SCHEMES =
        setOf("http", "https", "about", "data", "file", "blob", "javascript")

    fun schemeOf(url: String): String {
        val m = SCHEME_REGEX.find(url.trim()) ?: return ""
        return m.value.removeSuffix(":").lowercase()
    }

    /** Only these schemes carry an authority component; the rest are opaque. */
    private val HIERARCHICAL_SCHEMES = setOf("http", "https", "file", "ftp", "ws", "wss")

    fun hostOf(url: String): String {
        var s = url.trim()
        val m = SCHEME_REGEX.find(s)
        if (m != null) {
            val scheme = m.value.removeSuffix(":").lowercase()
            // about:blank, data:, javascript:, mailto: - no host to extract,
            // and returning the opaque part would make "blank" look like a host.
            if (scheme !in HIERARCHICAL_SCHEMES) return ""
            s = s.substring(m.range.last + 1)
        }
        while (s.startsWith("/")) s = s.substring(1)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@', s)
        return s.substringBefore(':').lowercase().trimEnd('.')
    }

    fun isProbablyUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty()) return false
        if (s.any { it.isWhitespace() }) return false
        if (SCHEME_REGEX.containsMatchIn(s)) return true
        if (s.startsWith("localhost", ignoreCase = true)) return true
        return HOST_REGEX.matches(s)
    }

    /** True when the scheme must be handed to another app (tel:, intent:, market:, ...). */
    fun isExternalScheme(url: String): Boolean {
        val scheme = schemeOf(url)
        if (scheme.isEmpty()) return false
        return scheme !in INTERNAL_SCHEMES
    }

    fun searchUrl(query: String): String =
        SEARCH_TEMPLATE + URLEncoder.encode(query.trim(), "UTF-8")

    /**
     * Turns free-form user input into something loadable:
     *  - already schemed   -> unchanged
     *  - looks like a host -> https:// prepended
     *  - anything else     -> a web search
     */
    fun normalize(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (SCHEME_REGEX.containsMatchIn(s)) return s
        return if (isProbablyUrl(s)) "https://$s" else searchUrl(s)
    }

    /** Presets are stored host-only so they stay editable; expand to a real URL. */
    fun presetToUrl(preset: String): String {
        val s = preset.trim()
        return if (SCHEME_REGEX.containsMatchIn(s)) s else "https://$s"
    }
}
