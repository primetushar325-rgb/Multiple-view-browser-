package com.example.multiview.utils

/**
 * How hard the ad-blocker pushes.
 *
 * NORMAL (default) blocks only ad/tracker SUBRESOURCES, so the page itself,
 * its streams and its login flows keep working. AGGRESSIVE additionally blocks
 * whole navigations to known ad/tracker domains - stronger, but it can break
 * sites that serve content and ads from the same host, so it is opt-in.
 */
enum class AdblockMode { NORMAL, AGGRESSIVE }

/**
 * Pure selectivity rules, kept free of Android types so they are pinned by
 * unit tests. The blocklist match itself lives in [HostMatcher]; this decides
 * what a match is allowed to stop.
 */
object BlockingPolicy {

    /**
     * Requests that must never be intercepted, whatever the mode.
     *
     * WebSockets and server-sent events are the transport for chat, live
     * streaming and realtime mail; answering them with an empty body does not
     * "block an ad", it kills the feature.
     */
    fun isProtected(scheme: String, acceptHeader: String): Boolean =
        scheme == "ws" || scheme == "wss" ||
            acceptHeader.contains("text/event-stream", ignoreCase = true)

    /**
     * @param matched     the host is on the blocklist (and not whitelisted)
     * @param isMainFrame this request is the page navigation itself
     * @param protected   [isProtected] said yes
     */
    fun shouldBlock(
        mode: AdblockMode,
        matched: Boolean,
        isMainFrame: Boolean,
        protected: Boolean,
    ): Boolean {
        if (!matched || protected) return false
        return when (mode) {
            // Never block the page itself in normal mode: a blocked iframe or
            // script disappears, but the site the user asked for still loads.
            AdblockMode.NORMAL -> !isMainFrame
            AdblockMode.AGGRESSIVE -> true
        }
    }

    /** Parses the persisted name, defaulting to NORMAL rather than throwing. */
    fun fromName(name: String?): AdblockMode =
        runCatching { AdblockMode.valueOf(name.orEmpty().uppercase()) }
            .getOrDefault(AdblockMode.NORMAL)
}
