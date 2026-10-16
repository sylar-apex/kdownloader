package org.app.kdownloader.model

import kotlinx.serialization.Serializable

/** What a file is, which decides the public folder it lands in. */
@Serializable
enum class MediaType { VIDEO, AUDIO, OTHER }

/** The type's own media folder (Movies, Music) or the shared Download folder. */
@Serializable
enum class Folder { MEDIA, DOWNLOADS }

/** Where a finished download is saved: `<Movies|Music|Download>/<appFolder>/<fileName>`. */
@Serializable
data class Destination(
    val type: MediaType = MediaType.OTHER,
    val folder: Folder = Folder.MEDIA,
)
