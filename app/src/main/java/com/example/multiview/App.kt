package com.example.multiview

import android.app.Application
import com.example.multiview.di.AppContainer

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Convenience accessor so activities do not cast the application everywhere. */
val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as App).container
