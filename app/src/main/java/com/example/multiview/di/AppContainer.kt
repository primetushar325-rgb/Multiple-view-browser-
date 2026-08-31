package com.example.multiview.di

import android.content.Context
import com.example.multiview.data.BlocklistRepo
import com.example.multiview.data.HistoryRepo
import com.example.multiview.data.PanesRepo
import com.example.multiview.data.SettingsRepo

/** Hand-rolled DI. Three repositories, one instance each, no framework. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsRepo by lazy { SettingsRepo(appContext) }
    val panes: PanesRepo by lazy { PanesRepo(appContext) }
    val blocklist: BlocklistRepo by lazy { BlocklistRepo(appContext) }
    val history: HistoryRepo by lazy { HistoryRepo(appContext) }
}
