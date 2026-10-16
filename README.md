<div align="center">

# KDownloader

**A download manager library for Kotlin Multiplatform — parallel, resumable, multi-connection downloads on Android, iOS, desktop and web.**

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-3.6-087CFA)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20Desktop%20%7C%20Web-152438)

</div>

```kotlin
val downloader = KDownloader(DownloaderConfig(maxParallel = 3, connections = 4, appFolder = "MyApp"))
downloader.enqueue(DownloadRequest(url, "clip.mp4", Destination(MediaType.VIDEO)))
downloader.tasks.collect { tasks -> render(tasks) }
```

## Features

**Queue**
- Parallel downloads with a configurable limit (`maxParallel`, 1–16)
- Priorities, move to top, move up/down
- Pause, resume, cancel, retry, remove — per task, per tag, or all at once
- Scheduled start (`notBefore`)
- Wi-Fi-only mode, globally or per task, that resumes by itself when Wi-Fi returns
- Tasks survive restarts (`JsonTaskStore`); interrupted downloads auto-resume or come back paused

**Transfer**
- Resume from where it stopped with HTTP Range requests
- Multi-connection downloads: a file splits into up to 16 ranged parts (`connections`, `minSegmentBytes`), each part resumable
- Speed limit: global and per task
- Live speed and time remaining on every task
- Automatic retries with exponential backoff for network errors, 5xx, 408 and 429
- Custom headers per request (cookies, referer, auth, user agent)
- Bring your own Ktor `HttpClient` (proxy, logging, certificates)

**Saving**
- Android: MediaStore (Movies, Music or Download / your app folder) — no storage permission on Android 10+
- Desktop: `~/Movies`, `~/Music` or `~/Downloads` / your app folder
- iOS: the app's Documents folder (visible in the Files app)
- Web: handed to the browser's own download manager
- Name conflicts: rename to `name (2).ext`, overwrite, or skip

**Events** — `Started`, `Completed`, `Failed`, `Retrying`, `Skipped`, `WaitingForWifi`

## Modules

| Module | Contents |
|---|---|
| `kdownloader:core` | `KDownloader`, models, Ktor-based engine, platform storage and network monitors. No Compose. |

## Setup

```bash
git submodule add https://github.com/sylar-apex/kdownloader.git kdownloader
```

```kotlin
// settings.gradle.kts
include(":kdownloader:core")

// your module
commonMain.dependencies { implementation(projects.kdownloader.core) }
```

The module reads versions from the host project's `libs.versions.toml`: `ktor-client-core`, `ktor-client-cio`,
`ktor-client-darwin`, `ktor-client-js`, `kotlinx-io-core`, `kotlinx-coroutines-core`, `kotlinx-browser`,
`kotlin-serialization`.

On Android, initialise once in your `Application`:

```kotlin
KDownloaderAndroid.init(this)
```

## Usage

```kotlin
val downloader = KDownloader(
    config = DownloaderConfig(
        maxParallel = 2,
        connections = 4,
        wifiOnly = false,
        speedLimitBytesPerSec = 0,
        retry = RetryPolicy(maxAttempts = 3),
        conflict = ConflictPolicy.RENAME,
        appFolder = "MyApp",
    ),
    store = JsonTaskStore(read = { prefs.get("downloads") }, write = { prefs.put("downloads", it) }),
)

downloader.enqueue(
    DownloadRequest(
        url = "https://example.com/movie.mp4",
        fileName = "movie.mp4",
        destination = Destination(MediaType.VIDEO),
        headers = mapOf("Referer" to "https://example.com/"),
        priority = 1,
        tags = setOf("movies"),
        extras = mapOf("myId" to "42"),
    ),
)

downloader.pause(id); downloader.resume(id); downloader.cancel(id)
downloader.pauseTag("movies"); downloader.resumeAll()
downloader.updateConfig(downloader.config.value.copy(maxParallel = 4))

downloader.events.collect { event ->
    if (event is DownloadEvent.Completed) notify(event.task.fileName)
}
```

## Platform status

| Platform | Engine | Storage | Wi-Fi detection |
|---|---|---|---|
| Android | Ktor CIO | MediaStore | ✅ |
| Desktop | Ktor CIO | user folders | always unmetered |
| iOS | Ktor Darwin | Documents | always unmetered |
| Web (Wasm) | Ktor JS | browser download (partials kept in memory) | always unmetered |

## Used by

- **ViMate** — video and music player and downloader
