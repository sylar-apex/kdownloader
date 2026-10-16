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
 */
@OptIn(ExperimentalTime::class)
class KDownloader(
    config: DownloaderConfig = DownloaderConfig(),
    private val store: TaskStore = MemoryTaskStore(),
    private val storage: DownloadStorage = defaultStorage(),
    private val network: NetworkMonitor = defaultNetworkMonitor(),
    httpClient: HttpClient = defaultHttpClient(),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every task mutation runs here, one at a time. */
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val runner = DownloadRunner(HttpFetcher(httpClient, config.userAgent), storage)
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
            val existing = list.firstOrNull { it.url == request.url && it.status.isActive }
            if (existing != null) {
                result = EnqueueResult.Duplicate(existing)
                list
            } else {
                val task = request.toTask("dl-$now-${counter++}", now, order = nextOrder(list))
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
        storage.deleteParts(id)
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
            storage.deleteParts(it.id)
        }
        _tasks.update { list -> list.filter { it.id !in ids } }
        persist()
        pump()
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
        _events.tryEmit(DownloadEvent.Started(task))
        val cfg = config.value
        val limiter = RateLimiter(clock) { find(task.id)?.speedLimitBytesPerSec ?: 0 }
        taskLimiters[task.id] = limiter
        val options = RunOptions(
            connections = task.connections ?: cfg.connections,
            minSegmentBytes = cfg.minSegmentBytes,
            limiters = listOf(globalLimiter, limiter),
        )
        jobs[task.id] = scope.launch(ioDispatcher) {
            try {
                var lastUpdate = 0L
                val meter = SpeedMeter()
                runner.run(task, options) { downloaded, total, segments ->
                    val now = clock()
                    if (now - lastUpdate < cfg.progressIntervalMs && (total <= 0 || downloaded < total)) return@run
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
                val finished = find(task.id) ?: task
                val published = storage.publish(finished, cfg.appFolder, cfg.conflict)
                storage.deleteParts(task.id)
                withContext(serial) {
                    finish(task.id, skipped = published.skipped) {
                        copy(status = DownloadStatus.COMPLETED, localUri = published.uri, downloadedBytes = totalBytes.coerceAtLeast(downloadedBytes), speedBytesPerSec = 0)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(serial) { fail(task.id, error) }
            }
        }
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

        fun nextOrder(list: List<DownloadTask>): Long = (list.maxOfOrNull { it.order } ?: 0) + 1

        fun DownloadRequest.toTask(id: String, now: Long, order: Long) = DownloadTask(
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
        )

        /** Downloads cut off by a restart either continue or wait paused. */
        fun restore(saved: List<DownloadTask>, autoResume: Boolean): List<DownloadTask> = saved.map {
            if (it.status == DownloadStatus.RUNNING) it.copy(status = if (autoResume) DownloadStatus.QUEUED else DownloadStatus.PAUSED) else it
        }
    }
}
