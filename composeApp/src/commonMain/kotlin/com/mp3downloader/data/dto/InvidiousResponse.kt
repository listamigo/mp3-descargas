package com.mp3downloader.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InvidiousSearchItem(
    val title: String,
    val videoId: String,
    val author: String? = null,
    val lengthSeconds: Long? = null,
    val videoThumbnails: List<InvidiousThumbnail>? = null,
    val viewCount: Long? = null
)

@Serializable
data class InvidiousThumbnail(
    val url: String? = null,
    val quality: String? = null
)

@Serializable
data class InvidiousVideoResponse(
    val title: String? = null,
    val author: String? = null,
    val lengthSeconds: Long? = null,
    val adaptiveFormats: List<InvidiousAdaptiveFormat>? = null,
    val formatStreams: List<InvidiousAdaptiveFormat>? = null
)

@Serializable
data class InvidiousAdaptiveFormat(
    val url: String,
    val type: String? = null,
    val mimeType: String? = null,
    val bitrate: Long? = null,
    val audioSampleRate: Long? = null,
    val audioChannels: Long? = null,
    val encoding: String? = null
)
