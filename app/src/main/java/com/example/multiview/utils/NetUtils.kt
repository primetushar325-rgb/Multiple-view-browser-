package com.example.multiview.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The only place in the app that touches ConnectivityManager. */
object NetUtils {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
