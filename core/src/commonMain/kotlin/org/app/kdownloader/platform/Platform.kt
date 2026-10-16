package org.app.kdownloader.platform

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher

/** An http client with the platform's engine. */
internal expect fun defaultHttpClient(): HttpClient

/** Dispatcher for blocking file and network work. */
internal expect val ioDispatcher: CoroutineDispatcher
