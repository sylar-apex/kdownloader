package org.app.kdownloader.platform

/** Whether the current connection is unmetered (Wi-Fi or ethernet). */
fun interface NetworkMonitor {
    fun isUnmetered(): Boolean
}

/** The platform check; true where it cannot be told. */
expect fun defaultNetworkMonitor(): NetworkMonitor
