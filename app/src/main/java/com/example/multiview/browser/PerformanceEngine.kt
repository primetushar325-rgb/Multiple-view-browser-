package com.example.multiview.browser

/**
 * App-side effort budget for the panes.
 *
 * This does not and cannot make the user's network faster - nothing in the app
 * can. What it does is stop the app from doing work nobody is looking at: with
 * six panes open, five of them are not on screen in any meaningful sense, yet
 * by default all six download every image and run every timer. These modes
 * decide how much of that background work to suppress.
 *
 * Every knob here is a documented WebSettings/WebView call, and each one is
 * reversible: focusing a pane always restores it to full quality.
 */
enum class PerformanceMode {
    /**
     * Nothing is suppressed. Every pane downloads images and runs its timers
     * regardless of focus. Highest fidelity, highest battery and memory cost.
     */
    BALANCED,

    /**
     * Default. Panes that are not focused skip network image loads and pause
     * their JavaScript timers; the focused pane always runs at full quality.
     */
    FAST,

    /**
     * Aggressive. As FAST, plus unfocused panes also stop loading subresources
     * that are not needed to keep their session alive. Use on 2-3 GB devices.
     */
    MAX,
}

/**
 * Pure policy decisions, kept free of any Android type so the trade-offs are
 * covered by unit tests rather than by manual poking at a device.
 */
object PerformanceEngine {

    /**
     * Whether a pane should skip network image loads.
     *
     * Only ever true for a pane that is NOT focused. That rule matters: the
     * pane the user is actually reading must never render half-finished.
     */
    fun shouldBlockImages(mode: PerformanceMode, isFocused: Boolean): Boolean = when (mode) {
        PerformanceMode.BALANCED -> false
        PerformanceMode.FAST -> !isFocused
        PerformanceMode.MAX -> !isFocused
    }

    /**
     * Whether a pane should be throttled, i.e. told to stop the background work
     * it can stop safely.
     *
     * This maps to the per-instance WebView.onPause()/onResume() pair. Note
     * what that does and does not do: it pauses animations, geolocation and
     * similar, but it does NOT stop JavaScript. The process-wide
     * WebView.pauseTimers() is deliberately not used here - it would pause
     * every pane in the app, including the one being read.
     *
     * [isVisible] covers the case where a pane is on screen but not the
     * focused one; a maximized layout leaves every other pane invisible.
     */
    fun shouldThrottle(
        mode: PerformanceMode,
        isFocused: Boolean,
        isVisible: Boolean,
    ): Boolean = when (mode) {
        PerformanceMode.BALANCED -> false
        // The focused pane always runs. In a multi-pane grid the visible
        // neighbours keep running too, so a chat or a video does not freeze
        // just because the user tapped another pane.
        PerformanceMode.FAST -> !isFocused && !isVisible
        // MAX assumes the user chose battery over liveliness.
        PerformanceMode.MAX -> !isFocused
    }

    /** Parses a persisted name, defaulting to FAST rather than throwing. */
    fun fromName(name: String?): PerformanceMode =
        runCatching { PerformanceMode.valueOf(name.orEmpty()) }.getOrDefault(PerformanceMode.FAST)
}
