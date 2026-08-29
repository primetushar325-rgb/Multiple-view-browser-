package com.example.multiview.data

import android.content.Context
import com.example.multiview.utils.HostMatcher
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads assets/blocklist.txt once and hands out a [HostMatcher] that already
 * knows the login-critical whitelist. Also counts blocked requests so the
 * Settings screen can show a live number.
 */
class BlocklistRepo(private val context: Context) {

    @Volatile
    private var matcher: HostMatcher? = null

    private val blockedRequests = AtomicInteger(0)

    val blockedCount: Int get() = blockedRequests.get()

    fun matcher(): HostMatcher {
        matcher?.let { return it }
        synchronized(this) {
            matcher?.let { return it }
            val hosts = runCatching {
                context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            }.getOrDefault(emptyList())
            return HostMatcher(hosts, HostMatcher.LOGIN_WHITELIST).also { matcher = it }
        }
    }

    fun recordBlocked() {
        blockedRequests.incrementAndGet()
    }

    fun resetCount() {
        blockedRequests.set(0)
    }
}
