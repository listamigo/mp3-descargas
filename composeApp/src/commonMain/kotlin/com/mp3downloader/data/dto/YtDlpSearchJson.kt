package com.mp3downloader.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class YtDlpSearchJson(
    val id: String,
    val title: String,
    val duration: Double? = null,
    val thumbnail: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val url: String? = null,
    @SerialName("display_id") val displayId: String? = null,
    @SerialName("original_url") val originalUrl: String? = null
)

@Serializable
data class PipedSearchResponse(
    val items: List<PipedSearchItem> = emptyList()
)

@Serializable
data class PipedSearchItem(
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val duration: Long? = null,
    @SerialName("uploaderName") val uploaderName: String? = null,
    @SerialName("uploadedDate") val uploadedDate: String? = null,
    val views: Long? = null
)

@Serializable
data class PipedStreamResponse(
    @SerialName("audioStreams") val audioStreams: List<PipedAudioStream> = emptyList(),
    @SerialName("videoStreams") val videoStreams: List<PipedVideoStream> = emptyList(),
    val title: String? = null,
    val duration: Long? = null,
    val thumbnailUrl: String? = null
)

@Serializable
data class PipedAudioStream(
    val url: String,
    val format: String? = null,
    @SerialName("bitrate") val bitRate: Int? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("codec") val codec: String? = null
)

@Serializable
data class PipedVideoStream(
    val url: String,
    val format: String? = null,
    val quality: String? = null,
    @SerialName("mimeType") val mimeType: String? = null
)
