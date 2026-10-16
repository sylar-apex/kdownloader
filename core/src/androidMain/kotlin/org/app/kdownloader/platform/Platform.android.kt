package org.app.kdownloader.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.app.kdownloader.KDownloaderAndroid

internal actual fun defaultHttpClient(): HttpClient = HttpClient(CIO) { followRedirects = true }

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun defaultNetworkMonitor(): NetworkMonitor = NetworkMonitor {
    val manager = KDownloaderAndroid.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return@NetworkMonitor false
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

actual fun defaultStorage(): DownloadStorage = AndroidDownloadStorage(KDownloaderAndroid.context)
