package com.example.multiview.panes

/**
 * Gives screens that live outside [MainActivity] (currently Settings'
 * "Pane Accounts" section) a handle to the live [PaneManager].
 *
 * Set once in MainActivity.onCreate and cleared in onDestroy, so it never
 * outlives the activity and cannot leak a dead manager.
 */
object PaneRegistry {
    @Volatile
    var manager: PaneManager? = null
}
