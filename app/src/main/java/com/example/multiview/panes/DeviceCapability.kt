package com.example.multiview.panes

import android.app.ActivityManager
import android.content.Context

/**
 * How many panes this device can actually carry, and when to warn the user.
 *
 * Each pane is a full WebView with its own JS heap, so the honest limit is set
 * by physical RAM rather than by what the layout maths can draw. The policy is
 * pure ([paneCapFor], [isMemoryPressure]) so the thresholds are covered by unit
 * tests instead of being discovered by a user's phone freezing.
 */
object DeviceCapability {

    /**
     * Pane ceiling for a device with [totalRamMb] of physical memory.
     *
     * These are deliberately conservative. A 4 GB phone can drive 8 live
     * WebViews comfortably; asking it for 12 just gets panes killed by the
     * renderer, which reads to the user as a broken app.
     */
    fun paneCapFor(totalRamMb: Long): Int = when {
        totalRamMb < 2_048 -> 4
        totalRamMb < 3_072 -> 6
        totalRamMb < 4_096 -> 8
        totalRamMb < 6_144 -> 10
        else -> 12
    }

    /**
     * True when free memory is low enough that opening another pane is likely
     * to cost the user an existing one.
     *
     * Uses a proportion of total RAM rather than a fixed number of megabytes,
     * because 300 MB free means very different things on a 2 GB and an 8 GB
     * device.
     */
    fun isMemoryPressure(availableMb: Long, totalRamMb: Long): Boolean {
        if (totalRamMb <= 0) return false
        return availableMb * 100 < totalRamMb * PRESSURE_PERCENT
    }

    /** Never let the cap exceed what the grid can draw. */
    fun effectiveCap(totalRamMb: Long): Int = minOf(paneCapFor(totalRamMb), LayoutResolver.MAX_PANES)

    const val PRESSURE_PERCENT = 12

    /**
     * Android half: reads real memory figures. Kept thin so the interesting
     * logic above stays testable without a device.
     */
    class Probe(context: Context) {

        private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        /** ActivityManager fills a caller-supplied struct; there is no getter. */
        private val info: ActivityManager.MemoryInfo = runCatching {
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        }.getOrDefault(ActivityManager.MemoryInfo())

        val totalRamMb: Long = info.totalMem / (1024 * 1024)

        val availableMb: Long = info.availMem / (1024 * 1024)

        val isLowRamDevice: Boolean = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)

        /** Pane ceiling for this device; a low-RAM device is capped hardest. */
        fun paneCap(): Int = if (isLowRamDevice) {
            minOf(4, effectiveCap(totalRamMb))
        } else {
            effectiveCap(totalRamMb)
        }

        fun underPressure(): Boolean = isMemoryPressure(availableMb, totalRamMb)
    }
}
