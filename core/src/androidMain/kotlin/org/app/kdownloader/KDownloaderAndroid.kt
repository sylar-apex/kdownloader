package org.app.kdownloader

import android.content.Context

/** Call [init] once from `Application.onCreate` before creating a [KDownloader]. */
object KDownloaderAndroid {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
