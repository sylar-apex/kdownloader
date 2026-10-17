package org.app.kdownloader

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.app.kdownloader.engine.DownloadRunner
import org.app.kdownloader.engine.HttpFetcher
import org.app.kdownloader.engine.PartKeys
import org.app.kdownloader.engine.PartRunner
import org.app.kdownloader.engine.ProgressSink
import org.app.kdownloader.engine.TaskHandle
import org.app.kdownloader.engine.QueuePlanner
import org.app.kdownloader.engine.RateLimiter
import org.app.kdownloader.engine.RunOptions
import org.app.kdownloader.engine.SpeedMeter
import org.app.kdownloader.engine.isRetryable
import org.app.kdownloader.model.Destination
import org.app.kdownloader.model.DownloadEvent
import org.app.kdownloader.model.DownloadRequest
import org.app.kdownloader.model.DownloadStatus
import org.app.kdownloader.model.DownloadTask
import org.app.kdownloader.model.DownloaderConfig
import org.app.kdownloader.model.EnqueueResult
import org.app.kdownloader.model.MediaType
import org.app.kdownloader.model.TaskExtras
import org.app.kdownloader.model.title
import org.app.kdownloader.merge.AudioExtractor
import org.app.kdownloader.merge.Merger
import org.app.kdownloader.merge.defaultAudioExtractor
import org.app.kdownloader.merge.defaultMerger
import org.app.kdownloader.platform.DownloadStorage
import org.app.kdownloader.platform.NetworkMonitor
import org.app.kdownloader.platform.defaultHttpClient
import org.app.kdownloader.platform.defaultNetworkMonitor
import org.app.kdownloader.platform.defaultStorage
import org.app.kdownloader.platform.ioDispatcher
import org.app.kdownloader.store.MemoryTaskStore
import org.app.kdownloader.store.TaskStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A download queue for Kotlin Multiplatform: parallel downloads, multi-connection files, pause and
 * resume, priorities, speed limits, retries, tags and scheduled starts. Add files with [enqueue];
 * watch [tasks] for progress and [events] for completions. Tasks survive restarts through [TaskStore].
 *
 * ```
 * val downloader = KDownloader(DownloaderConfig(maxParallel = 3, connections = 4, appFolder = "MyApp"), JsonTaskStore(read, write))
 * downloader.enqueue(DownloadRequest(url, "clip.mp4", Destination(MediaType.VIDEO), priority = 1))
 * ```
 *
 * Pass your own [httpClient] to share an app-wide Ktor client (proxy, logging, certificates).
 *
 * Multi-part downloads ([DownloadRequest.parts]: separate video and audio streams, HLS playlists) are
 * joined by [merger] while the task is [DownloadStatus.MERGING]. [urlRefresher] supplies fresh urls when
 * signed links expire. [audioExtractor] powers [convertToMusic] (Android only by default).
 */
/** Supplies parts (and final name / size) for a task queued without them; null when nothing can be downloaded. */
fun interface TaskPreparer {
    suspend fun prepare(task: DownloadTask): DownloadTask?
}

/** Extra marking a task whose parts [KDownloader.preparer] fills in when it starts. */
const val PREPARE = "kd.prepare"

@OptIn(ExperimentalTime::class)
class KDownloader(
    config: DownloaderConfig = DownloaderConfig(),
    private val store: TaskStore = MemoryTaskStore(),
    private val storage: DownloadStorage = defaultStorage(),
    private val network: NetworkMonitor = defaultNetworkMonitor(),
    httpClient: HttpClient = defaultHttpClient(),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val merger: Merger = defaultMerger(),
    /** Asked for fresh urls of multi-part tasks; may be set after construction. */
    var urlRefresher: UrlRefresher? = null,
    /** Fills in a task queued without parts (marked [PREPARE]) when it starts, e.g. by resolving its formats then. */
    var preparer: TaskPreparer? = null,
    private val audioExtractor: AudioExtractor? = defaultAudioExtractor(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every task mutation runs here, one at a time. */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val fetcher = HttpFetcher(httpClient, config.userAgent)
    private val runner = DownloadRunner(fetcher, storage)
    private val partRunner = PartRunner(fetcher, storage)

    /** Tasks the user resumed: their signed urls may have expired, so they are refreshed before restarting. */
    private val refreshOnStart = mutableSetOf<String>()
    private val jobs = mutableMapOf<String, Job>()
    private val taskLimiters = mutableMapOf<String, RateLimiter>()
    private var counter = 0
    private var lastSaveMs = 0L
    private var wakeJob: Job? = null

    private val _config = MutableStateFlow(config)
    val config: StateFlow<DownloaderConfig> = _config.asStateFlow()

    private val globalLimiter = RateLimiter(clock) { _config.value.speedLimitBytesPerSec }

    private val _tasks = MutableStateFlow(restore(store.load(), config.autoResume))
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _waitingForWifi = MutableStateFlow(false)

    /** True while queued downloads wait because Wi-Fi only is on and the network is metered. */
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    init {
        counter = _tasks.value.size
        serially { persist(); pump() }
    }

    // ---- Configuration ----

    /** Applies new queue settings and starts whatever they now allow. */
    fun updateConfig(config: DownloaderConfig) {
        _config.value = config
        serially { pump() }
    }

    /** The folder downloads of [destination] are saved to, for display. */
    fun locationOf(destination: Destination): String = storage.locationOf(destination, config.value.appFolder)

    // ---- Adding ----

    /** Queues [request] unless the same url is already queued, running or paused. */
    fun enqueue(request: DownloadRequest): EnqueueResult {
        val now = clock()
        var result: EnqueueResult? = null
        _tasks.update { list ->
            val url = request.url.ifBlank { request.parts.firstOrNull()?.url.orEmpty() }
            val existing = list.firstOrNull { it.url == url && it.status.isActive }
            if (existing != null) {
                result = EnqueueResult.Duplicate(existing)
                list
            } else {
                val task = request.toTask("dl-$now-${counter++}", url, now, order = nextOrder(list))
                result = EnqueueResult.Added(task)
                list + task
            }
        }
        if (result is EnqueueResult.Added) serially { persist(); pump() }
        return result!!
    }

    /** Queues several requests at once; returns one result per request, in order. */
    fun enqueueAll(requests: List<DownloadRequest>): List<EnqueueResult> = requests.map(::enqueue)

    // ---- Single-task control ----

    /** Stops [id] and keeps what was downloaded, so [resume] continues from there. */
    fun pause(id: String) = serially { pauseNow(id); persist(); pump() }

    /** Queues a paused or failed task again, resetting its retry count. */
    fun resume(id: String) = serially {
        if (find(id)?.status?.isResumable == true) refreshOnStart += id
        edit(id) { if (status.isResumable) copy(status = DownloadStatus.QUEUED, error = null, attempts = 0) else this }
        persist()
        pump()
    }

    fun retry(id: String) = resume(id)

    /** Stops [id] and deletes its partial file. Finished tasks are left alone. */
    fun cancel(id: String) = serially {
        val task = find(id) ?: return@serially
        if (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.CANCELED) return@serially
        stopJob(id)
        deleteAllParts(task)
        edit(id) { copy(status = DownloadStatus.CANCELED, speedBytesPerSec = 0) }
        persist()
        pump()
    }

    /**
     * Takes tasks off the list. Unfinished ones are stopped and their partial files deleted;
     * finished files stay where they were saved.
     */
    fun remove(ids: Collection<String>) = serially {
        tasks.value.filter { it.id in ids && it.status.isActive }.forEach {
            stopJob(it.id)
            deleteAllParts(it)
        }
        _tasks.update { list -> list.filter { it.id !in ids } }
        persist()
        pump()
    }

    /** Stops and removes [id]; with [withFile] its saved file is deleted too. */
    fun delete(id: String, withFile: Boolean = true) = serially {
        val task = find(id) ?: return@serially
        stopJob(id)
        deleteAllParts(task)
        if (withFile) task.localUri?.let { storage.deleteFile(it) }
        _tasks.update { list -> list.filter { it.id != id } }
        persist()
        pump()
    }

    // ---- Editing ----

    /** Applies [transform] to task [id] and saves it, e.g. to fill in a thumbnail or duration later. */
    fun update(id: String, transform: (DownloadTask) -> DownloadTask) = serially {
        edit(id) { transform(this) }
        persist()
    }

    /** Adds or replaces [extras] of task [id]. */
    fun putExtras(id: String, extras: Map<String, String>) = update(id) { it.copy(extras = it.extras + extras) }

    /**
     * Renames task [id] to [newName] plus its current extension. A saved file is renamed too where the
     * storage can (files, MediaStore); the [TaskExtras.TITLE] extra follows when present.
     */
    fun rename(id: String, newName: String) = serially {
        val task = find(id) ?: return@serially
        val ext = task.fileName.substringAfterLast('.', "")
        val newFileName = if (ext.isEmpty()) newName else "$newName.$ext"
        val uri = task.localUri?.takeIf { task.status == DownloadStatus.COMPLETED }?.let { storage.renameFile(it, newFileName) } ?: task.localUri
        edit(id) {
            copy(fileName = newFileName, localUri = uri, extras = if (TaskExtras.TITLE in extras) extras + (TaskExtras.TITLE to newName) else extras)
        }
        persist()
    }

    /**
     * Private folder: moves a finished file out of shared storage (galleries cannot see it) into app
     * storage, and back. Where the storage cannot move files (web) only [DownloadTask.hidden] changes.
     */
    fun setHidden(id: String, hidden: Boolean): Job = scope.launch(ioDispatcher) {
        val task = find(id) ?: return@launch
        if (task.status != DownloadStatus.COMPLETED || task.hidden == hidden) return@launch
        val cfg = config.value
        val uri = try {
            if (hidden) storage.hide(task) else storage.unhide(task, cfg.appFolder, cfg.conflict)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return@launch
        }
        withContext(serial) {
            edit(id) { copy(hidden = hidden, localUri = uri ?: localUri) }
            persist()
        }
    }

    /**
     * "Convert to music": copies the audio track of finished task [id] into a new M4A (no re-encode) and
     * adds it as a new completed task saved as audio. [fileName] defaults to the title + ".m4a"; [extras]
     * to the source's extras without [TaskExtras.QUALITY]. Returns null when it failed or the platform has
     * no [AudioExtractor] (only Android has one by default).
     */
    suspend fun convertToMusic(id: String, fileName: String? = null, extras: Map<String, String>? = null): DownloadTask? = withContext(ioDispatcher) {
        val extractor = audioExtractor ?: return@withContext null
        val source = find(id) ?: return@withContext null
        val uri = source.localUri ?: return@withContext null
        val now = clock()
        val newId = withContext(serial) { "dl-$now-${counter++}" }
        val work = "$newId.src"
        try {
            if (!storage.importPart(uri, work, 0)) return@withContext null
            val input = storage.localPartPath(work, 0) ?: return@withContext null
            val output = storage.localPartPath(newId, 0) ?: return@withContext null
            extractor.extractAudio(input, output)
            storage.deleteParts(work)
            val size = storage.partSize(newId, 0)
            val name = fileName ?: (source.title.replace(UNSAFE_NAME, "_").take(120) + ".m4a")
            val music = source.copy(
                id = newId,
                url = "$uri#audio",
                fileName = name,
                destination = Destination(MediaType.AUDIO, source.destination.folder),
                status = DownloadStatus.COMPLETED,
                downloadedBytes = size,
                totalBytes = size,
                error = null,
                localUri = null,
                createdAt = now,
                extras = extras ?: (source.extras - TaskExtras.QUALITY + (TaskExtras.EXT to "m4a")),
                segments = 1,
                attempts = 0,
                parts = emptyList(),
                hidden = false,
                finishedAt = now,
            )
            val cfg = config.value
            val published = storage.publish(music, cfg.appFolder, cfg.conflict)
            storage.deleteParts(newId)
            val done = music.copy(localUri = published.uri)
            withContext(serial) {
                _tasks.update { list -> list + done.copy(order = nextOrder(list)) }
                persist()
            }
            done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            storage.deleteParts(work)
            storage.deleteParts(newId)
            null
        }
    }

    // ---- Queue order ----

    /** Higher runs first. A running task keeps running; the change affects what starts next. */
    fun setPriority(id: String, priority: Int) = serially { edit(id) { copy(priority = priority) }; persist(); pump() }

    /** Puts [id] ahead of every task with the same priority. */
    fun moveToTop(id: String) = serially {
        val first = tasks.value.minOfOrNull { it.order } ?: 0
        edit(id) { copy(order = first - 1) }
        persist()
        pump()
    }

    /** Swaps [id] with the queued task just ahead of it (by [up]) or just behind it. */
    fun move(id: String, up: Boolean) = serially {
        val queue = tasks.value.filter { it.status == DownloadStatus.QUEUED || it.id == id }.sortedWith(QueuePlanner.queueOrder)
        val index = queue.indexOfFirst { it.id == id }
        val other = queue.getOrNull(if (up) index - 1 else index + 1) ?: return@serially
        val mine = queue[index]
        edit(mine.id) { copy(order = other.order, priority = other.priority) }
        edit(other.id) { copy(order = mine.order, priority = mine.priority) }
        persist()
        pump()
    }

    /** Caps one task's speed; 0 removes its own cap (the global limit still applies). */
    fun setSpeedLimit(id: String, bytesPerSec: Long) = serially {
        edit(id) { copy(speedLimitBytesPerSec = bytesPerSec.coerceAtLeast(0)) }
        persist()
    }

    // ---- Group control ----

    /** Pauses every queued or running task; returns how many. */
    fun pauseAll(): Int = pauseWhere { true }

    /** Queues every paused or failed task again; returns how many. */
    fun resumeAll(): Int = resumeWhere { true }

    fun pauseTag(tag: String): Int = pauseWhere { tag in it.tags }
    fun resumeTag(tag: String): Int = resumeWhere { tag in it.tags }
    fun removeTag(tag: String) = remove(tasks.value.filter { tag in it.tags }.map { it.id })

    /** Removes finished, failed and canceled tasks from the list; their files stay. */
    fun clearFinished() = remove(tasks.value.filter { !it.status.isActive }.map { it.id })

    fun tasksWithTag(tag: String): List<DownloadTask> = tasks.value.filter { tag in it.tags }

    /** Stops every download. The queue is kept for the next [KDownloader]. */
    fun close() = scope.cancel()

    // ---- Internals ----

    private fun pauseWhere(predicate: (DownloadTask) -> Boolean): Int {
        val active = tasks.value.filter { (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) && predicate(it) }
        serially {
            active.forEach { pauseNow(it.id) }
            persist()
            pump()
        }
        return active.size
    }

    private fun resumeWhere(predicate: (DownloadTask) -> Boolean): Int {
        val paused = tasks.value.filter { it.status.isResumable && predicate(it) }
        serially {
            paused.forEach { task -> refreshOnStart += task.id }
            paused.forEach { task -> edit(task.id) { copy(status = DownloadStatus.QUEUED, error = null, attempts = 0) } }
            persist()
            pump()
        }
        return paused.size
    }

    private fun serially(block: suspend () -> Unit) {
        scope.launch(serial) { block() }
    }

    private fun find(id: String) = tasks.value.firstOrNull { it.id == id }

    private fun edit(id: String, block: DownloadTask.() -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) it.block() else it } }
    }

    private fun pauseNow(id: String) {
        val status = find(id)?.status ?: return
        if (status != DownloadStatus.RUNNING && status != DownloadStatus.QUEUED) return
        stopJob(id)
        edit(id) { copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0) }
    }

    private suspend fun deleteAllParts(task: DownloadTask) {
        storage.deleteParts(task.id)
        PartKeys.all(task).forEach { storage.deleteParts(it) }
    }

    private fun stopJob(id: String) {
        jobs.remove(id)?.cancel()
        taskLimiters.remove(id)
    }

    /** Starts what the planner allows and schedules a wake-up for scheduled, backing-off or Wi-Fi-blocked tasks. */
    private fun pump() {
        val cfg = config.value
        val now = clock()
        val plan = QueuePlanner.plan(tasks.value, cfg, network.isUnmetered(), now)
        if (plan.waitingForWifi && !_waitingForWifi.value) _events.tryEmit(DownloadEvent.WaitingForWifi)
        _waitingForWifi.value = plan.waitingForWifi
        plan.start.forEach(::start)
        val wakeAt = listOfNotNull(plan.nextWakeMs, if (plan.waitingForWifi) now + cfg.networkRecheckMs else null).minOrNull()
        wakeJob?.cancel()
        wakeJob = wakeAt?.let { at ->
            scope.launch(serial) {
                delay((at - clock()).coerceAtLeast(1))
                pump()
            }
        }
    }

    private fun start(task: DownloadTask) {
        edit(task.id) { copy(status = DownloadStatus.RUNNING, error = null) }
        persist()
        val refreshFirst = refreshOnStart.remove(task.id) && task.parts.isNotEmpty()
        _events.tryEmit(DownloadEvent.Started(task))
        val cfg = config.value
        val limiter = RateLimiter(clock) { find(task.id)?.speedLimitBytesPerSec ?: 0 }
        taskLimiters[task.id] = limiter
        val options = RunOptions(
            connections = task.connections ?: cfg.connections,
            minSegmentBytes = cfg.minSegmentBytes,
            limiters = listOf(globalLimiter, limiter),
            segmentBytes = cfg.segmentBytes,
        )
        jobs[task.id] = scope.launch(ioDispatcher) {
            try {
                var lastUpdate = 0L
                val meter = SpeedMeter()
                val progress = ProgressSink { downloaded, total, segments ->
                    val now = clock()
                    if (now - lastUpdate < cfg.progressIntervalMs && (total <= 0 || downloaded < total)) return@ProgressSink
                    lastUpdate = now
                    val speed = meter.sample(downloaded, now)
                    withContext(serial) {
                        if (jobs.containsKey(task.id)) {
                            edit(task.id) {
                                copy(downloadedBytes = downloaded, totalBytes = if (total > 0) total else totalBytes, segments = segments, speedBytesPerSec = speed)
                            }
                            persistThrottled()
                        }
                    }
                }
                // Queued without parts (batch downloads): resolve what to fetch only now, when its turn comes.
                val ready = if (task.parts.isEmpty() && task.extras[PREPARE] == "1") {
                    val prepared = preparer?.prepare(task) ?: error("No downloadable format")
                    withContext(serial) { edit(task.id) { prepared.copy(id = id, status = status, extras = prepared.extras - PREPARE) } }
                    find(task.id) ?: task
                } else task
                if (ready.parts.isEmpty()) runner.run(ready, options, progress) else runParts(task.id, options, refreshFirst, progress)
                val finished = find(task.id) ?: task
                val published = storage.publish(finished, cfg.appFolder, cfg.conflict)
                deleteAllParts(finished)
                withContext(serial) {
                    finish(task.id, skipped = published.skipped) {
                        copy(
                            status = DownloadStatus.COMPLETED,
                            localUri = published.uri,
                            downloadedBytes = totalBytes.coerceAtLeast(downloadedBytes),
                            speedBytesPerSec = 0,
                            finishedAt = clock(),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(serial) { fail(task.id, error) }
            }
        }
    }

    /** Downloads every part of a multi-part task, then merges them into part 0 of the task. */
    private suspend fun runParts(id: String, options: RunOptions, refreshFirst: Boolean, progress: ProgressSink) {
        val handle = object : TaskHandle {
            override fun current(): DownloadTask = find(id) ?: throw CancellationException("Task removed")

            override suspend fun update(transform: (DownloadTask) -> DownloadTask) = withContext(serial) {
                if (jobs.containsKey(id)) {
                    edit(id) { transform(this) }
                    persist()
                }
            }

            override suspend fun refreshUrls(): Boolean = refreshUrls(id)

            override suspend fun progress(downloaded: Long, total: Long) = progress.update(downloaded, total, 1)
        }
        if (refreshFirst) {
            try {
                handle.refreshUrls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        val inputs = partRunner.run(handle, options)
        withContext(serial) {
            if (jobs.containsKey(id)) {
                edit(id) { copy(status = DownloadStatus.MERGING, speedBytesPerSec = 0) }
                persist()
            }
        }
        partRunner.assemble(handle.current(), inputs, merger)
    }

    /** Puts the [urlRefresher]'s urls into task [id]'s parts; true when any url changed. */
    private suspend fun refreshUrls(id: String): Boolean {
        val refresher = urlRefresher ?: return false
        val task = find(id) ?: return false
        val fresh = refresher.refresh(task) ?: return false
        var changed = false
        withContext(serial) {
            edit(id) {
                copy(parts = parts.mapIndexed { index, part ->
                    val match = if (part.key.isNotEmpty()) fresh.firstOrNull { it.key == part.key } else fresh.getOrNull(index)
                    if (match == null) {
                        part
                    } else {
                        if (match.url != part.url) changed = true
                        part.copy(url = match.url, headers = part.headers + match.headers)
                    }
                })
            }
            persist()
        }
        return changed
    }

    /** Retries with backoff while the policy allows, otherwise marks the task failed. */
    private fun fail(id: String, error: Exception) {
        val task = find(id) ?: return
        val policy = config.value.retry
        val attempt = task.attempts + 1
        if (error.isRetryable() && attempt <= policy.maxAttempts && jobs.containsKey(id)) {
            jobs.remove(id)
            taskLimiters.remove(id)
            val wait = policy.delayFor(attempt)
            edit(id) { copy(status = DownloadStatus.QUEUED, attempts = attempt, notBefore = clock() + wait, error = error.message, speedBytesPerSec = 0) }
            persist()
            find(id)?.let { _events.tryEmit(DownloadEvent.Retrying(it, attempt, wait)) }
            pump()
            return
        }
        finish(id) { copy(status = DownloadStatus.FAILED, error = error.message ?: error::class.simpleName, speedBytesPerSec = 0) }
    }

    private fun finish(id: String, skipped: Boolean = false, block: DownloadTask.() -> DownloadTask) {
        if (jobs.remove(id) == null) return
        taskLimiters.remove(id)
        edit(id, block)
        persist()
        find(id)?.let { task ->
            _events.tryEmit(
                when {
                    task.status != DownloadStatus.COMPLETED -> DownloadEvent.Failed(task)
                    skipped -> DownloadEvent.Skipped(task)
                    else -> DownloadEvent.Completed(task)
                },
            )
        }
        pump()
    }

    private fun persist() {
        lastSaveMs = clock()
        store.save(tasks.value)
    }

    private fun persistThrottled() {
        if (clock() - lastSaveMs >= SAVE_INTERVAL_MS) persist()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 2_000L
        val UNSAFE_NAME = Regex("""[\\/:*?"<>|]""")

        fun nextOrder(list: List<DownloadTask>): Long = (list.maxOfOrNull { it.order } ?: 0) + 1

        fun DownloadRequest.toTask(id: String, url: String, now: Long, order: Long) = DownloadTask(
            id = id,
            url = url,
            fileName = fileName,
            destination = destination,
            status = DownloadStatus.QUEUED,
            totalBytes = totalBytesHint,
            createdAt = now,
            extras = extras,
            headers = headers,
            priority = priority,
            order = order,
            tags = tags,
            connections = connections,
            wifiOnly = wifiOnly,
            speedLimitBytesPerSec = speedLimitBytesPerSec,
            notBefore = notBefore,
            parts = parts,
        )

        /** Downloads cut off by a restart either continue or wait paused. */
        fun restore(saved: List<DownloadTask>, autoResume: Boolean): List<DownloadTask> = saved.map {
            if (it.status.isWorking) it.copy(status = if (autoResume) DownloadStatus.QUEUED else DownloadStatus.PAUSED) else it
        }
    }
}
