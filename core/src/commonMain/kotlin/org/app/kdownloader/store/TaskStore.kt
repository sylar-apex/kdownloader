package org.app.kdownloader.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.app.kdownloader.model.DownloadTask

/** Keeps the task list across app restarts. */
interface TaskStore {
    fun load(): List<DownloadTask>
    fun save(tasks: List<DownloadTask>)
}

/** Nothing survives a restart. The default. */
class MemoryTaskStore : TaskStore {
    private var tasks: List<DownloadTask> = emptyList()
    override fun load() = tasks
    override fun save(tasks: List<DownloadTask>) { this.tasks = tasks }
}

/** Stores the list as one json string through [read] and [write], e.g. a preferences entry. */
class JsonTaskStore(private val read: () -> String?, private val write: (String) -> Unit) : TaskStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(DownloadTask.serializer())

    override fun load(): List<DownloadTask> =
        read()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    override fun save(tasks: List<DownloadTask>) = write(json.encodeToString(serializer, tasks))
}
