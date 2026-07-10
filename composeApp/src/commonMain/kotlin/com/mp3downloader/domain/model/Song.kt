package com.mp3downloader.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val thumbnailUrl: String,
    val audioUrl: String?
)
