package org.app.kdownloader.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadTask

internal actual fun defaultHttpClient(): HttpClient = HttpClient(Js)

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun defaultNetworkMonitor(): NetworkMonitor = NetworkMonitor { true }

actual fun defaultStorage(): DownloadStorage = BrowserDownloadStorage()
